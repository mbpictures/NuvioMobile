package com.nuvio.app.features.player.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

private const val TAG = "NuvioCastDlna"
private const val SSDP_ADDRESS = "239.255.255.250"
private const val SSDP_PORT = 1900
private const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
const val DLNA_ID_PREFIX = "dlna::"

private data class DlnaRenderer(
    val udn: String,
    val name: String,
    /** Absolute URL of the AVTransport service control endpoint. */
    val controlUrl: String,
)

/**
 * DLNA/UPnP MediaRenderer backend: discovers renderers via SSDP and controls them via SOAP
 * AVTransport actions. This is what can target a PC (e.g. Kodi with the UPnP renderer enabled) or a
 * DLNA-capable TV — protocols Google Cast can't reach. Pure JDK networking + coroutines, no library.
 */
internal class DlnaController(context: Context) : CastController {

    private val appContext = context.applicationContext
    private val wifiManager = appContext.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override var connectionState by mutableStateOf(CastConnectionState.NotConnected)
        private set

    private val deviceList = mutableStateListOf<CastDevice>()
    override val devices: List<CastDevice> get() = deviceList

    override var connectedDeviceName by mutableStateOf<String?>(null)
        private set

    override var isCasting by mutableStateOf(false)
        private set

    override var playbackSnapshot by mutableStateOf(CastPlaybackSnapshot())
        private set

    private val renderers = LinkedHashMap<String, DlnaRenderer>()
    private var connected: DlnaRenderer? = null
    private var discoveryJob: Job? = null
    private var pollJob: Job? = null

    fun detach() {
        discoveryJob?.cancel()
        pollJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    override fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = scope.launch {
            val lock = wifiManager?.createMulticastLock("nuvio-dlna")?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
            try {
                // A few rounds, since renderers answer M-SEARCH at slightly different times.
                repeat(4) {
                    if (!isActive) return@repeat
                    discoverOnce()
                    delay(1500)
                }
            } finally {
                runCatching { lock?.release() }
            }
        }
    }

    override fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    override fun connect(device: CastDevice) {
        val udn = device.id.removePrefix(DLNA_ID_PREFIX)
        val renderer = renderers[udn] ?: return
        connected = renderer
        connectedDeviceName = renderer.name
        connectionState = CastConnectionState.Connected
    }

    override fun disconnect() {
        val renderer = connected
        connected = null
        connectedDeviceName = null
        connectionState = CastConnectionState.NotConnected
        isCasting = false
        playbackSnapshot = CastPlaybackSnapshot()
        pollJob?.cancel()
        if (renderer != null) {
            scope.launch { runCatching { soap(renderer.controlUrl, "Stop", "<InstanceID>0</InstanceID>") } }
        }
    }

    override fun loadMedia(request: CastMediaRequest) {
        val renderer = connected ?: return
        scope.launch {
            val didl = buildDidl(request)
            val uri = xmlEscape(request.url)
            runCatching {
                soap(
                    renderer.controlUrl,
                    "SetAVTransportURI",
                    "<InstanceID>0</InstanceID>" +
                        "<CurrentURI>$uri</CurrentURI>" +
                        "<CurrentURIMetaData>${xmlEscape(didl)}</CurrentURIMetaData>",
                )
                soap(renderer.controlUrl, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
                if (request.startPositionMs > 0L) {
                    soap(
                        renderer.controlUrl,
                        "Seek",
                        "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit>" +
                            "<Target>${formatTime(request.startPositionMs)}</Target>",
                    )
                }
                withContext(Dispatchers.Main) { isCasting = true }
                startPolling(renderer)
            }.onFailure { Log.w(TAG, "loadMedia failed: ${it.message}") }
        }
    }

    override fun play() {
        val renderer = connected ?: return
        scope.launch { runCatching { soap(renderer.controlUrl, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>") } }
    }

    override fun pause() {
        val renderer = connected ?: return
        scope.launch { runCatching { soap(renderer.controlUrl, "Pause", "<InstanceID>0</InstanceID>") } }
    }

    override fun seekTo(positionMs: Long) {
        val renderer = connected ?: return
        scope.launch {
            runCatching {
                soap(
                    renderer.controlUrl,
                    "Seek",
                    "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>${formatTime(positionMs)}</Target>",
                )
            }
        }
    }

    private fun startPolling(renderer: DlnaRenderer) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val info = runCatching { soap(renderer.controlUrl, "GetPositionInfo", "<InstanceID>0</InstanceID>") }
                    .getOrNull()
                val transport = runCatching { soap(renderer.controlUrl, "GetTransportInfo", "<InstanceID>0</InstanceID>") }
                    .getOrNull()
                val positionMs = parseTime(extractTag(info, "RelTime"))
                val durationMs = parseTime(extractTag(info, "TrackDuration"))
                val state = extractTag(transport, "CurrentTransportState").orEmpty()
                withContext(Dispatchers.Main) {
                    playbackSnapshot = CastPlaybackSnapshot(
                        isPlaying = state == "PLAYING",
                        isBuffering = state == "TRANSITIONING",
                        positionMs = positionMs,
                        durationMs = durationMs,
                    )
                }
                delay(1000)
            }
        }
    }

    // ---- SSDP discovery ----

    private suspend fun discoverOnce() {
        val request = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: $MEDIA_RENDERER\r\n\r\n"
            ).toByteArray()

        DatagramSocket().use { socket ->
            socket.reuseAddress = true
            socket.soTimeout = 2500
            socket.send(DatagramPacket(request, request.size, InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT))
            val buffer = ByteArray(2048)
            while (true) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }
                val response = String(packet.data, 0, packet.length)
                val location = headerValue(response, "LOCATION") ?: continue
                runCatching { fetchRenderer(location) }
                    .onFailure { Log.d(TAG, "describe failed for $location: ${it.message}") }
            }
        }
    }

    private suspend fun fetchRenderer(descriptionUrl: String) {
        val xml = httpGet(descriptionUrl) ?: return
        val udn = extractTag(xml, "UDN")?.trim().orEmpty().ifEmpty { descriptionUrl }
        if (renderers.containsKey(udn)) return
        val name = extractTag(xml, "friendlyName")?.trim().orEmpty().ifEmpty { "DLNA renderer" }
        val controlPath = findAvTransportControlUrl(xml) ?: return
        val controlUrl = resolveUrl(descriptionUrl, xml, controlPath)
        val renderer = DlnaRenderer(udn, name, controlUrl)
        renderers[udn] = renderer
        withContext(Dispatchers.Main) {
            deviceList.add(CastDevice(id = DLNA_ID_PREFIX + udn, name = name, modelName = "DLNA"))
        }
    }

    /** Finds the controlURL of the AVTransport service within a device description document. */
    private fun findAvTransportControlUrl(xml: String): String? {
        var index = 0
        while (true) {
            val start = xml.indexOf("<service", index).takeIf { it >= 0 } ?: return null
            val end = xml.indexOf("</service>", start).takeIf { it >= 0 } ?: return null
            val block = xml.substring(start, end)
            if (block.contains(AV_TRANSPORT)) {
                return extractTag(block, "controlURL")?.trim()
            }
            index = end + 1
        }
    }

    private fun resolveUrl(descriptionUrl: String, xml: String, path: String): String {
        if (path.startsWith("http://", true) || path.startsWith("https://", true)) return path
        val base = extractTag(xml, "URLBase")?.trim()?.ifEmpty { null } ?: descriptionUrl
        return runCatching { URI(base).resolve(path).toString() }.getOrElse {
            val u = URL(descriptionUrl)
            "${u.protocol}://${u.host}:${u.port}${if (path.startsWith("/")) path else "/$path"}"
        }
    }

    // ---- HTTP / SOAP ----

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4000
            readTimeout = 4000
        }
        return try {
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            conn.disconnect()
        }
    }

    private fun soap(controlUrl: String, action: String, innerXml: String): String {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$AV_TRANSPORT\">$innerXml</u:$action></s:Body></s:Envelope>"
        val conn = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4000
            readTimeout = 5000
            doOutput = true
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPACTION", "\"$AV_TRANSPORT#$action\"")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        fun headerValue(response: String, name: String): String? =
            response.lineSequence()
                .firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
                ?.substringAfter(':')?.trim()

        fun extractTag(xml: String?, tag: String): String? {
            if (xml == null) return null
            // Match the tag ignoring namespace prefixes (e.g. <dc:title>) and attributes.
            val regex = Regex("<(?:\\w+:)?$tag\\b[^>]*>(.*?)</(?:\\w+:)?$tag>", RegexOption.DOT_MATCHES_ALL)
            return regex.find(xml)?.groupValues?.get(1)?.let(::unescapeXml)
        }

        fun buildDidl(request: CastMediaRequest): String {
            val contentType = request.contentType ?: guessCastContentType(request.url)
            val title = xmlEscape(request.title)
            val url = xmlEscape(request.url)
            return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
                "<dc:title>$title</dc:title>" +
                "<upnp:class>object.item.videoItem</upnp:class>" +
                "<res protocolInfo=\"http-get:*:$contentType:*\">$url</res>" +
                "</item></DIDL-Lite>"
        }

        fun xmlEscape(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        fun unescapeXml(value: String): String = value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

        fun formatTime(ms: Long): String {
            val totalSeconds = (ms / 1000).coerceAtLeast(0)
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            return "%d:%02d:%02d".format(h, m, s)
        }

        fun parseTime(value: String?): Long {
            if (value.isNullOrBlank()) return 0L
            val parts = value.trim().split(":")
            if (parts.size != 3) return 0L
            val h = parts[0].toLongOrNull() ?: return 0L
            val m = parts[1].toLongOrNull() ?: return 0L
            val s = parts[2].substringBefore('.').toLongOrNull() ?: return 0L
            return ((h * 3600) + (m * 60) + s) * 1000
        }
    }
}
