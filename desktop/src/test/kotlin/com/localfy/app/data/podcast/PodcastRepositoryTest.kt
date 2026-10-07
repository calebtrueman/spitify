package com.localfy.app.data.podcast

import com.localfy.app.data.db.EpisodeEntity
import com.localfy.app.data.db.PodcastEntity
import com.localfy.app.data.music.DownloadTransport
import com.localfy.app.data.music.TransferFailed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PodcastRepositoryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val root: File = Files.createTempDirectory("podcasts-test").toFile()
    @After fun tearDown() { scope.cancel(); root.deleteRecursively() }

    private fun ep(guid: String, date: Long, url: String = "https://cdn.example/$guid.mp3") =
        ParsedEpisode(guid, "Title $guid", "About $guid", url, "audio/mpeg", date, 60_000, null)

    private var feed = ParsedFeed("Show", "Host", "Desc", "https://img/show.jpg", listOf(ep("a", 1_000_000), ep("b", 2_000_000)))
    private val failing = mutableMapOf<String, Int>()
    private val transport = DownloadTransport { url, dest, progress ->
        failing[url]?.let { left -> if (left > 0) { failing[url] = left - 1; throw TransferFailed(503, "busy") } }
        dest.writeBytes(ByteArray(1000) { 1 }); progress(1000, 1000); 1000
    }

    private fun repo() = PodcastRepository(scope, dataDir = root, downloadsDir = File(root, "podcasts"), transport = transport,
        readFeed = { url -> if (url.contains("broken")) throw java.io.IOException("nope") else feed })

    @Test fun episodeSongMapping() {
        val podcast = PodcastEntity(7, "https://feed", "My Show", "Me", "", "https://art/show.jpg", 0, 1, KIND_AUDIOBOOK)
        val episode = EpisodeEntity(42, 7, "g", "Chapter 3", "", "https://cdn/x/ch3.mp3?token=1", "audio/mpeg", 5_000_000, 90_000, null, null, null, position = 2)
        val song = episode.toSong(podcast)
        assertEquals(-42L, song.id)
        assertEquals(-7L - 1_000_000, song.albumId)
        assertEquals("My Show", song.artist); assertEquals("My Show", song.album); assertEquals("Me", song.albumArtist)
        assertEquals("ch3.mp3", song.fileName)
        assertEquals("https://cdn/x/ch3.mp3?token=1", song.sourceUri)
        assertEquals("https://art/show.jpg", song.artUrl)
        assertEquals(3, song.track)
        assertEquals(5_000L, song.dateAddedSec)
        assertTrue(song.isPodcast); assertTrue(song.isAudiobook)
        assertEquals("ep:42", song.resumeKey)
        val local = File(root, "ep_42.mp3")
        assertEquals(local.toURI().toString(), episode.copy(localPath = local.path).toSong(podcast).sourceUri)
        assertEquals("123", song.copy(id = 123, episodeId = null).resumeKey)
    }

    @Test fun subscribeRefreshAndReload() = runBlocking {
        val r = repo()
        assertNull(r.subscribe("https://broken.example/feed"))
        val id = r.subscribe("https://good.example/feed", artworkHint = "https://hint.jpg")!!
        assertEquals(id, r.subscribe("https://good.example/feed")) // same feed, same show
        val show = r.shows.value.single()
        assertEquals("Show", show.podcast.title); assertEquals("https://hint.jpg", show.podcast.artworkUrl)
        assertEquals(listOf("b", "a"), show.episodes.map { it.guid }) // newest first
        val songs = withTimeout(5_000) { r.episodeSongs.first { it.size == 2 } }
        assertTrue(songs.values.all { it.isPodcast && it.id < 0 })

        // Refresh adds only new guids and keeps the old rows (and their ids).
        val oldIds = show.episodes.associate { it.guid to it.id }
        feed = feed.copy(title = "Show Renamed", episodes = listOf(ep("c", 3_000_000), ep("b", 2_000_000), ep("a", 1_000_000)))
        r.refreshAll().join()
        val refreshed = r.shows.value.single()
        assertEquals("Show Renamed", refreshed.podcast.title)
        assertEquals(listOf("c", "b", "a"), refreshed.episodes.map { it.guid })
        assertEquals(oldIds["a"], refreshed.episodes.first { it.guid == "a" }.id)

        r.setFollowing(id, false).join()
        assertEquals(0L, r.shows.value.single().podcast.subscribedAt)
        r.saveProgress("ep:${oldIds["a"]}", 30_000, 600_000).join()
        r.flush()

        val again = repo()
        assertEquals(3, again.shows.value.single().episodes.size)
        assertEquals(30_000L, again.resumePosition("ep:${oldIds["a"]}"))
        // A new show after reload gets a fresh id.
        assertTrue(again.subscribe("https://other.example/feed")!! > id)
        r.unsubscribe(id).join()
        assertTrue(r.shows.value.isEmpty())
    }

    @Test fun resumeAndPlayed() = runBlocking {
        val r = repo()
        r.saveProgress("ep:1", 120_000, 3_600_000).join()
        assertEquals(120_000L, r.resumePosition("ep:1"))
        r.saveProgress("ep:1", 3_590_000, 3_600_000).join() // within 30 s of the end: played
        assertEquals(0L, r.resumePosition("ep:1"))
        assertTrue(r.resume.value["ep:1"]!!.played)
        r.setPlayed("ep:1", false, 3_600_000).join()
        assertTrue(!r.resume.value["ep:1"]!!.played)
    }

    @Test fun downloadRetriesThenSavesAndDeletes() = runBlocking {
        val r = repo()
        r.subscribe("https://good.example/feed")
        val episode = r.shows.value.single().episodes.first { it.guid == "a" }
        failing[episode.audioUrl] = 1 // one temporary failure first
        r.download(episode.id).join()
        val done = withTimeout(20_000) { r.shows.first { s -> s.single().episodes.first { it.id == episode.id }.localPath != null } }
            .single().episodes.first { it.id == episode.id }
        val file = File(done.localPath!!)
        assertEquals(File(root, "podcasts/ep_${episode.id}.mp3").absolutePath, file.absolutePath)
        assertEquals(1000L, file.length())
        assertNotNull(done.downloadId)
        withTimeout(5_000) { while (r.downloadProgress.value.isNotEmpty()) delay(10) }
        assertEquals(file.toURI().toString(), r.episodeSongs.first { it[-episode.id]?.sourceUri?.startsWith("file:") == true }[-episode.id]!!.sourceUri)

        r.deleteDownload(episode.id).join()
        assertTrue(!file.exists())
        val cleared = r.shows.value.single().episodes.first { it.id == episode.id }
        assertNull(cleared.localPath); assertNull(cleared.downloadId)
    }
}
