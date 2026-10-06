package com.localfy.app.data.music

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Converts any audio the phone can decode (FLAC in practice) to AAC-LC 256 kbit/s in an .m4a,
 * using the platform codecs. Hi-res sources (88.2–192 kHz) are low-pass filtered and reduced to
 * 44.1 or 48 kHz first, since AAC encoders don't take higher rates.
 */
object AacTranscoder {
    const val BITRATE = 256_000
    const val LABEL = "AAC 256 kbps"

    class Unsupported(message: String) : Exception(message)

    /** Writes [out]; returns the converted duration in ms. Throws [Unsupported] when the source can't be converted. */
    fun transcode(source: (MediaExtractor) -> Unit, out: File, cancelled: () -> Boolean = { false }): Long {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            source(extractor)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw Unsupported("No audio in this file.")
            extractor.selectTrack(trackIndex)
            val inFormat = extractor.getTrackFormat(trackIndex)
            val inRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (channels !in 1..2) throw Unsupported("Only mono and stereo files are converted.")
            val outRate = when {
                inRate <= 48_000 -> inRate
                inRate % 44_100 == 0 -> 44_100
                inRate % 48_000 == 0 -> 48_000
                else -> throw Unsupported("Unusual sample rate ($inRate Hz).")
            }
            val factor = inRate / outRate
            val filters = Array(channels) { Decimator(factor) }

            decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!).apply { configure(inFormat, null, null, 0); start() }
            val outFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, outRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var muxTrack = -1
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var extractorDone = false
            var decoderDone = false
            var encoderDone = false
            var endSent = false
            var framesQueued = 0L // per channel, at outRate
            val pending = ShortArrayQueue()
            val info = MediaCodec.BufferInfo()

            fun drainEncoder() {
                while (true) {
                    val o = encoder.dequeueOutputBuffer(info, 0)
                    when {
                        o == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { muxTrack = muxer.addTrack(encoder.outputFormat); muxer.start() }
                        o >= 0 -> {
                            val buf = encoder.getOutputBuffer(o)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && muxTrack >= 0) muxer.writeSampleData(muxTrack, buf, info)
                            encoder.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { encoderDone = true; return }
                        }
                    }
                }
            }

            fun feedEncoder(final: Boolean) {
                if (endSent) return
                while (pending.size >= (if (final) 1 else 1024 * channels) || (final && !encoderDone)) {
                    val i = encoder.dequeueInputBuffer(5_000)
                    if (i < 0) { drainEncoder(); continue }
                    val buf = encoder.getInputBuffer(i)!!.order(ByteOrder.nativeOrder())
                    val count = minOf(pending.size, buf.capacity() / 2 / channels * channels)
                    if (count == 0 && final) {
                        encoder.queueInputBuffer(i, 0, 0, framesQueued * 1_000_000L / outRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        endSent = true
                        return
                    }
                    buf.clear(); repeat(count) { buf.putShort(pending.take()) }
                    encoder.queueInputBuffer(i, 0, count * 2, framesQueued * 1_000_000L / outRate, 0)
                    framesQueued += count / channels
                    drainEncoder()
                }
            }

            while (!encoderDone) {
                if (cancelled()) throw kotlinx.coroutines.CancellationException("Conversion cancelled")
                if (!extractorDone) {
                    val i = decoder.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                        if (n < 0) { decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); extractorDone = true }
                        else { decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                if (!decoderDone) {
                    val o = decoder.dequeueOutputBuffer(info, 5_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = decoder.outputFormat
                        encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        if (f.getInteger(MediaFormat.KEY_SAMPLE_RATE) != inRate || f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != channels) throw Unsupported("The decoder changed the audio format.")
                    } else if (o >= 0) {
                        val buf = decoder.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val bytes = when (encoding) { AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4; AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3; AudioFormat.ENCODING_PCM_8BIT -> 1; else -> 2 }
                        while (buf.remaining() >= bytes * channels) {
                            for (c in 0 until channels) {
                                val sample = when (encoding) {
                                    AudioFormat.ENCODING_PCM_FLOAT -> buf.getFloat()
                                    AudioFormat.ENCODING_PCM_32BIT -> buf.getInt() / 2147483648f
                                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> { val b0 = buf.get().toInt() and 0xff; val b1 = buf.get().toInt() and 0xff; val b2 = buf.get().toInt(); ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f }
                                    AudioFormat.ENCODING_PCM_8BIT -> ((buf.get().toInt() and 0xff) - 128) / 128f
                                    else -> buf.getShort() / 32768f
                                }
                                filters[c].push(sample)
                            }
                            // Every channel produces a sample at the same input positions.
                            if (filters[0].ready) for (c in 0 until channels) pending.add(toShort(filters[c].take()))
                        }
                        decoder.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoderDone = true
                        feedEncoder(final = false)
                    }
                } else {
                    feedEncoder(final = true)
                    // Wait for the encoder to flush its last frames.
                    if (!encoderDone) { Thread.sleep(2); drainEncoder() }
                }
            }
            muxer.stop()
            return framesQueued * 1000 / outRate
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            runCatching { decoder?.stop() }; decoder?.release()
            runCatching { encoder?.stop() }; encoder?.release()
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    private fun toShort(v: Float): Short = (v.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()

    /** Windowed-sinc low-pass + keep every [factor]th sample (factor 1 passes straight through). */
    private class Decimator(private val factor: Int) {
        private val taps: FloatArray = if (factor == 1) floatArrayOf(1f) else {
            val n = 32 * factor + 1
            val cutoff = 0.45f / factor // a little below the new Nyquist, so nothing folds back
            FloatArray(n) { i ->
                val m = i - n / 2
                val sinc = if (m == 0) 2 * cutoff else (sin(2 * PI * cutoff * m) / (PI * m)).toFloat()
                val window = (0.42 - 0.5 * cos(2 * PI * i / (n - 1)) + 0.08 * cos(4 * PI * i / (n - 1))).toFloat() // Blackman
                sinc * window
            }.let { h -> val sum = h.sum(); FloatArray(h.size) { h[it] / sum } }
        }
        private val history = FloatArray(taps.size)
        private var head = 0
        private var phase = 0
        var ready = false; private set

        fun push(x: Float) {
            history[head] = x; head = (head + 1) % history.size
            phase = (phase + 1) % factor
            ready = phase == 0
        }

        fun take(): Float {
            ready = false
            var acc = 0f
            var idx = head
            for (t in taps.indices) { idx = if (idx == 0) history.size - 1 else idx - 1; acc += taps[t] * history[idx] }
            return acc
        }
    }

    /** A growable FIFO of 16-bit samples without boxing. */
    private class ShortArrayQueue {
        private var data = ShortArray(1 shl 16)
        private var start = 0
        private var end = 0
        val size get() = end - start
        fun add(v: Short) {
            if (end == data.size) {
                if (start > 0) { data.copyInto(data, 0, start, end); end -= start; start = 0 }
                if (end == data.size) data = data.copyOf(data.size * 2)
            }
            data[end++] = v
        }
        fun take(): Short = data[start++]
    }
}
