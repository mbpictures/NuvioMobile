package com.nuvio.app.features.profiles

import com.nuvio.app.desktop.DesktopPreferences

internal actual object ProfilePinCacheStorage {
    private const val preferencesName = "nuvio_profile_pin_cache"

    actual fun loadPayload(profileIndex: Int): String? =
        DesktopPreferences.getString(preferencesName, payloadKey(profileIndex))

    actual fun savePayload(profileIndex: Int, payload: String) {
        DesktopPreferences.putString(preferencesName, payloadKey(profileIndex), payload)
    }

    actual fun removePayload(profileIndex: Int) {
        DesktopPreferences.remove(preferencesName, payloadKey(profileIndex))
    }

    private fun payloadKey(profileIndex: Int): String = "profile_pin_cache_$profileIndex"
}
