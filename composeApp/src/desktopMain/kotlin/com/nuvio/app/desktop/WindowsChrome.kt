package com.nuvio.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.CompositionLocalProvider
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
import com.nuvio.app.core.ui.LocalWindowChromeTopInset
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.awt.Cursor
import java.awt.MouseInfo

/**
 * Wraps [content] for the undecorated Windows window. [content] fills the whole window and is
 * registered as a Haze blur source, so the app's hero images and lists scroll *behind* a
 * translucent frosted glass title bar that overlays the top [CaptionHeight]. The bar blurs and
 * tints whatever passes under it, giving a real see-through caption.
 *
 * So the app's own top controls don't collide with the bar, the content subtree is given a
 * [LocalWindowChromeTopInset] of [CaptionHeight]; top-anchored controls (the navigation pill,
 * detail headers, back buttons) add that inset and therefore sit just below the bar, while
 * full-bleed backgrounds ignore it and keep scrolling underneath.
 *
 * The bar spans edge to edge with no break — dragging it moves the window everywhere except the
 * caption buttons in the top-right corner (you don't drag a window by its buttons). Thin resize
 * handles line the border.
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
    val hazeState = rememberHazeState()
    val isMaximized = windowState.placement == WindowPlacement.Maximized

    Box(Modifier.fillMaxSize()) {
        // Content fills the whole window so it scrolls behind the bar; the chrome inset pushes
        // the app's top-anchored controls below the bar.
        CompositionLocalProvider(LocalWindowChromeTopInset provides CaptionHeight) {
            Box(Modifier.fillMaxSize().hazeSource(state = hazeState)) {
                content()
            }
        }

        // A maximized window fills the monitor work area, so there is nothing to resize.
        if (!isMaximized) {
            ResizeHandles(window)
        }

        CaptionBar(window, hazeState, Modifier.align(Alignment.TopStart))

        // Drawn last so the caption buttons win their clicks over the bar's drag region beneath
        // them.
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
 * The full-width frosted glass title bar overlaid on the top of the content. It is one continuous
 * drag surface spanning the entire width; the caption buttons are drawn on top of its right edge
 * afterwards, so a drag started there hits the buttons instead of moving the window.
 */
@Composable
private fun CaptionBar(window: ComposeWindow, hazeState: HazeState, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(CaptionHeight)
            .captionGlass(hazeState)
            .pointerInput(Unit) {
                // Offset between the cursor and the window origin, captured when the drag begins
                // so the window tracks the cursor exactly (no incremental drift).
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

/**
 * Frosts the caption surface: blurs whatever is rendered behind it, then lays a translucent scrim
 * on top so the white caption icons stay legible over any backdrop.
 */
private fun Modifier.captionGlass(hazeState: HazeState): Modifier =
    this.hazeEffect(state = hazeState).background(CaptionScrim)

@Composable
private fun WindowButtons(
    isMaximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.height(CaptionHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            .width(CaptionHeight)
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

private val CaptionHeight = 30.dp
// Translucent dark scrim laid over the caption blur. The app content scrolls behind the bar, so
// this tints the blurred content just enough to keep the white caption icons legible over bright
// backdrops (e.g. a light hero image) while staying see-through.
private val CaptionScrim = Color.Black.copy(alpha = 0.32f)
private val EdgeThickness = 6.dp
private val CornerSize = 12.dp
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
