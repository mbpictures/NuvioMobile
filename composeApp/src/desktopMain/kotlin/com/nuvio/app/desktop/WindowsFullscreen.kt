package com.nuvio.app.desktop

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.awt.Rectangle

internal object WindowsFullscreen {
    private const val GWL_STYLE = -16
    private const val WS_CAPTION = 0x00C00000
    private const val WS_THICKFRAME = 0x00040000
    private const val WS_SYSMENU = 0x00080000
    private const val WS_MINIMIZEBOX = 0x00020000
    private const val WS_MAXIMIZEBOX = 0x00010000
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_FRAMECHANGED = 0x0020
    private const val SWP_SHOWWINDOW = 0x0040
    private const val MONITOR_DEFAULTTONEAREST = 2

    private val styleStripMask =
        (WS_CAPTION or WS_THICKFRAME or WS_SYSMENU or WS_MINIMIZEBOX or WS_MAXIMIZEBOX).inv()

    private val user32: User32 by lazy { Native.load("user32", User32::class.java) }

    private data class SavedState(val style: Int, val bounds: Rectangle)

    private var saved: SavedState? = null

    val isActive: Boolean
        get() = saved != null

    fun enter(window: ComposeWindow) {
        if (saved != null) return
        val hwnd = Native.getWindowPointer(window) ?: return
        val style = user32.GetWindowLongA(hwnd, GWL_STYLE)
        saved = SavedState(style = style, bounds = Rectangle(window.bounds))

        user32.SetWindowLongA(hwnd, GWL_STYLE, style and styleStripMask)

        val monitor = user32.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST)
        val info = MonitorInfo().apply { cbSize = size() }
        if (monitor != null && user32.GetMonitorInfoA(monitor, info)) {
            val left = info.rcMonitor.left
            val top = info.rcMonitor.top
            val width = info.rcMonitor.right - info.rcMonitor.left
            val height = info.rcMonitor.bottom - info.rcMonitor.top
            user32.SetWindowPos(
                hwnd,
                null,
                left,
                top,
                width,
                height,
                SWP_NOZORDER or SWP_FRAMECHANGED or SWP_SHOWWINDOW,
            )
        } else {
            user32.SetWindowPos(
                hwnd,
                null,
                0,
                0,
                window.toolkit.screenSize.width,
                window.toolkit.screenSize.height,
                SWP_NOZORDER or SWP_FRAMECHANGED or SWP_SHOWWINDOW,
            )
        }
    }

    fun exit(window: ComposeWindow) {
        val previous = saved ?: return
        saved = null
        val hwnd = Native.getWindowPointer(window) ?: return
        user32.SetWindowLongA(hwnd, GWL_STYLE, previous.style)
        user32.SetWindowPos(
            hwnd,
            null,
            previous.bounds.x,
            previous.bounds.y,
            previous.bounds.width,
            previous.bounds.height,
            SWP_NOZORDER or SWP_FRAMECHANGED or SWP_SHOWWINDOW,
        )
    }

    private interface User32 : Library {
        fun GetWindowLongA(hWnd: Pointer, nIndex: Int): Int
        fun SetWindowLongA(hWnd: Pointer, nIndex: Int, dwNewLong: Int): Int
        fun SetWindowPos(
            hWnd: Pointer,
            hWndInsertAfter: Pointer?,
            X: Int,
            Y: Int,
            cx: Int,
            cy: Int,
            uFlags: Int,
        ): Boolean

        fun MonitorFromWindow(hWnd: Pointer, dwFlags: Int): Pointer?
        fun GetMonitorInfoA(hMonitor: Pointer, lpmi: MonitorInfo): Boolean
    }

    @Structure.FieldOrder("left", "top", "right", "bottom")
    internal open class Rect : Structure() {
        @JvmField var left: Int = 0
        @JvmField var top: Int = 0
        @JvmField var right: Int = 0
        @JvmField var bottom: Int = 0
    }

    @Structure.FieldOrder("cbSize", "rcMonitor", "rcWork", "dwFlags")
    internal open class MonitorInfo : Structure() {
        @JvmField var cbSize: Int = 0
        @JvmField var rcMonitor: Rect = Rect()
        @JvmField var rcWork: Rect = Rect()
        @JvmField var dwFlags: Int = 0
    }
}
