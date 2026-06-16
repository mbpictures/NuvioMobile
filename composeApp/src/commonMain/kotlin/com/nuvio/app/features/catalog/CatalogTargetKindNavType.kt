package com.nuvio.app.features.catalog

import androidx.navigation.NavType
import androidx.savedstate.SavedState
import androidx.savedstate.read
import androidx.savedstate.write

/**
 * Custom [NavType] for the [CatalogTargetKind] enum.
 *
 * Type-safe navigation resolves enum route arguments automatically on Android (via
 * reflection), but on non-Android targets (desktop / iOS) it requires an explicit
 * [NavType] in the destination's `typeMap`. Without this, building the `CatalogRoute`
 * destination throws "could not find any NavType for argument targetKind".
 */
val CatalogTargetKindNavType: NavType<CatalogTargetKind> =
    object : NavType<CatalogTargetKind>(isNullableAllowed = false) {
        override fun put(bundle: SavedState, key: String, value: CatalogTargetKind) {
            bundle.write { putString(key, value.name) }
        }

        override fun get(bundle: SavedState, key: String): CatalogTargetKind? =
            bundle.read { getStringOrNull(key) }?.let(CatalogTargetKind::valueOf)

        override fun parseValue(value: String): CatalogTargetKind =
            CatalogTargetKind.valueOf(value)

        override fun serializeAsValue(value: CatalogTargetKind): String =
            value.name
    }
