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
    useYoutubeChunkedPlayback: Boolean,
    modifier: Modifier,
    playWhenReady: Boolean,
    resizeMode: PlayerResizeMode,
    useNativeController: Boolean,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val backend = remember { desktopPlaybackBackend() }
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
        onControllerReady = onControllerReady,
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
