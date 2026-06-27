package com.nuvio.app.features.player.cast

import android.util.Base64
import android.util.Log
import com.nuvio.app.features.player.PlayerPlaybackNetworking
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
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

    private val chunkCache = object : LinkedHashMap<String, Chunk>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Chunk>): Boolean = size > MAX_CACHED_CHUNKS
    }
    private val chunkFetchLocks = ConcurrentHashMap<String, Any>()
    private val mediaMeta = ConcurrentHashMap<String, Pair<Long, String>>()

    private var serverSocket: ServerSocket? = null
    @Volatile private var port: Int = 0

    private val activeClients = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var rateLimitedUntilMs = 0L

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
    fun subtitleUrl(originalUrl: String): String? {
        if (!ensureStarted()) return null
        val ip = lanIpv4() ?: return null
        val encoded = Base64.encodeToString(
            originalUrl.toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "http://$ip:$port/sub.srt?u=$encoded"
    }

    @Synchronized
    fun stop() {
        sessions.clear()
        synchronized(chunkCache) { chunkCache.clear() }
        chunkFetchLocks.clear()
        mediaMeta.clear()
        activeClients.forEach { runCatching { it.close() } } // unblocks any in-flight serveFromChunks writes
        activeClients.clear()
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
                activeClients.add(client)
                try {
                    serve(client)
                } catch (e: Exception) {
                    Log.d(TAG, "request failed: ${e.message}")
                } finally {
                    activeClients.remove(client)
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
        val targetB64 = query.split('&').firstOrNull { it.startsWith("u=") }?.substringAfter("u=")
        val target = targetB64?.let { runCatching { String(Base64.decode(it, Base64.URL_SAFE)) }.getOrNull() }

        // Subtitle route: no session/headers, just fetch the sidecar file and serve it as SRT.
        if (route.startsWith("/sub")) {
            Log.i(TAG, "proxy subtitle request: $method $route target=${target != null}")
            if (target.isNullOrEmpty()) writeHead(output, 404, mapOf("Content-Length" to "0"))
            else serveSubtitle(target, output)
            output.flush()
            return
        }

        val sessionId = route.removePrefix("/p/").substringBefore('/')
        val headers = sessions[sessionId]
        Log.i(TAG, "proxy request: $method $route range=${reqHeaders["range"] ?: "-"} session=$sessionId target=${target != null}")
        if (headers == null || target.isNullOrEmpty()) {
            Log.w(TAG, "proxy 404: no session/target for '$path' (known sessions: ${sessions.keys.joinToString()})")
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
        if (method.equals("HEAD", ignoreCase = true)) {
            respondHead(target, headers, output)
            return
        }

        if (looksLikeHls(target, null)) {
            serveHls(target, sessionId, headers, output)
        } else {
            serveBuffered(target, sessionId, headers, clientRange, output)
        }
    }

    private fun respondHead(target: String, headers: Map<String, String>, output: OutputStream) {
        mediaMeta[target]?.let { (total, contentType) ->
            Log.i(TAG, "HEAD (cached) -> type=$contentType total=$total")
            writeMediaHead(output, contentType, total)
            return
        }

        val builder = Request.Builder().url(target).header("Range", "bytes=0-0")
        headers.forEach { (k, v) -> builder.header(k, v) }

        val response = try {
            PlayerPlaybackNetworking.sharedPlaybackClient().newCall(builder.build()).execute()
        } catch (e: Exception) {
            Log.w(TAG, "HEAD probe failed: ${e.message}")
            writeHead(output, 502, mapOf("Content-Length" to "0"))
            return
        }

        response.use {
            noteOriginCode(response.code)
            val contentType = effectiveContentType(response.header("Content-Type"), target)
            // On 206 the total size lives in Content-Range ("bytes 0-0/123456"); Content-Length there
            // is just the 1 probed byte. On 200 the origin ignored the range, so Content-Length is the
            // real total. Either way we never read the body.
            val total = response.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { response.code == 200 }
            Log.i(TAG, "HEAD probe -> ${response.code} type=$contentType total=${total ?: "-"}")
            if (total != null) mediaMeta[target] = total to contentType
            writeMediaHead(output, contentType, total)
        }
    }

    /** Writes the synthesized 200 head DLNA renderers expect (type, optional size, DLNA features). */
    private fun writeMediaHead(output: OutputStream, contentType: String, total: Long?) {
        val passHeaders = LinkedHashMap<String, String>()
        passHeaders["Content-Type"] = contentType
        total?.let { passHeaders["Content-Length"] = it.toString() }
        passHeaders["Accept-Ranges"] = "bytes"
        passHeaders["transferMode.dlna.org"] = "Streaming"
        passHeaders["contentFeatures.dlna.org"] = DLNA_CONTENT_FEATURES
        writeHead(output, 200, passHeaders)
    }

    private fun serveHls(target: String, sessionId: String, headers: Map<String, String>, output: OutputStream) {
        val builder = Request.Builder().url(target)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val response = try {
            PlayerPlaybackNetworking.sharedPlaybackClient().newCall(builder.build()).execute()
        } catch (e: Exception) {
            Log.w(TAG, "HLS fetch failed: ${e.message}")
            writeHead(output, 502, mapOf("Content-Length" to "0"))
            return
        }
        response.use {
            val contentType = response.header("Content-Type")
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
        }
    }

    private fun serveBuffered(
        target: String,
        sessionId: String,
        headers: Map<String, String>,
        clientRange: String?,
        output: OutputStream,
    ) {
        val rangeSpec = clientRange?.substringAfter('=', "")?.trim().orEmpty()
        if (clientRange != null && (rangeSpec.contains(',') || rangeSpec.startsWith('-'))) {
            relayPassthrough(target, headers, clientRange, output)
            return
        }
        val reqStart = parseRangeStart(clientRange)

        // Total size + content type: prefer cached metadata (Samsung issues a HEAD first, which fills
        // it) so we needn't block on an origin fetch just to write headers. Otherwise discover it by
        // fetching the first needed chunk; if even that can't be had, fall back to a plain passthrough.
        val total: Long
        val contentType: String
        val meta = mediaMeta[target]
        if (meta != null) {
            total = meta.first
            contentType = meta.second
        } else {
            val first = getChunk(target, headers, (reqStart / CHUNK_SIZE) * CHUNK_SIZE)
            if (first == null) {
                relayPassthrough(target, headers, clientRange, output)
                return
            }
            total = first.total
            contentType = effectiveContentType(first.contentType, target)
        }
        if (contentType.contains("mpegurl", ignoreCase = true)) {
            serveHls(target, sessionId, headers, output)
            return
        }

        val end = (parseRangeEnd(clientRange) ?: (total - 1)).coerceIn(0, total - 1)
        val start = reqStart.coerceIn(0, end)

        val passHeaders = LinkedHashMap<String, String>()
        passHeaders["Content-Type"] = contentType
        passHeaders["Accept-Ranges"] = "bytes"
        passHeaders["transferMode.dlna.org"] = "Streaming"
        passHeaders["contentFeatures.dlna.org"] = DLNA_CONTENT_FEATURES
        val code = if (clientRange == null) {
            passHeaders["Content-Length"] = total.toString()
            200
        } else {
            passHeaders["Content-Range"] = "bytes $start-$end/$total"
            passHeaders["Content-Length"] = (end - start + 1).toString()
            206
        }
        Log.i(TAG, "buffered GET $start-$end/$total type=$contentType")
        writeHead(output, code, passHeaders)

        serveFromChunks(target, headers, start, end, output)
    }

    /**
     * Writes [start]..[end] to the renderer as one continuous stream assembled from cache chunks. Each
     * 2 MB chunk is fetched at most once across all connections, so the TV's overlapping reads cost one
     * origin request per distinct region, not one per read. While a chunk is written (renderer-paced) we
     * warm the next one so sequential playback never stalls on origin latency. We stop when the renderer
     * closes, the range is delivered, or no chunk can be fetched for [STREAM_STALL_BUDGET_MS] (e.g. a
     * sustained throttle) — the budget resets on every chunk served, so a flowing stream never trips it.
     */
    private fun serveFromChunks(target: String, headers: Map<String, String>, start: Long, end: Long, output: OutputStream) {
        var pos = start
        var deadline = System.currentTimeMillis() + STREAM_STALL_BUDGET_MS
        while (pos <= end) {
            val chunk = getChunk(target, headers, (pos / CHUNK_SIZE) * CHUNK_SIZE)
            if (chunk == null) {
                // Rate-limited, a short read, or a failed fetch. Wait briefly and retry the same chunk
                // (single-flight keeps this from ever becoming a storm); give up only on a long stall.
                if (System.currentTimeMillis() > deadline) {
                    Log.w(TAG, "serve @$pos giving up after a sustained origin stall")
                    return
                }
                runCatching { Thread.sleep(STREAM_RETRY_BACKOFF_MS) }
                continue
            }
            // Read-ahead: warm the next few chunks while we write this one to the renderer, so a
            // sequential reader stays fed even when the origin's per-fetch latency rivals playout time.
            val nextStart = chunk.start + chunk.bytes.size
            for (i in 0 until READ_AHEAD_CHUNKS) {
                prefetchChunk(target, headers, nextStart + i.toLong() * CHUNK_SIZE, end)
            }
            val wrote = try {
                writeFromChunk(chunk, pos, end, output)
            } catch (e: Exception) {
                return // renderer closed this connection — done
            }
            if (wrote <= 0L) return
            pos += wrote
            deadline = System.currentTimeMillis() + STREAM_STALL_BUDGET_MS
        }
    }

    /**
     * Warms the chunk at [nextStart] in the background so a sequential reader's next chunk is already
     * cached when it asks. No-op past [end], while rate-limited, or if already cached; single-flight in
     * getChunk dedupes it against the live read and any other prefetch.
     */
    private fun prefetchChunk(target: String, headers: Map<String, String>, nextStart: Long, end: Long) {
        if (nextStart > end || originRateLimited()) return
        val chunkStart = (nextStart / CHUNK_SIZE) * CHUNK_SIZE
        synchronized(chunkCache) { if (chunkCache.containsKey("$target@$chunkStart")) return }
        runCatching { executor.execute { runCatching { getChunk(target, headers, chunkStart) } } }
    }

    /** Writes the portion of [chunk] that satisfies [pos]..[end]; returns the number of bytes written. */
    private fun writeFromChunk(chunk: Chunk, pos: Long, end: Long, output: OutputStream): Long {
        val chunkEnd = chunk.start + chunk.bytes.size - 1
        if (pos > chunkEnd) return 0L
        val last = minOf(end, chunkEnd)
        val offset = (pos - chunk.start).toInt()
        val length = (last - pos + 1).toInt()
        output.write(chunk.bytes, offset, length)
        return length.toLong()
    }

    private fun originRateLimited(): Boolean = System.currentTimeMillis() < rateLimitedUntilMs

    private fun noteOriginCode(code: Int) {
        if (code == 429 || code == 503) {
            rateLimitedUntilMs = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
            Log.w(TAG, "origin returned $code (rate limited); pausing upstream fetches for ${RATE_LIMIT_COOLDOWN_MS}ms")
        }
    }

    private fun getChunk(target: String, headers: Map<String, String>, chunkStart: Long): Chunk? {
        val key = "$target@$chunkStart"
        synchronized(chunkCache) { chunkCache[key]?.let { return it } }
        // Single-flight: a Samsung TV opens ~25 parallel sockets to parse one MKV, many landing in the
        // same uncached 2 MB region at once. Without this each socket fires its own origin fetch — a
        // burst that itself trips the origin's 429 throttle. Serialize per chunk so it's fetched once.
        val lock = chunkFetchLocks.computeIfAbsent(key) { Any() }
        synchronized(lock) {
            synchronized(chunkCache) { chunkCache[key]?.let { return it } } // another thread may have filled it
            val fetched = fetchChunk(target, headers, chunkStart) ?: return null
            synchronized(chunkCache) { chunkCache[key] = fetched }
            return fetched
        }
    }

    private fun fetchChunk(target: String, headers: Map<String, String>, chunkStart: Long): Chunk? {
        if (originRateLimited()) return null
        val builder = Request.Builder().url(target)
            .header("Range", "bytes=$chunkStart-${chunkStart + CHUNK_SIZE - 1}")
        headers.forEach { (k, v) -> builder.header(k, v) }
        val response = try {
            PlayerPlaybackNetworking.sharedPlaybackClient().newCall(builder.build()).execute()
        } catch (e: Exception) {
            Log.w(TAG, "chunk @$chunkStart fetch failed: ${e.message}")
            return null
        }
        response.use {
            noteOriginCode(response.code)
            if (response.code !in 200..299) return null
            val total = response.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { response.code == 200 }
                ?: return null
            // A 200 to a ranged request means the origin ignored Range and streams from offset 0; we
            // can only chunk that when the request also started at 0, else fall back to passthrough.
            if (response.code == 200 && chunkStart != 0L) return null
            val bytes = response.body?.byteStream()?.let { readUpTo(it, CHUNK_SIZE) } ?: return null
            mediaMeta[target] = total to effectiveContentType(response.header("Content-Type"), target)
            // A read shorter than the chunk that isn't the file's tail means the origin dropped the
            // connection mid-chunk. Don't cache a hole — return null so the caller refetches the chunk
            // (this is how we survive debrid/CDN origins aborting a transfer partway).
            val expected = minOf(CHUNK_SIZE.toLong(), total - chunkStart)
            if (bytes.size.toLong() < expected) {
                Log.w(TAG, "chunk @$chunkStart short ${bytes.size}/$expected B; will refetch")
                return null
            }
            Log.i(TAG, "chunk @$chunkStart fetched ${bytes.size}B (total=$total)")
            return Chunk(chunkStart, bytes, total, response.header("Content-Type"))
        }
    }

    /** Plain forward-and-pipe with the client's Range untouched — the safe fallback. */
    private fun relayPassthrough(target: String, headers: Map<String, String>, clientRange: String?, output: OutputStream) {
        val builder = Request.Builder().url(target)
        headers.forEach { (k, v) -> builder.header(k, v) }
        clientRange?.let { builder.header("Range", it) }
        val response = try {
            PlayerPlaybackNetworking.sharedPlaybackClient().newCall(builder.build()).execute()
        } catch (e: Exception) {
            Log.w(TAG, "passthrough fetch failed: ${e.message}")
            writeHead(output, 502, mapOf("Content-Length" to "0"))
            return
        }
        Log.i(TAG, "passthrough -> ${response.code} type=${response.header("Content-Type") ?: "-"}")
        response.use {
            val passHeaders = LinkedHashMap<String, String>()
            passHeaders["Content-Type"] = effectiveContentType(response.header("Content-Type"), target)
            response.header("Content-Length")?.let { passHeaders["Content-Length"] = it }
            response.header("Content-Range")?.let { passHeaders["Content-Range"] = it }
            passHeaders["Accept-Ranges"] = response.header("Accept-Ranges") ?: "bytes"
            passHeaders["transferMode.dlna.org"] = "Streaming"
            passHeaders["contentFeatures.dlna.org"] = DLNA_CONTENT_FEATURES
            writeHead(output, response.code, passHeaders)
            response.body?.byteStream()?.let { stream -> runCatching { copy(stream, output) } }
        }
    }

    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(max, 64 * 1024))
        val buf = ByteArray(64 * 1024)
        var remaining = max
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size, remaining))
            if (n == -1) break
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    // "bytes=START-END" → START (0 when absent/garbage). Suffix ranges ("bytes=-N") are filtered out
    // before this is called, so a leading '-' never reaches here.
    private fun parseRangeStart(range: String?): Long {
        val spec = range?.substringAfter('=', "")?.substringBefore(',')?.trim().orEmpty()
        return spec.substringBefore('-').trim().toLongOrNull() ?: 0L
    }

    // "bytes=START-END" → END, or null for an open-ended range ("bytes=START-").
    private fun parseRangeEnd(range: String?): Long? {
        val spec = range?.substringAfter('=', "")?.substringBefore(',')?.trim().orEmpty()
        if (!spec.contains('-')) return null
        return spec.substringAfter('-').trim().toLongOrNull()
    }

    /** Fetches a sidecar subtitle and serves it as SRT (Samsung TVs want SRT, not WebVTT). */
    private fun serveSubtitle(target: String, output: OutputStream) {
        val response = try {
            PlayerPlaybackNetworking.sharedPlaybackClient().newCall(Request.Builder().url(target).build()).execute()
        } catch (e: Exception) {
            Log.w(TAG, "subtitle fetch failed: ${e.message}")
            writeHead(output, 502, mapOf("Content-Length" to "0"))
            return
        }
        val raw = response.use { it.body?.string().orEmpty() }
        val srt = if (raw.trimStart().startsWith("WEBVTT", ignoreCase = true)) vttToSrt(raw) else raw
        val bytes = srt.toByteArray()
        writeHead(
            output,
            200,
            mapOf(
                "Content-Type" to "application/x-subrip",
                "Content-Length" to bytes.size.toString(),
                "Cache-Control" to "no-cache",
            ),
        )
        runCatching { output.write(bytes) }
    }

    /** Minimal WebVTT → SRT conversion: drop header/NOTE/STYLE, renumber cues, fix timestamps. */
    private fun vttToSrt(vtt: String): String {
        val blocks = vtt.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n[ \t]*\n"))
        val sb = StringBuilder()
        var index = 1
        for (block in blocks) {
            val lines = block.split("\n").filter { it.isNotBlank() }
            val timingIdx = lines.indexOfFirst { it.contains("-->") }
            if (timingIdx < 0) continue // header / NOTE / STYLE block
            val body = lines.drop(timingIdx + 1)
            if (body.isEmpty()) continue
            val raw = lines[timingIdx]
            val start = normalizeVttTimestamp(raw.substringBefore("-->"))
            // The end side may carry cue settings ("00:00:04.000 align:start") — keep only the time.
            val end = normalizeVttTimestamp(raw.substringAfter("-->").trim().substringBefore(' '))
            sb.append(index++).append('\n').append(start).append(" --> ").append(end).append('\n')
            body.forEach { sb.append(it).append('\n') }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun normalizeVttTimestamp(raw: String): String {
        val t = raw.trim().replace(',', '.') // normalize, then re-emit with SRT comma below
        val hhmmss = if (t.count { it == ':' } == 1) "00:$t" else t // mm:ss.mmm -> 00:mm:ss.mmm
        return hhmmss.replace('.', ',')
    }

    private class Chunk(val start: Long, val bytes: ByteArray, val total: Long, val contentType: String?)

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

    /**
     * DLNA renderers (Samsung TVs especially) key playback off the HTTP Content-Type. Debrid/CDN
     * origins routinely label video as a generic octet-stream, which the TV refuses or mis-handles,
     * so substitute a real video MIME guessed from the source file extension in that case.
     */
    private fun effectiveContentType(upstream: String?, target: String): String {
        val ct = upstream?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return if (ct.isEmpty() || ct == "application/octet-stream" || ct == "binary/octet-stream") {
            guessCastContentType(target)
        } else {
            upstream!!
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
        // Read-ahead granularity and cache ceiling (8 × 2 MB ≈ 16 MB). One 2 MB chunk covers a
        // typical MKV header, so the whole init burst is served from a single origin fetch.
        const val CHUNK_SIZE = 2 * 1024 * 1024
        const val MAX_CACHED_CHUNKS = 8
        // How many chunks ahead of the current read to warm. A 2-chunk (4 MB) lead keeps sequential
        // playback fed when origin fetch latency rivals playout time, while staying within the cache cap.
        const val READ_AHEAD_CHUNKS = 2
        // Backoff between chunk-fetch retries, and how long a served stream may make zero progress
        // (origin drops/throttles) before giving up — reset on every chunk served, so a flowing stream
        // never trips it. Kept above RATE_LIMIT_COOLDOWN_MS so a served stream can wait out a throttle
        // and resume instead of truncating the renderer's playback.
        const val STREAM_RETRY_BACKOFF_MS = 300L
        const val STREAM_STALL_BUDGET_MS = 30_000L
        // How long to pause origin fetches after a 429/503. Short, because single-flight already keeps
        // us from storming: a long freeze just turns the origin's intermittent throttle (it still
        // serves some requests) into a long renderer-visible stall. We back off briefly and retry.
        const val RATE_LIMIT_COOLDOWN_MS = 3_000L
    }
}
