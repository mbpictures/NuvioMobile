package com.nuvio.app.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private const val ChevronScrollItemCount = 4

@Composable
internal actual fun BoxScope.NuvioShelfRowChevrons(
    state: LazyListState,
    edgePadding: Dp,
) {
    val scope = rememberCoroutineScope()
    val canScrollBackward by remember(state) { derivedStateOf { state.canScrollBackward } }
    val canScrollForward by remember(state) { derivedStateOf { state.canScrollForward } }

    AnimatedVisibility(
        visible = canScrollBackward,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.CenterStart)
            .padding(start = edgePadding),
    ) {
        ChevronButton(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            onClick = {
                scope.launch {
                    val target = (state.firstVisibleItemIndex - ChevronScrollItemCount)
                        .coerceAtLeast(0)
                    state.animateScrollToItem(target)
                }
            },
        )
    }

    AnimatedVisibility(
        visible = canScrollForward,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .padding(end = edgePadding),
    ) {
        ChevronButton(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            onClick = {
                scope.launch {
                    val target = state.firstVisibleItemIndex + ChevronScrollItemCount
                    state.animateScrollToItem(target)
                }
            },
        )
    }
}

@Composable
private fun ChevronButton(
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}
