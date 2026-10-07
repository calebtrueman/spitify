package com.localfy.app

import com.localfy.app.data.TestAudio
import com.localfy.app.data.file
import com.localfy.app.data.music.FlacConversion
import com.localfy.app.data.music.Monochrome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The whole desktop container end to end: scan a library, play local AAC and FLAC through the
 * real FFmpeg engine and speakers, stream an online song, then convert the FLAC to AAC.
 * Runs only with SPITIFY_LIVE_APP=1, on its own: ./gradlew :desktop:test --tests '*LiveAppCheck*'
 */
class LiveAppCheck {
    @Test fun scanPlayStreamConvert() = runBlocking {
        assumeTrue(System.getenv("SPITIFY_LIVE_APP") == "1")
        val root = TestAudio.tempDir("spitify-app")
        val music = File(root, "Music/Neon Harbor/Midnight Static").apply { mkdirs() }
        TestAudio.m4a(File(music, "01 Low Tide.m4a"), 75.0)
        TestAudio.flac(File(music, "02 Glass Hours.flac"), 75.0)
        System.setProperty("spitify.dataDir", File(root, "data").apply { mkdirs() }.path)
        System.setProperty("spitify.cacheDir", File(root, "cache").apply { mkdirs() }.path)
        File(root, "data/prefs-library.json").writeText("""{"folders":["${File(root, "Music").path}"]}""")

        val app = LocalfyApp()
        withContext(Dispatchers.Main) { app.start() }
        val songs = withTimeout(30_000) {
            while (app.library.library.value.songs.size < 2) delay(100)
            app.library.library.value.songs
        }
        println("APP scanned: " + songs.joinToString { "${it.title} (${it.durationMs} ms)" })

        for (song in songs.sortedBy { it.title }) {
            withContext(Dispatchers.Main) { app.player.playSongs(listOf(song), source = "check") }
            val pos = withTimeout(15_000) { while (app.player.positionMs.value < 1_500) delay(50); app.player.positionMs.value }
            println("APP local ${song.fileName}: playing=${app.player.state.value.isPlaying} at $pos ms")
            assertEquals(song.id, app.player.state.value.currentId)
        }

        val track = Monochrome.search("daft punk one more time").first { it.playable }
        val streamed = app.musicStreams.register(track)
        val started = System.currentTimeMillis()
        withContext(Dispatchers.Main) { app.player.playSongs(listOf(streamed), source = "check") }
        withTimeout(30_000) { while (app.player.positionMs.value < 1_500 || app.player.state.value.currentId != streamed.id) delay(50) }
        println("APP stream '${track.title}': audible after ${System.currentTimeMillis() - started - 1_500} ms")
        withContext(Dispatchers.Main) { app.player.setPlaying(false) }

        val flac = app.flacConversion.candidates().single()
        app.flacConversion.start()
        val done = withTimeout(120_000) {
            while (app.flacConversion.state.value !is FlacConversion.State.Finished) delay(100)
            app.flacConversion.state.value as FlacConversion.State.Finished
        }
        println("APP conversion: $done")
        assertEquals(1, done.converted)
        assertTrue(!flac.file!!.exists() && File(music, "02 Glass Hours.m4a").isFile)
        withContext(Dispatchers.Main) { app.shutdown() }
    }
}
