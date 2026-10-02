package com.localfy.app.data.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedParserTest {

    @Test
    fun parsesItunesDurations() {
        assertEquals(3_906_000L, FeedParser.parseDuration("65:06"))
        assertEquals(3_723_000L, FeedParser.parseDuration("1:02:03"))
        assertEquals(1_500L, FeedParser.parseDuration("1.5"))
        assertEquals(0L, FeedParser.parseDuration("n/a"))
    }

    @Test
    fun parsesRfc822AndIsoDates() {
        assertTrue(FeedParser.parseDate("Tue, 29 Sep 2026 10:00:00 +0000") > 0)
        assertTrue(FeedParser.parseDate("Tue, 29 Sep 2026 10:00:00 GMT") > 0)
        assertTrue(FeedParser.parseDate("2026-09-29T10:00:00Z") > 0)
        assertEquals(0L, FeedParser.parseDate("someday"))
    }
}
