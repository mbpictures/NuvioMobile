package com.nuvio.app.features.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

@Stable
interface PlayerFullscreenController {
    val isFullscreen: Boolean
    fun toggle()
}

val LocalPlayerFullscreenController = staticCompositionLocalOf<PlayerFullscreenController?> { null }
