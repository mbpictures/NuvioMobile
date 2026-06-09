import Foundation
import Darwin

final class CastHttpProxy: NSObject {

    static let shared = CastHttpProxy()

    private let stateLock = NSLock()
    private var sessions: [String: [String: String]] = [:]
    private var sessionCounter = 0
    private var listenFd: Int32 = -1
    private var port: UInt16 = 0
    private var started = false

    private let contextLock = NSLock()
    private var contexts: [ObjectIdentifier: RequestContext] = [:]

    func rewriteIfNeeded(url: String, headersJson: String?) -> String? {
        guard let headersJson, !headersJson.isEmpty,
              let data = headersJson.data(using: .utf8),
              let raw = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else { return nil }

        var headers: [String: String] = [:]
        for (key, value) in raw {
            let k = key.trimmingCharacters(in: .whitespacesAndNewlines)
            let v = (value as? String ?? String(describing: value)).trimmingCharacters(in: .whitespacesAndNewlines)
            if k.isEmpty || v.isEmpty || k.caseInsensitiveCompare("Range") == .orderedSame { continue }
            headers[k] = v
        }
        guard !headers.isEmpty, ensureStarted(), let ip = Self.siteLocalIPv4() else { return nil }

        stateLock.lock()
        sessionCounter += 1
        let sessionId = "s\(sessionCounter)"
        sessions[sessionId] = headers
        let currentPort = port
        stateLock.unlock()

        return Self.proxyUrl(ip: ip, port: currentPort, sessionId: sessionId, target: url)
    }

    func stop() {
        stateLock.lock()
        let fd = listenFd
        listenFd = -1
        started = false
        port = 0
        sessions.removeAll()
        stateLock.unlock()
        if fd >= 0 { close(fd) }
    }

    private func ensureStarted() -> Bool {
        stateLock.lock()
        if started, listenFd >= 0 { stateLock.unlock(); return true }

        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { stateLock.unlock(); return false }
        var yes: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_addr.s_addr = in_addr_t(0) // INADDR_ANY: all interfaces
        addr.sin_port = 0 // ephemeral port
        let bound = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0, listen(fd, 16) == 0 else { close(fd); stateLock.unlock(); return false }

        var name = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &name) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &len) }
        }
        guard named == 0 else { close(fd); stateLock.unlock(); return false }

        port = UInt16(bigEndian: name.sin_port)
        listenFd = fd
        started = true
        stateLock.unlock()

        Thread.detachNewThread { [weak self] in self?.acceptLoop(fd) }
        return true
    }

    private func acceptLoop(_ fd: Int32) {
        while true {
            var addr = sockaddr()
            var len = socklen_t(MemoryLayout<sockaddr>.size)
            let clientFd = accept(fd, &addr, &len)
            if clientFd < 0 {
                if errno == EBADF || errno == EINVAL { break } // listener closed by stop()
                continue
            }
            DispatchQueue.global().async { [weak self] in self?.serve(clientFd) }
        }
    }

    private func serve(_ clientFd: Int32) {
        // Darwin raises SIGPIPE on write to a peer that hung up; suppress it per-socket.
        var on: Int32 = 1
        setsockopt(clientFd, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))

        guard let request = readRequest(clientFd) else {
            writeSimpleStatus(clientFd, 400)
            close(clientFd)
            return
        }
        if request.method.caseInsensitiveCompare("OPTIONS") == .orderedSame {
            writeSimpleStatus(clientFd, 204) // CORS preflight
            close(clientFd)
            return
        }

        let route = request.path.components(separatedBy: "?").first ?? request.path
        let query = request.path.firstIndex(of: "?").map { String(request.path[request.path.index(after: $0)...]) } ?? ""
        let sessionId = route.hasPrefix("/p/")
            ? (String(route.dropFirst(3)).components(separatedBy: "/").first ?? "")
            : ""
        let targetB64 = query.components(separatedBy: "&")
            .first(where: { $0.hasPrefix("u=") })
            .map { String($0.dropFirst(2)) }

        stateLock.lock()
        let headers = sessions[sessionId]
        stateLock.unlock()

        guard let headers, let targetB64, let target = Self.base64UrlDecode(targetB64), !target.isEmpty else {
            writeSimpleStatus(clientFd, 404)
            close(clientFd)
            return
        }
        relay(method: request.method, target: target, sessionId: sessionId,
              headers: headers, clientRange: request.headers["range"], clientFd: clientFd)
    }

    private func relay(method: String, target: String, sessionId: String,
                       headers: [String: String], clientRange: String?, clientFd: Int32) {
        guard let url = URL(string: target) else {
            writeSimpleStatus(clientFd, 502)
            close(clientFd)
            return
        }
        let isHead = method.caseInsensitiveCompare("HEAD") == .orderedSame
        var request = URLRequest(url: url)
        request.httpMethod = isHead ? "HEAD" : "GET"
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        if let clientRange { request.setValue(clientRange, forHTTPHeaderField: "Range") }

        let delegateQueue = OperationQueue()
        delegateQueue.maxConcurrentOperationCount = 1 // preserve per-task body ordering
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 6 * 60 * 60
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: config, delegate: self, delegateQueue: delegateQueue)

        let context = RequestContext(clientFd: clientFd, isHead: isHead,
                                     target: target, sessionId: sessionId, headers: headers)
        contextLock.lock()
        contexts[ObjectIdentifier(session)] = context
        contextLock.unlock()

        session.dataTask(with: request).resume()
        context.done.wait() // delegate writes the response, closes the fd and signals when done
    }

    private func context(for session: URLSession) -> RequestContext? {
        contextLock.lock(); defer { contextLock.unlock() }
        return contexts[ObjectIdentifier(session)]
    }

    private func removeContext(for session: URLSession) -> RequestContext? {
        contextLock.lock(); defer { contextLock.unlock() }
        return contexts.removeValue(forKey: ObjectIdentifier(session))
    }

    private func currentPort() -> UInt16 {
        stateLock.lock(); defer { stateLock.unlock() }
        return port
    }

    private static func rewriteHlsManifest(_ body: String, baseUrl: String, ip: String,
                                           port: UInt16, sessionId: String) -> String {
        let uriRegex = try? NSRegularExpression(pattern: "URI=\"([^\"]*)\"")
        return body.components(separatedBy: "\n").map { rawLine -> String in
            let line = rawLine.hasSuffix("\r") ? String(rawLine.dropLast()) : rawLine
            if line.hasPrefix("#") {
                // Tag lines: rewrite any embedded URI="..." (EXT-X-KEY, EXT-X-MAP, EXT-X-MEDIA, ...).
                guard let uriRegex else { return line }
                let source = line as NSString
                var result = line
                let matches = uriRegex.matches(in: line, range: NSRange(location: 0, length: source.length))
                // Apply right-to-left so earlier match ranges stay valid against the original string.
                for match in matches.reversed() {
                    let original = source.substring(with: match.range(at: 1))
                    let proxied = proxyUrl(ip: ip, port: port, sessionId: sessionId,
                                           target: resolve(base: baseUrl, ref: original))
                    result = (result as NSString).replacingCharacters(in: match.range, with: "URI=\"\(proxied)\"")
                }
                return result
            }
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty { return line }
            // Bare URI lines: variant playlists and media segments.
            return proxyUrl(ip: ip, port: port, sessionId: sessionId, target: resolve(base: baseUrl, ref: trimmed))
        }.joined(separator: "\n")
    }

    private static func resolve(base: String, ref: String) -> String {
        let lower = ref.lowercased()
        if lower.hasPrefix("http://") || lower.hasPrefix("https://") { return ref }
        if let baseURL = URL(string: base), let resolved = URL(string: ref, relativeTo: baseURL) {
            return resolved.absoluteString
        }
        return ref
    }

    private static func proxyUrl(ip: String, port: UInt16, sessionId: String, target: String) -> String {
        "http://\(ip):\(port)/p/\(sessionId)?u=\(base64UrlEncode(target))"
    }

    private static func looksLikeHls(url: String, contentType: String?) -> Bool {
        let type = (contentType ?? "").lowercased()
        if type.contains("mpegurl") { return true } // x-mpegURL, vnd.apple.mpegurl
        if !type.isEmpty { return false } // a concrete non-HLS type (segment, dash, mp4) is authoritative
        let path = url.components(separatedBy: "?").first?.components(separatedBy: "#").first ?? url
        return path.lowercased().hasSuffix(".m3u8")
    }

    private struct ParsedRequest {
        let method: String
        let path: String
        let headers: [String: String]
    }

    private func readRequest(_ fd: Int32) -> ParsedRequest? {
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 2048)
        let terminator = Data("\r\n\r\n".utf8)
        while true {
            let read = recv(fd, &buffer, buffer.count, 0)
            if read <= 0 { return nil }
            data.append(contentsOf: buffer[0..<read])
            if let range = data.range(of: terminator) {
                return parseRequest(data.subdata(in: data.startIndex..<range.lowerBound))
            }
            if data.count > 65_536 { return nil } // header section too large
        }
    }

    private func parseRequest(_ data: Data) -> ParsedRequest? {
        guard let text = String(data: data, encoding: .utf8) else { return nil }
        let lines = text.components(separatedBy: "\r\n")
        guard let requestLine = lines.first else { return nil }
        let parts = requestLine.components(separatedBy: " ")
        guard parts.count >= 2 else { return nil }
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let key = line[line.startIndex..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            if !key.isEmpty { headers[key] = value }
        }
        return ParsedRequest(method: parts[0], path: parts[1], headers: headers)
    }

    private func writeHead(_ fd: Int32, _ code: Int, _ headers: [String: String]) {
        var response = "HTTP/1.1 \(code) \(Self.reasonPhrase(code))\r\n"
        for (key, value) in headers { response += "\(key): \(value)\r\n" }
        // Let the receiver's player (a different origin) read every response.
        response += "Access-Control-Allow-Origin: *\r\n"
        response += "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n"
        response += "Access-Control-Allow-Headers: *\r\n"
        response += "Access-Control-Expose-Headers: *\r\n"
        response += "Connection: close\r\n\r\n"
        writeAll(fd, Data(response.utf8))
    }

    private func writeSimpleStatus(_ fd: Int32, _ code: Int) {
        writeHead(fd, code, ["Content-Length": "0"])
    }

    @discardableResult
    private func writeAll(_ fd: Int32, _ data: Data) -> Bool {
        guard !data.isEmpty else { return true }
        return data.withUnsafeBytes { raw -> Bool in
            guard var pointer = raw.baseAddress else { return true }
            var remaining = data.count
            while remaining > 0 {
                let sent = send(fd, pointer, remaining, 0)
                if sent <= 0 { return false }
                remaining -= sent
                pointer = pointer + sent
            }
            return true
        }
    }

    private static func base64UrlEncode(_ value: String) -> String {
        Data(value.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func base64UrlDecode(_ value: String) -> String? {
        var base64 = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 { base64 += "=" }
        guard let data = Data(base64Encoded: base64) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func siteLocalIPv4() -> String? {
        var ifaddrPointer: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddrPointer) == 0 else { return nil }
        defer { freeifaddrs(ifaddrPointer) }

        var preferred: String?   // site-local on Wi-Fi
        var siteLocal: String?   // site-local on any interface
        var anyRoutable: String? // any non-loopback/non-link-local IPv4
        var cursor = ifaddrPointer
        while let current = cursor {
            defer { cursor = current.pointee.ifa_next }
            let flags = Int32(current.pointee.ifa_flags)
            guard (flags & IFF_UP) == IFF_UP, (flags & IFF_LOOPBACK) == 0,
                  let sockAddr = current.pointee.ifa_addr,
                  sockAddr.pointee.sa_family == sa_family_t(AF_INET) else { continue }

            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let result = getnameinfo(sockAddr, socklen_t(sockAddr.pointee.sa_len),
                                     &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST)
            guard result == 0 else { continue }
            let ip = String(cString: host)
            if ip.hasPrefix("169.254.") { continue } // link-local
            let name = String(cString: current.pointee.ifa_name)
            if isSiteLocal(ip) {
                if name == "en0" { preferred = preferred ?? ip } else { siteLocal = siteLocal ?? ip }
            } else {
                anyRoutable = anyRoutable ?? ip
            }
        }
        return preferred ?? siteLocal ?? anyRoutable
    }

    private static func isSiteLocal(_ ip: String) -> Bool {
        if ip.hasPrefix("10.") || ip.hasPrefix("192.168.") { return true }
        if ip.hasPrefix("172.") {
            let parts = ip.split(separator: ".")
            if parts.count > 1, let second = Int(parts[1]), (16...31).contains(second) { return true }
        }
        return false
    }

    private static func reasonPhrase(_ code: Int) -> String {
        switch code {
        case 200: return "OK"
        case 204: return "No Content"
        case 206: return "Partial Content"
        case 400: return "Bad Request"
        case 404: return "Not Found"
        case 502: return "Bad Gateway"
        default: return "OK"
        }
    }

    private final class RequestContext {
        let clientFd: Int32
        let isHead: Bool
        let target: String
        let sessionId: String
        let headers: [String: String]
        var isHls = false
        var buffer = Data()
        var headersWritten = false
        let done = DispatchSemaphore(value: 0)

        init(clientFd: Int32, isHead: Bool, target: String, sessionId: String, headers: [String: String]) {
            self.clientFd = clientFd
            self.isHead = isHead
            self.target = target
            self.sessionId = sessionId
            self.headers = headers
        }
    }
}

extension CastHttpProxy: URLSessionDataDelegate {

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                    didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let context = context(for: session) else { completionHandler(.cancel); return }
        let http = response as? HTTPURLResponse
        let contentType = http?.value(forHTTPHeaderField: "Content-Type")
        context.isHls = Self.looksLikeHls(url: context.target, contentType: contentType)

        if context.isHls && !context.isHead {
            completionHandler(.allow) // buffer the manifest, rewrite on completion
            return
        }

        var head: [String: String] = [:]
        if let contentType { head["Content-Type"] = contentType }
        if let length = http?.value(forHTTPHeaderField: "Content-Length") { head["Content-Length"] = length }
        if let contentRange = http?.value(forHTTPHeaderField: "Content-Range") { head["Content-Range"] = contentRange }
        head["Accept-Ranges"] = http?.value(forHTTPHeaderField: "Accept-Ranges") ?? "bytes"
        writeHead(context.clientFd, http?.statusCode ?? 200, head)
        context.headersWritten = true
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard let context = context(for: session) else { return }
        if context.isHls && !context.isHead {
            context.buffer.append(data)
        } else if !writeAll(context.clientFd, data) {
            dataTask.cancel() // receiver hung up; stop pulling from upstream
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        defer { session.finishTasksAndInvalidate() }
        guard let context = removeContext(for: session) else { return }
        if context.isHls && !context.isHead && error == nil {
            let body: Data
            if let manifest = String(data: context.buffer, encoding: .utf8), let ip = Self.siteLocalIPv4() {
                let rewritten = Self.rewriteHlsManifest(manifest, baseUrl: context.target, ip: ip,
                                                        port: currentPort(), sessionId: context.sessionId)
                body = rewritten.data(using: .utf8) ?? context.buffer
            } else {
                body = context.buffer
            }
            writeHead(context.clientFd, 200, [
                "Content-Type": "application/vnd.apple.mpegurl",
                "Content-Length": "\(body.count)",
                "Cache-Control": "no-cache",
            ])
            writeAll(context.clientFd, body)
        } else if !context.headersWritten && error != nil {
            writeSimpleStatus(context.clientFd, 502)
        }
        close(context.clientFd)
        context.done.signal()
    }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        if challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
           let trust = challenge.protectionSpace.serverTrust {
            completionHandler(.useCredential, URLCredential(trust: trust))
        } else {
            completionHandler(.performDefaultHandling, nil)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        guard let context = context(for: session) else { completionHandler(request); return }
        var redirected = request
        for (key, value) in context.headers { redirected.setValue(value, forHTTPHeaderField: key) }
        completionHandler(redirected)
    }
}
