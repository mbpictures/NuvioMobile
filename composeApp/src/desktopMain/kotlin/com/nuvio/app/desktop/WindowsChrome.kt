package com.nuvio.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Cursor
import java.awt.MouseInfo

/**
 * Wraps [content] for the undecorated Windows window so the app renders edge-to-edge while a
 * transparent title bar floats on top: a full-width drag band along the top moves the window,
 * the caption buttons sit in the top-right corner, and thin resize handles line the border.
 *
 * The drag band is rendered *in front* of [content] (so it wins over the home screen's
 * scrollable list) but leaves pass-through "holes" where the app already draws interactive
 * controls — the centered navigation pill and the top-left back button — so those keep
 * receiving clicks.
 *
 * Moving and resizing are driven directly through AWT using the absolute cursor position from
 * [MouseInfo]. Both the cursor and the window bounds live in the same screen-pixel space, so
 * this stays correct under display scaling without any density conversion, and avoids the
 * native window-message machinery that does not interoperate reliably with Compose's input.
 */
@Composable
internal fun WindowsChrome(
    window: ComposeWindow,
    windowState: WindowState,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        content()

        val isMaximized = windowState.placement == WindowPlacement.Maximized

        // A maximized window fills the monitor work area, so there is nothing to resize.
        if (!isMaximized) {
            ResizeHandles(window)
        }

        TitleBarDragBand(window, Modifier.align(Alignment.TopStart))

        WindowButtons(
            isMaximized = isMaximized,
            onMinimize = { windowState.isMinimized = true },
            onToggleMaximize = {
                windowState.placement =
                    if (isMaximized) WindowPlacement.Floating else WindowPlacement.Maximized
            },
            onClose = onClose,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/**
 * The draggable top band. Built from two drag strips separated by pass-through holes: a fixed
 * hole on the left (for the back button on detail screens) and a centered hole (for the
 * navigation pill). The far-right [WindowButtonsWidth] is left empty so [WindowButtons], drawn
 * afterwards, receives the clicks there.
 */
@Composable
private fun TitleBarDragBand(window: ComposeWindow, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().height(CaptionHeight)) {
        val totalWidth = maxWidth
        val centerHole = (totalWidth * 0.55f).coerceIn(360.dp, 640.dp)
        val sideWidth = ((totalWidth - centerHole) / 2f).coerceAtLeast(0.dp)
        val leftStrip = (sideWidth - LeftHoleWidth).coerceAtLeast(0.dp)
        val rightStrip = (sideWidth - WindowButtonsWidth).coerceAtLeast(0.dp)

        Row(Modifier.fillMaxSize()) {
            Spacer(Modifier.width(LeftHoleWidth).fillMaxHeight())
            DragStrip(window, Modifier.width(leftStrip).fillMaxHeight())
            Spacer(Modifier.width(centerHole).fillMaxHeight())
            DragStrip(window, Modifier.width(rightStrip).fillMaxHeight())
            // Remaining width (WindowButtonsWidth) intentionally left empty for the buttons.
        }
    }
}

@Composable
private fun DragStrip(window: ComposeWindow, modifier: Modifier) {
    Box(
        modifier.pointerInput(Unit) {
            // Offset between the cursor and the window origin, captured when the drag begins so
            // the window tracks the cursor exactly (no incremental drift).
            var grabX = 0
            var grabY = 0
            detectDragGestures(
                onDragStart = {
                    val cursor = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                    grabX = cursor.x - window.x
                    grabY = cursor.y - window.y
                },
                onDrag = { change, _ ->
                    change.consume()
                    val cursor = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                    window.setLocation(cursor.x - grabX, cursor.y - grabY)
                },
            )
        },
    )
}

@Composable
private fun WindowButtons(
    isMaximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.height(CaptionHeight), verticalAlignment = Alignment.CenterVertically) {
        CaptionButton(icon = Icons.Filled.Remove, contentDescription = "Minimize", onClick = onMinimize)
        CaptionButton(
            icon = if (isMaximized) Icons.Filled.FilterNone else Icons.Filled.CropSquare,
            contentDescription = if (isMaximized) "Restore" else "Maximize",
            iconSize = 14,
            onClick = onToggleMaximize,
        )
        CaptionButton(icon = Icons.Filled.Close, contentDescription = "Close", isClose = true, onClick = onClose)
    }
}

@Composable
private fun CaptionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    isClose: Boolean = false,
    iconSize: Int = 16,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        isClose && hovered -> Color(0xFFC42B1C)
        hovered -> Color.White.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(46.dp)
            .background(background)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(iconSize.dp),
        )
    }
}

@Composable
private fun BoxScope.ResizeHandles(window: ComposeWindow) {
    // Edges.
    ResizeHandle(window, ResizeEdge.Top, Cursor.N_RESIZE_CURSOR, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(EdgeThickness))
    ResizeHandle(window, ResizeEdge.Bottom, Cursor.S_RESIZE_CURSOR, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(EdgeThickness))
    ResizeHandle(window, ResizeEdge.Left, Cursor.W_RESIZE_CURSOR, Modifier.align(Alignment.CenterStart).fillMaxHeight().width(EdgeThickness))
    ResizeHandle(window, ResizeEdge.Right, Cursor.E_RESIZE_CURSOR, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(EdgeThickness))
    // Corners are drawn last so they take priority over the edges they overlap.
    ResizeHandle(window, ResizeEdge.TopLeft, Cursor.NW_RESIZE_CURSOR, Modifier.align(Alignment.TopStart).size(CornerSize))
    ResizeHandle(window, ResizeEdge.TopRight, Cursor.NE_RESIZE_CURSOR, Modifier.align(Alignment.TopEnd).size(CornerSize))
    ResizeHandle(window, ResizeEdge.BottomLeft, Cursor.SW_RESIZE_CURSOR, Modifier.align(Alignment.BottomStart).size(CornerSize))
    ResizeHandle(window, ResizeEdge.BottomRight, Cursor.SE_RESIZE_CURSOR, Modifier.align(Alignment.BottomEnd).size(CornerSize))
}

@Composable
private fun ResizeHandle(
    window: ComposeWindow,
    edge: ResizeEdge,
    cursorType: Int,
    modifier: Modifier,
) {
    Box(
        modifier
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(cursorType)))
            .pointerInput(edge) {
                detectDragGestures(
                    onDrag = { change, _ ->
                        change.consume()
                        val cursor = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                        resizeWindow(window, edge, cursor.x, cursor.y)
                    },
                )
            },
    )
}

private fun resizeWindow(window: ComposeWindow, edge: ResizeEdge, cursorX: Int, cursorY: Int) {
    var x = window.x
    var y = window.y
    var width = window.width
    var height = window.height
    val right = x + width
    val bottom = y + height

    if (edge.affectsLeft) {
        x = cursorX.coerceAtMost(right - MinWidth)
        width = right - x
    }
    if (edge.affectsRight) {
        width = (cursorX - x).coerceAtLeast(MinWidth)
    }
    if (edge.affectsTop) {
        y = cursorY.coerceAtMost(bottom - MinHeight)
        height = bottom - y
    }
    if (edge.affectsBottom) {
        height = (cursorY - y).coerceAtLeast(MinHeight)
    }
    window.setBounds(x, y, width, height)
}

private val CaptionHeight = 44.dp
private val EdgeThickness = 6.dp
private val CornerSize = 12.dp
private val LeftHoleWidth = 80.dp
private val WindowButtonsWidth = 138.dp // three 46.dp caption buttons
private const val MinWidth = 480
private const val MinHeight = 320

internal enum class ResizeEdge(
    val affectsLeft: Boolean,
    val affectsTop: Boolean,
    val affectsRight: Boolean,
    val affectsBottom: Boolean,
) {
    Left(affectsLeft = true, affectsTop = false, affectsRight = false, affectsBottom = false),
    Right(affectsLeft = false, affectsTop = false, affectsRight = true, affectsBottom = false),
    Top(affectsLeft = false, affectsTop = true, affectsRight = false, affectsBottom = false),
    Bottom(affectsLeft = false, affectsTop = false, affectsRight = false, affectsBottom = true),
    TopLeft(affectsLeft = true, affectsTop = true, affectsRight = false, affectsBottom = false),
    TopRight(affectsLeft = false, affectsTop = true, affectsRight = true, affectsBottom = false),
    BottomLeft(affectsLeft = true, affectsTop = false, affectsRight = false, affectsBottom = true),
    BottomRight(affectsLeft = false, affectsTop = false, affectsRight = true, affectsBottom = true),
}
