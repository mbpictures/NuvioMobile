package com.nuvio.app.features.details

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.ui.LocalWindowChromeTopInset
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.features.details.components.DetailActionButtons
import com.nuvio.app.features.details.components.DetailAdditionalInfoSection
import com.nuvio.app.features.details.components.DetailCastSection
import com.nuvio.app.features.details.components.DetailHero
import com.nuvio.app.features.details.components.DetailMetaInfo
import com.nuvio.app.features.details.components.DetailPosterRailSection
import com.nuvio.app.features.details.components.DetailProductionSection
import com.nuvio.app.features.details.components.DetailSecondaryAction
import com.nuvio.app.features.details.components.DetailSeriesContent
import com.nuvio.app.features.details.components.DetailTrailersSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsScreen
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.action_saved
import nuvio.composeapp.generated.resources.action_save
import nuvio.composeapp.generated.resources.details_check_connection
import nuvio.composeapp.generated.resources.details_failed_to_load
import nuvio.composeapp.generated.resources.details_more_like_this
import nuvio.composeapp.generated.resources.details_servers_unreachable
import nuvio.composeapp.generated.resources.hero_mark_unwatched
import nuvio.composeapp.generated.resources.hero_mark_watched
import nuvio.composeapp.generated.resources.streams_select_episode_prompt
import org.jetbrains.compose.resources.stringResource

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
internal fun MetaDetailsScreenDesktop(
    type: String,
    id: String,
    onBack: () -> Unit,
    onLaunchStream: ((target: PlayableTarget, stream: StreamItem, forceExternal: Boolean, forceInternal: Boolean, resumePositionMs: Long?, resumeProgressFraction: Float?) -> Unit)? = null,
    onOpenMeta: ((MetaPreview) -> Unit)? = null,
    onCastClick: ((MetaPerson, String?) -> Unit)? = null,
    onCompanyClick: ((MetaCompany, String) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val localDensity = LocalDensity.current
    val detailsScope = rememberCoroutineScope()
    var metaHeight by remember {
        mutableStateOf(0.dp)
    }
    val uiState by MetaDetailsRepository.uiState.collectAsStateWithLifecycle()
    val displayedMeta = uiState.meta?.takeIf { it.type == type && it.id == id }
        ?: MetaDetailsRepository.peek(type, id)
    val libraryUiState by remember {
        LibraryRepository.ensureLoaded()
        LibraryRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchProgressUiState by remember {
        WatchProgressRepository.ensureLoaded()
        WatchProgressRepository.uiState
    }.collectAsStateWithLifecycle()
    val metaScreenSettingsUiState by remember {
        MetaScreenSettingsRepository.ensureLoaded()
        MetaScreenSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val networkStatusUiState by NetworkStatusRepository.uiState.collectAsStateWithLifecycle()

    var autoLoadAttempted by remember(type, id) { mutableStateOf(false) }
    var observedOfflineState by remember(type, id) { mutableStateOf(false) }
    var selectedPlayable by remember(type, id) { mutableStateOf<PlayableTarget?>(null) }

    LaunchedEffect(type, id, displayedMeta, uiState.isLoading, autoLoadAttempted) {
        if (!autoLoadAttempted && displayedMeta == null && !uiState.isLoading) {
            autoLoadAttempted = true
            MetaDetailsRepository.load(type, id)
        }
    }

    LaunchedEffect(networkStatusUiState.condition, displayedMeta, uiState.isLoading, type, id) {
        when (networkStatusUiState.condition) {
            NetworkCondition.NoInternet,
            NetworkCondition.ServersUnreachable,
            -> observedOfflineState = true

            NetworkCondition.Online -> {
                if (!observedOfflineState) return@LaunchedEffect
                observedOfflineState = false
                if (displayedMeta == null && !uiState.isLoading) {
                    MetaDetailsRepository.load(type, id)
                }
            }

            NetworkCondition.Unknown,
            NetworkCondition.Checking,
            -> Unit
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            displayedMeta == null && uiState.isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            displayedMeta == null && uiState.errorMessage != null -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.details_failed_to_load),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = when (networkStatusUiState.condition) {
                            NetworkCondition.NoInternet -> stringResource(Res.string.details_check_connection)
                            NetworkCondition.ServersUnreachable -> stringResource(Res.string.details_servers_unreachable)
                            else -> uiState.errorMessage.orEmpty()
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            NetworkStatusRepository.requestRefresh(force = true)
                            MetaDetailsRepository.load(type, id)
                        },
                    ) {
                        Text(stringResource(Res.string.action_retry))
                    }
                }
            }

            displayedMeta != null -> {
                val meta = displayedMeta
                val hasEpisodes = meta.videos.any { it.season != null || it.episode != null }
                val isSeriesLike = meta.type == "series" || hasEpisodes
                val isSaved = remember(
                    libraryUiState.items,
                    libraryUiState.sections,
                    libraryUiState.sourceMode,
                    meta.id,
                    meta.type,
                ) { LibraryRepository.isSaved(meta.id, meta.type) }
                val metaPreview = remember(meta) { meta.toMetaPreview() }
                val isWatched = remember(watchedUiState.watchedKeys, metaPreview) {
                    WatchingState.isPosterWatched(
                        watchedKeys = watchedUiState.watchedKeys,
                        item = metaPreview,
                    )
                }
                val toggleWatched = remember(metaPreview) {
                    {
                        detailsScope.launch {
                            WatchingActions.togglePosterWatched(metaPreview)
                        }
                        Unit
                    }
                }
                val progressByVideoId = remember(watchProgressUiState.entries) {
                    watchProgressUiState.byVideoId
                }
                val movieProgress = progressByVideoId[meta.id]?.takeUnless { it.isCompleted }

                var episodeImdbRatings by remember(meta.id, meta.type) {
                    mutableStateOf<Map<Pair<Int, Int>, Double>>(emptyMap())
                }
                LaunchedEffect(meta.id, meta.videos) {
                    if (!meta.isSeriesLikeForEpisodeRatings()) {
                        episodeImdbRatings = emptyMap()
                        return@LaunchedEffect
                    }
                    val imdbId = extractImdbId(meta.id) ?: extractImdbId(id)
                    val tmdbId = extractTmdbId(meta.id)
                        ?: extractTmdbId(id)
                        ?: TmdbService.ensureTmdbId(meta.id, meta.type)?.toIntOrNull()
                        ?: TmdbService.ensureTmdbId(id, type)?.toIntOrNull()
                    if (imdbId == null && tmdbId == null) {
                        episodeImdbRatings = emptyMap()
                        return@LaunchedEffect
                    }
                    episodeImdbRatings = ImdbEpisodeRatingsRepository.getEpisodeRatings(
                        imdbId = imdbId,
                        tmdbId = tmdbId,
                    )
                }

                LaunchedEffect(meta.id, meta.type, isSeriesLike, movieProgress?.lastPositionMs) {
                    if (isSeriesLike) return@LaunchedEffect
                    selectedPlayable = PlayableTarget(
                        type = meta.type,
                        videoId = meta.id,
                        parentMetaId = meta.id,
                        parentMetaType = meta.type,
                        title = meta.name,
                        logo = meta.logo,
                        poster = meta.poster,
                        background = meta.background,
                        seasonNumber = null,
                        episodeNumber = null,
                        episodeTitle = null,
                        episodeThumbnail = null,
                        pauseDescription = meta.description,
                        resumePositionMs = movieProgress?.lastPositionMs,
                    )
                }

                val onEpisodeClick: (MetaVideo) -> Unit = { video ->
                    val season = video.season
                    val episode = video.episode
                    val playbackVideoId = buildPlaybackVideoId(
                        parentMetaId = meta.id,
                        seasonNumber = season,
                        episodeNumber = episode,
                        fallbackVideoId = video.id,
                    )
                    val streamVideoId = video.id.takeIf { it.isNotBlank() } ?: playbackVideoId
                    val savedProgress = watchProgressUiState.byVideoId[streamVideoId]
                        ?.takeUnless { it.isCompleted }
                    selectedPlayable = PlayableTarget(
                        type = meta.type,
                        videoId = streamVideoId,
                        parentMetaId = meta.id,
                        parentMetaType = meta.type,
                        title = meta.name,
                        logo = meta.logo,
                        poster = meta.poster,
                        background = meta.background,
                        seasonNumber = season,
                        episodeNumber = episode,
                        episodeTitle = video.title,
                        episodeThumbnail = video.thumbnail,
                        pauseDescription = video.overview,
                        resumePositionMs = savedProgress?.lastPositionMs,
                    )
                }

                val detailsScroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(detailsScroll),
                ) {
                    DetailHero(
                        meta = meta,
                        isTablet = true,
                        scrollOffset = detailsScroll.value,
                        onHeightChanged = { },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(0.55f)
                                .onGloballyPositioned{ coordinates ->
                                    metaHeight = with(localDensity) { coordinates.size.height.toDp() }
                                },
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            DetailActionButtons(
                                secondaryActions = listOf(
                                    DetailSecondaryAction(
                                        label = if (isWatched) {
                                            stringResource(Res.string.hero_mark_unwatched)
                                        } else {
                                            stringResource(Res.string.hero_mark_watched)
                                        },
                                        icon = if (isWatched) {
                                            Icons.Default.CheckCircle
                                        } else {
                                            Icons.Default.CheckCircleOutline
                                        },
                                        isActive = isWatched,
                                        onClick = toggleWatched,
                                    ),
                                    DetailSecondaryAction(
                                        label = if (isSaved) {
                                            stringResource(Res.string.action_saved)
                                        } else {
                                            stringResource(Res.string.action_save)
                                        },
                                        icon = if (isSaved) Icons.Default.Check else Icons.Default.Add,
                                        isActive = isSaved,
                                        onClick = {
                                            LibraryRepository.toggleSaved(meta.toLibraryItem(savedAtEpochMs = 0L))
                                        },
                                    ),
                                ),
                                isTablet = true,
                                showPlayButton = false,
                            )

                            DetailMetaInfo(meta = meta)

                            if (hasEpisodes) {
                                DetailSeriesContent(
                                    meta = meta,
                                    episodeCardStyle = metaScreenSettingsUiState.episodeCardStyle,
                                    progressByVideoId = progressByVideoId,
                                    watchedKeys = watchedUiState.watchedKeys,
                                    episodeRatings = episodeImdbRatings,
                                    blurUnwatchedEpisodes = metaScreenSettingsUiState.blurUnwatchedEpisodes,
                                    onEpisodeClick = onEpisodeClick,
                                )
                            }

                            if (meta.cast.isNotEmpty()) {
                                DetailCastSection(
                                    cast = meta.cast,
                                    onCastClick = onCastClick,
                                    sharedTransitionScope = sharedTransitionScope,
                                    animatedVisibilityScope = animatedVisibilityScope,
                                )
                            }

                            if (meta.trailers.isNotEmpty()) {
                                DetailTrailersSection(
                                    trailers = meta.trailers,
                                    onTrailerClick = { },
                                )
                            }

                            if (meta.productionCompanies.isNotEmpty() || meta.networks.isNotEmpty()) {
                                DetailProductionSection(meta = meta, onCompanyClick = onCompanyClick)
                            }

                            val hasAdditionalInfo = meta.status != null ||
                                meta.releaseInfo != null ||
                                meta.runtime != null ||
                                meta.ageRating != null ||
                                meta.country != null ||
                                meta.language != null
                            if (hasAdditionalInfo) {
                                DetailAdditionalInfoSection(meta = meta)
                            }

                            if (meta.moreLikeThis.isNotEmpty()) {
                                DetailPosterRailSection(
                                    title = stringResource(Res.string.details_more_like_this),
                                    items = meta.moreLikeThis,
                                    watchedKeys = watchedUiState.watchedKeys,
                                    onPosterClick = onOpenMeta,
                                )
                            }

                            Spacer(modifier = Modifier.height(32.dp))
                        }

                        Box(modifier = Modifier.weight(0.45f).height(metaHeight).verticalScroll(rememberScrollState())) {
                            val target = selectedPlayable
                            if (target != null) {
                                StreamsScreen(
                                    type = target.type,
                                    videoId = target.videoId,
                                    parentMetaId = target.parentMetaId,
                                    parentMetaType = target.parentMetaType,
                                    title = target.title,
                                    logo = target.logo,
                                    poster = target.poster,
                                    background = target.background,
                                    seasonNumber = target.seasonNumber,
                                    episodeNumber = target.episodeNumber,
                                    episodeTitle = target.episodeTitle,
                                    episodeThumbnail = target.episodeThumbnail,
                                    resumePositionMs = target.resumePositionMs,
                                    embedded = true,
                                    onStreamSelected = { stream, posMs, frac ->
                                        onLaunchStream?.invoke(target, stream, false, false, posMs, frac)
                                    },
                                    onStreamActionOpen = { stream, openExt, posMs, frac ->
                                        onLaunchStream?.invoke(target, stream, openExt, !openExt, posMs, frac)
                                    },
                                    onBack = { selectedPlayable = null },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = stringResource(Res.string.streams_select_episode_prompt),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                NuvioBackButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 16.dp, top = 16.dp + LocalWindowChromeTopInset.current)
                        .zIndex(2f),
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                    contentColor = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}
