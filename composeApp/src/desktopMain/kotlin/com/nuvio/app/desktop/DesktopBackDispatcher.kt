package com.nuvio.app.desktop

internal object DesktopBackDispatcher {
    private val handlers = mutableListOf<() -> Boolean>()

    @Volatile
    var fallback: (() -> Unit)? = null

    fun register(handler: () -> Boolean): () -> Unit {
        synchronized(handlers) { handlers.add(handler) }
        return {
            synchronized(handlers) { handlers.remove(handler) }
        }
    }

    fun dispatch(): Boolean {
        val snapshot = synchronized(handlers) { handlers.toList() }
        for (i in snapshot.indices.reversed()) {
            if (snapshot[i].invoke()) return true
        }
        fallback?.invoke()
        return fallback != null
    }
}
