package com.localfy.app.ui.player

import org.junit.Assert.*
import org.junit.Test

class VideoSurfaceCacheTest {
    private class Surface(val name: String)

    @Test fun oldScreenReturningLateKeepsTheNextPreparedClip() {
        val removed = mutableListOf<Surface>()
        val cache = VideoSurfaceCache<Surface> { removed += it }
        val old = cache.take("old") { Surface("old") }
        val next = Surface("next")
        cache.prepare("next") { next }
        cache.store(old, "old")
        assertEquals(listOf(old), removed)
        assertSame(next, cache.take("next") { error("The prepared clip was lost") })
    }

    @Test fun preparingTheSameWarmOrActiveClipDoesNotLoadItAgain() {
        val cache = VideoSurfaceCache<Surface> { error("The current clip should stay alive") }
        val view = Surface("same")
        cache.prepare("same") { view }
        cache.prepare("same") { error("Loaded the same warm clip twice") }
        assertSame(view, cache.take("same") { error("Lost the warm clip") })
        cache.prepare("same") { error("Loaded the active clip twice") }
        cache.store(view, "same")
        assertSame(view, cache.take("same") { error("Lost the returned clip") })
    }

    @Test fun takingAnotherClipDiscardsTheMismatchedWarmView() {
        val removed = mutableListOf<Surface>()
        val cache = VideoSurfaceCache<Surface> { removed += it }
        val old = Surface("old")
        val next = Surface("next")
        cache.prepare("old") { old }
        assertSame(next, cache.take("next") { next })
        assertEquals(listOf(old), removed)
    }

    @Test fun olderScreenForTheSameClipCannotReplaceTheNewActiveView() {
        val removed = mutableListOf<Surface>()
        val cache = VideoSurfaceCache<Surface> { removed += it }
        val old = cache.take("same") { Surface("old instance") }
        val next = cache.take("same") { Surface("new instance") }
        cache.store(old, "same")
        cache.store(next, "same")
        assertEquals(listOf(old), removed)
        assertSame(next, cache.take("same") { error("The old screen replaced the current one") })
    }
}
