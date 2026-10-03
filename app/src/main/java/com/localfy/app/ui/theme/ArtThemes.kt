package com.localfy.app.ui.theme

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.json.JSONArray

data class ArtTheme(val id: String, val name: String, val group: String, val background: Long,
    val accent: Long, val light: Boolean, val border: String, val detail: String) {
    fun apply(current: ThemeSettings) = current.copy(artThemeID = id, hideThemeArt = false,
        mode = if (light) ThemeMode.Light else ThemeMode.Dark, backdrop = background,
        accentSource = AccentSource.Preset, accent = accent, font = AppFont.Figtree,
        artShape = if (border == "blocks") ArtShape.Square else ArtShape.Rounded)
}

object ArtThemes {
    @Volatile private var cached: List<ArtTheme>? = null
    fun all(context: Context): List<ArtTheme> = cached ?: synchronized(this) {
        cached ?: run {
            val rows = JSONArray(context.assets.open("theme-catalog.json").bufferedReader().use { it.readText() })
            List(rows.length()) { index -> rows.getJSONObject(index).let { row ->
                ArtTheme(row.getString("id"), row.getString("name"), row.getString("group"),
                    0xFF000000 or row.getString("background").toLong(16), 0xFF000000 or row.getString("accent").toLong(16),
                    row.getBoolean("light"), row.getString("border"), row.getString("detail"))
            } }.also { cached = it }
        }
    }
}

@Composable
fun ThemeArtImage(id: String, modifier: Modifier = Modifier) {
    AsyncImage(model = "file:///android_asset/art/$id.png", contentDescription = null,
        modifier = modifier, contentScale = androidx.compose.ui.layout.ContentScale.Fit)
}

@Composable
fun ThemeScene(compact: Boolean = false) {
    val settings = LocalThemeSettings.current
    val theme = ArtThemes.all(LocalContext.current).firstOrNull { it.id == settings.artThemeID }
    if (!settings.hideThemeArt && theme != null) Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = if (compact) 2.dp else 8.dp), contentAlignment = Alignment.Center) {
        ThemeArtImage(theme.id, Modifier.widthIn(max = if (compact) 300.dp else 480.dp).fillMaxWidth().height(if (compact) 58.dp else 110.dp))
    }
}

/** All marks stay at the edge, outside the space used by labels and controls. */
@Composable
fun ThemeFrame(modifier: Modifier = Modifier) {
    val settings = LocalThemeSettings.current
    val theme = ArtThemes.all(LocalContext.current).firstOrNull { it.id == settings.artThemeID }
    val accent = MaterialTheme.colorScheme.primary
    if (!settings.hideThemeArt && theme != null) Canvas(modifier) {
        val ink = accent.copy(alpha = if (theme.border == "deco") 0.38f else 0.25f)
        for (x in listOf(3.dp.toPx(), size.width - 3.dp.toPx())) {
            drawLine(ink.copy(alpha = ink.alpha * .55f), Offset(x, 20.dp.toPx()), Offset(x, size.height - 20.dp.toPx()), 1.dp.toPx())
        }
        var y = 28.dp.toPx()
        while (y < size.height - 20.dp.toPx()) {
            for (x in listOf(4.dp.toPx(), size.width - 4.dp.toPx())) {
                fun rect(dx: Float, dy: Float, w: Float, h: Float) = drawRect(ink, Offset(x + dx.dp.toPx(), y + dy.dp.toPx()), Size(w.dp.toPx(), h.dp.toPx()))
                fun oval(dx: Float, dy: Float, w: Float, h: Float) = drawOval(ink, Offset(x + dx.dp.toPx(), y + dy.dp.toPx()), Size(w.dp.toPx(), h.dp.toPx()))
                when (theme.border) {
                    "stars", "crystals" -> drawPath(Path().apply { moveTo(x, y-5.dp.toPx()); lineTo(x+3.dp.toPx(), y); lineTo(x, y+5.dp.toPx()); lineTo(x-3.dp.toPx(), y); close() }, ink)
                    "petals", "leaves" -> { oval(-3f,-6f,6f,9f); oval(-2f,4f,4f,5f) }
                    "paws" -> { oval(-2f,0f,4f,4f); oval(-2f,-4f,2f,2f); oval(1f,-4f,2f,2f) }
                    "waves", "rain" -> { rect(-2f,0f,3f,2f); rect(-1f,4f,3f,2f); rect(-2f,8f,3f,2f) }
                    "dots", "gears" -> oval(-3f,-3f,6f,6f)
                    else -> { rect(-2f,-5f,4f,if (theme.border == "deco") 10f else 5f); if (theme.border in listOf("circuit", "steps")) rect(-1f,2f,3f,4f) }
                }
            }
            y += 68.dp.toPx()
        }
    }
}

@Composable
fun ArtThemeGallery(repository: ThemeRepository) {
    val settings = LocalThemeSettings.current
    val themes = ArtThemes.all(LocalContext.current)
    var group by remember { mutableStateOf("All") }
    val groups = listOf("All", "Cozy", "Nature", "Night", "Places", "Play", "Quiet")
    val largeText = LocalConfiguration.current.fontScale >= 1.5f || settings.textSize == TextSize.Huge
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("25 little worlds for your music", style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEach { value -> FilterChip(selected = group == value, onClick = { group = value }, label = { Text(value) }) }
        }
        BoxWithConstraints {
            val count = if (largeText) 1 else (maxWidth.value / 170).toInt().coerceIn(2, 4)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                themes.filter { group == "All" || it.group == group }.chunked(count).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { theme ->
                            val isSelected = settings.artThemeID == theme.id
                            val shape = RoundedCornerShape(if (theme.border == "blocks") 6.dp else 16.dp)
                            val ink = if (theme.light) Color(0xFF241F25) else Color(0xFFF5F3EE)
                            Column(Modifier.weight(1f).background(Color(theme.background), shape)
                                .border(if (isSelected) 3.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color(theme.accent).copy(alpha = .3f), shape)
                                .clickable { repository.update(theme::apply) }.semantics { this.selected = isSelected; contentDescription = theme.name }
                                .padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ThemeArtImage(theme.id, Modifier.fillMaxWidth().height(76.dp))
                                Text(theme.name + if (isSelected) " ✓" else "", color = ink, style = MaterialTheme.typography.titleSmall)
                                Text(theme.detail, color = ink.copy(alpha = .78f), fontSize = 12.sp, lineHeight = 16.sp)
                            }
                        }
                        repeat(count - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Show theme art & borders", Modifier.weight(1f))
            Switch(checked = !settings.hideThemeArt, onCheckedChange = { visible -> repository.update { it.copy(hideThemeArt = !visible) } })
        }
    }
}
