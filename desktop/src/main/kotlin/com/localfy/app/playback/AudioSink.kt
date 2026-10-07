package com.localfy.app.playback

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Where the engine's 48 kHz stereo 16-bit little-endian PCM goes. [write] blocks while the device
 * buffer is full, which is what paces the audio thread.
 */
interface AudioSink {
    fun open()
    fun write(data: ByteArray, length: Int)
    /** Frames written but not yet heard. */
    fun queuedFrames(): Int
    fun start()
    fun stop()
    /** Blocks until everything written has played. */
    fun drain()
    fun flush()
    fun close()
}

/** The real output: a Java Sound [SourceDataLine] with a short (~120 ms) buffer. */
class JavaSoundSink(private val bufferMs: Int = 120) : AudioSink {
    private var line: SourceDataLine? = null
    private val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, CHANNELS, true, false)

    override fun open() {
        if (line != null) return
        val l = AudioSystem.getSourceDataLine(format)
        val bytes = (SAMPLE_RATE * bufferMs / 1000) * CHANNELS * 2
        l.open(format, bytes)
        line = l
    }

    override fun write(data: ByteArray, length: Int) {
        val l = line ?: return
        var off = 0
        while (off < length) {
            val n = l.write(data, off, length - off)
            if (n <= 0) break
            off += n
        }
    }

    override fun queuedFrames(): Int = line?.let { (it.bufferSize - it.available()).coerceAtLeast(0) / (CHANNELS * 2) } ?: 0
    override fun start() { line?.start() }
    override fun stop() { line?.stop() }
    override fun drain() { line?.let { if (it.isRunning) it.drain() } }
    override fun flush() { line?.flush() }
    override fun close() { line?.close(); line = null }
}
