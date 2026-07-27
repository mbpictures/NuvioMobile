package com.nuvio.app.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import com.nuvio.app.navigation.NuvioNavigator

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    BackHandler(enabled = enabled, onBack = onBack)
}

@Composable
internal actual fun BindPlatformBackNavigation(navigator: NuvioNavigator) = Unit
