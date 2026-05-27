package com.nuvio.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.nuvio.app.features.player.prewarmDesktopPlaybackBackend
import java.awt.Color as AwtColor

private val DesktopWindowBackground = AwtColor(0x0D, 0x0D, 0x0D)

private fun configureMacOsNativeAppearance() {
    val osName = System.getProperty("os.name")?.lowercase() ?: return
    if (!osName.contains("mac")) return
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
}

fun main() {
    configureMacOsNativeAppearance()
    System.setProperty("compose.interop.blending", "true")
    application {
        Window(
            onCloseRequest = ::exitApplication,
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

            App()
        }
    }
}
