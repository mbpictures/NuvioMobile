package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/**
 * Overlays left/right chevron buttons on a horizontal shelf row to page the scroll by a few items.
 * Desktop only — on touch platforms (Android, iOS) this renders nothing.
 *
 * @param edgePadding inset from the row edges so the chevrons sit over the first/last content.
 */
@Composable
internal expect fun BoxScope.NuvioShelfRowChevrons(
    state: LazyListState,
    edgePadding: Dp,
)
