package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.LocalWindowChromeImmersiveRequest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.player.cast.rememberCastController
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_airs_prefix
import nuvio.composeapp.generated.resources.compose_player_downloaded
import nuvio.composeapp.generated.resources.compose_player_resize_fill
import nuvio.composeapp.generated.resources.compose_player_resize_fit
import nuvio.composeapp.generated.resources.compose_player_resize_zoom
import nuvio.composeapp.generated.resources.generic_unknown
import nuvio.composeapp.generated.resources.parental_alcohol
import nuvio.composeapp.generated.resources.parental_frightening
import nuvio.composeapp.generated.resources.parental_nudity
import nuvio.composeapp.generated.resources.parental_profanity
import nuvio.composeapp.generated.resources.parental_severity_mild
import nuvio.composeapp.generated.resources.parental_severity_moderate
import nuvio.composeapp.generated.resources.parental_severity_severe
import nuvio.composeapp.generated.resources.parental_violence
import nuvio.composeapp.generated.resources.compose_player_tba
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlayerScreenContent(args: PlayerScreenArgs) {
    LockPlayerToLandscape()

    val playerSettingsUiState by remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val p2pSettingsUiState by remember {
        P2pSettingsRepository.ensureLoaded()
        P2pSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val p2pStreamingState by P2pStreamingEngine.state.collectAsStateWithLifecycle()
    val metaScreenSettingsUiState by remember {
        MetaScreenSettingsRepository.ensureLoaded()
        MetaScreenSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchProgressUiState by remember {
        WatchProgressRepository.ensureLoaded()
        WatchProgressRepository.uiState
    }.collectAsStateWithLifecycle()
    val sourceStreamsState by PlayerStreamsRepository.sourceState.collectAsStateWithLifecycle()
    val episodeStreamsRepoState by PlayerStreamsRepository.episodeStreamsState.collectAsStateWithLifecycle()
    val metaUiState by MetaDetailsRepository.uiState.collectAsStateWithLifecycle()
    val addonsUiState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val addonSubtitles by SubtitleRepository.addonSubtitles.collectAsStateWithLifecycle()
    val isLoadingAddonSubtitles by SubtitleRepository.isLoading.collectAsStateWithLifecycle()

    val runtime = remember { PlayerScreenRuntime(args) }
    runtime.args = args

    BoxWithConstraints(
        modifier = args.modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val horizontalSafePadding = playerHorizontalSafePadding()
        val metrics = remember(maxWidth) { PlayerLayoutMetrics.fromWidth(maxWidth) }

        runtime.scope = rememberCoroutineScope()
        runtime.hapticFeedback = LocalHapticFeedback.current
        runtime.gestureController = rememberPlayerGestureController()
        runtime.playerSettingsUiState = playerSettingsUiState
        runtime.p2pSettingsUiState = p2pSettingsUiState
        runtime.p2pStreamingState = p2pStreamingState
        runtime.metaScreenSettingsUiState = metaScreenSettingsUiState
        runtime.watchedUiState = watchedUiState
        runtime.watchProgressUiState = watchProgressUiState
        runtime.sourceStreamsState = sourceStreamsState
        runtime.episodeStreamsRepoState = episodeStreamsRepoState
        runtime.metaUiState = metaUiState
        runtime.addonsUiState = addonsUiState
        runtime.addonSubtitles = addonSubtitles
        runtime.isLoadingAddonSubtitles = isLoadingAddonSubtitles
        runtime.horizontalSafePadding = horizontalSafePadding
        runtime.metrics = metrics
        runtime.sliderEdgePadding = horizontalSafePadding + metrics.horizontalPadding
        runtime.overlayBottomPadding = sliderOverlayBottomPadding(metrics)
        val systemGestureInsets = WindowInsets.systemGestures
        runtime.systemGestureEdges = PlayerSystemGestureEdges(
            leftPx = systemGestureInsets.getLeft(density, layoutDirection).toFloat(),
            rightPx = systemGestureInsets.getRight(density, layoutDirection).toFloat(),
            topPx = systemGestureInsets.getTop(density).toFloat(),
            bottomPx = systemGestureInsets.getBottom(density).toFloat(),
        )
        runtime.resizeModeFitLabel = stringResource(Res.string.compose_player_resize_fit)
        runtime.resizeModeFillLabel = stringResource(Res.string.compose_player_resize_fill)
        runtime.resizeModeZoomLabel = stringResource(Res.string.compose_player_resize_zoom)
        runtime.downloadedLabel = stringResource(Res.string.compose_player_downloaded)
        runtime.airsPrefix = stringResource(Res.string.compose_player_airs_prefix)
        runtime.tbaLabel = stringResource(Res.string.compose_player_tba)
        runtime.genericUnknownLabel = stringResource(Res.string.generic_unknown)
        runtime.parentalGuideLabels = ParentalGuideLabels(
            nudity = stringResource(Res.string.parental_nudity),
            violence = stringResource(Res.string.parental_violence),
            profanity = stringResource(Res.string.parental_profanity),
            alcohol = stringResource(Res.string.parental_alcohol),
            frightening = stringResource(Res.string.parental_frightening),
            severe = stringResource(Res.string.parental_severity_severe),
            moderate = stringResource(Res.string.parental_severity_moderate),
            mild = stringResource(Res.string.parental_severity_mild),
        )
        if (runtime.playerMetaVideos.isEmpty()) {
            runtime.playerMetaVideos = MetaDetailsRepository.peek(
                args.parentMetaType,
                args.parentMetaId,
            )?.videos ?: emptyList()
        }
        if (runtime.lastSyncedSettingsResizeMode != playerSettingsUiState.resizeMode) {
            runtime.resizeMode = playerSettingsUiState.resizeMode
            runtime.lastSyncedSettingsResizeMode = playerSettingsUiState.resizeMode
        }
        runtime.resetIdentityStateIfNeeded()

        val keepScreenAwake = runtime.errorMessage == null &&
            (runtime.playbackSnapshot.isPlaying ||
                (runtime.shouldPlay && runtime.playbackSnapshot.isLoading))
        EnterImmersivePlayerMode(keepScreenAwake = keepScreenAwake)
        runtime.pictureInPictureController = ManagePlayerPictureInPicture(
            isPlaying = runtime.playbackSnapshot.isPlaying,
            playerSize = runtime.layoutSize,
        )
        runtime.fullscreenController = LocalPlayerFullscreenController.current

        // Ask a custom window chrome (the Windows title bar) to auto-hide while the player is open
        // so the video is unobstructed; it reappears when the pointer returns to the top edge.
        val windowChromeImmersiveRequest = LocalWindowChromeImmersiveRequest.current
        DisposableEffect(windowChromeImmersiveRequest) {
            windowChromeImmersiveRequest?.invoke(true)
            onDispose { windowChromeImmersiveRequest?.invoke(false) }
        }

        val fullscreenController = runtime.fullscreenController
        if (fullscreenController != null) {
            DisposableEffect(fullscreenController) {
                val wasFullscreenOnEntry = fullscreenController.isFullscreen
                onDispose {
                    if (fullscreenController.isFullscreen != wasFullscreenOnEntry) {
                        fullscreenController.toggle()
                    }
                }
            }
        }

        runtime.castController = rememberCastController()
        runtime.BindCastEffects()

        runtime.BindPlayerRuntimeEffects()
        runtime.RenderPlayerRuntimeUi()
    }
}
