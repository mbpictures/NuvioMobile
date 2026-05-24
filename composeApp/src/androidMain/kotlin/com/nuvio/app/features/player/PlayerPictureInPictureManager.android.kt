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
import android.os.Handler
import android.os.Looper
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal interface PlayerPipPlaybackActions {
    fun togglePlayback()
    fun skipBack()
    fun skipForward()
}

internal object PlayerPictureInPictureManager {
    private data class SessionState(
        val isActive: Boolean = false,
        val isPlaying: Boolean = false,
        val playerSize: IntSize = IntSize.Zero,
    )

    private var sessionState = SessionState()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pipState = MutableStateFlow(false)
    val isInPictureInPictureMode: StateFlow<Boolean> = pipState
    private var pendingPictureInPictureExitCheck: Runnable? = null
    private var pausePlaybackCallback: (() -> Unit)? = null
    private var playbackActions: PlayerPipPlaybackActions? = null

    fun updateSession(
        activity: Activity,
        isActive: Boolean,
        isPlaying: Boolean,
        playerSize: IntSize,
    ) {
        sessionState = SessionState(
            isActive = isActive,
            isPlaying = isPlaying,
            playerSize = playerSize,
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

    fun dispatchSkipBack() {
        mainHandler.post { playbackActions?.skipBack() }
    }

    fun dispatchTogglePlayback() {
        mainHandler.post { playbackActions?.togglePlayback() }
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        activity.setPictureInPictureParams(buildParams(activity))
    }

    private fun enterIfEligible(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!sessionState.isActive || !sessionState.isPlaying) return false
        if (activity.isFinishing) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode) return false
        return activity.enterPictureInPictureMode(buildParams(activity))
    }

    private fun buildParams(activity: Activity): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        buildAspectRatio(sessionState.playerSize)?.let(builder::setAspectRatio)
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
            icon = android.R.drawable.ic_media_rew,
            title = "Rewind 10s",
        )
        val toggleIcon = if (sessionState.isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val toggleTitle = if (sessionState.isPlaying) "Pause" else "Play"
        val toggle = buildRemoteAction(
            activity = activity,
            requestCode = pendingRequestToggle,
            action = PlayerPipActionsReceiver.actionTogglePlayback,
            icon = toggleIcon,
            title = toggleTitle,
        )
        val skipForward = buildRemoteAction(
            activity = activity,
            requestCode = pendingRequestSkipForward,
            action = PlayerPipActionsReceiver.actionSkipForward,
            icon = android.R.drawable.ic_media_ff,
            title = "Forward 10s",
        )
        return listOf(skipBack, toggle, skipForward)
    }

    private fun buildRemoteAction(
        activity: Activity,
        requestCode: Int,
        action: String,
        icon: Int,
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
            Icon.createWithResource(activity, icon),
            title,
            title,
            pendingIntent,
        )
    }

    private fun buildAspectRatio(playerSize: IntSize): Rational? {
        if (playerSize.width <= 0 || playerSize.height <= 0) return null

        val width = playerSize.width.coerceAtLeast(1)
        val height = playerSize.height.coerceAtLeast(1)
        val ratio = width.toDouble() / height.toDouble()

        return when {
            ratio > MaxPictureInPictureAspectRatio -> Rational(239, 100)
            ratio < MinPictureInPictureAspectRatio -> Rational(100, 239)
            else -> Rational(width, height)
        }
    }

    private fun clearPendingPictureInPictureExitCheck() {
        pendingPictureInPictureExitCheck?.let(mainHandler::removeCallbacks)
        pendingPictureInPictureExitCheck = null
    }
}

private const val MaxPictureInPictureAspectRatio = 2.39
private const val MinPictureInPictureAspectRatio = 1.0 / MaxPictureInPictureAspectRatio
private const val pendingRequestSkipBack = 401
private const val pendingRequestToggle = 402
private const val pendingRequestSkipForward = 403
