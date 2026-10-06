package com.localfy.app

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localfy.app.data.music.AacTranscoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AacTranscoderTest {
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext

    private fun convert(asset: String): Pair<MediaFormat, Long> {
        val source = File(appContext.cacheDir, asset.substringAfterLast('/'))
        testContext.assets.open(asset).use { input -> source.outputStream().use { input.copyTo(it) } }
        val out = File(appContext.cacheDir, source.nameWithoutExtension + ".m4a")
        val duration = AacTranscoder.transcode({ it.setDataSource(source.path) }, out)
        val extractor = MediaExtractor().apply { setDataSource(out.path) }
        try {
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
            assertTrue("The file starts with an MP4 'ftyp' box", out.readBytes().copyOfRange(4, 8).decodeToString() == "ftyp")
            return format to duration
        } finally { extractor.release(); source.delete(); out.delete() }
    }

    @Test fun convertsA24BitFlacToAacKeepingItsRateAndLength() {
        val (format, duration) = convert("playback/quiet-24.flac")
        assertEquals(48_000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        assertEquals(2, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        assertEquals(1_000.0, duration.toDouble(), 60.0)
    }

    @Test fun bringsHiResFlacDownTo48kHz() {
        val (format, duration) = convert("playback/hires-96k-24.flac")
        assertEquals(48_000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        assertEquals(2_000.0, duration.toDouble(), 60.0)
    }
}
