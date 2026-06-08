package com.nuvio.app.features.player.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage

private const val TAG = "NuvioCast"

/**
 * Google Cast (Chromecast) implementation. Discovery is done through [MediaRouter] so we can render a
 * custom Compose picker; connection and playback go through the Cast framework's [CastContext]
 * session manager and [RemoteMediaClient].
 */
internal class AndroidCastController(
    private val castContext: CastContext,
    private val mediaRouter: MediaRouter,
    private val routeSelector: MediaRouteSelector,
) : CastController {

    override var connectionState by mutableStateOf(mapCastState(castContext.castState))
        private set

    private val deviceList = mutableStateListOf<CastDevice>()
    override val devices: List<CastDevice> get() = deviceList

    override var connectedDeviceName by mutableStateOf<String?>(null)
        private set

    override var isCasting by mutableStateOf(false)
        private set

    override var playbackSnapshot by mutableStateOf(CastPlaybackSnapshot())
        private set

    private var remoteMediaClient: RemoteMediaClient? = null

    private val castStateListener = CastStateListener { state -> updateStateFromCast(state) }

    private val mediaRouterCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
    }

    private val progressListener = RemoteMediaClient.ProgressListener { progressMs, durationMs ->
        playbackSnapshot = playbackSnapshot.copy(positionMs = progressMs, durationMs = durationMs)
    }

    private val mediaClientCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val client = remoteMediaClient ?: return
            playbackSnapshot = playbackSnapshot.copy(
                isPlaying = client.isPlaying,
                isBuffering = client.isBuffering,
            )
            isCasting = client.hasMediaSession()
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            connectionState = CastConnectionState.Connecting
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) = bindSession(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = bindSession(session)
        override fun onSessionResuming(session: CastSession, sessionId: String) {}

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            unbindSession()
            updateStateFromCast()
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            unbindSession()
            updateStateFromCast()
        }

        override fun onSessionEnding(session: CastSession) {}

        override fun onSessionEnded(session: CastSession, error: Int) {
            unbindSession()
            updateStateFromCast()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    fun attach() {
        castContext.addCastStateListener(castStateListener)
        castContext.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        mediaRouter.addCallback(
            routeSelector,
            mediaRouterCallback,
            MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY,
        )
        castContext.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let(::bindSession)
        updateStateFromCast()
        refreshRoutes()
    }

    fun detach() {
        castContext.removeCastStateListener(castStateListener)
        castContext.sessionManager.removeSessionManagerListener(sessionListener, CastSession::class.java)
        mediaRouter.removeCallback(mediaRouterCallback)
        remoteMediaClient?.unregisterCallback(mediaClientCallback)
        remoteMediaClient?.removeProgressListener(progressListener)
    }

    override fun startDiscovery() {
        mediaRouter.addCallback(
            routeSelector,
            mediaRouterCallback,
            MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN,
        )
        refreshRoutes()
    }

    override fun stopDiscovery() {
        // Downgrade to passive discovery rather than removing the callback entirely, so the cast
        // button keeps reflecting device availability while the player stays open.
        mediaRouter.addCallback(
            routeSelector,
            mediaRouterCallback,
            MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY,
        )
    }

    override fun connect(device: CastDevice) {
        val route = mediaRouter.routes.firstOrNull { it.id == device.id }
        if (route == null) {
            Log.w(TAG, "connect: no route for device ${device.id}")
            return
        }
        mediaRouter.selectRoute(route)
    }

    override fun disconnect() {
        castContext.sessionManager.endCurrentSession(true)
        mediaRouter.unselect(MediaRouter.UNSELECT_REASON_DISCONNECTED)
    }

    override fun loadMedia(request: CastMediaRequest) {
        val client = castContext.sessionManager.currentCastSession?.remoteMediaClient
        if (client == null) {
            Log.w(TAG, "loadMedia: no remote media client (not connected)")
            return
        }
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, request.title)
            request.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            request.posterUrl?.takeIf { it.isNotBlank() }?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val mediaInfo = MediaInfo.Builder(request.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(request.contentType ?: guessCastContentType(request.url))
            .setMetadata(metadata)
            .build()
        val loadRequest = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .setCurrentTime(request.startPositionMs)
            .build()
        client.load(loadRequest)
        isCasting = true
    }

    override fun play() {
        remoteMediaClient?.play()
    }

    override fun pause() {
        remoteMediaClient?.pause()
    }

    override fun seekTo(positionMs: Long) {
        remoteMediaClient?.seek(MediaSeekOptions.Builder().setPosition(positionMs).build())
    }

    private fun bindSession(session: CastSession) {
        connectionState = CastConnectionState.Connected
        connectedDeviceName = session.castDevice?.friendlyName
        val client = session.remoteMediaClient
        remoteMediaClient = client
        client?.registerCallback(mediaClientCallback)
        client?.addProgressListener(progressListener, PROGRESS_INTERVAL_MS)
        isCasting = client?.hasMediaSession() == true
    }

    private fun unbindSession() {
        remoteMediaClient?.unregisterCallback(mediaClientCallback)
        remoteMediaClient?.removeProgressListener(progressListener)
        remoteMediaClient = null
        connectedDeviceName = null
        isCasting = false
        playbackSnapshot = CastPlaybackSnapshot()
    }

    private fun refreshRoutes() {
        deviceList.clear()
        mediaRouter.routes
            .filter { it.matchesSelector(routeSelector) && !it.isDefault }
            .forEach { deviceList.add(CastDevice(id = it.id, name = it.name, modelName = it.description)) }
    }

    private fun updateStateFromCast(state: Int = castContext.castState) {
        connectionState = mapCastState(state)
        if (state != CastState.CONNECTED) {
            connectedDeviceName = null
            isCasting = false
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 500L

        fun mapCastState(state: Int): CastConnectionState = when (state) {
            CastState.NO_DEVICES_AVAILABLE -> CastConnectionState.Unavailable
            CastState.NOT_CONNECTED -> CastConnectionState.NotConnected
            CastState.CONNECTING -> CastConnectionState.Connecting
            CastState.CONNECTED -> CastConnectionState.Connected
            else -> CastConnectionState.NotConnected
        }
    }
}

/**
 * Merges the Google Cast (Chromecast) and DLNA/UPnP backends behind a single [CastController] so both
 * device types appear in one picker. Device ids carry their backend (DLNA ids are prefixed with
 * [DLNA_ID_PREFIX]); control calls route to whichever backend currently owns the session.
 */
internal class CombinedAndroidCastController(
    private val cast: AndroidCastController?,
    private val dlna: DlnaController,
) : CastController {

    override val connectionState: CastConnectionState
        get() {
            val c = cast?.connectionState ?: CastConnectionState.Unavailable
            val d = dlna.connectionState
            return when {
                c == CastConnectionState.Connected || d == CastConnectionState.Connected -> CastConnectionState.Connected
                c == CastConnectionState.Connecting || d == CastConnectionState.Connecting -> CastConnectionState.Connecting
                c == CastConnectionState.NotConnected || d == CastConnectionState.NotConnected -> CastConnectionState.NotConnected
                else -> CastConnectionState.Unavailable
            }
        }

    override val devices: List<CastDevice>
        get() = (cast?.devices ?: emptyList()) + dlna.devices

    override val connectedDeviceName: String?
        get() = cast?.connectedDeviceName ?: dlna.connectedDeviceName

    override val isCasting: Boolean
        get() = cast?.isCasting == true || dlna.isCasting

    override val playbackSnapshot: CastPlaybackSnapshot
        get() = when {
            dlna.isCasting -> dlna.playbackSnapshot
            cast?.isCasting == true -> cast.playbackSnapshot
            else -> CastPlaybackSnapshot()
        }

    private val dlnaActive: Boolean
        get() = dlna.connectionState == CastConnectionState.Connected || dlna.isCasting

    fun attach() {
        cast?.attach()
    }

    fun detach() {
        cast?.detach()
        dlna.detach()
    }

    override fun startDiscovery() {
        cast?.startDiscovery()
        dlna.startDiscovery()
    }

    override fun stopDiscovery() {
        cast?.stopDiscovery()
        dlna.stopDiscovery()
    }

    override fun connect(device: CastDevice) {
        if (device.id.startsWith(DLNA_ID_PREFIX)) dlna.connect(device) else cast?.connect(device)
    }

    override fun disconnect() {
        dlna.disconnect()
        cast?.disconnect()
    }

    override fun loadMedia(request: CastMediaRequest) {
        if (dlnaActive) dlna.loadMedia(request) else cast?.loadMedia(request)
    }

    override fun play() {
        if (dlnaActive) dlna.play() else cast?.play()
    }

    override fun pause() {
        if (dlnaActive) dlna.pause() else cast?.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (dlnaActive) dlna.seekTo(positionMs) else cast?.seekTo(positionMs)
    }
}

@Composable
actual fun rememberCastController(): CastController? {
    val context = LocalContext.current.applicationContext
    val controller = remember {
        val cast = runCatching {
            @Suppress("DEPRECATION")
            val castContext = CastContext.getSharedInstance(context)
            val selector = MediaRouteSelector.Builder()
                .addControlCategory(
                    CastMediaControlIntent.categoryForCast(
                        CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
                    ),
                )
                .build()
            AndroidCastController(castContext, MediaRouter.getInstance(context), selector)
                .also { Log.i(TAG, "Google Cast supported; initial castState=${castContext.castState}") }
        }.onFailure { Log.w(TAG, "Google Cast unavailable (DLNA still available): ${it.message}") }.getOrNull()
        // DLNA works without Play services, so the combined controller is always returned.
        CombinedAndroidCastController(cast, DlnaController(context))
    }

    DisposableEffect(controller) {
        controller.attach()
        onDispose { controller.detach() }
    }
    return controller
}
