package com.nuvio.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nuvio.app.desktop.DesktopBackDispatcher
import com.nuvio.app.desktop.WindowsFullscreen
import com.nuvio.app.features.player.LocalPlayerFullscreenController
import com.nuvio.app.features.player.PlayerFullscreenController
import com.nuvio.app.features.player.prewarmDesktopPlaybackBackend
import java.awt.AWTEvent
import java.awt.Color as AwtColor
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent

private val DesktopWindowBackground = AwtColor(0x0D, 0x0D, 0x0D)

private val isMacOs: Boolean by lazy {
    System.getProperty("os.name")?.lowercase()?.contains("mac") == true
}

private val isWindows: Boolean by lazy {
    System.getProperty("os.name")?.lowercase()?.contains("win") == true
}

private fun configureMacOsNativeAppearance() {
    if (!isMacOs) return
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
}

fun main() {
    configureMacOsNativeAppearance()
    System.setProperty("compose.interop.blending", "true")
    application {
        val windowState = rememberWindowState()
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Nuvio",
        ) {
            DisposableEffect(window) {
                window.background = DesktopWindowBackground
                window.contentPane.background = DesktopWindowBackground
                window.rootPane.background = DesktopWindowBackground
                onDispose { }
            }

            LaunchedEffect(Unit) {
                prewarmDesktopPlaybackBackend()
            }

            DisposableEffect(Unit) {
                val toolkit = Toolkit.getDefaultToolkit()
                val mouseBackButton = 4
                val listener = AWTEventListener { event ->
                    if (event is MouseEvent &&
                        event.id == MouseEvent.MOUSE_PRESSED &&
                        event.button == mouseBackButton
                    ) {
                        DesktopBackDispatcher.dispatch()
                    }
                }
                toolkit.addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
                onDispose { toolkit.removeAWTEventListener(listener) }
            }

            val composeWindow = window
            var nativeFullscreen by remember { mutableStateOf(false) }
            val fullscreenController = remember(composeWindow, windowState) {
                object : PlayerFullscreenController {
                    override val isFullscreen: Boolean
                        get() = when {
                            isMacOs -> windowState.placement == WindowPlacement.Fullscreen
                            else -> nativeFullscreen
                        }

                    override fun toggle() {
                        when {
                            isMacOs -> {
                                windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) {
                                    WindowPlacement.Floating
                                } else {
                                    WindowPlacement.Fullscreen
                                }
                            }
                            isWindows -> {
                                if (nativeFullscreen) {
                                    WindowsFullscreen.exit(composeWindow)
                                } else {
                                    WindowsFullscreen.enter(composeWindow)
                                }
                                nativeFullscreen = !nativeFullscreen
                            }
                            else -> {
                                val device = composeWindow.graphicsConfiguration?.device ?: return
                                if (nativeFullscreen) {
                                    device.fullScreenWindow = null
                                } else {
                                    device.fullScreenWindow = composeWindow
                                }
                                nativeFullscreen = !nativeFullscreen
                            }
                        }
                    }
                }
            }

            DisposableEffect(composeWindow) {
                onDispose {
                    if (nativeFullscreen) {
                        if (isWindows) {
                            WindowsFullscreen.exit(composeWindow)
                        } else if (!isMacOs) {
                            composeWindow.graphicsConfiguration?.device?.fullScreenWindow = null
                        }
                        nativeFullscreen = false
                    }
                }
            }

            CompositionLocalProvider(
                LocalPlayerFullscreenController provides fullscreenController,
            ) {
                App()
            }
        }
    }
}
