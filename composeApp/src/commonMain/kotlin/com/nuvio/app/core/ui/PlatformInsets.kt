package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal expect val nuvioPlatformExtraTopPadding: Dp
internal expect val nuvioPlatformExtraBottomPadding: Dp
internal expect val nuvioBottomNavigationExtraVerticalPadding: Dp
@Composable
internal expect fun nuvioBottomNavigationBarInsets(): WindowInsets

/** Physical display-safe top inset, excluding any enclosing native toolbar. */
@Composable
internal expect fun platformPhysicalTopInset(): Dp

internal val LocalNuvioBottomNavigationOverlayPadding = staticCompositionLocalOf { 0.dp }

/** CompositionLocal providing the shared [NuvioNavBarScrollState] so child screens can attach the nestedScrollConnection. */
val LocalNuvioNavBarScrollState = staticCompositionLocalOf<NuvioNavBarScrollState?> { null }

/**
 * Extra top inset claimed by a custom window chrome that overlays the app — currently the
 * undecorated Windows title bar, which floats a translucent caption band over the top of the
 * content. It is `0` everywhere else. App content (hero images, lists) still fills the whole
 * window so it scrolls *behind* the bar, but top-anchored controls add this inset so they clear
 * the bar instead of colliding with it.
 */
internal val LocalWindowChromeTopInset = staticCompositionLocalOf { 0.dp }

/**
 * Hook for a full-screen surface (the video player) to ask a custom window chrome to enter
 * "immersive" mode — hiding its title bar until the pointer returns to the top edge, and dropping
 * the [LocalWindowChromeTopInset] so the surface can use the full window height. `null` when there
 * is no such chrome (mobile, or desktop platforms with a native title bar). Call with `true` on
 * enter and `false` on dispose.
 */
internal val LocalWindowChromeImmersiveRequest = staticCompositionLocalOf<((Boolean) -> Unit)?> { null }

/**
 * Top safe-area padding for controls anchored to the top of the window: the platform status bar
 * plus any [LocalWindowChromeTopInset] claimed by a custom window chrome.
 */
@Composable
internal fun nuvioStatusBarTopPadding(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + LocalWindowChromeTopInset.current

/**
 * Modifier form of [nuvioStatusBarTopPadding]: applies the platform status-bar inset and then the
 * custom-chrome inset as top padding.
 */
@Composable
internal fun Modifier.nuvioStatusBarsTopPadding(): Modifier =
    this.windowInsetsPadding(WindowInsets.statusBars).padding(top = LocalWindowChromeTopInset.current)

@Composable
internal fun nuvioSafeBottomPadding(extra: Dp = 0.dp): Dp {
	val navigationBarBottom = nuvioBottomNavigationBarInsets()
		.asPaddingValues()
		.calculateBottomPadding()
	return navigationBarBottom.coerceAtLeast(nuvioPlatformExtraBottomPadding) +
		LocalNuvioBottomNavigationOverlayPadding.current +
		extra
}
