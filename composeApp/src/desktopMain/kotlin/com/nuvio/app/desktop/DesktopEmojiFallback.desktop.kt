package com.nuvio.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font

private object EmojiFontResource

/**
 * The app renders text in the bundled JetBrains Sans family, which has no emoji glyphs. On macOS and
 * Windows the OS provides a color-emoji font (Apple Color Emoji / Segoe UI Emoji) that Skia falls back
 * to automatically. Minimal Linux installs (and WSL) ship no emoji font, so emoji render as blank tofu.
 *
 * We bundle Noto Color Emoji (fetched into desktop resources at build time) and register it as a
 * last-resort fallback via the font resolver's preload API. On macOS/Windows the resource is absent
 * (the fetch task is Linux-gated), so this is a no-op there.
 */
@Composable
fun EmojiFallbackPreloader() {
    val resolver = LocalFontFamilyResolver.current
    LaunchedEffect(resolver) {
        runCatching {
            val bytes = EmojiFontResource.javaClass
                .getResourceAsStream("/fonts/NotoColorEmoji.ttf")
                ?.use { it.readBytes() }
                ?: return@runCatching
            resolver.preload(FontFamily(Font("NotoColorEmoji", bytes)))
        }
    }
}
