package com.nuvio.app.core.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondary
import androidx.compose.ui.input.pointer.onPointerEvent

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.nuvioSecondaryClick(onSecondaryClick: (() -> Unit)?): Modifier =
    if (onSecondaryClick != null) {
        onPointerEvent(
            eventType = PointerEventType.Press,
            pass = PointerEventPass.Initial,
        ) { event ->
            if (event.button?.isSecondary == true) {
                event.changes.forEach { it.consume() }
                onSecondaryClick()
            }
        }
    } else {
        this
    }
