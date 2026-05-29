package com.nuvio.app.features.profiles

import com.nuvio.app.desktop.DesktopPreferences

internal actual object AvatarStorage {
    private const val preferencesName = "nuvio_avatar_cache"
    private const val payloadKey = "avatar_catalog_payload"

    actual fun loadPayload(): String? =
        DesktopPreferences.getString(preferencesName, payloadKey)

    actual fun savePayload(payload: String) {
        DesktopPreferences.putString(preferencesName, payloadKey, payload)
    }
}
