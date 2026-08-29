 package com.nuvio.app.features.plugins

import com.nuvio.app.desktop.DesktopPreferences
import java.io.File

internal object PluginStorage {
    private const val preferencesName = "nuvio_plugins"
    private const val pluginsStateKey = "plugins_state"
    private const val scraperCodeDirectoryName = "nuvio_plugin_scrapers"

    private val scraperCodeStore: PluginScraperCodeFileStore by lazy {
        PluginScraperCodeFileStore(
            File(System.getProperty("user.home"))
                .resolve("Library")
                .resolve("Application Support")
                .resolve("Nuvio")
                .resolve(scraperCodeDirectoryName),
        )
    }

    fun loadState(profileId: Int): String? =
        DesktopPreferences.getString(preferencesName, "${pluginsStateKey}_$profileId")

    fun saveState(profileId: Int, payload: String) {
        DesktopPreferences.putString(preferencesName, "${pluginsStateKey}_$profileId", payload)
    }

    fun hasScraperCode(profileId: Int, scraperId: String): Boolean =
        scraperCodeStore.contains(profileId, scraperId)

    fun loadScraperCode(profileId: Int, scraperId: String): String? =
        scraperCodeStore.load(profileId, scraperId)

    fun saveScraperCode(
        profileId: Int,
        scraperId: String,
        code: String,
        overwrite: Boolean,
    ): Boolean = scraperCodeStore.save(profileId, scraperId, code, overwrite)

    fun loadScraperSettings(scraperId: String): String? =
        DesktopPreferences.getString(preferencesName, "settings_${scraperId}")

    fun saveScraperSettings(scraperId: String, payload: String) {
        DesktopPreferences.putString(preferencesName, "settings_${scraperId}", payload)
    }
}
internal fun currentPluginPlatform(): String = "desktop"

internal fun currentEpochMillis(): Long = System.currentTimeMillis()
