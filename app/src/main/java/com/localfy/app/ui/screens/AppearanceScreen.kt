package com.localfy.app.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.LocalfyApp
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.art.artKey
import com.localfy.app.ui.components.BigPlayButton
import com.localfy.app.ui.components.EqualizerBars
import com.localfy.app.ui.components.Pill
import com.localfy.app.ui.components.SectionHeader
import com.localfy.app.ui.components.pressable
import com.localfy.app.ui.player.SwitchRow
import com.localfy.app.ui.theme.AccentPresets
import com.localfy.app.ui.theme.AccentSource
import com.localfy.app.ui.theme.AppFont
import com.localfy.app.ui.theme.ArtShape
import com.localfy.app.ui.theme.LocalPalette
import com.localfy.app.ui.theme.LocalThemeSettings
import com.localfy.app.ui.theme.LocalfyColors
import com.localfy.app.ui.theme.PlayerStyle
import com.localfy.app.ui.theme.TextSize
import com.localfy.app.ui.theme.ThemeMode
import com.localfy.app.ui.theme.ThemePresets
import com.localfy.app.ui.theme.family

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen() {
    val app = LocalApp.current
    val repo = (androidx.compose.ui.platform.LocalContext.current.applicationContext as LocalfyApp).theme
    val settings = LocalThemeSettings.current
    val library by app.repo.library.collectAsStateWithLifecycle()
    val sample = library.songs.firstOrNull()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 140.dp)) {
        item {
            com.localfy.app.ui.components.PageHeader("Appearance", onBack = { app.nav.popBackStack() }) {
                TextButton(onClick = repo::reset) { Text("Reset") }
            }
        }

        // Live preview of the current choices.
        item {
            Column(
                Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(LocalfyColors.SurfaceHigh, LocalfyColors.Surface)))
                    .padding(16.dp),
            ) {
                Text("PREVIEW", style = MaterialTheme.typography.labelSmall, color = LocalfyColors.TextSecondary)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(sample?.artKey, Modifier.size(72.dp), RoundedCornerShape(8.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(sample?.title ?: "Song title", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(sample?.artist ?: "Artist", style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EqualizerBars(true, size = 14.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Now playing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    BigPlayButton(true, {}, size = 52.dp)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Selected", true, {})
                    Pill("Chip", false, {})
                }
            }
        }

        item { com.localfy.app.ui.components.AppIconSettingsRow() }

        listOf("Everyday", "Kids").forEach { group ->
            item { SectionHeader(if (group == "Kids") "Made for little listeners" else "Ready-made looks") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(ThemePresets.filter { it.group == group }, key = { it.name }) { preset ->
                        ChoiceCard(preset.name, preset.matches(settings), { repo.update(preset::apply) }) {
                            Box(Modifier.size(width = 100.dp, height = 68.dp).clip(RoundedCornerShape(12.dp)).background(Color(preset.background)), contentAlignment = Alignment.Center) {
                                Text(preset.symbol, fontSize = 32.sp, color = Color(preset.accent))
                                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).size(width = 44.dp, height = 3.dp).background(Color(preset.accent), CircleShape))
                            }
                        }
                    }
                }
            }
        }
        item { SectionHeader("Theme") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ThemeMode.entries.toList()) { mode ->
                    val (bg, fg) = when (mode) {
                        ThemeMode.Dark -> Color(0xFF131316) to Color.White
                        ThemeMode.Light -> Color(0xFFF2F2F5) to Color(0xFF111114)
                        ThemeMode.Amoled -> Color.Black to Color.White
                        ThemeMode.System -> Color(0xFF6E6E76) to Color.White
                    }
                    ChoiceCard(mode.label, settings.mode == mode, { repo.update { it.copy(mode = mode, backdrop = null) } }) {
                        Box(Modifier.size(width = 96.dp, height = 64.dp).clip(RoundedCornerShape(10.dp)).background(
                            if (mode == ThemeMode.System) Brush.linearGradient(listOf(Color(0xFF131316), Color(0xFFF2F2F5))) else Brush.linearGradient(listOf(bg, bg)),
                        ).padding(10.dp)) {
                            Column {
                                Box(Modifier.size(width = 40.dp, height = 6.dp).clip(CircleShape).background(fg.copy(alpha = 0.85f)))
                                Spacer(Modifier.height(6.dp))
                                Box(Modifier.size(width = 60.dp, height = 6.dp).clip(CircleShape).background(fg.copy(alpha = 0.4f)))
                                Spacer(Modifier.height(10.dp))
                                Box(Modifier.size(14.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                            }
                        }
                    }
                }
            }
        }

        item { SectionHeader("Accent colour") }
        item {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceChip(Icons.Rounded.Image, "Pick", settings.accentSource == AccentSource.Preset) { repo.update { it.copy(accentSource = AccentSource.Preset) } }
                SourceChip(Icons.Rounded.Album, "Album art", settings.accentSource == AccentSource.Artwork) { repo.update { it.copy(accentSource = AccentSource.Artwork) } }
                if (Build.VERSION.SDK_INT >= 31) {
                    SourceChip(Icons.Rounded.Wallpaper, "Material You", settings.accentSource == AccentSource.Wallpaper) { repo.update { it.copy(accentSource = AccentSource.Wallpaper) } }
                }
            }
        }
        item {
            Text(
                when (settings.accentSource) {
                    AccentSource.Preset -> "Used for buttons, progress and highlights."
                    AccentSource.Artwork -> "The accent follows the album art of whatever's playing."
                    AccentSource.Wallpaper -> "Matches your wallpaper colours (Material You)."
                },
                style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (settings.accentSource == AccentSource.Preset) item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(AccentPresets) { (name, value) ->
                    val selected = settings.accent == value
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).pressable { repo.update { it.copy(accent = value) } }) {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(Color(value))
                                .border(if (selected) 3.dp else 0.dp, LocalfyColors.TextPrimary, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (selected) Icon(Icons.Rounded.Check, null, tint = Color.Black) }
                        Spacer(Modifier.height(6.dp))
                        Text(name, style = MaterialTheme.typography.labelSmall, color = LocalfyColors.TextSecondary, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }

        item { SectionHeader("Typeface") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(AppFont.entries.toList()) { font ->
                    ChoiceCard(font.label, settings.font == font, { repo.update { it.copy(font = font) } }) {
                        Text("Aa", style = TextStyle(fontFamily = font.family(), fontSize = 40.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }

        item { SectionHeader("Text size") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TextSize.entries.toList()) { size -> Pill(size.label, settings.textSize == size, { repo.update { it.copy(textSize = size) } }) }
            }
        }

        item { SectionHeader("Artwork shape") }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ArtShape.entries.forEach { shape ->
                    ChoiceCard(shape.label, settings.artShape == shape, { repo.update { it.copy(artShape = shape) } }, modifier = Modifier.weight(1f), labelLines = 2) {
                        val r = when (shape) { ArtShape.Square -> 0.dp; ArtShape.Rounded -> 8.dp; ArtShape.Soft -> 18.dp }
                        Box(Modifier.size(64.dp).clip(RoundedCornerShape(r)).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, Color(0xFF3949AB)))))
                    }
                }
            }
        }

        item { SectionHeader("Now Playing style") }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlayerStyle.entries.forEach { style ->
                    ChoiceCard(style.label, settings.playerStyle == style, { repo.update { it.copy(playerStyle = style) } }, modifier = Modifier.weight(1f), labelLines = 2) {
                        when (style) {
                            PlayerStyle.Artwork -> Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(listOf(Color(0xFFFF2A6D), Color(0xFF05D9E8)))))
                            PlayerStyle.Vinyl -> Box(Modifier.size(64.dp).clip(CircleShape).background(Color(0xFF0D0D0F)), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(26.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFFF2A6D), Color(0xFF05D9E8)))))
                            }
                            PlayerStyle.Minimal -> Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)).background(Brush.linearGradient(listOf(Color(0xFFFF2A6D), Color(0xFF05D9E8)))))
                            }
                        }
                    }
                }
            }
        }

        item { SectionHeader("Effects") }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SwitchRow("Artwork colours", "Tint headers, the player and Home with colours from the album art", settings.artworkTint, { v -> repo.update { it.copy(artworkTint = v) } })
                SwitchRow("Blurred backdrop", "Soft, blurred artwork behind the player (Android 12+)", settings.blurBackdrop, { v -> repo.update { it.copy(blurBackdrop = v) } })
                SwitchRow("Reduce motion", "Turn off press bounce, breathing artwork and the spinning record", settings.reduceMotion, { v -> repo.update { it.copy(reduceMotion = v) } })
                SwitchRow("Haptic feedback", "Gentle vibration on play, skip, like and long-press", settings.haptics, { v -> repo.update { it.copy(haptics = v) } })
            }
        }
    }
}

@Composable
private fun ChoiceCard(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, labelLines: Int = 1, preview: @Composable () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(LocalfyColors.Tint)
            .border(2.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(16.dp))
            .pressable(onClick = onClick)
            .semantics { this.selected = selected; role = Role.RadioButton }
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(72.dp), contentAlignment = Alignment.Center) { preview() }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) accent else LocalfyColors.TextPrimary, minLines = labelLines, maxLines = labelLines, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SourceChip(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.primary else LocalfyColors.Tint)
            .pressable(onClick = onClick)
            .semantics { this.selected = selected; role = Role.RadioButton }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (selected) LocalPalette.current.onBrand else LocalfyColors.TextPrimary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) LocalPalette.current.onBrand else LocalfyColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
