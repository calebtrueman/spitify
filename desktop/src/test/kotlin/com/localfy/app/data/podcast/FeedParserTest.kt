package com.localfy.app.data.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

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

    private val rss = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:content="http://purl.org/rss/1.0/modules/content/" xmlns:media="http://search.yahoo.com/mrss/">
  <channel>
    <title>The Test Show</title>
    <itunes:author>Jane Host</itunes:author>
    <description><![CDATA[<p>A show about <b>tests</b> &amp; things.</p>]]></description>
    <image><url>https://example.com/channel-image.jpg</url><title>Ignored image title</title></image>
    <itunes:image href="https://example.com/itunes.jpg"/>
    <item>
      <title>Episode 2: Edge cases</title>
      <guid isPermaLink="false">ep-2</guid>
      <pubDate>Tue, 29 Sep 2026 10:00:00 +0000</pubDate>
      <itunes:duration>1:02:03</itunes:duration>
      <description>Short</description>
      <content:encoded><![CDATA[<p>Line one<br/>Line two</p><ul><li>Point</li></ul><p>Caf&eacute; &#8212; &#x2019;s</p>]]></content:encoded>
      <enclosure url="https://cdn.example.com/ep2.mp3?x=1" type="audio/mpeg" length="123"/>
      <itunes:image href="https://example.com/ep2.jpg"/>
    </item>
    <item>
      <title>No audio, skipped</title>
      <guid>ep-x</guid>
    </item>
    <item>
      <title></title>
      <enclosure url="http://cdn.example.com/ep1.m4a" type="audio/x-m4a"/>
      <media:thumbnail url="https://example.com/thumb.jpg"/>
      <itunes:duration>95</itunes:duration>
    </item>
  </channel>
</rss>"""

    @Test
    fun parsesInlineRss() {
        val feed = FeedParser.parse(rss.byteInputStream())
        assertEquals("The Test Show", feed.title)
        assertEquals("Jane Host", feed.author)
        assertEquals("A show about tests & things.", feed.description)
        assertEquals("https://example.com/itunes.jpg", feed.artworkUrl) // like the phone: <itunes:image> beats <image>
        assertEquals(2, feed.episodes.size)
        val first = feed.episodes[0]
        assertEquals("ep-2", first.guid)
        assertEquals("Episode 2: Edge cases", first.title)
        assertEquals("https://cdn.example.com/ep2.mp3?x=1", first.audioUrl)
        assertEquals("audio/mpeg", first.mimeType)
        assertEquals(3_723_000L, first.durationMs)
        assertEquals("https://example.com/ep2.jpg", first.artworkUrl)
        assertTrue(first.pubDate > 0)
        assertEquals(0, first.position)
        assertEquals("Line one\nLine two\n\n• Point\n\nCafé — ’s", first.description)
        val second = feed.episodes[1]
        assertEquals("http://cdn.example.com/ep1.m4a", second.guid) // no guid: falls back to the URL
        assertEquals("Untitled episode", second.title)
        assertEquals(95_000L, second.durationMs)
        assertEquals("https://example.com/thumb.jpg", second.artworkUrl)
        assertEquals(1, second.position)
    }

    @Test
    fun itunesImageWinsWhenNoChannelImage() {
        val feed = FeedParser.parse("""<rss xmlns:itunes="x"><channel><title>T</title><itunes:image href="https://a/b.jpg"/></channel></rss>""".byteInputStream())
        assertEquals("https://a/b.jpg", feed.artworkUrl)
        assertTrue(feed.episodes.isEmpty())
    }

    @Test
    fun parsesAtomEnclosures() {
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Atom Show</title>
            <entry><title>A1</title><id>urn:a1</id><published>2026-09-29T10:00:00Z</published>
            <link rel="enclosure" href="https://x.example/a1.mp3" type="audio/mpeg"/></entry></feed>"""
        val feed = FeedParser.parse(atom.byteInputStream())
        assertEquals("Atom Show", feed.title)
        assertEquals("urn:a1", feed.episodes.single().guid)
        assertEquals("https://x.example/a1.mp3", feed.episodes.single().audioUrl)
    }

    @Test
    fun brokenFeedKeepsWhatWasRead() {
        val broken = """<rss><channel><title>Half</title><item><title>E</title><enclosure url="https://a/e.mp3"/></item><item><title>bad &nbsp; entity"""
        val feed = FeedParser.parse(broken.byteInputStream())
        assertEquals("Half", feed.title)
        assertEquals(1, feed.episodes.size)
    }

    @Test
    fun garbageThrowsIOException() {
        try { FeedParser.parse("<html><body>Not a feed".byteInputStream()); fail() } catch (_: IOException) { }
        try { FeedParser.parse("".byteInputStream()); fail() } catch (_: IOException) { }
    }

    @Test
    fun externalEntitiesAreNotResolved() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel><title>&x;</title></channel></rss>"""
        val title = runCatching { FeedParser.parse(xxe.byteInputStream()).title }.getOrNull()
        assertTrue(title == null || !title.contains("root"))
    }

    @Test
    fun cleansHtml() {
        assertEquals("a & b", FeedParser.cleanHtml("<span>a &amp; b</span>"))
        assertEquals("x\ny", FeedParser.cleanHtml("x<br>y"))
        assertEquals("", FeedParser.cleanHtml("<script>alert(1)</script>"))
        assertNull(null)
    }
}
