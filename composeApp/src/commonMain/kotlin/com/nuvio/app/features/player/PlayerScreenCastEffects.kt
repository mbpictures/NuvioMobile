package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.nuvio.app.features.player.cast.CastConnectionState
import com.nuvio.app.features.player.cast.CastMediaRequest

@Composable
internal fun PlayerScreenRuntime.BindCastEffects() {
    val cast = castController ?: return

    // When a receiver connects, hand it the current stream at the local position and pause locally
    // so playback doesn't run in two places at once.
    LaunchedEffect(cast) {
        snapshotFlow { cast.connectionState to cast.isCasting }
            .collect { (state, casting) ->
                if (state == CastConnectionState.Connected && !casting) {
                    cast.loadMedia(
                        CastMediaRequest(
                            url = activeSourceUrl,
                            title = title,
                            subtitle = activeStreamTitle,
                            posterUrl = poster ?: background,
                            headers = activeSourceHeaders,
                            startPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L),
                        ),
                    )
                    shouldPlay = false
                    playerController?.pause()
                }
            }
    }

    // While casting, mirror the receiver's progress into the local controls; when casting stops,
    // resume local playback from where the TV left off.
    LaunchedEffect(cast) {
        var previouslyCasting = false
        snapshotFlow { cast.isCasting to cast.playbackSnapshot }
            .collect { (casting, snap) ->
                if (casting) {
                    playbackSnapshot = playbackSnapshot.copy(
                        isPlaying = snap.isPlaying,
                        isLoading = snap.isBuffering,
                        positionMs = snap.positionMs,
                        durationMs = if (snap.durationMs > 0L) snap.durationMs else playbackSnapshot.durationMs,
                    )
                } else if (previouslyCasting) {
                    val resumeMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
                    playerController?.seekTo(resumeMs)
                    shouldPlay = true
                    playerController?.play()
                }
                previouslyCasting = casting
            }
    }
}
