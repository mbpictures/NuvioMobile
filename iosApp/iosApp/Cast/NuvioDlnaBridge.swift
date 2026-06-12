import Foundation
import Network
import ComposeApp

enum NuvioDlnaRegistration {
    static func register() {
        NuvioDlnaBridgeFactory.shared.registerFactory(creator: NuvioDlnaBridgeCreatorImpl())
    }
}

final class NuvioDlnaBridgeCreatorImpl: NSObject, NuvioCastBridgeCreator {
    func createBridge() -> any NuvioCastBridge {
        return NuvioDlnaBridgeImpl()
    }
}

private struct DlnaRenderer {
    let udn: String
    let name: String
    let controlURL: URL
}

final class NuvioDlnaBridgeImpl: NSObject, NuvioCastBridge {

    private weak var listenerRef: CastBridgeListener?
    private let queue = DispatchQueue(label: "com.nuvio.dlna")
    private let session = URLSession(configuration: .default)

    private var group: NWConnectionGroup?
    private var renderers: [DlnaRenderer] = []
    private var seenUdns = Set<String>()
    private var connected: DlnaRenderer?
    private var casting = false
    private var positionMs: Int64 = 0
    private var durationMs: Int64 = 0
    private var playing = false
    private var buffering = false
    private var pollTimer: DispatchSourceTimer?

    private let ssdpHost = "239.255.255.250"
    private let ssdpPort: UInt16 = 1900
    private let avTransport = "urn:schemas-upnp-org:service:AVTransport:1"
    private let mediaRenderer = "urn:schemas-upnp-org:device:MediaRenderer:1"
    private let dlnaPrefix = "dlna::"

    func setListener(listener: CastBridgeListener?) {
        self.listenerRef = listener
    }

    private func notifyChanged() {
        DispatchQueue.main.async { [weak self] in self?.listenerRef?.onCastStateChanged() }
    }

    func startDiscovery() {
        queue.async { [weak self] in self?.startSsdp() }
    }

    func stopDiscovery() {
        queue.async { [weak self] in
            self?.group?.cancel()
            self?.group = nil
        }
    }

    private func startSsdp() {
        if group != nil {
            sendMSearch()
            return
        }
        guard let port = NWEndpoint.Port(rawValue: ssdpPort) else { return }
        do {
            let multicast = try NWMulticastGroup(for: [.hostPort(host: NWEndpoint.Host(ssdpHost), port: port)])
            let group = NWConnectionGroup(with: multicast, using: .udp)
            group.setReceiveHandler(maximumMessageSize: 65535, rejectOversizedMessages: false) { [weak self] _, content, _ in
                guard let self, let data = content, let text = String(data: data, encoding: .utf8) else { return }
                self.queue.async { self.handleSsdp(text) }
            }
            group.stateUpdateHandler = { [weak self] state in
                if case .ready = state {
                    self?.queue.async { self?.sendMSearch() }
                }
            }
            self.group = group
            group.start(queue: queue)
        } catch {
            NSLog("NuvioCastDlna: multicast unavailable (entitlement?): \(error.localizedDescription)")
        }
    }

    private func sendMSearch() {
        let message = "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: \(ssdpHost):\(ssdpPort)\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: \(mediaRenderer)\r\n\r\n"
        group?.send(content: message.data(using: .utf8)) { _ in }
    }

    private func handleSsdp(_ response: String) {
        guard let location = headerValue(response, "LOCATION") else { return }
        fetchRenderer(location)
    }

    private func fetchRenderer(_ descriptionUrl: String) {
        guard let url = URL(string: descriptionUrl) else { return }
        session.dataTask(with: url) { [weak self] data, _, _ in
            guard let self, let data, let xml = String(data: data, encoding: .utf8) else { return }
            self.queue.async {
                let udn = (self.extractTag(xml, "UDN")?.trimmingCharacters(in: .whitespacesAndNewlines))
                    .flatMap { $0.isEmpty ? nil : $0 } ?? descriptionUrl
                if self.seenUdns.contains(udn) { return }
                guard let controlPath = self.findAvTransportControlUrl(xml) else { return }
                guard let controlURL = self.resolveURL(descriptionUrl, xml, controlPath) else { return }
                let name = (self.extractTag(xml, "friendlyName")?.trimmingCharacters(in: .whitespacesAndNewlines))
                    .flatMap { $0.isEmpty ? nil : $0 } ?? "DLNA renderer"
                self.seenUdns.insert(udn)
                self.renderers.append(DlnaRenderer(udn: udn, name: name, controlURL: controlURL))
                self.notifyChanged()
            }
        }.resume()
    }

    private func findAvTransportControlUrl(_ xml: String) -> String? {
        var searchRange = xml.startIndex..<xml.endIndex
        while let start = xml.range(of: "<service", range: searchRange),
              let end = xml.range(of: "</service>", range: start.lowerBound..<xml.endIndex) {
            let block = String(xml[start.lowerBound..<end.upperBound])
            if block.contains(avTransport) {
                return extractTag(block, "controlURL")?.trimmingCharacters(in: .whitespacesAndNewlines)
            }
            searchRange = end.upperBound..<xml.endIndex
        }
        return nil
    }

    private func resolveURL(_ descriptionUrl: String, _ xml: String, _ path: String) -> URL? {
        if path.lowercased().hasPrefix("http://") || path.lowercased().hasPrefix("https://") {
            return URL(string: path)
        }
        let base = (extractTag(xml, "URLBase")?.trimmingCharacters(in: .whitespacesAndNewlines))
            .flatMap { $0.isEmpty ? nil : $0 } ?? descriptionUrl
        return URL(string: path, relativeTo: URL(string: base))?.absoluteURL
    }

    // MARK: Device accessors

    func getDeviceCount() -> Int32 { queue.sync { Int32(renderers.count) } }

    func getDeviceId(at: Int32) -> String {
        queue.sync { renderer(at).map { dlnaPrefix + $0.udn } ?? "" }
    }

    func getDeviceName(at: Int32) -> String {
        queue.sync { renderer(at)?.name ?? "" }
    }

    func getDeviceModel(at: Int32) -> String {
        queue.sync { renderer(at) != nil ? "DLNA" : "" }
    }

    private func renderer(_ index: Int32) -> DlnaRenderer? {
        guard index >= 0, Int(index) < renderers.count else { return nil }
        return renderers[Int(index)]
    }

    // MARK: Connection

    func connect(deviceId: String) {
        let udn = deviceId.hasPrefix(dlnaPrefix) ? String(deviceId.dropFirst(dlnaPrefix.count)) : deviceId
        queue.async { [weak self] in
            guard let self else { return }
            self.connected = self.renderers.first { $0.udn == udn }
            self.notifyChanged()
        }
    }

    func disconnect() {
        queue.async { [weak self] in
            guard let self else { return }
            if let renderer = self.connected {
                self.soap(renderer.controlURL, "Stop", "<InstanceID>0</InstanceID>") { _ in }
            }
            self.connected = nil
            self.casting = false
            self.positionMs = 0
            self.durationMs = 0
            self.playing = false
            self.stopPolling()
            self.notifyChanged()
        }
    }

    func getConnectionState() -> Int32 {
        queue.sync { connected != nil ? 3 : 1 }
    }

    func getConnectedDeviceName() -> String {
        queue.sync { connected?.name ?? "" }
    }

    // MARK: Media

    func loadMedia(
        url: String,
        title: String,
        subtitle: String,
        posterUrl: String,
        contentType: String,
        startPositionMs: Int64,
        headersJson: String,
    ) {
        // DLNA renderers fetch the URL themselves and can't send headers; route header-authenticated
        // streams through the local proxy. contentType stays derived from the real URL.
        let effectiveUrl = CastHttpProxy.shared.rewriteIfNeeded(url: url, headersJson: headersJson) ?? url
        queue.async { [weak self] in
            guard let self, let renderer = self.connected else { return }
            let didl = self.buildDidl(url: effectiveUrl, title: title, contentType: contentType)
            let inner = "<InstanceID>0</InstanceID>" +
                "<CurrentURI>\(self.xmlEscape(effectiveUrl))</CurrentURI>" +
                "<CurrentURIMetaData>\(self.xmlEscape(didl))</CurrentURIMetaData>"
            self.soap(renderer.controlURL, "SetAVTransportURI", inner) { [weak self] _ in
                guard let self else { return }
                self.soap(renderer.controlURL, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>") { [weak self] _ in
                    guard let self else { return }
                    if startPositionMs > 0 {
                        let seek = "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit>" +
                            "<Target>\(self.formatTime(startPositionMs))</Target>"
                        self.soap(renderer.controlURL, "Seek", seek) { _ in }
                    }
                    self.queue.async {
                        self.casting = true
                        self.startPolling(renderer)
                        self.notifyChanged()
                    }
                }
            }
        }
    }

    func play() {
        queue.async { [weak self] in
            guard let self, let renderer = self.connected else { return }
            self.soap(renderer.controlURL, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>") { _ in }
        }
    }

    func pause() {
        queue.async { [weak self] in
            guard let self, let renderer = self.connected else { return }
            self.soap(renderer.controlURL, "Pause", "<InstanceID>0</InstanceID>") { _ in }
        }
    }

    func seekTo(positionMs: Int64) {
        queue.async { [weak self] in
            guard let self, let renderer = self.connected else { return }
            let inner = "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>\(self.formatTime(positionMs))</Target>"
            self.soap(renderer.controlURL, "Seek", inner) { _ in }
        }
    }

    func isCasting() -> Bool { queue.sync { casting } }
    func getPositionMs() -> Int64 { queue.sync { positionMs } }
    func getDurationMs() -> Int64 { queue.sync { durationMs } }
    func getIsPlaying() -> Bool { queue.sync { playing } }
    func getIsBuffering() -> Bool { queue.sync { buffering } }

    func destroy() {
        queue.async { [weak self] in
            self?.stopPolling()
            self?.group?.cancel()
            self?.group = nil
        }
        listenerRef = nil
    }

    private func startPolling(_ renderer: DlnaRenderer) {
        stopPolling()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 1, repeating: 1)
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            self.soap(renderer.controlURL, "GetPositionInfo", "<InstanceID>0</InstanceID>") { [weak self] info in
                guard let self else { return }
                self.queue.async {
                    self.positionMs = self.parseTime(self.extractTag(info ?? "", "RelTime"))
                    self.durationMs = self.parseTime(self.extractTag(info ?? "", "TrackDuration"))
                }
            }
            self.soap(renderer.controlURL, "GetTransportInfo", "<InstanceID>0</InstanceID>") { [weak self] transport in
                guard let self else { return }
                self.queue.async {
                    let state = self.extractTag(transport ?? "", "CurrentTransportState") ?? ""
                    self.playing = state == "PLAYING"
                    self.buffering = state == "TRANSITIONING"
                    self.notifyChanged()
                }
            }
        }
        pollTimer = timer
        timer.resume()
    }

    private func stopPolling() {
        pollTimer?.cancel()
        pollTimer = nil
    }

    private func soap(_ controlURL: URL, _ action: String, _ inner: String, completion: @escaping (String?) -> Void) {
        var request = URLRequest(url: controlURL)
        request.httpMethod = "POST"
        request.timeoutInterval = 5
        request.setValue("text/xml; charset=\"utf-8\"", forHTTPHeaderField: "Content-Type")
        request.setValue("\"\(avTransport)#\(action)\"", forHTTPHeaderField: "SOAPACTION")
        let body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:\(action) xmlns:u=\"\(avTransport)\">\(inner)</u:\(action)></s:Body></s:Envelope>"
        request.httpBody = body.data(using: .utf8)
        session.dataTask(with: request) { data, _, _ in
            completion(data.flatMap { String(data: $0, encoding: .utf8) })
        }.resume()
    }

    private func headerValue(_ response: String, _ name: String) -> String? {
        for line in response.split(separator: "\r\n") {
            let parts = line.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
            if parts.count == 2, parts[0].trimmingCharacters(in: .whitespaces).caseInsensitiveCompare(name) == .orderedSame {
                return parts[1].trimmingCharacters(in: .whitespaces)
            }
        }
        return nil
    }

    private func extractTag(_ xml: String, _ tag: String) -> String? {
        let pattern = "<(?:\\w+:)?\(tag)\\b[^>]*>(.*?)</(?:\\w+:)?\(tag)>"
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.dotMatchesLineSeparators]) else { return nil }
        let range = NSRange(xml.startIndex..<xml.endIndex, in: xml)
        guard let match = regex.firstMatch(in: xml, options: [], range: range),
              let group = Range(match.range(at: 1), in: xml) else { return nil }
        return unescapeXml(String(xml[group]))
    }

    private func buildDidl(url: String, title: String, contentType: String) -> String {
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>\(xmlEscape(title))</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:\(contentType):*\">\(xmlEscape(url))</res>" +
            "</item></DIDL-Lite>"
    }

    private func xmlEscape(_ value: String) -> String {
        return value
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&apos;")
    }

    private func unescapeXml(_ value: String) -> String {
        return value
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&apos;", with: "'")
            .replacingOccurrences(of: "&amp;", with: "&")
    }

    private func formatTime(_ ms: Int64) -> String {
        let totalSeconds = max(ms / 1000, 0)
        let h = totalSeconds / 3600
        let m = (totalSeconds % 3600) / 60
        let s = totalSeconds % 60
        return String(format: "%d:%02d:%02d", h, m, s)
    }

    private func parseTime(_ value: String?) -> Int64 {
        guard let value = value?.trimmingCharacters(in: .whitespaces), !value.isEmpty else { return 0 }
        let parts = value.split(separator: ":")
        guard parts.count == 3,
              let h = Int64(parts[0]),
              let m = Int64(parts[1]),
              let s = Int64(parts[2].split(separator: ".").first ?? "") else { return 0 }
        return ((h * 3600) + (m * 60) + s) * 1000
    }
}
