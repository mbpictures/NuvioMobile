package com.nuvio.app.core.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

internal actual fun Modifier.nuvioHorizontalDragScroll(state: LazyListState): Modifier = composed {
    val draggableState = rememberDraggableState { delta ->
        // Dragging right (positive delta) should reveal earlier items, i.e. scroll content back.
        state.dispatchRawDelta(-delta)
    }
    draggable(
        state = draggableState,
        orientation = Orientation.Horizontal,
    )
}
