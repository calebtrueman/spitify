package com.localfy.app

import com.localfy.app.data.uri

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localfy.app.data.music.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
@UnstableApi
class StreamingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext as LocalfyApp
    private fun track() = OnlineTrack("9900111222", "Streaming test", "Spitify", "Stream tests", "9900", 30000, 1, 1, null, true)

    @Test fun librarySaveIsSeparateFromCacheAndSurvivesReload() {
        val streams = context.musicStreams
        val item = track(); streams.remove(listOf(item))
        streams.register(item)
        assertFalse(streams.contains(item))
        streams.save(listOf(item))
        assertTrue(MusicStreams(context).contains(item))
        val download = File(context.filesDir, "stream-test-kept-download").apply { writeText("keep") }
        try {
            ListeningCache.clear(context)
            assertEquals("keep", download.readText())
            assertTrue(streams.contains(item))
            streams.remove(listOf(item))
            assertFalse(MusicStreams(context).contains(item))
            assertNotNull(streams.lookup(MusicStreams.streamId(item.id)))
        } finally { download.delete(); streams.remove(listOf(item)) }
    }

    @Test fun opusSourceKeepsTheOriginalRecordingInsteadOfFallingBack() {
        val bytes = instrumentation.context.assets.open("playback/quiet-opus.ogg").use { it.readBytes() }
        val item = track().copy(id = (System.nanoTime() % 1_000_000_000).toString())
        val song = context.musicStreams.register(item)
        val key = "https://stream-test.invalid/${System.nanoTime()}.flac"
        val source = MusicStreamDataSource(context, sourceURL = { key },
            alternate = { throw AssertionError("Valid Opus audio must not select another recording") },
            upstreamFactory = DataSource.Factory { ByteArrayDataSource(bytes) })
        try {
            assertEquals(bytes.size.toLong(), source.open(DataSpec(song.uri)))
            val actual = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) { val count = source.read(buffer, 0, buffer.size); if (count < 0) break; actual.write(buffer, 0, count) }
            assertArrayEquals(bytes, actual.toByteArray())
        } finally { source.close(); ListeningCache.get(context).removeResource(key) }
    }

    @Test fun playbackStartsBeforeTransferFinishesAndReplaysWithoutNetwork() = runBlocking {
        val content = wave()
        val read = AtomicInteger()
        val fallbacks = AtomicInteger()
        val key = "https://stream-test.invalid/${System.nanoTime()}.wav"
        val item = track().copy(id = (System.nanoTime() % 1_000_000_000).toString())
        val song = context.musicStreams.register(item)
        val factory = DataSource.Factory {
            MusicStreamDataSource(context, sourceURL = { it.audioURL ?: "https://stream-test.invalid/missing" },
                alternate = { val attempt = fallbacks.incrementAndGet(); it.copy(audioURL = if (attempt == 1) key + "?bad" else key, audioExtension = "flac") },
                upstreamFactory = DataSource.Factory { SlowSource(content, read) })
        }
        var player: ExoPlayer? = null
        fun start(source: DataSource.Factory) {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(source)).build().apply {
                    volume = 0f; setMediaItem(MediaItem.fromUri(song.uri)); prepare(); play()
                }
            }
        }
        try {
            start(factory)
            var started = false
            withTimeout(15_000) {
                while (!started) {
                    instrumentation.runOnMainSync {
                        player!!.playerError?.let { throw it }
                        started = player!!.currentPosition > 150
                    }
                    if (!started) delay(50)
                }
            }
            assertTrue("Playback must start before all bytes arrive", read.get() < content.size)
            assertEquals(2, fallbacks.get())
            withTimeout(20_000) { while (read.get() < content.size) delay(50) }
            instrumentation.runOnMainSync { player!!.release(); player = null }
            assertTrue(ListeningCache.get(context).isCached(key, 0, content.size.toLong()))
            val offline = DataSource.Factory {
                MusicStreamDataSource(context, sourceURL = { key }, alternate = { null },
                    upstreamFactory = DataSource.Factory { object : DataSource {
                        override fun open(dataSpec: DataSpec): Long = throw IOException("Network disabled")
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("Network disabled")
                        override fun getUri(): Uri? = null
                        override fun close() {}
                        override fun addTransferListener(listener: TransferListener) {}
                    } })
            }
            start(offline); started = false
            withTimeout(10_000) {
                while (!started) {
                    instrumentation.runOnMainSync { player!!.playerError?.let { throw it }; started = player!!.currentPosition > 150 }
                    if (!started) delay(50)
                }
            }
            assertTrue(started)
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            ListeningCache.get(context).removeResource(key)
        }
    }
    private class SlowSource(val bytes: ByteArray, val count: AtomicInteger) : DataSource {
        private var offset = 0
        private var end = 0
        private var uri: Uri? = null
        private var content = bytes
        override fun open(dataSpec: DataSpec): Long {
            if (dataSpec.uri.lastPathSegment == "missing") throw IOException("First source failed")
            content = if (dataSpec.uri.query == "bad") "<html>Service unavailable</html>".toByteArray() else bytes
            uri = dataSpec.uri; offset = dataSpec.position.toInt()
            end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) content.size else minOf(content.size, offset + dataSpec.length.toInt())
            return (end - offset).toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (this.offset >= end) return C.RESULT_END_OF_INPUT
            Thread.sleep(20)
            val n = minOf(length, 4096, end - this.offset)
            content.copyInto(buffer, offset, this.offset, this.offset + n); this.offset += n; if (content === bytes) count.addAndGet(n); return n
        }
        override fun getUri() = uri
        override fun addTransferListener(listener: TransferListener) {}
        override fun close() {}
    }
    private fun wave(): ByteArray {
        val frames = 22_050 * 30
        return ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + frames * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(22_050); putInt(44_100); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(frames * 2)
            repeat(frames) { putShort((sin(it * 2.0 * Math.PI * 440 / 22_050) * 1000).toInt().toShort()) }
        }.array()
    }
}
