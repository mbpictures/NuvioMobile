package com.nuvio.app.features.library

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.desktop.DesktopPreferences

actual object LibraryDisplaySettingsStorage {
    private const val preferencesName = "nuvio_library_display_settings"
    private const val payloadKey = "library_display_settings_payload"

    actual fun loadPayload(): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        DesktopPreferences.putString(preferencesName, ProfileScopedKey.of(payloadKey), payload)
    }
}
