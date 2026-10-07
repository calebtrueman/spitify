package com.localfy.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QueueRulesTest {
    private var uid = 0L
    private fun entries(vararg ids: Long, manual: Set<Int> = emptySet()) =
        ids.mapIndexed { i, id -> QueueEntry(uid++, song(id), manual = i in manual) }

    @Test fun shuffleThenUnshuffleRestoresTheOriginalOrder() {
        val q = entries(1, 2, 3, 2, 5, 6, 7) // a playlist can repeat a song
        val on = QueueRules.toggleShuffle(q, 2, enable = true, unshuffledOrder = null, random = Random(7))
        assertEquals(0, on.current)
        assertEquals(q[2], on.entries[0])
        assertEquals(q.map { it.song.id }.sorted(), on.entries.map { it.song.id }.sorted())
        assertEquals(listOf(1L, 2, 3, 2, 5, 6, 7), on.unshuffledOrder)
        val off = QueueRules.toggleShuffle(on.entries, on.current, enable = false, unshuffledOrder = on.unshuffledOrder)
        assertEquals(listOf(1L, 2, 3, 2, 5, 6, 7), off.entries.map { it.song.id })
        assertEquals(2, off.current)
        assertNull(off.unshuffledOrder)
    }

    @Test fun shuffleKeepsQueuedSongsUpNext() {
        val q = entries(1, 2, 3, 4, 5, manual = setOf(1))
        val on = QueueRules.toggleShuffle(q, 0, enable = true, unshuffledOrder = null, random = Random(1))
        assertEquals(listOf(1L, 2L), on.entries.take(2).map { it.song.id })
        val off = QueueRules.toggleShuffle(on.entries, 0, enable = false, unshuffledOrder = on.unshuffledOrder)
        assertEquals(listOf(1L, 2, 3, 4, 5), off.entries.map { it.song.id })
        assertTrue(off.entries[1].manual)
    }

    @Test fun unshuffleWhilePlayingAQueuedSong() {
        val q = entries(9, 1, 2, 3, manual = setOf(0))
        val off = QueueRules.toggleShuffle(q, 0, enable = false, unshuffledOrder = listOf(3, 2, 1))
        assertEquals(listOf(9L, 3, 2, 1), off.entries.map { it.song.id })
        assertEquals(0, off.current)
    }

    @Test fun navigationRules() {
        assertEquals(1, QueueRules.autoNextIndex(3, 0, RepeatMode.OFF))
        assertNull(QueueRules.autoNextIndex(3, 2, RepeatMode.OFF))
        assertEquals(0, QueueRules.autoNextIndex(3, 2, RepeatMode.ALL))
        assertEquals(2, QueueRules.autoNextIndex(3, 2, RepeatMode.ONE))
        // Buttons treat repeat-one as off.
        assertNull(QueueRules.nextIndex(3, 2, RepeatMode.ONE))
        assertEquals(0, QueueRules.nextIndex(3, 2, RepeatMode.ALL))
        assertNull(QueueRules.previousIndex(3, 0, RepeatMode.OFF))
        assertEquals(2, QueueRules.previousIndex(3, 0, RepeatMode.ALL))
        assertTrue(QueueRules.previousRestarts(3_001, hasPrevious = true))
        assertFalse(QueueRules.previousRestarts(3_000, hasPrevious = true))
        assertTrue(QueueRules.previousRestarts(0, hasPrevious = false))
        assertEquals(RepeatMode.ALL, QueueRules.nextRepeat(RepeatMode.OFF))
        assertEquals(RepeatMode.ONE, QueueRules.nextRepeat(RepeatMode.ALL))
        assertEquals(RepeatMode.OFF, QueueRules.nextRepeat(RepeatMode.ONE))
    }

    @Test fun moveKeepsTheCurrentSong() {
        assertEquals(4, QueueRules.indexAfterMove(2, 2, 4))
        assertEquals(1, QueueRules.indexAfterMove(2, 0, 3))
        assertEquals(3, QueueRules.indexAfterMove(2, 4, 1))
        assertEquals(2, QueueRules.indexAfterMove(2, 3, 4))
    }

    @Test fun crossfadeDecision() {
        val a1 = song(1, album = "LP", track = 1)
        val a2 = song(2, album = "LP", track = 2)
        val a4 = song(4, album = "LP", track = 4)
        val ep = song(3, podcast = true)
        assertEquals(0, QueueRules.crossfadeFor(a1, a2, 5_000, keepAlbums = true, repeatMode = RepeatMode.OFF))
        assertEquals(5_000, QueueRules.crossfadeFor(a1, a2, 5_000, keepAlbums = false, repeatMode = RepeatMode.OFF))
        assertEquals(5_000, QueueRules.crossfadeFor(a1, a4, 5_000, keepAlbums = true, repeatMode = RepeatMode.OFF))
        assertEquals(0, QueueRules.crossfadeFor(a1, ep, 5_000, keepAlbums = false, repeatMode = RepeatMode.OFF))
        assertEquals(0, QueueRules.crossfadeFor(a1, a4, 5_000, keepAlbums = false, repeatMode = RepeatMode.ONE))
        assertEquals(0, QueueRules.crossfadeFor(a1, a4, 0, keepAlbums = false, repeatMode = RepeatMode.OFF))
    }

    @Test fun autoplayPicksUnheardSongsFirst() {
        // core's continuationIds: never re-queue, least recently played first.
        val ids = continuationIds(listOf(5, 1, 6, 2, 7), recent = listOf(1, 2, 3), queued = setOf(3, 7), count = 3)
        assertEquals(listOf(5L, 6, 1), ids)
    }
}
