package com.nuvio.app.core.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier

/**
 * Enables click-and-drag horizontal scrolling for a [LazyRow] on platforms that lack touch
 * gestures (desktop). On touch platforms (Android, iOS) the row already scrolls by drag, so this
 * is a no-op there.
 */
internal expect fun Modifier.nuvioHorizontalDragScroll(state: LazyListState): Modifier
