package com.localfy.app.ui.player

/** Keep a late return from the old screen from replacing the next song's prepared view. */
internal class VideoSurfaceCache<T : Any>(private val discard: (T) -> Unit) {
    private var requestedID: String? = null
    private var warm: Pair<String, T>? = null
    private var active: Pair<String, T>? = null

    fun prepare(id: String, create: () -> T) {
        requestedID = id
        if (warm?.first != id) clearWarm()
        if (warm?.first == id || active?.first == id) return
        warm = id to create()
    }

    fun take(id: String, create: () -> T): T {
        requestedID = id
        if (warm?.first != id) clearWarm()
        val view = warm?.second ?: create()
        warm = null
        active = id to view
        return view
    }

    fun store(view: T, id: String) {
        if (active?.second === view) active = null
        if (requestedID != id || active != null || (warm != null && warm?.second !== view)) {
            discard(view)
            return
        }
        warm = id to view
    }

    private fun clearWarm() {
        warm?.second?.let(discard)
        warm = null
    }
}
