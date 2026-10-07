package com.localfy.app.playback

import com.localfy.app.desktop.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ln

/** Standard graphic-EQ centre frequencies (Hz) used for presets and the 10-band engine. */
val EqFrequencies = listOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f)

data class EqPreset(val name: String, val gains: List<Float>)

/** Curves in dB at [EqFrequencies] (same presets as Android). */
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

/** What the effect engine supports (fixed on desktop: our own DSP chain does everything). */
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
    /** Bass boost strength 0..1. */
    val bass: Float = 0f,
    /** Stereo widening ("surround") 0..1. */
    val surround: Float = 0f,
    /** Pre-gain in dB ("loudness"). */
    val loudnessDb: Float = 0f,
    val limiter: Boolean = true,
)

/**
 * Equaliser settings shared by the UI and the audio engine. The UI edits [state]; the engine
 * observes it and applies changes live. Persisted in the "effects" prefs, like Android.
 */
object EqStore {
    const val FILE = "effects"
    private val _capabilities = MutableStateFlow<EqCapabilities?>(
        EqCapabilities("10-band precision EQ", EqFrequencies, -12f, 12f, bassBoost = true, virtualizer = true, loudness = true),
    )
    val capabilities: StateFlow<EqCapabilities?> = _capabilities.asStateFlow()
    private val _state = MutableStateFlow(EqState())
    val state: StateFlow<EqState> = _state.asStateFlow()
    private var prefs: Prefs? = null

    /** Loads the saved settings (idempotent; also done lazily by the first [update]). */
    @Synchronized fun init(store: Prefs? = null) {
        if (prefs != null) return
        val p = store ?: Prefs(FILE)
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

    @Synchronized fun update(transform: (EqState) -> EqState) {
        init()
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

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }
    fun setBass(strength: Float) = update { it.copy(bass = strength.coerceIn(0f, 1f)) }
    fun setSurround(strength: Float) = update { it.copy(surround = strength.coerceIn(0f, 1f)) }
    fun setLoudness(db: Float) = update { it.copy(loudnessDb = db) }
    fun setLimiter(enabled: Boolean) = update { it.copy(limiter = enabled) }

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
