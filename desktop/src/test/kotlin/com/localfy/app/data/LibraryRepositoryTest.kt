package com.localfy.app.data

import com.localfy.app.data.art.ArtworkStore
import com.localfy.app.data.art.OnlineArtRepository
import com.localfy.app.data.meta.FileTags
import com.localfy.app.data.meta.MetadataEdit
import com.localfy.app.data.meta.MetadataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LibraryRepositoryTest {
    private val root = TestAudio.tempDir("lib")
    private val music = File(root, "Music").apply { mkdirs() }
    private val data = File(root, "data")
    private val cache = File(root, "cache")
    private val scopes = mutableListOf<CoroutineScope>()
    private val repos = mutableListOf<LibraryRepository>()

    private class Env(val library: LibraryRepository, val metadata: MetadataRepository, val art: OnlineArtRepository)

    private fun env(watch: Boolean = false, saved: MutableStateFlow<List<Song>> = MutableStateFlow(emptyList())): Env {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val art = OnlineArtRepository(cache, data).apply { setEnabled(false) }
        val metadata = MetadataRepository(scope, art, data, cache)
        val library = LibraryRepository(scope, metadata, saved, dataDir = data, cacheDir = cache, defaultFolders = listOf(music), watchFolders = watch)
        repos += library
        return Env(library, metadata, art)
    }

    @After fun tearDown() {
        repos.forEach { it.stop() }
        DataFile.flushAll()
        scopes.forEach { it.cancel() }
    }

    private fun scan(lib: LibraryRepository) = runBlocking {
        lib.refresh()
        lib.awaitScan()
    }

    private fun buildLibrary() {
        val cover = TestAudio.cover()
        val album = File(music, "Artist/Album").apply { mkdirs() }
        TestAudio.tag(TestAudio.m4a(File(album, "01 One.m4a"), 31.0, rate = 8000, channels = 1),
            MetadataEdit("One", "The Band", "First Album", "The Band", "Rock", 2001, 1, 1), cover)
        TestAudio.tag(TestAudio.flac(File(album, "02 Two.flac"), 31.0, rate = 8000, channels = 1),
            MetadataEdit("Two", "The Band feat. Guest", "First Album", "The Band", "Rock", 2001, 2, 1))
        // Untagged WAV: title from file name, album from folder.
        TestAudio.wav(File(music, "Loose Folder/untagged track.wav"), 31.0, rate = 8000, channels = 1)
        // Short clip (hidden while "hide short tracks" is on).
        TestAudio.wav(File(music, "Loose Folder/jingle.wav"), 2.0, rate = 8000, channels = 1)
        // Corrupt and empty files must be skipped, not crash.
        File(music, "Loose Folder/broken.mp3").writeBytes(ByteArray(20_000) { (it * 7 + 3).toByte() })
        File(music, "Loose Folder/empty.flac").writeBytes(ByteArray(0))
        File(music, "Loose Folder/notes.txt").writeText("not audio")
        // Spoken word goes to its own lists.
        TestAudio.wav(File(music, "Audiobooks/Some Book/chapter 1.wav"), 3.0, rate = 8000, channels = 1)
        TestAudio.tag(TestAudio.m4a(File(music, "Shows/episode.m4a"), 3.0, rate = 8000, channels = 1), MetadataEdit(title = "Episode 1", genre = "Podcast"))
        // Hidden folders are ignored.
        TestAudio.wav(File(music, ".hidden/secret.wav"), 31.0, rate = 8000, channels = 1)
    }

    @Test fun scansTagsAndSplitsKinds() {
        buildLibrary()
        val e = env()
        scan(e.library)
        val lib = TestAudio.waitFor(value = { e.library.library.value }) { it.songs.size == 3 }
        val one = lib.songs.first { it.title == "One" }
        assertEquals("The Band", one.artist)
        assertEquals("First Album", one.album)
        assertEquals(2001, one.year)
        assertEquals(1, one.track)
        assertEquals("Rock", one.genre)
        assertTrue(one.durationMs in 30_500..31_500)
        val file = File(music, "Artist/Album/01 One.m4a")
        assertEquals(stableSongId(file), one.id)
        assertTrue(one.id > 0)
        assertEquals(file.toURI().toString(), one.sourceUri)
        assertEquals(file.absoluteFile, one.file)
        assertEquals("01 One.m4a", one.fileName)
        assertEquals("audio/mp4", one.mimeType)

        val two = lib.songs.first { it.title == "Two" }
        assertEquals(one.albumId, two.albumId)
        assertEquals("audio/flac", two.mimeType)
        assertEquals(listOf("One", "Two"), lib.albums.single { it.title == "First Album" }.songs.map { it.title })

        val loose = lib.songs.first { it.fileName == "untagged track.wav" }
        assertEquals("untagged track", loose.title)
        assertEquals(MediaScanner.UNKNOWN_ARTIST, loose.artist)
        assertEquals("Loose Folder", loose.album)
        assertNotEquals(one.albumId, loose.albumId)

        assertEquals(listOf("chapter 1"), e.library.localBooks.value.map { it.title })
        assertTrue(e.library.localBooks.value.single().isAudiobook)
        assertEquals(listOf("Episode 1"), e.library.localPodcasts.value.map { it.title })
        assertTrue(e.library.localPodcasts.value.single().isPodcast)
        // broken.mp3 is skipped (or, if FFmpeg finds "audio" in the noise, hidden as a short clip); empty files are ignored.
        assertTrue(e.library.skippedFiles.value <= 1)

        // Short tracks appear once the filter is off.
        e.library.setHideShortTracks(false)
        runBlocking { e.library.awaitScan() }
        TestAudio.waitFor(value = { e.library.library.value.songs.map { it.title } }) { "jingle" in it }
    }

    @Test fun rescanPicksUpChangesAndUsesCache() {
        buildLibrary()
        val e = env()
        scan(e.library)
        TestAudio.waitFor(value = { e.library.library.value.songs.size }) { it == 3 }
        val progressFirst = e.library.scanProgress.value
        assertTrue("read $progressFirst", progressFirst >= 7)

        // Nothing changed: the second scan reads no files.
        scan(e.library)
        assertEquals(0, e.library.scanProgress.value)

        // Retag one file, add one, delete one.
        val two = File(music, "Artist/Album/02 Two.flac")
        runBlocking { FileTags.write(two, MetadataEdit(title = "Two (Remastered)")) }
        TestAudio.tag(TestAudio.m4a(File(music, "New/three.m4a"), 31.0, rate = 8000, channels = 1), MetadataEdit(title = "Three", artist = "Other"))
        File(music, "Loose Folder/untagged track.wav").delete()
        scan(e.library)
        assertEquals(2, e.library.scanProgress.value)
        val titles = TestAudio.waitFor(value = { e.library.library.value.songs.map { it.title }.toSet() }) { "Three" in it }
        assertEquals(setOf("One", "Two (Remastered)", "Three"), titles)

        // A new repository (next launch) gets the same ids from the on-disk cache without reading files.
        val again = env()
        scan(again.library)
        assertEquals(0, again.library.scanProgress.value)
        assertEquals(e.library.library.value.songs.map { it.id }.toSet(), TestAudio.waitFor(value = { again.library.library.value.songs.map { it.id }.toSet() }) { it.size == 3 })
    }

    @Test fun overridesAndSavedStreamsMerge() {
        buildLibrary()
        val saved = MutableStateFlow<List<Song>>(emptyList())
        val e = env(saved = saved)
        scan(e.library)
        val one = TestAudio.waitFor(value = { e.library.library.value.songs.firstOrNull { it.title == "One" } }) { it != null }!!
        runBlocking { e.metadata.save(listOf(one), MetadataEdit(title = "Uno", album = "Renamed")).join() }
        val renamed = TestAudio.waitFor(value = { e.library.library.value.songs.firstOrNull { it.id == one.id } }) { it?.title == "Uno" }!!
        assertEquals("Renamed", renamed.album)
        assertEquals(e.metadata.syntheticAlbumId("Renamed", "The Band"), renamed.albumId)
        assertEquals("One", e.library.rawSongs.value.first { it.id == one.id }.title)

        val stream = Song(9_000_001, "Streamed", "Someone", "Online", 77, "Someone", 200_000, 1, 1, 2020, null, "", 0, 0, null, sourceUri = "spitify:track:1")
        saved.value = listOf(stream)
        TestAudio.waitFor(value = { e.library.library.value.songs.map { it.title } }) { "Streamed" in it }

        runBlocking { e.metadata.reset(listOf(one)).join() }
        TestAudio.waitFor(value = { e.library.library.value.songs.firstOrNull { it.id == one.id }?.title }) { it == "One" }
    }

    @Test fun likesStatsAndPlaylistsPersist() {
        val e = env()
        runBlocking {
            e.library.toggleLike(1).join(); e.library.toggleLike(2).join(); e.library.toggleLike(1).join(); e.library.toggleLike(3).join()
            e.library.recordPlay(2).join(); e.library.recordPlay(2).join(); e.library.recordSkip(2).join(); e.library.recordSkip(5).join()
            val p = e.library.createPlaylist("  Road trip ", listOf(1, 2, 3))
            e.library.appendToPlaylist(p, listOf(4))
            e.library.renamePlaylist(p, "Road trip!").join()
            val gone = e.library.createPlaylist("", listOf(9))
            e.library.deletePlaylist(gone).join()
            e.library.reorderPlaylist(p, listOf(3, 1, 2, 4)).join()
        }
        e.library.flush()

        val again = env()
        assertEquals(setOf(3L, 2L), again.library.likedIds.value)
        assertEquals(listOf(3L, 2L), again.library.likedIds.value.toList()) // newest first
        assertEquals(2, again.library.stats.value[2]!!.playCount)
        assertEquals(1, again.library.stats.value[2]!!.skipCount)
        assertEquals(0, again.library.stats.value[5]!!.playCount)
        assertTrue(again.library.stats.value[2]!!.lastPlayed > 0)

        // Songs 1..4 aren't in the (empty) library: playlists list only songs that exist, like Android.
        val playlists = TestAudio.waitFor(value = { again.library.playlists.value }) { it.size == 1 }
        assertEquals("Road trip!", playlists.single().name)
        assertTrue(playlists.single().songs.isEmpty())

        // With a stream lookup the entries resolve, in the reordered order, with entry ids.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val art = OnlineArtRepository(cache, data).apply { setEnabled(false) }
        val lookup = LibraryRepository(scope, MetadataRepository(scope, art, data, cache), streamLookup = { id -> fakeSong(id) },
            dataDir = data, cacheDir = cache, defaultFolders = listOf(music), watchFolders = false).also { repos += it }
        val p = TestAudio.waitFor(value = { lookup.playlists.value.firstOrNull() }) { it != null && it.songs.size == 4 }!!
        assertEquals(listOf(3L, 1L, 2L, 4L), p.songs.map { it.id })
        assertEquals(4, p.entryIds.distinct().size)
        runBlocking { lookup.removeFromPlaylist(p.id, p.entryIds[1]).join() }
        TestAudio.waitFor(value = { lookup.playlists.value.first().songs.map { it.id } }) { it == listOf(3L, 2L, 4L) }

        // Playlist cover round trip.
        val img = File(root, "cover.jpg").apply { writeBytes(TestAudio.cover(1500)) }
        assertTrue(runBlocking { lookup.setPlaylistCover(p.id, img) })
        val withArt = TestAudio.waitFor(value = { lookup.playlists.value.first() }) { it.artwork != null }
        assertEquals(1000, javax.imageio.ImageIO.read(File(java.net.URI(withArt.artwork!!))).width)
        assertTrue(runBlocking { lookup.setPlaylistCover(p.id, null) })
        TestAudio.waitFor(value = { lookup.playlists.value.first() }) { it.artwork == null }
    }

    @Test fun mixesCanBeHiddenAndRestored() {
        val e = env()
        val song = fakeSong(1)
        e.library.publishMixes(listOf(Mix("daily1", "Daily Mix 1", "", listOf(song)), Mix("discover", "Discover", "", listOf(song))))
        TestAudio.waitFor(value = { e.library.mixes.value.size }) { it == 2 }
        e.library.deleteMix("daily1")
        TestAudio.waitFor(value = { e.library.mixes.value.map { it.key } }) { it == listOf("discover") }
        assertEquals(1, TestAudio.waitFor(value = { e.library.hiddenMixCount.value }) { it == 1 })
        e.library.flush()
        val again = env()
        assertEquals(1, again.library.hiddenMixCount.value)
        again.library.restoreDeletedMixes()
        TestAudio.waitFor(value = { again.library.hiddenMixCount.value }) { it == 0 }
    }

    @Test fun foldersCanBeAddedAndRemoved() {
        val other = File(root, "Elsewhere").apply { mkdirs() }
        TestAudio.tag(TestAudio.m4a(File(other, "x.m4a"), 31.0, rate = 8000, channels = 1), MetadataEdit(title = "Elsewhere Song", artist = "X"))
        val e = env()
        scan(e.library)
        assertTrue(e.library.library.value.songs.isEmpty())
        e.library.addFolder(other)
        runBlocking { e.library.awaitScan() }
        TestAudio.waitFor(value = { e.library.library.value.songs.map { it.title } }) { it == listOf("Elsewhere Song") }
        assertEquals(listOf(music, other.absoluteFile.normalize()), e.library.folders.value)
        e.library.removeFolder(other)
        runBlocking { e.library.awaitScan() }
        TestAudio.waitFor(value = { e.library.library.value.songs }) { it.isEmpty() }
    }

    @Test fun watcherRescansWhenFilesAppear() {
        val e = env(watch = true)
        e.library.start()
        runBlocking { e.library.awaitScan() }
        TestAudio.tag(TestAudio.m4a(File(music, "Dropped/new.m4a"), 31.0, rate = 8000, channels = 1), MetadataEdit(title = "Dropped In", artist = "Y"))
        // macOS's WatchService polls (≈10 s); allow for that plus the debounce.
        TestAudio.waitFor(timeoutMs = 40_000, value = { e.library.library.value.songs.map { it.title } }) { "Dropped In" in it }
    }

    @Test fun smartCollections() {
        val now = 1_000_000_000_000L
        val day = 86_400_000L
        val songs = (1L..5L).map { fakeSong(it).copy(dateAddedSec = it * 100) }
        val lib = Library.from(songs)
        val stats = mapOf(
            1L to PlayStat(1, 10, now - 40 * day, 0), // forgotten favourite
            2L to PlayStat(2, 3, now - day, 0),
            3L to PlayStat(3, 0, 0, 2),
        )
        val smart = SmartCollection.build(lib, setOf(1L), stats, now, seed = 42)
        assertEquals(5, smart[SmartCollection.Kind.AllSongs]!!.songs.size)
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), smart[SmartCollection.Kind.RecentlyAdded]!!.songs.map { it.id })
        assertEquals(listOf(1L, 2L), smart[SmartCollection.Kind.MostPlayed]!!.songs.map { it.id })
        assertEquals(listOf(2L, 1L), smart[SmartCollection.Kind.RecentlyPlayed]!!.songs.map { it.id })
        assertEquals(listOf(1L), smart[SmartCollection.Kind.Forgotten]!!.songs.map { it.id })
        assertEquals(setOf(3L, 4L, 5L), smart[SmartCollection.Kind.NeverPlayed]!!.songs.map { it.id }.toSet())
        assertEquals(SmartCollection.Kind.entries.toSet(), smart.keys)
    }

    @Test fun artworkComesFromCustomEmbeddedAndCache() {
        buildLibrary()
        val e = env()
        scan(e.library)
        val lib = TestAudio.waitFor(value = { e.library.library.value }) { it.songs.size == 3 }
        val store = ArtworkStore(e.metadata, e.art, cache)
        val one = lib.songs.first { it.title == "One" }
        val bytes = runBlocking { store.albumArt(one, 200) }
        assertNotNull(bytes)
        val img = javax.imageio.ImageIO.read(bytes!!.inputStream())
        assertEquals(256, img.width) // bucketed size
        assertTrue(File(cache, "art-embedded").listFiles()!!.any { it.name.endsWith("-256.jpg") })
        // No art anywhere (and online disabled): null, cached as a miss.
        val loose = lib.songs.first { it.fileName == "untagged track.wav" }
        assertNull(runBlocking { store.albumArt(loose, 200) })
        // A custom cover wins.
        runBlocking { e.metadata.setArt(loose.albumId, File(root, "c.jpg").apply { writeBytes(TestAudio.cover(800)) }.path).join() }
        val custom = TestAudio.waitFor(value = { e.library.library.value.songs.first { it.id == loose.id } }) { it.artVersion > 0 }
        assertNotNull(runBlocking { store.albumArt(custom, 512) })
        assertNotNull(e.metadata.customArt(loose.albumId))
    }

    private fun fakeSong(id: Long) = Song(id, "Song $id", "Artist $id", "Album", 1, "Artist $id", 200_000, id.toInt(), 1, 2000, "Pop", "/m/", 0, 0, null)

    private fun assertNotEquals(a: Any?, b: Any?) = assertFalse("$a should differ from $b", a == b)
}
