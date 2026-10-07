package com.localfy.app.data

import com.localfy.app.data.meta.FileTags
import com.localfy.app.data.meta.MetadataEdit
import com.localfy.app.data.music.AacEncoder
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin

/** Generates small real audio files for tests (WAV via javax.sound, FLAC/M4A via FFmpeg). */
object TestAudio {
    fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

    fun wav(file: File, seconds: Double, rate: Int = 44_100, channels: Int = 2): File {
        val frames = (seconds * rate).toInt()
        val buf = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * 440 * i / rate) * 8000).toInt().toShort()
            repeat(channels) { buf.putShort(v) }
        }
        val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
        file.parentFile?.mkdirs()
        AudioInputStream(ByteArrayInputStream(buf.array()), format, frames.toLong()).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, file)
        }
        return file
    }

    /** FLAC at the same sample rate as the generated WAV (hi-res rates are kept). */
    fun flac(file: File, seconds: Double, rate: Int = 44_100, channels: Int = 2): File {
        val wav = File.createTempFile("src", ".wav")
        try {
            wav(wav, seconds, rate, channels)
            AacEncoder.encode(wav.path, file, AacEncoder.Spec("flac", listOf("flac"), AV_SAMPLE_FMT_S16, 0, { it }))
        } finally { wav.delete() }
        return file
    }

    fun m4a(file: File, seconds: Double, rate: Int = 44_100, channels: Int = 2): File {
        val wav = File.createTempFile("src", ".wav")
        try {
            wav(wav, seconds, rate, channels)
            AacEncoder.transcode(wav, file)
        } finally { wav.delete() }
        return file
    }

    fun tag(file: File, edit: MetadataEdit, cover: ByteArray? = null): File { FileTags.tag(file, edit, cover); return file }

    fun cover(size: Int = 600, color: Color = Color.RED): ByteArray {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); g.color = color; g.fillRect(0, 0, size, size); g.color = Color.BLUE; g.fillRect(0, 0, size / 2, size / 2); g.dispose()
        return Images.jpeg(img)
    }

    fun <T> waitFor(timeoutMs: Long = 20_000, value: () -> T, done: (T) -> Boolean): T {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val v = value()
            if (done(v)) return v
            if (System.currentTimeMillis() > end) throw AssertionError("Timed out waiting; last value: $v")
            Thread.sleep(50)
        }
    }
}
