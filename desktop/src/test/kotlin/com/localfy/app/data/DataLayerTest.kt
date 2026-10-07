package com.localfy.app.data

import com.localfy.app.data.art.OnlineArtRepository
import com.localfy.app.data.db.PlayEventEntity
import com.localfy.app.data.lyrics.LyricsRepository
import com.localfy.app.data.lyrics.LyricsSource
import com.localfy.app.data.lyrics.LyricsState
import com.localfy.app.data.lyrics.TagLyricsReader
import com.localfy.app.data.meta.FileTags
import com.localfy.app.data.meta.MetadataEdit
import com.localfy.app.data.meta.MetadataRepository
import com.localfy.app.data.music.AacEncoder
import com.localfy.app.data.music.FlacConversion
import com.localfy.app.data.taste.ProfileRepository
import com.localfy.app.data.taste.TasteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Metadata writes, lyrics sources, taste/profile persistence and FLAC → AAC conversion with id remapping. */
class DataLayerTest {
    private val root = TestAudio.tempDir("data")
    private val music = File(root, "Music").apply { mkdirs() }
    private val data = File(root, "data")
    private val cache = File(root, "cache")
    private val scopes = mutableListOf<CoroutineScope>()
    private val libraries = mutableListOf<LibraryRepository>()

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    private class Env(val scope: CoroutineScope, val library: LibraryRepository, val metadata: MetadataRepository)

    private fun env(): Env {
        val scope = scope()
        val art = OnlineArtRepository(cache, data).apply { setEnabled(false) }
        val metadata = MetadataRepository(scope, art, data, cache)
        val library = LibraryRepository(scope, metadata, dataDir = data, cacheDir = cache, defaultFolders = listOf(music), watchFolders = false)
        libraries += library
        return Env(scope, library, metadata)
    }

    private fun scanned(e: Env, count: Int): Library {
        runBlocking { e.library.refresh(); e.library.awaitScan() }
        return TestAudio.waitFor(value = { e.library.library.value }) { it.songs.size == count }
    }

    @After fun tearDown() {
        libraries.forEach { it.stop() }
        DataFile.flushAll()
        scopes.forEach { it.cancel() }
    }

    @Test fun saveFilesWritesRealTags() {
        TestAudio.tag(TestAudio.flac(File(music, "a.flac"), 31.0, rate = 8000, channels = 1), MetadataEdit("Old", "Someone", "Album", null, null, null, 1, null))
        val e = env()
        val song = scanned(e, 1).songs.single()
        val cover = File(root, "cover.png").apply {
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(1600, 900, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", this)
        }
        runBlocking { e.metadata.saveFiles(listOf(song), MetadataEdit(title = "New Title", year = 1999, genre = "Jazz"), cover.path) }
        val snap = FileTags.read(File(music, "a.flac"))
        assertEquals("New Title", snap.fields.title)
        assertEquals(1999, snap.fields.year)
        assertEquals("Jazz", snap.fields.genre)
        assertEquals("Someone", snap.fields.artist)
        val art = javax.imageio.ImageIO.read(snap.artwork!!.inputStream())
        assertEquals(900, art.width) // square crop, ≤1200
        assertEquals(900, art.height)
        assertNotNull(e.metadata.customArt(song.albumId))
        // No staged leftovers.
        assertTrue(music.listFiles()!!.none { it.name.startsWith(".") })
        // The library rescans and shows the new tags.
        TestAudio.waitFor(value = { e.library.library.value.songs.firstOrNull()?.title }) { it == "New Title" }

        // Read-only files are refused with a readable message.
        File(music, "a.flac").setWritable(false)
        try {
            runBlocking { e.metadata.saveFiles(listOf(song), MetadataEdit(title = "Nope"), null) }
            throw AssertionError("expected failure")
        } catch (err: IllegalStateException) {
            assertTrue(err.message!!.contains("a.flac"))
        } finally { File(music, "a.flac").setWritable(true) }
    }

    @Test fun metadataHelpers() {
        val e = env()
        val untagged = Song(1, "03_some_song", MediaScanner.UNKNOWN_ARTIST, "Downloads", 5, MediaScanner.UNKNOWN_ARTIST, 180_000, 0, 1, 0, null, "/x/Downloads/", 0, 0, null, fileName = "03_some_song.mp3")
        assertTrue(e.metadata.needsFix(untagged))
        assertEquals("some song", e.metadata.queryFor(untagged))
        val candidates = listOf(
            com.localfy.app.data.meta.MetadataCandidate("Some Song", "Band", "LP", 2010, "Pop", 3, 1, 181_000, null, "iTunes"),
            com.localfy.app.data.meta.MetadataCandidate("Some Song", "Band", "LP", null, null, null, null, 240_000, null, "Deezer"),
        )
        assertNull(e.metadata.confidentMatch(untagged, candidates)) // artist words must appear in the query
        val named = untagged.copy(artist = "Band", albumArtist = "Band")
        assertEquals(2010, e.metadata.confidentMatch(named, candidates)!!.year)
        assertTrue(e.metadata.syntheticAlbumId("A", "B") < -10_000_000_000L + 1)
    }

    @Test fun lyricsFromTagsSidecarsFolderAndOnline() {
        val withTag = TestAudio.m4a(File(music, "tagged.m4a"), 31.0, rate = 8000, channels = 1)
        AudioFileIO.read(withTag).let { f -> f.tagOrCreateAndSetDefault.setField(FieldKey.LYRICS, "[00:01.00]Hello\n[00:02.50]World"); f.commit() }
        assertEquals("[00:01.00]Hello\n[00:02.50]World", TagLyricsReader().read(withTag)?.replace("\r", ""))

        val withSidecar = TestAudio.wav(File(music, "side.wav"), 31.0, rate = 8000, channels = 1)
        File(music, "side.lrc").writeText("[00:00.50]Sidecar line")
        val inFolder = TestAudio.wav(File(music, "folder song.wav"), 31.0, rate = 8000, channels = 1)
        val lrcDir = File(root, "lyrics/sub").apply { mkdirs() }
        File(lrcDir, "Folder Song.lrc").writeText("Plain line one\nPlain line two")
        TestAudio.wav(File(music, "nothing.wav"), 31.0, rate = 8000, channels = 1)

        val e = env()
        val lib = scanned(e, 4)
        val online = mutableListOf<Long>()
        val lyrics = LyricsRepository(e.scope, data) { s -> online += s.id; if (s.title == "nothing") "[00:03.00]From the internet" else null }
        lyrics.setOnlineEnabled(false)
        lyrics.setFolder(File(root, "lyrics"))
        fun state(title: String): LyricsState {
            val song = lib.songs.first { it.title == title }
            lyrics.request(song)
            return TestAudio.waitFor(value = { lyrics.states.value[song.id] }) { it != null && it != LyricsState.Loading }!!
        }
        Thread.sleep(200) // setFolder/setOnlineEnabled clear states asynchronously

        val tagged = state("tagged") as LyricsState.Found
        assertEquals(LyricsSource.Embedded, tagged.lyrics.source)
        assertTrue(tagged.lyrics.synced)
        assertEquals(listOf(1000L, 2500L), tagged.lyrics.lines.map { it.timeMs })

        val side = state("side") as LyricsState.Found
        assertEquals(LyricsSource.LrcFile, side.lyrics.source)
        assertEquals("Sidecar line", side.lyrics.lines.single().text)

        val folder = state("folder song") as LyricsState.Found
        assertEquals(LyricsSource.LrcFile, folder.lyrics.source)
        assertFalse(folder.lyrics.synced)
        assertEquals(listOf("Plain line one", "Plain line two"), folder.lyrics.lines.map { it.text })

        assertEquals(LyricsState.NotFound(searchedOnline = false), state("nothing"))
        assertTrue(online.isEmpty())

        // Explicit online search.
        val nothing = lib.songs.first { it.title == "nothing" }
        lyrics.request(nothing, forceOnline = true)
        val fetched = TestAudio.waitFor(value = { lyrics.states.value[nothing.id] }) { it is LyricsState.Found } as LyricsState.Found
        assertEquals(LyricsSource.Lrclib, fetched.lyrics.source)

        // Offsets persist with the cache.
        lyrics.setOffset(nothing, 250)
        lyrics.flush()
        val reopened = LyricsRepository(e.scope, data) { null }
        reopened.request(nothing)
        val again = TestAudio.waitFor(value = { reopened.states.value[nothing.id] }) { it is LyricsState.Found } as LyricsState.Found
        assertEquals(250, again.lyrics.offsetMs)

        // Podcasts and audiobooks never get lyrics (nor lookups).
        reopened.request(nothing.copy(id = 77, isPodcast = true))
        reopened.request(nothing.copy(id = 78, isAudiobook = true))
        Thread.sleep(100)
        assertNull(reopened.states.value[77]); assertNull(reopened.states.value[78])

        // Remap moves cached lyrics to the new id.
        reopened.remapSongs(mapOf(nothing.id to 4242L))
        reopened.request(nothing.copy(id = 4242))
        TestAudio.waitFor(value = { reopened.states.value[4242L] }) { it is LyricsState.Found }
    }

    @Test fun profileAndTastePersist() {
        val e = env()
        val profiles = ProfileRepository(e.scope, data)
        profiles.setName("  Caleb ")
        profiles.setSeedArtists(setOf("A", "B"))
        profiles.completeOnboarding()
        val photo = File(root, "me.jpg").apply { writeBytes(TestAudio.cover(2000)) }
        runBlocking { profiles.setPhoto(photo).join() }
        assertTrue(profiles.profile.value.hasPhoto)
        assertEquals(1536, javax.imageio.ImageIO.read(profiles.photoFile).width)

        val taste = TasteRepository(e.scope, e.library, profiles, data)
        taste.hideSong(5); taste.hideArtist("Nope")
        runBlocking {
            taste.record(PlayEventEntity(songId = 5, startedAt = System.currentTimeMillis(), listenedMs = 1000, durationMs = 2000, completed = false, skipped = true, source = "test")).join()
            taste.record(PlayEventEntity(songId = 6, startedAt = System.currentTimeMillis(), listenedMs = 2000, durationMs = 2000, completed = true, skipped = false, source = null)).join()
        }
        taste.remapSongs(mapOf(5L to 50L))
        runBlocking { taste.remapEvents(mapOf(5L to 50L)) }
        DataFile.flushAll()

        val profiles2 = ProfileRepository(e.scope, data)
        assertEquals(com.localfy.app.data.taste.Profile("Caleb", true, profiles.profile.value.photoVersion, true, setOf("A", "B"), profiles.profile.value.createdAt), profiles2.profile.value)
        val taste2 = TasteRepository(e.scope, e.library, profiles2, data)
        assertEquals(setOf(50L), taste2.hiddenSongs.value)
        assertEquals(setOf("Nope"), taste2.hiddenArtists.value)
        taste2.start()
        val events = TestAudio.waitFor(value = { taste2.events.value }) { it.size == 2 }
        assertEquals(listOf(50L, 6L), events.map { it.songId })
        assertEquals(listOf(1L, 2L), events.map { it.id })
        taste2.unhideAll()
        assertTrue(taste2.hiddenSongs.value.isEmpty())
        assertEquals(emptyList<Song>(), taste2.artistRadio("x"))
    }

    @Test fun tasteGeneratesMixesForTheLibrary() {
        repeat(6) { i ->
            TestAudio.tag(TestAudio.m4a(File(music, "s$i.m4a"), 31.0, rate = 8000, channels = 1),
                MetadataEdit("Song $i", "Artist ${i % 2}", "Album ${i % 2}", null, "Rock", 2000 + i, i + 1, 1))
        }
        val e = env()
        val lib = scanned(e, 6)
        val taste = TasteRepository(e.scope, e.library, ProfileRepository(e.scope, data), data)
        taste.start()
        TestAudio.waitFor(value = { taste.model.value }) { it != null }
        assertTrue(taste.songRadio(lib.songs.first()).isNotEmpty())
    }

    @Test fun flacConversionReplacesFilesAndRemapsEverything() {
        val cover = TestAudio.cover(700)
        val flac = TestAudio.tag(TestAudio.flac(File(music, "Album/01 Track.flac"), 31.0, rate = 96_000, channels = 2),
            MetadataEdit("Track", "Artist", "Album", "Artist", "Rock", 2005, 1, 1), cover)
        AudioFileIO.read(flac).let { f -> f.tag.setField(FieldKey.LYRICS, "plain words"); f.commit() }
        val keep = TestAudio.tag(TestAudio.flac(File(music, "Album/02 Keep.flac"), 31.0, rate = 8000, channels = 1), MetadataEdit(title = "Keep"))

        val e = env()
        val lib = scanned(e, 2)
        val song = lib.songs.first { it.title == "Track" }
        val profiles = ProfileRepository(e.scope, data)
        val taste = TasteRepository(e.scope, e.library, profiles, data)
        val lyrics = LyricsRepository(e.scope, data) { null }
        runBlocking {
            e.library.toggleLike(song.id).join()
            e.library.recordPlay(song.id).join()
            e.library.createPlaylist("P", listOf(song.id))
            e.metadata.save(listOf(song), MetadataEdit(genre = "Edited")).join()
            taste.record(PlayEventEntity(songId = song.id, startedAt = System.currentTimeMillis(), listenedMs = 1, durationMs = 1, completed = true, skipped = false, source = null)).join()
        }
        taste.hideSong(song.id)
        lyrics.request(song)
        TestAudio.waitFor(value = { lyrics.states.value[song.id] }) { it is LyricsState.Found }

        val queueRemaps = mutableListOf<Map<Long, Long>>()
        val keepId = lib.songs.first { it.title == "Keep" }.id
        val conversion = FlacConversion(e.scope, e.library,
            FlacConversion.standardRemap(e.library, e.metadata, lyrics, taste) { queueRemaps += it },
            playingSongId = { keepId })
        assertEquals(2, conversion.candidates().size)
        assertTrue(conversion.estimatedSaving(conversion.candidates()) >= 0) // a pure sine compresses better as FLAC than 256k AAC

        // Declining keeps the library exactly as it was.
        conversion.start()
        val ask = TestAudio.waitFor(timeoutMs = 60_000, value = { conversion.state.value }) { it is FlacConversion.State.NeedsApproval } as FlacConversion.State.NeedsApproval
        assertEquals(listOf(flac.absoluteFile), ask.originals.map { it.absoluteFile })
        runBlocking { conversion.approvalResult(false).join() }
        assertTrue(flac.isFile)
        assertTrue(File(music, "Album").listFiles()!!.none { it.extension == "m4a" || it.name.startsWith(".") })

        // Approving replaces the FLAC.
        conversion.start()
        TestAudio.waitFor(timeoutMs = 60_000, value = { conversion.state.value }) { it is FlacConversion.State.NeedsApproval }
        runBlocking { conversion.approvalResult(true).join() }
        val finished = TestAudio.waitFor(value = { conversion.state.value }) { it is FlacConversion.State.Finished } as FlacConversion.State.Finished
        assertEquals(1, finished.converted)
        assertFalse(flac.exists())
        assertTrue(keep.exists()) // the playing song waits for the next run
        val m4a = File(music, "Album/01 Track.m4a")
        assertTrue(m4a.isFile)
        val probe = AacEncoder.probe(m4a)!!
        assertEquals("aac", probe.codec)
        assertEquals(48_000, probe.sampleRate)
        val tags = FileTags.read(m4a)
        assertEquals("Track", tags.fields.title)
        assertEquals("Artist", tags.fields.artist)
        assertEquals(2005, tags.fields.year)
        assertNotNull(tags.artwork)
        assertEquals("plain words", FileTags.lyrics(m4a))

        val newId = stableSongId(m4a)
        assertEquals(listOf(mapOf(song.id to newId)), queueRemaps)
        assertEquals(setOf(newId), e.library.likedIds.value)
        assertEquals(1, e.library.stats.value[newId]!!.playCount)
        assertEquals(setOf(newId), taste.hiddenSongs.value)
        assertEquals(listOf(newId), taste.events.value.map { it.songId })
        val override = e.metadata.overrides.value[newId]!!
        assertEquals("01 Track.m4a", override.fileName)
        assertEquals("Edited", override.genre)
        // The library rescans: the playlist, likes and edits follow the song to its new file.
        val converted = TestAudio.waitFor(value = { e.library.library.value.songById[newId] }) { it != null }!!
        assertEquals("Edited", converted.genre)
        TestAudio.waitFor(value = { e.library.playlists.value.firstOrNull()?.songs?.map { it.id } }) { it == listOf(newId) }
        lyrics.request(converted)
        TestAudio.waitFor(value = { lyrics.states.value[newId] }) { it is LyricsState.Found }
    }
}
