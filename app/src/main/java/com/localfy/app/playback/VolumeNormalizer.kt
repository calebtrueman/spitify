package com.localfy.app.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.sqrt

/** Measures decoded samples and turns loud recordings down. Quiet recordings are never boosted. */
@UnstableApi
internal class VolumeNormalizer(private val enabled: () -> Boolean) : BaseAudioProcessor() {
    private val level = NormalizationLevel()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        val start = inputBuffer.position()
        val samples = inputBuffer.remaining() / 2
        if (samples == 0) return
        var energy = 0.0
        var peak = 0.0
        repeat(samples) { val sample = inputBuffer.short / 32768.0; energy += sample * sample; peak = maxOf(peak, kotlin.math.abs(sample)) }
        val gain = if (enabled()) level.measure(energy, samples, peak) else 1.0
        inputBuffer.position(start)
        val out = replaceOutputBuffer(samples * 2)
        // Apply the same gain to every channel, preserving the stereo image and byte order.
        repeat(samples) { out.putShort((inputBuffer.short * gain).toInt().coerceIn(-32768, 32767).toShort()) }
        out.flip()
    }
    override fun onFlush() { level.reset() }
}

/** A running RMS estimate; attenuation only, with no gain recovery that could cause pumping. */
internal class NormalizationLevel {
    private var energy = 0.0
    private var count = 0L
    private var gain = 1.0
    fun measure(blockEnergy: Double, samples: Int, peak: Double = 0.0): Double {
        if (!blockEnergy.isFinite() || blockEnergy < 0 || samples <= 0) return 0.0
        energy += blockEnergy; count += samples
        val rms = sqrt(energy / count)
        if (rms > 0) gain = min(gain, TARGET_RMS / rms).coerceIn(0.0, 1.0)
        // A quiet intro must not hide a sudden noisy block inside a long running average.
        val blockRms = sqrt(blockEnergy / samples)
        if (blockRms > 0) gain = min(gain, MAX_SHORT_RMS / blockRms)
        if (peak > 0) gain = min(gain, MAX_PEAK / peak)
        return gain
    }
    fun reset() { energy = 0.0; count = 0; gain = 1.0 }
    companion object {
        private const val TARGET_RMS = 0.12589254117941673 // -18 dBFS
        private const val MAX_SHORT_RMS = 0.251188643150958 // -12 dBFS
        private const val MAX_PEAK = 0.8912509381337456 // -1 dBFS
    }
}
