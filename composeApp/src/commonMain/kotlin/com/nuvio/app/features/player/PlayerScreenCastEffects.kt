package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.nuvio.app.features.player.cast.CastConnectionState
import com.nuvio.app.features.player.cast.CastMediaRequest
import com.nuvio.app.features.player.cast.CastSubtitle

@Composable
internal fun PlayerScreenRuntime.BindCastEffects() {
    val cast = castController ?: return

    // When a receiver connects, hand it the current stream at the local position and pause locally
    // so playback doesn't run in two places at once.
    LaunchedEffect(cast) {
        snapshotFlow { cast.connectionState to cast.isCasting }
            .collect { (state, casting) ->
                if (state == CastConnectionState.Connected && !casting) {
                    // Only an external (addon) subtitle can be handed to a receiver; embedded tracks
                    // are the renderer's to choose. Captured at cast start — changing it mid-cast
                    // doesn't re-push (would require reloading the receiver and losing position).
                    val externalSubtitles = selectedAddonSubtitle?.let {
                        listOf(CastSubtitle(url = it.url, language = it.language, label = it.display))
                    }.orEmpty()
                    cast.loadMedia(
                        CastMediaRequest(
                            url = activeSourceUrl,
                            title = title,
                            subtitle = activeStreamTitle,
                            posterUrl = poster ?: background,
                            headers = activeSourceHeaders,
                            startPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L),
                            subtitles = externalSubtitles,
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
