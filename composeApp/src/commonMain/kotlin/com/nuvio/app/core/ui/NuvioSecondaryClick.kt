package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier

internal expect fun Modifier.nuvioSecondaryClick(onSecondaryClick: (() -> Unit)?): Modifier
