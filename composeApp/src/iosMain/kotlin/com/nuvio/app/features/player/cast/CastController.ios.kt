package com.nuvio.app.features.player.cast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Bridge to the Swift Google Cast implementation. Swift implements [NuvioCastBridge] (backed by the
 * google-cast-sdk) and registers a factory at app startup, mirroring
 * [com.nuvio.app.features.player.NuvioPlayerBridge].
 *
 * Collections are exposed via count/index accessors (like the player bridge) to keep the Kotlin↔Swift
 * surface free of complex generic marshalling. The Swift side pushes change notifications through
 * [CastBridgeListener]; the Kotlin controller then re-reads the getters and republishes Compose state.
 */
interface NuvioCastBridge {
    fun setListener(listener: CastBridgeListener?)

    fun startDiscovery()
    fun stopDiscovery()
    fun getDeviceCount(): Int
    fun getDeviceId(at: Int): String
    fun getDeviceName(at: Int): String
    fun getDeviceModel(at: Int): String

    fun connect(deviceId: String)
    fun disconnect()

    /** 0 = Unavailable, 1 = NotConnected, 2 = Connecting, 3 = Connected. */
    fun getConnectionState(): Int
    fun getConnectedDeviceName(): String

    fun loadMedia(
        url: String,
        title: String,
        subtitle: String,
        posterUrl: String,
        contentType: String,
        startPositionMs: Long,
    )

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)

    fun isCasting(): Boolean
    fun getPositionMs(): Long
    fun getDurationMs(): Long
    fun getIsPlaying(): Boolean
    fun getIsBuffering(): Boolean

    fun destroy()
}

/** Swift calls [onCastStateChanged] whenever discovery, session, or remote playback state changes. */
interface CastBridgeListener {
    fun onCastStateChanged()
}

/** Registry for the cast bridge factory; Swift registers during app startup before Compose starts. */
object NuvioCastBridgeFactory {
    private var factoryRef: NuvioCastBridgeCreator? = null

    fun registerFactory(creator: NuvioCastBridgeCreator) {
        this.factoryRef = creator
    }

    fun create(): NuvioCastBridge? = factoryRef?.createBridge()

    val isRegistered: Boolean get() = factoryRef != null
}

interface NuvioCastBridgeCreator {
    fun createBridge(): NuvioCastBridge
}

private class IosCastController(private val bridge: NuvioCastBridge) : CastController {

    override var connectionState by mutableStateOf(mapState(bridge.getConnectionState()))
        private set

    private val deviceList = mutableStateListOf<CastDevice>()
    override val devices: List<CastDevice> get() = deviceList

    override var connectedDeviceName by mutableStateOf<String?>(null)
        private set

    override var isCasting by mutableStateOf(false)
        private set

    override var playbackSnapshot by mutableStateOf(CastPlaybackSnapshot())
        private set

    private val listener = object : CastBridgeListener {
        override fun onCastStateChanged() = sync()
    }

    fun attach() {
        bridge.setListener(listener)
        sync()
    }

    fun detach() {
        bridge.setListener(null)
    }

    override fun startDiscovery() {
        bridge.startDiscovery()
        refreshDevices()
    }

    override fun stopDiscovery() {
        bridge.stopDiscovery()
    }

    override fun connect(device: CastDevice) {
        bridge.connect(device.id)
    }

    override fun disconnect() {
        bridge.disconnect()
    }

    override fun loadMedia(request: CastMediaRequest) {
        bridge.loadMedia(
            url = request.url,
            title = request.title,
            subtitle = request.subtitle ?: "",
            posterUrl = request.posterUrl ?: "",
            contentType = request.contentType ?: guessCastContentType(request.url),
            startPositionMs = request.startPositionMs,
        )
    }

    override fun play() = bridge.play()
    override fun pause() = bridge.pause()
    override fun seekTo(positionMs: Long) = bridge.seekTo(positionMs)

    private fun sync() {
        connectionState = mapState(bridge.getConnectionState())
        connectedDeviceName = bridge.getConnectedDeviceName().ifBlank { null }
        isCasting = bridge.isCasting()
        playbackSnapshot = CastPlaybackSnapshot(
            isPlaying = bridge.getIsPlaying(),
            isBuffering = bridge.getIsBuffering(),
            positionMs = bridge.getPositionMs(),
            durationMs = bridge.getDurationMs(),
        )
        refreshDevices()
    }

    private fun refreshDevices() {
        deviceList.clear()
        val count = bridge.getDeviceCount()
        for (i in 0 until count) {
            deviceList.add(
                CastDevice(
                    id = bridge.getDeviceId(i),
                    name = bridge.getDeviceName(i),
                    modelName = bridge.getDeviceModel(i).ifBlank { null },
                ),
            )
        }
    }

    private companion object {
        fun mapState(raw: Int): CastConnectionState = when (raw) {
            0 -> CastConnectionState.Unavailable
            2 -> CastConnectionState.Connecting
            3 -> CastConnectionState.Connected
            else -> CastConnectionState.NotConnected
        }
    }
}

@Composable
actual fun rememberCastController(): CastController? {
    val controller = remember { NuvioCastBridgeFactory.create()?.let(::IosCastController) }
    if (controller != null) {
        DisposableEffect(controller) {
            controller.attach()
            onDispose { controller.detach() }
        }
    }
    return controller
}
