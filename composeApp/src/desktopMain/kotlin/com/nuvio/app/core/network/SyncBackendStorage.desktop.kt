package com.nuvio.app.core.network

import com.nuvio.app.desktop.DesktopPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

internal actual object SyncBackendStorage {
    private const val PREFS_NAME = "nuvio_sync_backend"
    private const val KEY_SELECTION_PAYLOAD = "selection_payload_v1"

    actual fun loadSelectionPayload(): String? =
        DesktopPreferences.getString(PREFS_NAME, KEY_SELECTION_PAYLOAD)

    actual fun saveSelectionPayload(payload: String) {
        DesktopPreferences.putString(PREFS_NAME, KEY_SELECTION_PAYLOAD, payload)
    }
}

internal actual suspend fun fetchSyncBackendManifestText(url: String): String =
    withContext(Dispatchers.IO) {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                error("Sync backend manifest request failed with HTTP $code")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
                .takeIf { it.isNotBlank() }
                ?: error("Sync backend manifest response was empty")
        } finally {
            connection.disconnect()
        }
    }
