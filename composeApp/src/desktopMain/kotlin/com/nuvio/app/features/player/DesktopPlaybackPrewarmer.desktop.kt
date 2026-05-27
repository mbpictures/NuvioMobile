package com.nuvio.app.features.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private val desktopPlaybackPrewarmStarted = AtomicBoolean(false)

internal suspend fun prewarmDesktopPlaybackBackend() {
    if (!desktopPlaybackPrewarmStarted.compareAndSet(false, true)) return
    withContext(Dispatchers.IO) {
        runCatching { LibMpv.INSTANCE }
    }
}
