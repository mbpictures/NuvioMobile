package com.nuvio.app.features.streams

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import com.nuvio.app.desktop.DesktopPreferences
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal actual object StreamBadgeSettingsStorage {
    private const val preferencesName = "nuvio_stream_badge_settings"
    private const val legacyDebridPreferencesName = "nuvio_debrid_settings"
    private const val streamBadgeRulesKey = "stream_badge_rules"
    private const val showFileSizeBadgesKey = "show_file_size_badges"
    private const val showAddonLogoKey = "show_addon_logo"
    private const val streamBadgePlacementKey = "stream_badge_placement"
    private const val legacyDebridStreamBadgeRulesKey = "debrid_stream_badge_rules"

    private val syncKeys = listOf(streamBadgeRulesKey, showFileSizeBadgesKey, streamBadgePlacementKey)

    actual fun loadStreamBadgeRules(): String? = loadString(streamBadgeRulesKey)

    actual fun saveStreamBadgeRules(rules: String) {
        saveString(streamBadgeRulesKey, rules)
    }

    actual fun loadShowFileSizeBadges(): Boolean? = loadBoolean(showFileSizeBadgesKey)

    actual fun saveShowFileSizeBadges(enabled: Boolean) {
        saveBoolean(showFileSizeBadgesKey, enabled)
    }

    actual fun loadShowAddonLogo(): Boolean? = loadBoolean(showAddonLogoKey)

    actual fun saveShowAddonLogo(enabled: Boolean) {
        saveBoolean(showAddonLogoKey, enabled)
    }

    actual fun loadStreamBadgePlacement(): String? = loadString(streamBadgePlacementKey)

    actual fun saveStreamBadgePlacement(placement: String) {
        saveString(streamBadgePlacementKey, placement)
    }

    actual fun loadLegacyDebridStreamBadgeRules(): String? =
        DesktopPreferences.getString(
            legacyDebridPreferencesName,
            ProfileScopedKey.of(legacyDebridStreamBadgeRulesKey),
        )

    actual fun clearLegacyDebridStreamBadgeRules() {
        DesktopPreferences.remove(
            legacyDebridPreferencesName,
            ProfileScopedKey.of(legacyDebridStreamBadgeRulesKey),
        )
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadStreamBadgeRules()?.let { put(streamBadgeRulesKey, encodeSyncString(it)) }
        loadShowFileSizeBadges()?.let { put(showFileSizeBadgesKey, encodeSyncBoolean(it)) }
        loadStreamBadgePlacement()?.let { put(streamBadgePlacementKey, encodeSyncString(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        syncKeys.forEach { DesktopPreferences.remove(preferencesName, ProfileScopedKey.of(it)) }

        payload.decodeSyncString(streamBadgeRulesKey)?.let(::saveStreamBadgeRules)
        payload.decodeSyncBoolean(showFileSizeBadgesKey)?.let(::saveShowFileSizeBadges)
        payload.decodeSyncString(streamBadgePlacementKey)?.let(::saveStreamBadgePlacement)
    }

    private fun loadString(key: String): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(key))

    private fun saveString(key: String, value: String) {
        DesktopPreferences.putString(preferencesName, ProfileScopedKey.of(key), value)
    }

    private fun loadBoolean(key: String): Boolean? =
        DesktopPreferences.getBoolean(preferencesName, ProfileScopedKey.of(key))

    private fun saveBoolean(key: String, value: Boolean) {
        DesktopPreferences.putBoolean(preferencesName, ProfileScopedKey.of(key), value)
    }
}
