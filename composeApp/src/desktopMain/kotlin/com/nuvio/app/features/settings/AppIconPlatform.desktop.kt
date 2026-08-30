package com.nuvio.app.features.settings

/**
 * Desktop has no launcher-alias / alternate-icon mechanism, so the app always runs with the
 * original icon and icon activation is a no-op.
 */
internal actual object AppIconPlatform {
    actual val requiresCloseConfirmation: Boolean = false

    actual fun currentIconName(): String? = null

    actual suspend fun activateIcon(name: String?): Boolean = name == null
}
