package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) = Unit

@Composable
actual fun BindPlatformBackNavigation(navController: NavHostController) = Unit
