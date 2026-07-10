package com.nuvio.app.core.sync

import com.nuvio.app.desktop.DesktopPreferences

actual object SyncClientIdentityStorage {
    private const val preferencesName = "nuvio_sync_client_identity"
    private const val clientIdKey = "client_instance_id"

    actual fun loadClientId(): String? =
        DesktopPreferences.getString(preferencesName, clientIdKey)

    actual fun saveClientId(clientId: String) {
        DesktopPreferences.putString(preferencesName, clientIdKey, clientId)
    }
}
