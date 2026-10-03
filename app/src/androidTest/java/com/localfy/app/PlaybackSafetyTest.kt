package com.localfy.app

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localfy.app.playback.PlaybackVolume
import com.localfy.app.playback.buildPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.sqrt

@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackSafetyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as LocalfyApp
    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return result!!.getOrThrow()
    }
    private fun waitFor(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 12_000
        while (!main(check) && System.currentTimeMillis() < deadline) Thread.sleep(30)
        assertTrue(main(check))
    }

    @Test fun sampleFormatsDecodeToQuietStereoWithoutNoise() {
        for (name in listOf("quiet-24.flac", "quiet-24.aiff", "quiet-float.wav", "quiet-alac.m4a", "quiet-opus.ogg", "quiet.mp3", "quiet-aac.m4a", "quiet-vorbis.ogg")) {
            // Deliberately give every container the wrong extension: the bytes must win.
            val file = File(app.cacheDir, "$name.flac")
            instrumentation.context.assets.open("playback/$name").use { input -> file.outputStream().use(input::copyTo) }
            val probe = SampleProbe()
            val player = main { buildPlayer(app, arrayOf(probe)).build().apply {
                volume = 0f // These tests never play audible audio or touch the phone's volume.
                setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file))); prepare(); play()
            } }
            try {
                waitFor { player.playerError?.let { throw it }; player.playbackState == Player.STATE_ENDED }
                assertTrue("$name decoded too few samples", probe.samples >= 90_000)
                assertTrue("$name has distorted sample levels: ${probe.rms}", probe.rms in .015..0.027)
                assertTrue("$name has an unexpected full-scale peak: ${probe.peak}", probe.peak < .08)
                assertEquals(0f, main { player.volume }, 0f)
            } finally { main { player.release() }; file.delete() }
        }
    }

    @Test fun downloadedOpusUsesItsRealFormatAndCanBeReadWithoutRewritingAudio() {
        val bytes = instrumentation.context.assets.open("playback/quiet-opus.ogg").use { it.readBytes() }
        val format = checkNotNull(com.localfy.app.data.music.AudioContainer.detect(bytes.take(128).toByteArray()))
        assertEquals("opus", format.extension)
        val file = File(app.cacheDir, "download-check.${format.extension}")
        try {
            file.writeBytes(bytes)
            assertArrayEquals(bytes, file.readBytes())
            android.media.MediaMetadataRetriever().use { reader ->
                reader.setDataSource(file.path)
                assertEquals("yes", reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                assertTrue(checkNotNull(reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong() in 950..1100)
            }
        } finally { file.delete() }
    }

    @Test fun normalizationMeasuresEachSongWithoutCarryingThePreviousAttenuation() {
        val names = listOf("loud.wav", "quiet-16.wav")
        val files = names.map { name -> File(app.cacheDir, name).also { file -> instrumentation.context.assets.open("playback/$name").use { input -> file.outputStream().use(input::copyTo) } } }
        val probe = SampleProbe()
        val player = main { buildPlayer(app, arrayOf(probe)).build().apply {
            volume = 0f; setMediaItems(files.map { MediaItem.fromUri(android.net.Uri.fromFile(it)) }); prepare(); play()
        } }
        try {
            waitFor { player.playerError?.let { throw it }; player.playbackState == Player.STATE_ENDED }
            assertTrue("The quiet song kept the previous song's attenuation: ${probe.tailRms}", probe.tailRms in .015..0.027)
        } finally { main { player.release() }; files.forEach { it.delete() } }
    }

    @Test fun sleepAndCrossfadeNeverReplaceMuteOrChosenVolumeWithFullVolume() {
        val player = main { buildPlayer(app).build().apply { volume = .2f } }
        val tail = main { buildPlayer(app).build().apply { volume = 0f } }
        val volume = main { PlaybackVolume(player) }
        try {
            main { volume.setFade(.5f, .5f, tail); volume.setSleep(.1f) }
            assertEquals(.01f, main { player.volume }, .00001f)
            assertEquals(.01f, main { tail.volume }, .00001f)
            main { volume.setSleep(1f); volume.setFade(1f, 0f, null) }
            assertEquals(.2f, main { player.volume }, .00001f)
            main { player.volume = 0f }
            waitFor { player.volume == 0f }
            // Flush the player callback before starting a fade on the next track.
            instrumentation.waitForIdleSync()
            main { volume.setFade(.5f, .5f, tail); volume.setSleep(.3f); volume.setFade(1f, 0f, null); volume.setSleep(1f) }
            assertEquals(0f, main { player.volume }, 0f)
            assertEquals(0f, main { tail.volume }, 0f)
        } finally { main { volume.release(); player.release(); tail.release() } }
    }

    private class SampleProbe : BaseAudioProcessor() {
        @Volatile var samples = 0
        @Volatile var energy = 0.0
        @Volatile var peak = 0.0
        var tailEnergy = 0.0
        var tailSamples = 0
        val tailRms get() = sqrt(tailEnergy / tailSamples.coerceAtLeast(1))
        val rms get() = sqrt(energy / samples.coerceAtLeast(1))
        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            check(inputAudioFormat.encoding == C.ENCODING_PCM_16BIT)
            check(inputAudioFormat.channelCount == 2)
            return inputAudioFormat
        }
        override fun queueInput(inputBuffer: ByteBuffer) {
            val out = replaceOutputBuffer(inputBuffer.remaining())
            while (inputBuffer.remaining() >= 2) {
                val value = inputBuffer.short
                val sample = value / 32768.0
                if (samples >= 96_000) { tailEnergy += sample * sample; tailSamples++ }
                energy += sample * sample; samples++; peak = maxOf(peak, kotlin.math.abs(sample))
                out.putShort(value)
            }
            out.flip()
        }
    }
}
