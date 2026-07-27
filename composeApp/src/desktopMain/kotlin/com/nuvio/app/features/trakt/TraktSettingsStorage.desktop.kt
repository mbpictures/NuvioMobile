package com.nuvio.app.features.trakt

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.desktop.DesktopPreferences

internal actual object TraktSettingsStorage {
    private const val preferencesName = "nuvio_trakt_settings"
    private const val payloadKey = "trakt_settings_payload"
    private const val pendingWatchProgressSourceKey = "trakt_pending_watch_progress_source"

    actual fun loadPayload(): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        DesktopPreferences.putString(preferencesName, ProfileScopedKey.of(payloadKey), payload)
    }

    actual fun loadPendingWatchProgressSourcePayload(profileId: Int): String? =
        DesktopPreferences.getString(
            preferencesName,
            ProfileScopedKey.of(pendingWatchProgressSourceKey, profileId),
        )

    actual fun savePendingWatchProgressSourcePayload(profileId: Int, payload: String) {
        DesktopPreferences.putString(
            preferencesName,
            ProfileScopedKey.of(pendingWatchProgressSourceKey, profileId),
            payload,
        )
    }

    actual fun clearPendingWatchProgressSourcePayload(profileId: Int) {
        DesktopPreferences.remove(
            preferencesName,
            ProfileScopedKey.of(pendingWatchProgressSourceKey, profileId),
        )
    }
}
