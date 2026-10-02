package com.localfy.app

import android.content.ComponentName
import android.net.Uri
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localfy.app.data.Song
import com.localfy.app.playback.PlaybackService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class QueueTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return result!!.getOrThrow()
    }
    private fun waitFor(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (!main(check) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(main(check))
    }

    @Test fun manualPicksKeepPriorityThroughPlaybackShuffleAndEdits() {
        val app = instrumentation.targetContext.applicationContext as LocalfyApp
        val audio = File(app.cacheDir, "queue-test.flac")
        instrumentation.context.assets.open("tags/sample.flac").use { input -> audio.outputStream().use(input::copyTo) }
        fun song(id: Long) = Song(id, "$id", "Test", "Test", 1, "Test", 1000, 1, 1, 0, null, "", 0, audio.length(), "audio/flac", "test.flac", Uri.fromFile(audio))
        val control = main { MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }.get(20, TimeUnit.SECONDS)
        try {
            main { app.player.connect() }
            waitFor { app.player.state.value.connected }
            main { app.player.playSongs(listOf(song(1), song(2), song(3)), shuffle = false); control.pause() }
            waitFor { control.mediaItemCount == 3 }
            main { control.pause(); app.player.addToQueue(listOf(song(4))); app.player.addToQueue(listOf(song(5), song(6))) }
            waitFor { app.player.state.value.queue == listOf(1L, 4L, 5L, 6L, 2L, 3L) }
            main { app.player.next(); control.pause(); app.player.addToQueue(listOf(song(7))) }
            waitFor { app.player.state.value.upNext.map { it.value } == listOf(5L, 6L, 7L, 2L, 3L) }
            main { app.player.playNext(listOf(song(8))) }
            waitFor { app.player.state.value.upNext.map { it.value } == listOf(8L, 5L, 6L, 7L, 2L, 3L) }
            main { app.player.toggleShuffle() }
            waitFor { app.player.state.value.upNext.take(4).map { it.value } == listOf(8L, 5L, 6L, 7L) }
            main { app.player.toggleShuffle() }
            waitFor { app.player.state.value.upNext.take(4).map { it.value } == listOf(8L, 5L, 6L, 7L) }
            main { app.player.removeAt(2); app.player.move(3, 1) }
            waitFor { app.player.state.value.upNext.take(3).map { it.value } == listOf(7L, 8L, 6L) }
            main { app.player.clearUpNext(); app.player.addToQueue(listOf(song(2))) }
            waitFor { app.player.state.value.upNext.map { it.value } == listOf(2L) }
        } finally {
            main { control.stop(); control.clearMediaItems(); control.release(); app.player.disconnect() }
            audio.delete()
        }
    }
}
