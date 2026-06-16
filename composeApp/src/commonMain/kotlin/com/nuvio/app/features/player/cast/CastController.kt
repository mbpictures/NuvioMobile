package com.nuvio.app.features.player.cast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable

/**
 * Platform-agnostic handle for casting the current stream to a TV receiver (Chromecast / Cast-enabled
 * devices). Implementations back their observable properties with Compose snapshot state, so reading
 * them inside composition triggers recomposition on change.
 *
 * Mirrors the [com.nuvio.app.features.player.ExternalPlayerPlatform] abstraction but, unlike external
 * players, keeps an ongoing session the player UI can drive (play/pause/seek) while casting.
 */
@Stable
interface CastController {
    /** Current connection lifecycle state. [CastConnectionState.Unavailable] means hide the button. */
    val connectionState: CastConnectionState

    /** Receivers discovered on the local network. Populated while [startDiscovery] is active. */
    val devices: List<CastDevice>

    /** Human-readable name of the connected receiver, or null when not connected. */
    val connectedDeviceName: String?

    /** True once media has been handed to the receiver and remote playback is the source of truth. */
    val isCasting: Boolean

    /** Latest remote playback snapshot, used to mirror progress into the local controls. */
    val playbackSnapshot: CastPlaybackSnapshot

    /** Begin scanning for receivers (call when the picker opens). Safe to call repeatedly. */
    fun startDiscovery()

    /** Stop scanning for receivers (call when the picker closes) to save battery. */
    fun stopDiscovery()

    /** Connect to [device]. Connection result is reflected in [connectionState]. */
    fun connect(device: CastDevice)

    /** Tear down the current session and stop remote playback. */
    fun disconnect()

    /** Load [request] onto the connected receiver and start remote playback. */
    fun loadMedia(request: CastMediaRequest)

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
}

/**
 * Returns the platform [CastController], or null on platforms/builds without cast support (desktop).
 * The instance is remembered for the composition's lifetime and cleaned up on disposal.
 */
@Composable
expect fun rememberCastController(): CastController?
