package com.nuvio.app.features.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal object MacOSLibMpvPlayerBackend : DesktopPlaybackBackend {
    private val sessionOptions = MpvSessionOptions(
        hwdec = "videotoolbox-copy",
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.0 Safari/605.1.15",
        logLevel = "v",
    )

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
        var session by remember { mutableStateOf<MpvSession?>(null) }
        var initFailure by remember { mutableStateOf<String?>(null) }
        var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
        var frame by remember { mutableStateOf<ImageBitmap?>(null) }

        DisposableEffect(Unit) {
            val createdRef = arrayOfNulls<MpvSession>(1)
            try {
                val created = MpvSession(LibMpv.INSTANCE, sessionOptions)
                createdRef[0] = created
                session = created
            } catch (t: Throwable) {
                initFailure = t.message ?: "Failed to initialize libmpv"
            }
            onDispose {
                createdRef[0]?.dispose()
                session = null
            }
        }

        LaunchedEffect(initFailure) {
            initFailure?.let(onError)
        }

        LaunchedEffect(session, sourceUrl, sourceAudioUrl, sourceHeaders) {
            session?.load(sourceUrl, sourceAudioUrl, sourceHeaders, playWhenReady)
        }

        LaunchedEffect(session, playWhenReady) {
            session?.setPaused(!playWhenReady)
        }

        LaunchedEffect(session, resizeMode) {
            session?.setResizeMode(resizeMode)
        }

        val controller = remember(session) {
            session?.let { sess ->
                object : PlayerEngineController {
                    override fun play() = sess.setPaused(false)
                    override fun pause() = sess.setPaused(true)
                    override fun seekTo(positionMs: Long) = sess.seekTo(positionMs)
                    override fun seekBy(offsetMs: Long) = sess.seekBy(offsetMs)
                    override fun retry() = sess.retry()
                    override fun setPlaybackSpeed(speed: Float) = sess.setSpeed(speed)
                    override fun getAudioTracks(): List<AudioTrack> = sess.audioTracks()
                    override fun getSubtitleTracks(): List<SubtitleTrack> = sess.subtitleTracks()
                    override fun selectAudioTrack(index: Int) = sess.selectAudioTrack(index)
                    override fun selectSubtitleTrack(index: Int) = sess.selectSubtitleTrack(index)
                    override fun setSubtitleUri(url: String) = sess.addExternalSubtitle(url)
                    override fun clearExternalSubtitle() = sess.clearExternalSubtitle()
                    override fun clearExternalSubtitleAndSelect(trackIndex: Int) =
                        sess.clearExternalSubtitleAndSelect(trackIndex)
                    override fun applySubtitleStyle(style: SubtitleStyleState) = sess.applySubtitleStyle(style)
                    override fun setSubtitleDelayMs(delayMs: Int) = sess.setSubtitleDelayMs(delayMs)
                }
            }
        }

        LaunchedEffect(controller) {
            controller?.let(onControllerReady)
        }

        LaunchedEffect(session) {
            val s = session ?: return@LaunchedEffect
            while (isActive) {
                delay(250)
                onSnapshot(s.snapshot())
                onError(s.consumeError())
            }
        }

        LaunchedEffect(session, surfaceSize) {
            val s = session ?: return@LaunchedEffect
            if (surfaceSize.width <= 0 || surfaceSize.height <= 0) return@LaunchedEffect
            while (isActive) {
                if (s.hasNewFrame()) {
                    val rendered = s.renderFrame(surfaceSize.width, surfaceSize.height)
                    if (rendered != null) frame = rendered
                }
                delay(8)
            }
        }

        Box(
            modifier = modifier
                .background(Color.Black)
                .onSizeChanged { surfaceSize = it },
            contentAlignment = Alignment.Center,
        ) {
            frame?.let { bm ->
                Image(
                    bitmap = bm,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = when (resizeMode) {
                        PlayerResizeMode.Fit -> ContentScale.Fit
                        PlayerResizeMode.Fill -> ContentScale.FillBounds
                        PlayerResizeMode.Zoom -> ContentScale.Crop
                    },
                )
            }
        }
    }
}
