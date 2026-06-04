package com.nuvio.app.features.player.cast

import androidx.compose.runtime.Composable

/** Desktop has no Cast/Chromecast support; the player surface already targets the local display. */
@Composable
actual fun rememberCastController(): CastController? = null
