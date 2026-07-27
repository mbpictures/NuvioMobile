package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
actual fun PlatformPlayerSurface(
    sourceUrl: String,
    sourceAudioUrl: String?,
    sourceHeaders: Map<String, String>,
    sourceResponseHeaders: Map<String, String>,
    externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle>,
    streamType: String?,
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    initialPositionMs: Long?,
    initialPositionRequestKey: String?,
    resizeMode: PlayerResizeMode,
    useNativeController: Boolean,
    onInitialPositionHandled: (key: String, handled: Boolean) -> Unit,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val backend = remember { desktopPlaybackBackend() }
    // The desktop backends have no start-position parameter, so the initial position is applied
    // by seeking once the controller for this request key becomes available.
    val handledPositionKeys = remember { mutableSetOf<String>() }
    backend.PlayerSurface(
        sourceUrl = sourceUrl,
        sourceAudioUrl = sourceAudioUrl,
        sourceHeaders = sourceHeaders,
        sourceResponseHeaders = sourceResponseHeaders,
        useYoutubeChunkedPlayback = useYoutubeChunkedPlayback,
        modifier = modifier,
        playWhenReady = playWhenReady,
        resizeMode = resizeMode,
        useNativeController = useNativeController,
        onControllerReady = { controller ->
            val startPositionMs = initialPositionMs?.takeIf { it > 0L }
            val key = initialPositionRequestKey
            if (key != null && handledPositionKeys.add(key)) {
                if (startPositionMs != null) {
                    controller.seekTo(startPositionMs)
                }
                onInitialPositionHandled(key, startPositionMs != null)
            } else if (key == null && startPositionMs != null) {
                controller.seekTo(startPositionMs)
            }
            onControllerReady(controller)
        },
        onSnapshot = onSnapshot,
        onError = onError,
    )
}

internal interface DesktopPlaybackBackend {
    @Composable
    fun PlayerSurface(
        sourceUrl: String,
        sourceAudioUrl: String?,
        sourceHeaders: Map<String, String>,
        sourceResponseHeaders: Map<String, String>,
        useYoutubeChunkedPlayback: Boolean,
        modifier: Modifier,
        playWhenReady: Boolean,
        resizeMode: PlayerResizeMode,
        useNativeController: Boolean,
        onControllerReady: (PlayerEngineController) -> Unit,
        onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
        onError: (String?) -> Unit,
    )
}

private fun desktopPlaybackBackend(): DesktopPlaybackBackend {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        osName.contains("mac") -> MacOSLibMpvPlayerBackend
        osName.contains("win") -> WindowsMpvPlayerBackend
        osName.contains("linux") || osName.contains("nux") -> LinuxLibMpvPlayerBackend
        else -> UnsupportedDesktopPlaybackBackend(osName.ifBlank { "unknown" })
    }
}

private class UnsupportedDesktopPlaybackBackend(
    private val osName: String,
) : DesktopPlaybackBackend {
    @Composable
    override fun PlayerSurface(
        sourceUrl: String,
        sourceAudioUrl: String?,
        sourceHeaders: Map<String, String>,
        sourceResponseHeaders: Map<String, String>,
        useYoutubeChunkedPlayback: Boolean,
        modifier: Modifier,
        playWhenReady: Boolean,
        resizeMode: PlayerResizeMode,
        useNativeController: Boolean,
        onControllerReady: (PlayerEngineController) -> Unit,
        onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
        onError: (String?) -> Unit,
    ) {
        LaunchedEffect(osName) {
            onError("Desktop playback is not implemented for $osName")
        }
        Box(modifier = modifier.background(Color.Black))
    }
}
