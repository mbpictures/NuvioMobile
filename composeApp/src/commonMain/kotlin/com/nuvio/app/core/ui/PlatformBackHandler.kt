package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController

@Composable
expect fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)

@Composable
expect fun BindPlatformBackNavigation(navController: NavHostController)
