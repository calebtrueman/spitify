package com.localfy.app.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcTest {

    @Test
    fun parsesCentisecondsMillisecondsAndMultipleStamps() {
        val lines = Lrc.parse("[00:01.50]One\n[00:02.250]Two\n[00:03.00][00:05.00]Chorus\n[ar:Someone]")
        assertEquals(listOf(1500L, 2250L, 3000L, 5000L), lines.map { it.timeMs })
        assertEquals(listOf("One", "Two", "Chorus", "Chorus"), lines.map { it.text })
    }

    @Test
    fun appliesOffsetTagAndStripsWordTimings() {
        val lines = Lrc.parse("[offset:+500]\n[00:02.00]<00:02.00>Hello <00:02.50>world")
        assertEquals(1500L, lines.single().timeMs)
        assertEquals("Hello world", lines.single().text)
    }

    @Test
    fun detectsSyncedText() {
        assertTrue(Lrc.isSynced("[01:02.03]x"))
        assertFalse(Lrc.isSynced("just words\nmore words"))
    }

    @Test
    fun activeIndexIsLastLineAtOrBeforePosition() {
        val lines = Lrc.parse("[00:01.00]a\n[00:02.00]b\n[00:03.00]c")
        assertEquals(-1, Lrc.activeIndex(lines, 500))
        assertEquals(0, Lrc.activeIndex(lines, 1000))
        assertEquals(1, Lrc.activeIndex(lines, 2999))
        assertEquals(2, Lrc.activeIndex(lines, 60_000))
    }

    @Test
    fun plainLinesTrimBlankEdges() {
        assertEquals(listOf("a", "", "b"), Lrc.plainLines("\n\na\n\nb\n\n").map { it.text })
    }
}
