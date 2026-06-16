package com.nuvio.app.features.player.cast

import android.util.Base64
import android.util.Log
import com.nuvio.app.features.player.PlayerPlaybackNetworking
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "NuvioCastProxy"

internal class CastProxyServer {

    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "cast-proxy-worker").apply { isDaemon = true } }
    private val sessions = ConcurrentHashMap<String, Map<String, String>>()
    private val sessionCounter = AtomicInteger(0)

    private var serverSocket: ServerSocket? = null
    @Volatile private var port: Int = 0

    @Synchronized
    fun prepare(originalUrl: String, headers: Map<String, String>): String? {
        if (!ensureStarted()) {
            Log.w(TAG, "proxy server failed to start; casting with direct (unauthenticated) URL")
            return null
        }
        val ip = lanIpv4() ?: run {
            Log.w(TAG, "no reachable LAN IPv4 found; casting with direct (unauthenticated) URL")
            return null
        }
        val sessionId = "s${sessionCounter.incrementAndGet()}"
        sessions[sessionId] = headers
        val url = proxyUrl(ip, sessionId, originalUrl)
        Log.i(TAG, "proxy ready at http://$ip:$port (session $sessionId)")
        return url
    }

    @Synchronized
    fun stop() {
        sessions.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        port = 0
    }

    private fun ensureStarted(): Boolean {
        serverSocket?.let { if (!it.isClosed) return true }
        return runCatching {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0)) // all interfaces, ephemeral port
            port = socket.localPort
            serverSocket = socket
            Thread({ acceptLoop(socket) }, "cast-proxy").apply { isDaemon = true; start() }
            Log.i(TAG, "proxy listening on :$port")
            true
        }.onFailure { Log.w(TAG, "proxy start failed: ${it.message}") }.getOrDefault(false)
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break // socket closed by stop()
            }
            executor.execute {
                try {
                    serve(client)
                } catch (e: Exception) {
                    Log.d(TAG, "request failed: ${e.message}")
                } finally {
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = REQUEST_TIMEOUT_MS
        val input = client.getInputStream()
        val output = BufferedOutputStream(client.getOutputStream())
        val request = readRequest(input)
        if (request == null) {
            writeHead(output, 400, mapOf("Content-Length" to "0"))
            output.flush()
            return
        }
        val (method, path, reqHeaders) = request

        if (method.equals("OPTIONS", ignoreCase = true)) {
            writeHead(output, 204, mapOf("Content-Length" to "0"))
            output.flush()
            return
        }

        val route = path.substringBefore('?')
        val query = path.substringAfter('?', "")
        val sessionId = route.removePrefix("/p/").substringBefore('/')
        val headers = sessions[sessionId]
        val targetB64 = query.split('&').firstOrNull { it.startsWith("u=") }?.substringAfter("u=")
        val target = targetB64?.let { runCatching { String(Base64.decode(it, Base64.URL_SAFE)) }.getOrNull() }
        if (headers == null || target.isNullOrEmpty()) {
            writeHead(output, 404, mapOf("Content-Length" to "0"))
            output.flush()
            return
        }

        relay(method, target, sessionId, headers, reqHeaders["range"], output)
        output.flush()
    }

    private fun relay(
        method: String,
        target: String,
        sessionId: String,
        headers: Map<String, String>,
        clientRange: String?,
        output: OutputStream,
    ) {
        val isHead = method.equals("HEAD", ignoreCase = true)
        val builder = Request.Builder().url(target)
        headers.forEach { (k, v) -> builder.header(k, v) }
        clientRange?.let { builder.header("Range", it) }
        if (isHead) builder.head()

        val response = try {
            PlayerPlaybackNetworking.sharedPlaybackClient().newCall(builder.build()).execute()
        } catch (e: Exception) {
            Log.w(TAG, "upstream fetch failed: ${e.message}")
            writeHead(output, 502, mapOf("Content-Length" to "0"))
            return
        }

        response.use {
            val contentType = response.header("Content-Type")
            if (!isHead && looksLikeHls(target, contentType)) {
                val ip = lanIpv4()
                val original = response.body?.string().orEmpty()
                val rewritten = if (ip != null) rewriteHlsManifest(original, target, ip, sessionId) else original
                val bytes = rewritten.toByteArray()
                writeHead(
                    output,
                    200,
                    mapOf(
                        "Content-Type" to (contentType ?: "application/vnd.apple.mpegurl"),
                        "Content-Length" to bytes.size.toString(),
                        "Cache-Control" to "no-cache",
                    ),
                )
                runCatching { output.write(bytes) }
                return
            }

            val passHeaders = LinkedHashMap<String, String>()
            contentType?.let { passHeaders["Content-Type"] = it }
            response.header("Content-Length")?.let { passHeaders["Content-Length"] = it }
            response.header("Content-Range")?.let { passHeaders["Content-Range"] = it }
            passHeaders["Accept-Ranges"] = response.header("Accept-Ranges") ?: "bytes"
            writeHead(output, response.code, passHeaders)
            if (!isHead) {
                response.body?.byteStream()?.let { stream -> runCatching { copy(stream, output) } }
            }
        }
    }

    private fun rewriteHlsManifest(body: String, baseUrl: String, ip: String, sessionId: String): String {
        val uriAttr = Regex("URI=\"([^\"]*)\"")
        return body.lineSequence().joinToString("\n") { raw ->
            val line = raw.trimEnd('\r')
            when {
                // Tag lines: rewrite any embedded URI="..." (EXT-X-KEY, EXT-X-MAP, EXT-X-MEDIA, ...).
                line.startsWith("#") -> uriAttr.replace(line) { match ->
                    "URI=\"${proxyUrl(ip, sessionId, resolve(baseUrl, match.groupValues[1]))}\""
                }
                line.isBlank() -> line
                // Bare URI lines: variant playlists and media segments.
                else -> proxyUrl(ip, sessionId, resolve(baseUrl, line.trim()))
            }
        }
    }

    private fun resolve(base: String, ref: String): String {
        if (ref.startsWith("http://", true) || ref.startsWith("https://", true)) return ref
        return runCatching { URI(base).resolve(ref).toString() }.getOrDefault(ref)
    }

    private fun proxyUrl(ip: String, sessionId: String, target: String): String {
        val encoded = Base64.encodeToString(
            target.toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "http://$ip:$port/p/$sessionId?u=$encoded"
    }

    private data class RequestLine(val method: String, val path: String, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): RequestLine? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        return RequestLine(parts[0], parts[1], headers)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun writeHead(output: OutputStream, code: Int, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code ${reasonPhrase(code)}\r\n")
        headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        // Allow the receiver's player (different origin) to read every response.
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Access-Control-Expose-Headers: *\r\n")
        sb.append("Connection: close\r\n\r\n")
        output.write(sb.toString().toByteArray())
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
        }
    }

    private fun looksLikeHls(url: String, contentType: String?): Boolean {
        val ct = contentType?.lowercase().orEmpty()
        if (ct.contains("mpegurl")) return true // application/x-mpegURL, application/vnd.apple.mpegurl
        if (ct.isNotEmpty()) return false // a concrete non-HLS type (segment, dash, mp4) is authoritative
        return url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)
    }

    private fun lanIpv4(): String? = runCatching {
        val addresses = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface -> iface.inetAddresses.toList().map { iface to it } }
            .filter { (_, addr) ->
                addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress && !addr.isAnyLocalAddress
            }
        val preferred = addresses.firstOrNull { (iface, addr) ->
            addr.isSiteLocalAddress &&
                (iface.name.startsWith("wlan", true) || iface.name.startsWith("ap", true) || iface.name.startsWith("en", true))
        }
            ?: addresses.firstOrNull { (_, addr) -> addr.isSiteLocalAddress }
            ?: addresses.firstOrNull()
        preferred?.second?.hostAddress
    }.getOrNull()

    private fun reasonPhrase(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        206 -> "Partial Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        502 -> "Bad Gateway"
        else -> "OK"
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 20_000
    }
}
