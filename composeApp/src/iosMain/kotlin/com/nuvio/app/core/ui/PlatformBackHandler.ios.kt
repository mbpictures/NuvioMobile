package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import com.nuvio.app.navigation.NuvioNavigator

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) = Unit

@Composable
internal actual fun BindPlatformBackNavigation(navigator: NuvioNavigator) = Unit
