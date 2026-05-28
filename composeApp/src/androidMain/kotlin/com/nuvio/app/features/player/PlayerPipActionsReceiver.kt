package com.nuvio.app.features.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PlayerPipActionsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            actionSkipBack -> PlayerPictureInPictureManager.dispatchSkipBack()
            actionTogglePlayback -> PlayerPictureInPictureManager.dispatchTogglePlayback()
            actionSkipForward -> PlayerPictureInPictureManager.dispatchSkipForward()
        }
    }

    companion object {
        const val actionSkipBack = "com.nuvio.app.player.pip.SKIP_BACK"
        const val actionTogglePlayback = "com.nuvio.app.player.pip.TOGGLE_PLAYBACK"
        const val actionSkipForward = "com.nuvio.app.player.pip.SKIP_FORWARD"
    }
}
