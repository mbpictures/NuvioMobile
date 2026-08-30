package com.nuvio.app.features.simkl

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.desktop.DesktopPreferences

internal actual object SimklSyncStorage {
    private const val preferencesName = "nuvio_simkl_sync"
    private const val payloadKey = "simkl_sync_snapshot"

    actual fun loadPayload(): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        DesktopPreferences.putString(preferencesName, ProfileScopedKey.of(payloadKey), payload)
    }

    actual fun removeProfile(profileId: Int) {
        DesktopPreferences.remove(preferencesName, ProfileScopedKey.of(payloadKey, profileId))
    }
}
