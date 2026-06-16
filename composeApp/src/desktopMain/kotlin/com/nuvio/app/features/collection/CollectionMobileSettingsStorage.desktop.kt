package com.nuvio.app.features.collection

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.desktop.DesktopPreferences

internal actual object CollectionMobileSettingsStorage {
    private const val preferencesName = "nuvio_collection_mobile_settings"
    private const val payloadKey = "collection_mobile_settings_payload"

    actual fun loadPayload(): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        DesktopPreferences.putString(preferencesName, ProfileScopedKey.of(payloadKey), payload)
    }
}
