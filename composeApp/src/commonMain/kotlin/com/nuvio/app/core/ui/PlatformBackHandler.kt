package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import com.nuvio.app.navigation.NuvioNavigator

@Composable
expect fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)

@Composable
internal expect fun BindPlatformBackNavigation(navigator: NuvioNavigator)
