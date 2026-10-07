package com.localfy.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Real FFmpeg decoding through the engine, into a fake output line. */
class FfmpegEngineTest {
    init { TestEnv.ensure() }

    private val assets = listOf("app/src/androidTest/assets/playback", "../app/src/androidTest/assets/playback")
        .map(::File).first { it.isDirectory }

    /** Records everything written; optionally consumes in real time like a sound card. */
    private class FakeSink(realtime: Boolean = false, private val speedUp: Double = if (realtime) 1.0 else 0.0) : AudioSink {
        private var owedNanos = 0L
        val bytes = ByteArrayOutputStream()
        @Volatile var running = false
        override fun open() {}
        override fun write(data: ByteArray, length: Int) {
            synchronized(bytes) { bytes.write(data, 0, length) }
            if (speedUp > 0) {
                owedNanos += (length / 4 * 1_000_000_000L / SAMPLE_RATE / speedUp).toLong()
                if (owedNanos > 4_000_000) { Thread.sleep(owedNanos / 1_000_000); owedNanos %= 1_000_000 }
            }
        }
        override fun queuedFrames() = 0
        override fun start() { running = true }
        override fun stop() { running = false }
        override fun drain() {}
        override fun flush() {}
        override fun close() {}
        val frames get() = synchronized(bytes) { bytes.size() } / 4
        fun samples(): ShortArray = synchronized(bytes) {
            val b = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            ShortArray(b.remaining()).also { b.get(it) }
        }
    }

    private class Events : AudioEngineListener {
        val states = CopyOnWriteArrayList<Int>()
        val transitions = CopyOnWriteArrayList<Long>()
        val errors = CopyOnWriteArrayList<EngineError>()
        val durations = CopyOnWriteArrayList<Long>()
        val ended = CountDownLatch(1)
        val failed = CountDownLatch(1)
        var onTransition: (Long) -> Unit = {}
        override fun onStateChanged(playbackState: Int, playWhenReady: Boolean) {
            states += playbackState
            if (playbackState == PlaybackState.ENDED) ended.countDown()
        }
        override fun onAutoTransition(uid: Long) { transitions += uid; onTransition(uid) }
        override fun onDuration(uid: Long, durationMs: Long) { durations += durationMs }
        override fun onError(uid: Long, error: EngineError) { errors += error; failed.countDown() }
    }

    private fun track(uid: Long, name: String) = File(assets, name).let { f ->
        EngineTrack(uid, uid, name) { f.toURI().toString() }
    }

    @Test fun decodesAFlacToTheEnd() {
        val sink = FakeSink()
        val engine = FfmpegAudioEngine(sink)
        val events = Events()
        engine.listener = events
        engine.setNormalize(false)
        engine.load(track(1, "quiet-24.flac"), 0, play = true)
        assertTrue("ended", events.ended.await(20, TimeUnit.SECONDS))
        engine.release()
        assertTrue(events.errors.isEmpty())
        val durationMs = events.durations.single()
        assertTrue(durationMs > 0)
        // Every sample of the file came out (resampled to 48 kHz), give or take the resampler edge.
        assertEquals(durationMs * 48.0, sink.frames.toDouble(), 48.0 * 30)
        assertTrue("audible", sink.samples().any { abs(it.toInt()) > 30 })
        assertEquals(PlaybackState.ENDED, events.states.last())
    }

    @Test fun playsSeveralFormatsGaplessly() {
        val sink = FakeSink()
        val engine = FfmpegAudioEngine(sink)
        val events = Events()
        val names = listOf("quiet-24.flac", "quiet-alac.m4a", "quiet-aac.m4a", "quiet.mp3", "quiet-opus.ogg", "hires-96k-24.flac", "quiet-24.aiff")
        events.onTransition = { uid -> engine.setNext(names.getOrNull(uid.toInt() + 1)?.let { track(uid + 1, it) }, 0) }
        engine.listener = events
        engine.load(track(0, names[0]), 0, play = true)
        engine.setNext(track(1, names[1]), 0)
        assertTrue("ended", events.ended.await(60, TimeUnit.SECONDS))
        engine.release()
        assertTrue(events.errors.toString(), events.errors.isEmpty())
        assertEquals((1L until names.size).toList(), events.transitions.toList())
        assertEquals(names.size, events.durations.size)
        val expected = events.durations.sum() * 48.0
        // Gapless: the output is the sum of the tracks (codec priming/padding aside, ~50 ms each).
        assertEquals(expected, sink.frames.toDouble(), 48.0 * 60 * names.size)
    }

    @Test fun seeksAndReportsPositionWhilePlayingInRealTime() {
        val sink = FakeSink(realtime = true)
        val engine = FfmpegAudioEngine(sink)
        val events = Events()
        engine.listener = events
        val wav = sineWav(seconds = 4, rate = 44_100)
        engine.load(EngineTrack(1, 1, "sine") { wav.toURI().toString() }, 0, play = true)
        // Startup time varies a lot on CI runners; measure real-time progress once audio is flowing.
        val deadline = System.currentTimeMillis() + 5_000
        while (engine.positionMs <= 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(engine.isPlaying)
        val p0 = engine.positionMs
        Thread.sleep(400)
        val p1 = engine.positionMs - p0
        assertTrue("advanced $p1 ms in 400 ms", p1 in 200..700)
        engine.seekTo(1_200)
        Thread.sleep(200)
        val p2 = engine.positionMs
        assertTrue("after seek $p2", p2 in 1_200..1_700)
        assertEquals(4_000L, engine.durationMs)
        engine.pause()
        Thread.sleep(150)
        assertTrue(!engine.isPlaying)
        val paused = engine.positionMs
        Thread.sleep(200)
        assertEquals(paused, engine.positionMs)
        engine.setSpeed(2f)
        engine.play()
        assertTrue("ended", events.ended.await(10, TimeUnit.SECONDS))
        engine.release()
        assertTrue(events.errors.isEmpty())
    }

    /** A 440 Hz 16-bit stereo WAV, so the test can seek around in something longer than the bundled clips. */
    private fun sineWav(seconds: Int, rate: Int): File {
        val frames = seconds * rate
        val data = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
        data.put("data".toByteArray()).putInt(frames * 4)
        for (i in 0 until frames) {
            val v = (Math.sin(2 * Math.PI * 440 * i / rate) * 8_000).toInt().toShort()
            data.putShort(v).putShort(v)
        }
        return File.createTempFile("sine", ".wav").apply { writeBytes(data.array()); deleteOnExit() }
    }

    @Test fun crossfadesLongTracks() {
        val sink = FakeSink(speedUp = 8.0)
        val engine = FfmpegAudioEngine(sink)
        val events = Events()
        engine.listener = events
        val a = sineWav(seconds = 6, rate = 48_000)
        val b = sineWav(seconds = 6, rate = 48_000)
        engine.load(EngineTrack(1, 1, "a") { a.toURI().toString() }, 0, play = true)
        engine.setNext(EngineTrack(2, 2, "b") { b.toURI().toString() }, 2_000)
        assertTrue(events.ended.await(20, TimeUnit.SECONDS))
        engine.release()
        assertEquals(listOf(2L), events.transitions.toList())
        // The 2 s overlap shortens the total: 6 + 6 - 2 s.
        assertEquals(10.0 * 48_000, sink.frames.toDouble(), 48.0 * 60)
        // Equal-power mix of two in-phase sines peaks above either alone, but the limiter keeps it legal.
        assertTrue(sink.samples().all { it.toInt() in -32767..32767 })
    }

    @Test fun crossfadesBetweenTracks() {
        // Two 1-2 s files are too short for a 12 s fade; the engine falls back to gapless.
        val sink = FakeSink()
        val engine = FfmpegAudioEngine(sink)
        val events = Events()
        engine.listener = events
        engine.load(track(1, "quiet-16.wav"), 0, play = true)
        engine.setNext(track(2, "loud.wav"), 12_000)
        assertTrue(events.ended.await(20, TimeUnit.SECONDS))
        engine.release()
        assertEquals(listOf(2L), events.transitions.toList())
    }

    @Test fun missingFileReportsAnError() {
        val engine = FfmpegAudioEngine(FakeSink())
        val events = Events()
        engine.listener = events
        engine.load(EngineTrack(5, 5, "gone") { "file:///definitely/not/here.flac" }, 0, play = true)
        assertTrue(events.failed.await(10, TimeUnit.SECONDS))
        engine.release()
        assertEquals(EngineError.Kind.OTHER, events.errors.single().kind)
        assertNull(events.durations.firstOrNull())
    }

    @Test fun garbageIsUnsupported() {
        val junk = File.createTempFile("junk", ".flac").apply { writeBytes(ByteArray(4096) { (it * 31).toByte() }); deleteOnExit() }
        val engine = FfmpegAudioEngine(FakeSink())
        val events = Events()
        engine.listener = events
        engine.load(EngineTrack(6, 6, "junk") { junk.toURI().toString() }, 0, play = true)
        assertTrue(events.failed.await(10, TimeUnit.SECONDS))
        engine.release()
        assertEquals(EngineError.Kind.UNSUPPORTED, events.errors.single().kind)
    }

    @Test fun streamsOverHttps() {
        val url = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
        assumeTrue("network", runCatching {
            (URI(url).toURL().openConnection() as HttpURLConnection).run { requestMethod = "HEAD"; connectTimeout = 4_000; readTimeout = 4_000; responseCode in 200..399 }
        }.getOrDefault(false))
        val sink = FakeSink(realtime = true)
        val engine = FfmpegAudioEngine(sink)
        val events = Events()
        engine.listener = events
        engine.load(EngineTrack(7, 7, "remote", isRemote = true) { url }, 30_000, play = true)
        val deadline = System.currentTimeMillis() + 20_000
        while (sink.frames < 48_000 && System.currentTimeMillis() < deadline && events.errors.isEmpty()) Thread.sleep(100)
        val pos = engine.positionMs
        engine.release()
        assertTrue(events.errors.toString(), events.errors.isEmpty())
        assertTrue("streamed ${sink.frames} frames", sink.frames >= 48_000)
        assertTrue("position $pos after seek-on-open", pos >= 30_500)
        assertTrue(events.durations.single() > 300_000)
        assertTrue(sink.samples().any { abs(it.toInt()) > 1_000 })
    }
}
