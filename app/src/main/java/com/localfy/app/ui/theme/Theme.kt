package com.localfy.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.localfy.app.R

@Immutable
data class LocalfyPalette(
    val brand: Color,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceHighest: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val isDark: Boolean,
) {
    /** Text/icons drawn on top of [brand] (black on bright accents, white on dark ones). */
    val onBrand: Color get() = if (brand.luminance() > 0.4f) Color.Black else Color.White
    /** Subtle fill for chips/tiles that works on both light and dark backgrounds. */
    val tint: Color get() = textPrimary.copy(alpha = if (isDark) 0.08f else 0.06f)
}

fun darkPalette(brand: Color) = LocalfyPalette(
    brand, Color(0xFF09090B), Color(0xFF131316), Color(0xFF1C1C21), Color(0xFF27272D),
    Color.White, Color(0xFFA7A7AE), Color(0xFF6E6E76), isDark = true,
)

fun amoledPalette(brand: Color) = LocalfyPalette(
    brand, Color.Black, Color(0xFF0A0A0B), Color(0xFF141416), Color(0xFF1E1E21),
    Color.White, Color(0xFF9E9EA6), Color(0xFF66666E), isDark = true,
)

fun lightPalette(brand: Color) = LocalfyPalette(
    lerp(brand, Color.Black, 0.12f), Color(0xFFF7F7F9), Color(0xFFFFFFFF), Color(0xFFEDEDF1), Color(0xFFE2E2E8),
    Color(0xFF111114), Color(0xFF5E5E66), Color(0xFF8E8E96), isDark = false,
)

val LocalPalette = staticCompositionLocalOf { darkPalette(Color(0xFF1ED760)) }

/** Theme-aware colours. Read inside composition; they follow the user's appearance settings. */
object LocalfyColors {
    val Brand: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.brand
    val Background: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.background
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface
    val SurfaceHigh: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surfaceHigh
    val SurfaceHighest: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surfaceHighest
    val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textPrimary
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textSecondary
    val TextTertiary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textTertiary
    val Tint: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.tint
}

private val Figtree = FontFamily(
    Font(R.font.figtree_400, FontWeight.Normal), Font(R.font.figtree_500, FontWeight.Medium),
    Font(R.font.figtree_600, FontWeight.SemiBold), Font(R.font.figtree_700, FontWeight.Bold),
    Font(R.font.figtree_800, FontWeight.ExtraBold), Font(R.font.figtree_900, FontWeight.Black),
)
private val Nunito = FontFamily(
    Font(R.font.nunito_400, FontWeight.Normal), Font(R.font.nunito_500, FontWeight.Medium),
    Font(R.font.nunito_600, FontWeight.SemiBold), Font(R.font.nunito_700, FontWeight.Bold),
    Font(R.font.nunito_800, FontWeight.ExtraBold), Font(R.font.nunito_900, FontWeight.Black),
)
private val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_400, FontWeight.Normal), Font(R.font.space_grotesk_500, FontWeight.Medium),
    Font(R.font.space_grotesk_600, FontWeight.SemiBold), Font(R.font.space_grotesk_700, FontWeight.Bold),
)

fun AppFont.family(): FontFamily = when (this) {
    AppFont.Figtree -> Figtree
    AppFont.Nunito -> Nunito
    AppFont.SpaceGrotesk -> SpaceGrotesk
    AppFont.System -> FontFamily.Default
    AppFont.Serif -> FontFamily.Serif
}

private fun typography(family: FontFamily): Typography {
    fun style(size: Int, weight: FontWeight, line: Int = (size * 1.3).toInt(), tracking: Float = 0f) =
        TextStyle(fontFamily = family, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp, letterSpacing = tracking.sp)
    return Typography(
        displayLarge = style(56, FontWeight.Black, 60, -2f),
        displayMedium = style(44, FontWeight.Black, 48, -1.5f),
        displaySmall = style(34, FontWeight.ExtraBold, 40, -1f),
        headlineLarge = style(30, FontWeight.ExtraBold, 36, -0.8f),
        headlineMedium = style(26, FontWeight.ExtraBold, 32, -0.6f),
        headlineSmall = style(22, FontWeight.Bold, 28, -0.4f),
        titleLarge = style(20, FontWeight.Bold, 26, -0.2f),
        titleMedium = style(16, FontWeight.Bold, 22),
        titleSmall = style(14, FontWeight.Bold, 20),
        bodyLarge = style(16, FontWeight.Medium, 22),
        bodyMedium = style(14, FontWeight.Medium, 20),
        bodySmall = style(12, FontWeight.Medium, 16),
        labelLarge = style(14, FontWeight.Bold, 18),
        labelMedium = style(12, FontWeight.Bold, 16, 0.2f),
        labelSmall = style(11, FontWeight.Bold, 14, 0.6f),
    )
}

@Composable
fun resolvePalette(settings: ThemeSettings, artAccent: Color?): LocalfyPalette {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.mode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        else -> true
    }
    val accent = when (settings.accentSource) {
        AccentSource.Artwork -> artAccent ?: Color(settings.accent)
        AccentSource.Wallpaper -> if (Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(context).primary else dynamicLightColorScheme(context).primary
        } else Color(settings.accent)
        AccentSource.Preset -> Color(settings.accent)
    }
    return when {
        !dark -> lightPalette(accent)
        settings.mode == ThemeMode.Amoled || (settings.mode == ThemeMode.System && false) -> amoledPalette(accent)
        else -> darkPalette(accent)
    }
}

@Composable
fun LocalfyTheme(settings: ThemeSettings = ThemeSettings(), artAccent: Color? = null, content: @Composable () -> Unit) {
    val palette = resolvePalette(settings, artAccent)
    ProvidePalette(palette, settings, content)
}

/** Forces the dark palette (keeps the accent) - used for the art-backed player surfaces. */
@Composable
fun DarkSurface(content: @Composable () -> Unit) {
    val current = LocalPalette.current
    if (current.isDark) return content()
    ProvidePalette(darkPalette(current.brand), LocalThemeSettings.current, content)
}

@Composable
private fun ProvidePalette(palette: LocalfyPalette, settings: ThemeSettings, content: @Composable () -> Unit) {
    val scheme = remember(palette) {
        if (palette.isDark) darkColorScheme(
            primary = palette.brand, onPrimary = palette.onBrand,
            primaryContainer = lerp(palette.brand, Color.Black, 0.3f), onPrimaryContainer = Color.White,
            secondary = Color.White, onSecondary = Color.Black,
            background = palette.background, onBackground = palette.textPrimary,
            surface = palette.background, onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceHigh, onSurfaceVariant = palette.textSecondary,
            surfaceContainerLowest = palette.background, surfaceContainerLow = palette.surface,
            surfaceContainer = palette.surface, surfaceContainerHigh = palette.surfaceHigh,
            surfaceContainerHighest = palette.surfaceHighest,
            outline = Color(0xFF3A3A40), outlineVariant = Color(0xFF26262B),
        ) else lightColorScheme(
            primary = palette.brand, onPrimary = palette.onBrand,
            primaryContainer = lerp(palette.brand, Color.White, 0.6f), onPrimaryContainer = Color.Black,
            secondary = Color.Black, onSecondary = Color.White,
            background = palette.background, onBackground = palette.textPrimary,
            surface = palette.background, onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceHigh, onSurfaceVariant = palette.textSecondary,
            surfaceContainerLowest = Color.White, surfaceContainerLow = palette.surface,
            surfaceContainer = palette.surface, surfaceContainerHigh = palette.surfaceHigh,
            surfaceContainerHighest = palette.surfaceHighest,
            outline = Color(0xFFC4C4CC), outlineVariant = Color(0xFFDDDDE3),
        )
    }
    val typography = remember(settings.font) { typography(settings.font.family()) }
    val density = LocalDensity.current
    MaterialTheme(colorScheme = scheme, typography = typography) {
        CompositionLocalProvider(
            LocalPalette provides palette,
            LocalThemeSettings provides settings,
            LocalContentColor provides palette.textPrimary,
            LocalDensity provides Density(density.density, density.fontScale * settings.textSize.scale),
            content = content,
        )
    }
}
