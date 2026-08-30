package com.nuvio.app.features.search

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.desktop.DesktopPreferences

internal actual object DiscoverSelectionStorage {
    private const val preferencesName = "nuvio_discover_selection"
    private const val catalogKeyKey = "discover_catalog_key"

    actual fun loadCatalogKey(): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(catalogKeyKey))

    actual fun saveCatalogKey(catalogKey: String) {
        DesktopPreferences.putString(preferencesName, ProfileScopedKey.of(catalogKeyKey), catalogKey)
    }
}
