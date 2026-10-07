package com.localfy.app.data.social

import com.localfy.app.data.Song
import com.localfy.app.data.music.OnlineTrack
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class PlaylistMatchesTest {
    private val dir: File = Files.createTempDirectory("matches").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    private fun song(id: Long, title: String, artist: String, duration: Long = 200_000, uri: String? = null) =
        Song(id, title, artist, "Album", 1, artist, duration, 1, 1, 2020, null, "/music", 0, 0, "audio/flac", sourceUri = uri)

    private val library = listOf(song(1, "Café del Mar", "Energy 52"), song(2, "Hey Jude", "The Beatles", 431_000), song(3, "Hey Jude", "The Beatles", 300_000))
    private val searches = AtomicInteger()
    private fun matches(online: List<OnlineTrack> = emptyList(), fallback: OnlineTrack? = null) = PlaylistMatches(scope,
        librarySongs = { library },
        registerStream = { t -> song(-t.id.hashCode().toLong().let { if (it == 0L) -1 else it }, t.title, t.artist, t.durationMs, uri = "stream:${t.id}") },
        searchOnline = { searches.incrementAndGet(); delay(50); online },
        fallback = { fallback },
        file = File(dir, "m.json"))

    @Test fun prefersLibraryIgnoringAccentsCaseAndSmallDurationDrift() = runBlocking {
        val m = matches()
        assertEquals(1L, m.resolve(SharedTrack(title = "cafe DEL mar", artist = "ENERGY 52", durationMs = 203_000)).id)
        assertEquals(2L, m.resolve(SharedTrack(title = "Hey Jude", artist = "The Beatles", durationMs = 430_000)).id)
        assertEquals(1L, m.resolve(SharedTrack(title = "Café del Mar", artist = "Energy 52", durationMs = 0)).id)
        assertEquals(0, searches.get())
    }

    @Test fun usesCatalogueIdThenSearch() = runBlocking {
        val m = matches(online = listOf(OnlineTrack("555", "Other", "Band", "", "", 1000, 1, 1, null, true), OnlineTrack("777", "New Song", "Band", "", "", 180_000, 1, 1, null, true)))
        assertEquals("stream:42", m.resolve(SharedTrack(title = "Unknown", artist = "X", sourceID = "42")).sourceUri)
        // Concurrent requests for the same song share one search.
        val track = SharedTrack(title = "New Song", artist = "Band", durationMs = 181_000)
        val results = (0 until 3).map { async { m.resolve(track.copy(id = "t$it")) } }.awaitAll()
        assertTrue(results.all { it.sourceUri == "stream:777" })
        assertEquals(1, searches.get())
    }

    @Test fun failureIsRememberedUntilRetryOrChoice() = runBlocking {
        val m = matches()
        val track = SharedTrack(title = "Nowhere", artist = "Nobody", durationMs = 1000)
        assertTrue(runCatching { m.resolve(track) }.exceptionOrNull()!!.message!!.contains("No matching copy"))
        assertTrue(PlaylistMatches.key(track) in m.failed.value)
        assertEquals("Choose a local copy or try matching again.", runCatching { m.resolve(track) }.exceptionOrNull()?.message)
        m.choose(track, library[1])
        eventually { PlaylistMatches.key(track) !in m.failed.value }
        assertEquals(2L, m.resolve(track).id)
        delay(500)
        // Choices persist.
        val again = matches()
        assertEquals(2L, again.resolve(track).id)
    }

    @Test fun fallbackWhenSearchFindsNothing() = runBlocking {
        val m = matches(fallback = OnlineTrack("yt-abc", "Rare", "Band", "", "", 1000, 0, 1, null, false))
        val song = m.resolve(SharedTrack(title = "Rare", artist = "Band"))
        assertEquals("stream:yt-abc", song.sourceUri)
    }

    @Test fun prepareWarmsAllTracks() = runBlocking {
        val m = matches()
        val playlist = SharedPlaylist(owner = "a".repeat(64), name = "P", tracks = listOf(SharedTrack(title = "Hey Jude", artist = "The Beatles", durationMs = 300_500), SharedTrack(title = "Missing", artist = "Nobody")))
        m.prepare(playlist)
        eventually { m.failed.value.size == 1 }
        m.retry(playlist)
        eventually { m.failed.value.size == 1 && searches.get() == 2 }
    }
}
