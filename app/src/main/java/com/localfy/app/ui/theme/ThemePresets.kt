package com.localfy.app.ui.theme

data class ThemePreset(val name: String, val group: String, val symbol: String, val background: Long, val accent: Long, val light: Boolean) {
    fun apply(settings: ThemeSettings) = settings.copy(mode = if (light) ThemeMode.Light else ThemeMode.Dark,
        backdrop = background, accentSource = AccentSource.Preset, accent = accent,
        font = if (group == "Kids") AppFont.Nunito else AppFont.Figtree,
        artShape = if (group == "Kids") ArtShape.Soft else ArtShape.Rounded)
    fun matches(settings: ThemeSettings) = settings.backdrop == background && settings.accent == accent &&
        settings.accentSource == AccentSource.Preset && settings.mode == if (light) ThemeMode.Light else ThemeMode.Dark
}
val ThemePresets = listOf(
    ThemePreset("Classic", "Everyday", "●", 0xFF09090B, 0xFF1ED760, false),
    ThemePreset("Midnight", "Everyday", "☾", 0xFF071326, 0xFF8FB8FF, false),
    ThemePreset("Aurora", "Everyday", "✦", 0xFF171027, 0xFFC5A0FF, false),
    ThemePreset("Rose", "Everyday", "❀", 0xFF28131E, 0xFFFF9ABC, false),
    ThemePreset("Ocean", "Everyday", "≈", 0xFF06232C, 0xFF62DDD1, false),
    ThemePreset("Paper", "Everyday", "◒", 0xFFF5F0E8, 0xFF316344, true),
    ThemePreset("Space crew", "Kids", "🚀", 0xFF101738, 0xFF97BDFF, false),
    ThemePreset("Dino park", "Kids", "🦕", 0xFF112C23, 0xFFA8E66C, false),
    ThemePreset("Race day", "Kids", "🏁", 0xFF202126, 0xFFFFCA63, false),
    ThemePreset("Candy cloud", "Kids", "🍬", 0xFFFFF0F5, 0xFF9A2861, true),
    ThemePreset("Magic garden", "Kids", "🦋", 0xFFEEE8FF, 0xFF6245A5, true),
    ThemePreset("Rainbow pop", "Kids", "🌈", 0xFFE9F7F5, 0xFF126E70, true),
)
