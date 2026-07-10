package com.nuvio.app.features.settings

import com.nuvio.app.desktop.DesktopPreferences

internal actual object SentrySettingsPlatform {
    actual val crashReportsSupported: Boolean = false
}

internal actual object SentrySettingsStorage {
    private const val preferencesName = "nuvio_sentry"
    private const val enabledKey = "sentry_enabled"

    actual fun loadEnabled(): Boolean? =
        DesktopPreferences.getBoolean(preferencesName, enabledKey)

    actual fun saveEnabled(enabled: Boolean) {
        DesktopPreferences.putBoolean(preferencesName, enabledKey, enabled)
    }
}
