package com.localfy.app.playback

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ln
import kotlin.math.pow

/** Standard graphic-EQ centre frequencies (Hz) used for presets and the 10-band engine. */
val EqFrequencies = listOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)

data class EqPreset(val name: String, val gains: List<Float>)

/** Curves in dB at [EqFrequencies]; interpolated onto whatever bands the device offers. */
val EqPresets = listOf(
    EqPreset("Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    EqPreset("Bass boost", listOf(6f, 5f, 4f, 2f, 0.5f, 0f, 0f, 0f, 0f, 0f)),
    EqPreset("Bass reducer", listOf(-6f, -5f, -4f, -2f, -0.5f, 0f, 0f, 0f, 0f, 0f)),
    EqPreset("Treble boost", listOf(0f, 0f, 0f, 0f, 0f, 1f, 2.5f, 4f, 5f, 6f)),
    EqPreset("Vocal", listOf(-2f, -2f, -1f, 1f, 3f, 4f, 3.5f, 2f, 0f, -1f)),
    EqPreset("Rock", listOf(4.5f, 3.5f, 2f, 0f, -1f, -0.5f, 1.5f, 3f, 4f, 4.5f)),
    EqPreset("Pop", listOf(-1f, 0.5f, 2f, 3.5f, 4f, 3f, 1f, 0f, -0.5f, -1f)),
    EqPreset("Jazz", listOf(3f, 2f, 1f, 2f, -1.5f, -1.5f, 0f, 1.5f, 3f, 3.5f)),
    EqPreset("Classical", listOf(4f, 3f, 2.5f, 1.5f, -1f, -1f, 0f, 2f, 3f, 3.5f)),
    EqPreset("Electronic", listOf(5f, 4.5f, 1.5f, 0f, -2f, 1.5f, 0.5f, 1.5f, 4.5f, 5f)),
    EqPreset("Hip-hop", listOf(5f, 4.5f, 2f, 3f, -1f, -1f, 1.5f, -0.5f, 2f, 3f)),
    EqPreset("Acoustic", listOf(4f, 4f, 3f, 1f, 2f, 2f, 3f, 3.5f, 3f, 2f)),
    EqPreset("Late night", listOf(3f, 2f, 1f, 0f, 0f, 0f, 0f, -1f, -2f, -3f)),
    EqPreset("Small speakers", listOf(-6f, -4f, 2f, 3f, 2f, 1f, 1f, 2f, 1f, 0f)),
    EqPreset("Podcast / speech", listOf(-6f, -4f, -1f, 1f, 3f, 4f, 4f, 2f, 0f, -2f)),
)

/** What the device's audio effect engine supports (published by the service). */
data class EqCapabilities(
    val engine: String,
    val bandFrequencies: List<Float>,
    val minDb: Float,
    val maxDb: Float,
    val bassBoost: Boolean,
    val virtualizer: Boolean,
    val loudness: Boolean,
)

data class EqState(
    val enabled: Boolean = false,
    val preset: String = "Flat",
    /** Gain in dB for each of [EqFrequencies]. */
    val gains: List<Float> = List(EqFrequencies.size) { 0f },
    val bass: Float = 0f,
    val surround: Float = 0f,
    val loudnessDb: Float = 0f,
    val limiter: Boolean = true,
)

/**
 * Settings store shared by the UI and the service (same process). The UI edits [state];
 * the service's [AudioEffectsEngine] listens to the prefs and applies changes live.
 */
object EqStore {
    const val FILE = "effects"
    private val _capabilities = MutableStateFlow<EqCapabilities?>(null)
    val capabilities: StateFlow<EqCapabilities?> = _capabilities.asStateFlow()
    private val _state = MutableStateFlow(EqState())
    val state: StateFlow<EqState> = _state.asStateFlow()
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        _state.value = EqState(
            enabled = p.getBoolean("enabled", false),
            preset = p.getString("preset", "Flat") ?: "Flat",
            gains = p.getString("gains", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
                ?.takeIf { it.size == EqFrequencies.size } ?: List(EqFrequencies.size) { 0f },
            bass = p.getFloat("bass", 0f),
            surround = p.getFloat("surround", 0f),
            loudnessDb = p.getFloat("loudness", 0f),
            limiter = p.getBoolean("limiter", true),
        )
    }

    fun update(transform: (EqState) -> EqState) {
        val s = transform(_state.value)
        _state.value = s
        prefs?.edit {
            putBoolean("enabled", s.enabled)
            putString("preset", s.preset)
            putString("gains", s.gains.joinToString(","))
            putFloat("bass", s.bass)
            putFloat("surround", s.surround)
            putFloat("loudness", s.loudnessDb)
            putBoolean("limiter", s.limiter)
        }
    }

    fun applyPreset(p: EqPreset) = update { it.copy(preset = p.name, gains = p.gains, enabled = true) }

    fun setGain(index: Int, db: Float) = update {
        it.copy(preset = "Custom", gains = it.gains.toMutableList().also { g -> g[index] = db }, enabled = true)
    }

    internal fun publish(caps: EqCapabilities?) { _capabilities.value = caps }
}

/** Interpolates a curve defined at [EqFrequencies] (log-frequency) to an arbitrary frequency. */
fun curveAt(gains: List<Float>, hz: Float): Float {
    val x = ln(hz.coerceIn(EqFrequencies.first(), EqFrequencies.last()))
    for (i in 0 until EqFrequencies.lastIndex) {
        val a = ln(EqFrequencies[i]); val b = ln(EqFrequencies[i + 1])
        if (x <= b) return gains[i] + (gains[i + 1] - gains[i]) * ((x - a) / (b - a))
    }
    return gains.last()
}

/**
 * Lives in the playback service and owns the effects on ExoPlayer's audio session.
 * Prefers DynamicsProcessing (true 10-band EQ + limiter, Android 9+); falls back to the classic
 * Equalizer's 5-ish bands. Bass boost, virtualizer and loudness are added when available.
 */
class AudioEffectsEngine(private val context: Context) {
    private var session = AudioEffect.ERROR
    private var dynamics: DynamicsProcessing? = null
    private var equalizer: Equalizer? = null
    private var bass: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudness: LoudnessEnhancer? = null
    private var job: kotlinx.coroutines.Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate)

    init {
        EqStore.init(context)
        job = scope.launch { EqStore.state.collect { apply(it) } }
    }

    fun attach(sessionId: Int) {
        if (sessionId == session || sessionId <= 0) return
        releaseEffects()
        session = sessionId
        dynamics = runCatching {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, EqFrequencies.size, false, 0, false, 0, true,
            ).build()
            DynamicsProcessing(PRIORITY, sessionId, config).also { dp ->
                // Band i covers up to the midpoint (in log space) between neighbouring centres.
                for (ch in 0 until 2) {
                    val eq = dp.getPreEqByChannelIndex(ch)
                    EqFrequencies.forEachIndexed { i, f ->
                        val cutoff = if (i == EqFrequencies.lastIndex) 20_000f else kotlin.math.sqrt(f * EqFrequencies[i + 1])
                        eq.getBand(i).cutoffFrequency = cutoff
                    }
                    dp.setPreEqByChannelIndex(ch, eq)
                }
            }
        }.onFailure { Log.w(TAG, "DynamicsProcessing unavailable: ${it.message}") }.getOrNull()
        if (dynamics == null) equalizer = runCatching { Equalizer(PRIORITY, sessionId) }.getOrNull()
        bass = runCatching { BassBoost(PRIORITY, sessionId).takeIf { it.strengthSupported } }.getOrNull()
        virtualizer = runCatching { Virtualizer(PRIORITY, sessionId).takeIf { it.strengthSupported } }.getOrNull()
        loudness = if (dynamics == null) runCatching { LoudnessEnhancer(sessionId) }.getOrNull() else null

        val eq = equalizer
        EqStore.publish(
            when {
                dynamics != null -> EqCapabilities("10-band precision EQ", EqFrequencies, -12f, 12f, bass != null, virtualizer != null, true)
                eq != null -> {
                    val range = eq.bandLevelRange
                    EqCapabilities(
                        "${eq.numberOfBands}-band EQ",
                        (0 until eq.numberOfBands).map { eq.getCenterFreq(it.toShort()) / 1000f },
                        range[0] / 100f, range[1] / 100f, bass != null, virtualizer != null, loudness != null,
                    )
                }
                else -> null
            },
        )
        apply(EqStore.state.value)
    }

    private fun apply(s: EqState) {
        runCatching {
            dynamics?.let { dp ->
                dp.enabled = s.enabled
                for (ch in 0 until 2) {
                    val eq = dp.getPreEqByChannelIndex(ch)
                    eq.isEnabled = true
                    s.gains.forEachIndexed { i, g -> eq.getBand(i).gain = g }
                    dp.setPreEqByChannelIndex(ch, eq)
                    // Pre-gain for "loudness", with a limiter so boosts never clip.
                    dp.setInputGainbyChannel(ch, if (s.enabled) s.loudnessDb - maxOf(0f, s.gains.maxOrNull() ?: 0f) * 0.5f else 0f)
                    val lim = dp.getLimiterByChannelIndex(ch)
                    lim.isEnabled = s.limiter
                    lim.threshold = -1f
                    lim.ratio = 10f
                    lim.attackTime = 1f
                    lim.releaseTime = 60f
                    dp.setLimiterByChannelIndex(ch, lim)
                }
            }
            equalizer?.let { eq ->
                eq.enabled = s.enabled
                val range = eq.bandLevelRange
                for (b in 0 until eq.numberOfBands) {
                    val hz = eq.getCenterFreq(b.toShort()) / 1000f
                    val mb = (curveAt(s.gains, hz) * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                    eq.setBandLevel(b.toShort(), mb.toShort())
                }
            }
            bass?.let { it.enabled = s.enabled && s.bass > 0f; it.setStrength((s.bass * 1000).toInt().toShort()) }
            virtualizer?.let { it.enabled = s.enabled && s.surround > 0f; it.setStrength((s.surround * 1000).toInt().toShort()) }
            loudness?.let { it.enabled = s.enabled && s.loudnessDb > 0f; it.setTargetGain((s.loudnessDb * 100).toInt()) }
        }.onFailure { Log.w(TAG, "Applying effects failed", it) }
    }

    private fun releaseEffects() {
        listOf<AudioEffect?>(dynamics, equalizer, bass, virtualizer, loudness).forEach { runCatching { it?.release() } }
        dynamics = null; equalizer = null; bass = null; virtualizer = null; loudness = null
    }

    fun release() {
        job?.cancel()
        releaseEffects()
        EqStore.publish(null)
    }

    companion object {
        private const val TAG = "LocalfyEffects"
        private const val PRIORITY = 1000
        @Suppress("unused") private fun dbToLinear(db: Float) = 10f.pow(db / 20f)
    }
}
