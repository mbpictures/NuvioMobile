package com.nuvio.app.features.player

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import android.os.Handler
import android.os.Looper
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import com.nuvio.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

internal interface PlayerPipPlaybackActions {
    fun togglePlayback()
    fun skipBack()
    fun skipForward()
}

internal object PlayerPictureInPictureManager {
    private data class SessionState(
        val isActive: Boolean = false,
        val isPlaying: Boolean = false,
        val videoSize: IntSize = IntSize.Zero,
    )

    private var sessionState = SessionState()
    private var lastAppliedSessionState: SessionState? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pipState = MutableStateFlow(false)
    val isInPictureInPictureMode: StateFlow<Boolean> = pipState
    private var pendingPictureInPictureExitCheck: Runnable? = null
    private var pausePlaybackCallback: (() -> Unit)? = null
    private var playbackActions: PlayerPipPlaybackActions? = null
    private var togglePlaybackCallback: (() -> Unit)? = null

    fun updateSession(
        activity: Activity,
        isActive: Boolean,
        isPlaying: Boolean,
        videoSize: IntSize,
    ) {
        sessionState = SessionState(
            isActive = isActive,
            isPlaying = isPlaying,
            videoSize = videoSize,
        )
        applyPictureInPictureParams(activity)
    }

    fun clearSession(activity: Activity) {
        sessionState = SessionState()
        pipState.value = false
        clearPendingPictureInPictureExitCheck()
        applyPictureInPictureParams(activity)
    }

    fun registerPausePlaybackCallback(callback: (() -> Unit)?) {
        pausePlaybackCallback = callback
        if (callback == null) {
            clearPendingPictureInPictureExitCheck()
        }
    }

    fun registerPlaybackActions(actions: PlayerPipPlaybackActions?) {
        playbackActions = actions
    }

    /**
     * Fallback play/pause hook for engines that do not publish full [PlayerPipPlaybackActions]
     * (the libmpv surface). The skip actions are no-ops for those engines.
     */
    fun registerTogglePlaybackCallback(callback: (() -> Unit)?) {
        togglePlaybackCallback = callback
    }

    fun dispatchSkipBack() {
        mainHandler.post { playbackActions?.skipBack() }
    }

    fun dispatchTogglePlayback() {
        mainHandler.post {
            val actions = playbackActions
            if (actions != null) {
                actions.togglePlayback()
            } else {
                togglePlaybackCallback?.invoke()
            }
        }
    }

    fun dispatchSkipForward() {
        mainHandler.post { playbackActions?.skipForward() }
    }

    fun isSupported(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    fun enterPictureInPicture(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!sessionState.isActive) return false
        if (activity.isFinishing) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode) return false
        return activity.enterPictureInPictureMode(buildParams(activity))
    }

    fun onUserLeaveHint(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return false
        }
        return enterIfEligible(activity)
    }

    fun onPictureInPictureModeChanged(
        activity: ComponentActivity,
        isInPictureInPictureMode: Boolean,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val wasInPictureInPicture = pipState.value
        pipState.value = isInPictureInPictureMode
        clearPendingPictureInPictureExitCheck()

        if (!wasInPictureInPicture || isInPictureInPictureMode) return

        val exitCheck = Runnable {
            val returnedToForeground = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (!returnedToForeground || activity.isFinishing || activity.isDestroyed) {
                pausePlaybackCallback?.invoke()
            }
        }
        pendingPictureInPictureExitCheck = exitCheck
        mainHandler.postDelayed(exitCheck, 250L)
    }

    private fun applyPictureInPictureParams(activity: Activity) {
        if (!activity.canUsePictureInPicture()) return
        if (sessionState == lastAppliedSessionState) return
        if (runCatching { activity.setPictureInPictureParams(buildParams(activity)) }.isSuccess) {
            lastAppliedSessionState = sessionState
        }
    }

    private fun enterIfEligible(activity: Activity): Boolean {
        if (!activity.canUsePictureInPicture()) return false
        if (!sessionState.isActive || !sessionState.isPlaying) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode) return false
        return runCatching { activity.enterPictureInPictureMode(buildParams(activity)) }.getOrDefault(false)
    }

    private fun buildParams(activity: Activity): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        buildAspectRatio(sessionState.videoSize)?.let(builder::setAspectRatio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setActions(buildRemoteActions(activity))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(sessionState.isActive && sessionState.isPlaying)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun buildRemoteActions(activity: Activity): List<RemoteAction> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()

        val skipBack = buildRemoteAction(
            activity = activity,
            requestCode = pendingRequestSkipBack,
            action = PlayerPipActionsReceiver.actionSkipBack,
            icon = Icon.createWithResource(activity, android.R.drawable.ic_media_rew),
            title = pipSkipBackLabel,
        )
        val isPlaying = sessionState.isPlaying
        val toggleIcon = Icon
            .createWithResource(
                activity,
                if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play,
            )
            .apply { setTint(android.graphics.Color.WHITE) }
        val toggle = buildRemoteAction(
            activity = activity,
            requestCode = pendingRequestToggle,
            action = PlayerPipActionsReceiver.actionTogglePlayback,
            icon = toggleIcon,
            title = if (isPlaying) pipPauseLabel else pipPlayLabel,
        )
        val skipForward = buildRemoteAction(
            activity = activity,
            requestCode = pendingRequestSkipForward,
            action = PlayerPipActionsReceiver.actionSkipForward,
            icon = Icon.createWithResource(activity, android.R.drawable.ic_media_ff),
            title = pipSkipForwardLabel,
        )
        return listOf(skipBack, toggle, skipForward)
    }

    private fun buildRemoteAction(
        activity: Activity,
        requestCode: Int,
        action: String,
        icon: Icon,
        title: String,
    ): RemoteAction {
        val intent = Intent(action).apply {
            component = ComponentName(activity, PlayerPipActionsReceiver::class.java)
            setPackage(activity.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            activity,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return RemoteAction(
            icon,
            title,
            title,
            pendingIntent,
        )
    }

    private fun buildAspectRatio(videoSize: IntSize): Rational? {
        if (videoSize.width <= 0 || videoSize.height <= 0) return null

        val width = videoSize.width.coerceAtLeast(1)
        val height = videoSize.height.coerceAtLeast(1)
        val ratio = width.toDouble() / height.toDouble()

        return when {
            ratio > MaxPictureInPictureAspectRatio ->
                Rational((MaxPictureInPictureAspectRatio * 100).roundToInt(), 100)
            ratio < MinPictureInPictureAspectRatio ->
                Rational(100, (MaxPictureInPictureAspectRatio * 100).roundToInt())
            else -> Rational(width, height)
        }
    }

    private fun clearPendingPictureInPictureExitCheck() {
        pendingPictureInPictureExitCheck?.let(mainHandler::removeCallbacks)
        pendingPictureInPictureExitCheck = null
    }

    private fun Activity.canUsePictureInPicture(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (isFinishing || isDestroyed) return false
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        if (this is ComponentActivity && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return false
        return true
    }

    private val pipPlayLabel: String by lazy { runBlocking { getString(Res.string.action_play) } }
    private val pipPauseLabel: String by lazy { runBlocking { getString(Res.string.compose_action_pause) } }
    private val pipSkipBackLabel: String by lazy {
        runBlocking { getString(Res.string.compose_player_seek_back_10) }
    }
    private val pipSkipForwardLabel: String by lazy {
        runBlocking { getString(Res.string.compose_player_seek_forward_10) }
    }
}

private const val MaxPictureInPictureAspectRatio = 2.39
private const val MinPictureInPictureAspectRatio = 1.0 / MaxPictureInPictureAspectRatio
private const val pendingRequestSkipBack = 401
private const val pendingRequestToggle = 402
private const val pendingRequestSkipForward = 403
