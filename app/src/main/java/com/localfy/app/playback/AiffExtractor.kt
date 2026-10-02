package com.localfy.app.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import kotlin.math.pow

/**
 * AIFF / AIFF-C extractor (Media3 has none). Handles uncompressed big-endian PCM ("NONE"/"twos"),
 * little-endian PCM ("sowt") at 16/24/32-bit, and 32/64-bit float ("fl32"/"fl64").
 */
@OptIn(UnstableApi::class)
class AiffExtractor : Extractor {

    private lateinit var output: ExtractorOutput
    private lateinit var track: TrackOutput
    private var dataStart = -1L
    private var dataEnd = 0L
    private var sampleRate = 0
    private var bytesPerFrame = 0

    override fun sniff(input: ExtractorInput): Boolean {
        val b = ByteArray(12)
        if (!input.peekFully(b, 0, 12, true)) return false
        val form = String(b, 0, 4, Charsets.ISO_8859_1)
        val type = String(b, 8, 4, Charsets.ISO_8859_1)
        return form == "FORM" && (type == "AIFF" || type == "AIFC")
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        track = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (dataStart < 0) {
            parseHeader(input)
            return Extractor.RESULT_CONTINUE
        }
        if (input.position < dataStart) {
            seekPosition.position = dataStart
            return Extractor.RESULT_SEEK
        }
        val remaining = dataEnd - input.position
        if (remaining < bytesPerFrame) return Extractor.RESULT_END_OF_INPUT
        val target = minOf((sampleRate / 10L) * bytesPerFrame, remaining).let { it - it % bytesPerFrame }.toInt()
        val timeUs = (input.position - dataStart) / bytesPerFrame * C.MICROS_PER_SECOND / sampleRate
        var written = 0
        var ended = false
        while (written < target) {
            val n = track.sampleData(input, target - written, true)
            if (n == C.RESULT_END_OF_INPUT) { ended = true; break }
            written += n
        }
        val whole = written - written % bytesPerFrame
        if (whole > 0) track.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, whole, written - whole, null)
        return if (ended) Extractor.RESULT_END_OF_INPUT else Extractor.RESULT_CONTINUE
    }

    private fun parseHeader(input: ExtractorInput) {
        val head = ByteArray(12)
        input.readFully(head, 0, 12)
        val isAifc = String(head, 8, 4, Charsets.ISO_8859_1) == "AIFC"
        var channels = 0
        var bits = 0
        var numFrames = 0L
        var compression = "NONE"
        while (true) {
            val ch = ByteArray(8)
            input.readFully(ch, 0, 8)
            val id = String(ch, 0, 4, Charsets.ISO_8859_1)
            val size = be32(ch, 4)
            when (id) {
                "COMM" -> {
                    val c = ByteArray(size.toInt())
                    input.readFully(c, 0, c.size)
                    channels = ((c[0].toInt() and 0xFF) shl 8) or (c[1].toInt() and 0xFF)
                    numFrames = be32(c, 2)
                    bits = ((c[6].toInt() and 0xFF) shl 8) or (c[7].toInt() and 0xFF)
                    sampleRate = extended80(c, 8).toInt()
                    if (isAifc && c.size >= 22) compression = String(c, 18, 4, Charsets.ISO_8859_1)
                    if (size % 2 == 1L) input.skipFully(1)
                }
                "SSND" -> {
                    val s = ByteArray(8)
                    input.readFully(s, 0, 8)
                    val offset = be32(s, 0)
                    input.skipFully(offset.toInt())
                    dataStart = input.position
                    dataEnd = dataStart + size - 8 - offset
                    break
                }
                else -> input.skipFully((size + (size and 1)).toInt())
            }
        }
        val encoding = when (compression.lowercase()) {
            "none", "twos" -> when (bits) {
                16 -> C.ENCODING_PCM_16BIT_BIG_ENDIAN
                24 -> C.ENCODING_PCM_24BIT_BIG_ENDIAN
                32 -> C.ENCODING_PCM_32BIT_BIG_ENDIAN
                else -> unsupported("$bits-bit AIFF")
            }
            "sowt" -> when (bits) {
                16 -> C.ENCODING_PCM_16BIT
                24 -> C.ENCODING_PCM_24BIT
                32 -> C.ENCODING_PCM_32BIT
                else -> unsupported("$bits-bit AIFF-C sowt")
            }
            "fl32" -> C.ENCODING_PCM_FLOAT_BIG_ENDIAN
            "fl64" -> C.ENCODING_PCM_DOUBLE_BIG_ENDIAN
            else -> unsupported("AIFF-C compression '$compression'")
        }
        if (channels <= 0 || sampleRate <= 0) throw ParserException.createForMalformedContainer("Bad AIFF header", null)
        bytesPerFrame = channels * (if (compression.lowercase() == "fl64") 8 else (bits + 7) / 8)
        val durationUs = numFrames * C.MICROS_PER_SECOND / sampleRate
        track.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setContainerMimeType("audio/x-aiff")
                .setChannelCount(channels)
                .setSampleRate(sampleRate)
                .setPcmEncoding(encoding)
                .setAverageBitrate(sampleRate * bytesPerFrame * 8)
                .setPeakBitrate(sampleRate * bytesPerFrame * 8)
                .setMaxInputSize((sampleRate / 10) * bytesPerFrame)
                .build(),
        )
        output.seekMap(object : SeekMap {
            override fun isSeekable() = true
            override fun getDurationUs() = durationUs
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                val frame = (timeUs * sampleRate / C.MICROS_PER_SECOND).coerceIn(0, maxOf(0, numFrames - 1))
                return SeekMap.SeekPoints(SeekPoint(frame * C.MICROS_PER_SECOND / sampleRate, dataStart + frame * bytesPerFrame))
            }
        })
    }

    private fun unsupported(what: String): Nothing = throw ParserException.createForUnsupportedContainerFeature("Unsupported $what")

    override fun seek(position: Long, timeUs: Long) = Unit
    override fun release() = Unit

    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    /** IEEE 754 80-bit extended float (AIFF's sample-rate field). */
    private fun extended80(b: ByteArray, o: Int): Double {
        val exponent = (((b[o].toInt() and 0x7F) shl 8) or (b[o + 1].toInt() and 0xFF)) - 16383
        var mantissa = 0.0
        for (i in 0 until 8) mantissa = mantissa * 256 + (b[o + 2 + i].toInt() and 0xFF)
        return mantissa * 2.0.pow(exponent - 63)
    }
}

/** Every built-in Media3 extractor plus AIFF, each wrapped by [SanitizingExtractor]. */
@OptIn(UnstableApi::class)
val LocalfyExtractors = ExtractorsFactory {
    arrayOf<Extractor>(AiffExtractor(), *DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true).createExtractors())
        .map { SanitizingExtractor(it) }.toTypedArray()
}

/**
 * Some encoders (e.g. ffmpeg's WAVE_FORMAT_EXTENSIBLE writer) tag mono as "front centre" only,
 * which Android's AudioTrack rejects. For mono/stereo we drop such masks and let the platform
 * use its standard layout; real multichannel layouts are left alone.
 */
@OptIn(UnstableApi::class)
private class SanitizingExtractor(private val inner: Extractor) : Extractor by inner {
    override fun init(output: ExtractorOutput) = inner.init(SanitizingOutput(output))
    override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation
}

@OptIn(UnstableApi::class)
private class SanitizingOutput(private val inner: ExtractorOutput) : ExtractorOutput by inner {
    override fun track(id: Int, type: Int): TrackOutput = SanitizingTrack(inner.track(id, type))
}

@OptIn(UnstableApi::class)
private class SanitizingTrack(private val inner: TrackOutput) : TrackOutput by inner {
    override fun format(format: Format) = inner.format(
        if (format.channelCount in 1..2 && format.channelMask != Format.NO_VALUE) format.buildUpon().setChannelMask(Format.NO_VALUE).build() else format,
    )
}
