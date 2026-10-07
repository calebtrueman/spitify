package com.localfy.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.localfy.app.desktop.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { System("Follow system"), Dark("Dark"), Light("Light"), Amoled("AMOLED black") }
/** Wallpaper (Material You) has no desktop source: it's not offered and falls back to the picked colour. */
enum class AccentSource(val label: String) { Preset("Pick a colour"), Artwork("From album art"), Wallpaper("Material You") }
enum class AppFont(val label: String) { Figtree("Figtree"), Nunito("Nunito (rounded)"), SpaceGrotesk("Space Grotesk"), System("System font"), Serif("Serif") }
enum class ArtShape(val label: String) { Rounded("Rounded"), Square("Square"), Soft("Extra round") }
enum class PlayerStyle(val label: String) { Artwork("Artwork"), Vinyl("Spinning vinyl"), Minimal("Minimal") }
enum class TextSize(val label: String, val scale: Float) { Small("Small", 0.9f), Default("Default", 1f), Large("Large", 1.12f), Huge("Extra large", 1.25f) }

@Immutable
data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.Dark,
    val artThemeID: String? = null,
    val hideThemeArt: Boolean = false,
    val backdrop: Long? = null,
    val accentSource: AccentSource = AccentSource.Preset,
    val accent: Long = AccentPresets.first().second,
    val artworkTint: Boolean = true,
    val blurBackdrop: Boolean = true,
    val font: AppFont = AppFont.Figtree,
    val textSize: TextSize = TextSize.Default,
    val artShape: ArtShape = ArtShape.Rounded,
    val playerStyle: PlayerStyle = PlayerStyle.Artwork,
    val reduceMotion: Boolean = false,
    val haptics: Boolean = true,
)

val AccentPresets: List<Pair<String, Long>> = listOf(
    "Spitify green" to 0xFF1ED760,
    "Ocean" to 0xFF3D8BFF,
    "Violet" to 0xFF9B6BFF,
    "Hot pink" to 0xFFFF4FA3,
    "Coral" to 0xFFFF6B5A,
    "Amber" to 0xFFFFB300,
    "Lime" to 0xFFB6F23D,
    "Aqua" to 0xFF22D3C5,
    "Crimson" to 0xFFE53950,
    "Ice" to 0xFFA5C8FF,
)

/** Persists appearance settings; the whole UI recomposes when they change. */
class ThemeRepository(private val prefs: Prefs = Prefs("theme")) {
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ThemeSettings> = _settings.asStateFlow()

    fun update(transform: (ThemeSettings) -> ThemeSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit {
            putString("artThemeID", next.artThemeID)
            putBoolean("hideThemeArt", next.hideThemeArt)
            putString("mode", next.mode.name)
            if (next.backdrop == null) remove("backdrop") else putLong("backdrop", next.backdrop)
            putString("accentSource", next.accentSource.name)
            putLong("accent", next.accent)
            putBoolean("artworkTint", next.artworkTint)
            putBoolean("blur", next.blurBackdrop)
            putString("font", next.font.name)
            putString("textSize", next.textSize.name)
            putString("artShape", next.artShape.name)
            putString("playerStyle", next.playerStyle.name)
            putBoolean("reduceMotion", next.reduceMotion)
            putBoolean("haptics", next.haptics)
        }
    }

    fun reset() = update { ThemeSettings() }

    private fun load(): ThemeSettings {
        val d = ThemeSettings()
        fun <E : Enum<E>> enum(key: String, values: Array<E>, default: E) =
            prefs.getString(key, null)?.let { n -> values.firstOrNull { it.name == n } } ?: default
        return ThemeSettings(
            artThemeID = prefs.getString("artThemeID", null),
            hideThemeArt = prefs.getBoolean("hideThemeArt", false),
            mode = enum("mode", ThemeMode.entries.toTypedArray(), d.mode),
            backdrop = if (prefs.contains("backdrop")) prefs.getLong("backdrop", 0) else null,
            accentSource = enum("accentSource", AccentSource.entries.toTypedArray(), d.accentSource)
                .let { if (it == AccentSource.Wallpaper) AccentSource.Preset else it },
            accent = prefs.getLong("accent", d.accent),
            artworkTint = prefs.getBoolean("artworkTint", d.artworkTint),
            blurBackdrop = prefs.getBoolean("blur", d.blurBackdrop),
            font = enum("font", AppFont.entries.toTypedArray(), d.font),
            textSize = enum("textSize", TextSize.entries.toTypedArray(), d.textSize),
            artShape = enum("artShape", ArtShape.entries.toTypedArray(), d.artShape),
            playerStyle = enum("playerStyle", PlayerStyle.entries.toTypedArray(), d.playerStyle),
            reduceMotion = prefs.getBoolean("reduceMotion", d.reduceMotion),
            haptics = prefs.getBoolean("haptics", d.haptics),
        )
    }
}

val LocalThemeSettings = staticCompositionLocalOf { ThemeSettings() }
