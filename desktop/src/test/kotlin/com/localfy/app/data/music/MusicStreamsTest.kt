package com.localfy.app.data.music

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MusicStreamsTest {
    private fun track(id: String, title: String = "Song $id") = OnlineTrack(id, title, "Artist", "Album", "900", 200_000, 1, 1, null, true)
    private fun dir(): File = Files.createTempDirectory("streams-test").toFile()

    @Test fun streamIdIsStableAndMatchesThePhones() {
        // Same SHA-256 derivation as Android/iOS: ids stored on any device keep pointing at the same song.
        assertEquals(MusicStreams.streamId("155102871061270528"), MusicStreams.streamId("155102871061270528"))
        assertEquals(-5301940841454047530L, MusicStreams.streamId("12345"))
        assertTrue(MusicStreams.streamId("12345") < -(1L shl 62) + 1)
        assertTrue(MusicStreams.streamId("a") != MusicStreams.streamId("b"))
    }

    @Test fun registerSaveAndReload() {
        val d = dir()
        val streams = MusicStreams(d)
        val song = streams.register(track("1"))
        assertEquals("spitify://music/1", song.sourceUri)
        assertSame(song, streams.lookup(song.id))
        assertEquals(track("1"), streams.track(song))
        streams.save(listOf(track("1"), track("2")))
        assertEquals(listOf("1", "2"), streams.saved.value.map { streams.track(it)!!.id })
        assertTrue(streams.contains(track("2")))
        streams.remove(listOf(track("1")))
        // A later partial copy keeps what was known.
        streams.register(track("2").copy(album = "", artwork = null))
        streams.rememberSource(track("2").copy(audioURL = "https://archive.org/download/item/x.flac", audioExtension = "flac"))
        streams.flush()

        val again = MusicStreams(d)
        assertEquals(setOf("1", "2"), again.knownTracks().map { it.id }.toSet())
        assertEquals("Album", again.track("2")!!.album)
        assertEquals(listOf("2"), again.saved.value.map { again.track(it)!!.id })
        assertTrue(again.saved.value.single().dateAddedSec > 0)
        assertEquals("https://archive.org/download/item/x.flac", again.lastSource(track("2")).audioURL)
    }

    @Test fun streamUrlPrefersLocalCopyThenWorkingSourceThenFallback() = runBlocking {
        val d = dir()
        val probed = mutableListOf<String>()
        var working = setOf<String>()
        val fallback = track("2").copy(audioURL = "https://archive.org/download/item/two.flac")
        val streams = MusicStreams(d, probe = { probed += it; it in working }, alternate = { if (it.audioURL == null) fallback else null })
        val song = streams.register(track("2"))

        // 1. The Monochrome stream works.
        working = setOf(Monochrome.audioUrl("2"))
        assertEquals(Monochrome.audioUrl("2"), streams.streamUrl(song))

        // 2. It stops working: the fallback is found, checked and remembered.
        working = setOf(fallback.audioURL!!)
        assertEquals(fallback.audioURL, streams.streamUrl(song))
        assertEquals(fallback.audioURL, streams.lastSource(track("2")).audioURL)
        probed.clear()
        assertEquals(fallback.audioURL, streams.streamUrl(song))
        assertEquals(listOf(fallback.audioURL), probed) // replay skips the search

        // 3. Nothing works: null, never an unchecked URL.
        working = emptySet()
        assertNull(streams.streamUrl(song))

        // 4. A downloaded copy wins without touching the network.
        val local = File(d, "two.m4a").apply { writeBytes(ByteArray(10)) }
        streams.localCopy = { if (it.id == "2") local else null }
        probed.clear()
        assertEquals(local.toURI().toString(), streams.streamUrl(song))
        assertTrue(probed.isEmpty())
    }

    @Test fun listeningCacheServesFinishedFilesAndTrims() = runBlocking {
        val d = dir()
        val flacHead = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()) + ByteArray(600)
        val cache = ListeningCache(d, limit = 1_000, transport = { _, dest, _ -> dest.writeBytes(flacHead); flacHead.size.toLong() })
        assertNull(cache.cachedFileFor("https://a/1"))
        val one = cache.fill("https://a/1")!!
        assertEquals(one, cache.cachedFileFor("https://a/1"))
        one.setLastModified(System.currentTimeMillis() - 60_000)
        cache.fill("https://a/2")
        // Over the 1000-byte limit: the least recently used file goes.
        assertNull(cache.cachedFileFor("https://a/1"))
        assertTrue(cache.cachedFileFor("https://a/2") != null)
    }
}
