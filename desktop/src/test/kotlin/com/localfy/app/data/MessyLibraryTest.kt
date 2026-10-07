package com.localfy.app.data

import com.localfy.app.data.art.OnlineArtRepository
import com.localfy.app.data.meta.MetadataEdit
import com.localfy.app.data.meta.MetadataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/**
 * A big, badly tagged library (the phone app once crashed on real-world ones): thousands of files,
 * truncated and fake audio, broken ID3 headers, odd names, deep nesting and a symlink loop.
 * The scan must finish, keep every readable song and skip the rest.
 */
class MessyLibraryTest {
    @Test fun survivesAMessyLibrary() {
        val root = TestAudio.tempDir("messy")
        val music = File(root, "Music").apply { mkdirs() }
        val template = TestAudio.tag(TestAudio.m4a(File(root, "template.m4a"), 31.0, rate = 8000, channels = 1), MetadataEdit(title = "T", artist = "A", album = "Al"))
        val templateBytes = template.readBytes()
        val rnd = Random(7)
        val good = 2_000
        repeat(good) { i ->
            val dir = File(music, "Artist ${i % 40}/Album ${i % 120}").apply { mkdirs() }
            File(dir, "${i} – ${"ünïcødé ".repeat(i % 3)}track.m4a").writeBytes(templateBytes)
        }
        val junk = File(music, "junk").apply { mkdirs() }
        repeat(150) { i -> File(junk, "truncated $i.m4a").writeBytes(templateBytes.copyOf(200 + i * 10)) }
        repeat(150) { i -> File(junk, "noise $i.${listOf("mp3", "flac", "ogg", "wav", "opus", "wma", "ape")[i % 7]}").writeBytes(rnd.nextBytes(3000 + i)) }
        repeat(20) { i ->
            // "ID3" with an absurd tag size, then garbage.
            File(junk, "bad id3 $i.mp3").writeBytes(byteArrayOf(0x49, 0x44, 0x33, 3, 0, 0, 0x7F, 0x7F, 0x7F, 0x7F) + rnd.nextBytes(5000))
        }
        var deep = music
        repeat(30) { deep = File(deep, "d$it") }
        deep.mkdirs()
        File(deep, "deep.m4a").writeBytes(templateBytes)
        File(music, "x".repeat(200) + ".m4a").writeBytes(templateBytes)
        runCatching { Files.createSymbolicLink(File(music, "loop").toPath(), music.toPath()) }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val data = File(root, "data"); val cache = File(root, "cache")
            val art = OnlineArtRepository(cache, data).apply { setEnabled(false) }
            val library = LibraryRepository(scope, MetadataRepository(scope, art, data, cache), dataDir = data, cacheDir = cache,
                defaultFolders = listOf(music), watchFolders = false)
            val started = System.currentTimeMillis()
            runBlocking { library.refresh(); library.awaitScan() }
            val firstScan = System.currentTimeMillis() - started
            val lib = TestAudio.waitFor(value = { library.library.value }) { it.songs.size >= good + 2 }
            assertEquals(good + 2, lib.songs.count { it.title == "T" })
            assertEquals(lib.songs.size, lib.songs.map { it.id }.toSet().size)
            assertTrue(library.skippedFiles.value >= 150)

            val again = System.currentTimeMillis()
            runBlocking { library.refresh(); library.awaitScan() }
            val rescan = System.currentTimeMillis() - again
            assertEquals(0, library.scanProgress.value)
            println("Messy library: ${lib.songs.size} songs, ${library.skippedFiles.value} skipped; first scan ${firstScan}ms, rescan ${rescan}ms")
            library.stop()
        } finally {
            scope.cancel()
        }
    }
}
