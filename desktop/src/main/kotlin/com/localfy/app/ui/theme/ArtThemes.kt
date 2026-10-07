package com.localfy.app.ui.theme


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


import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localfy.app.ui.BundledResources
import com.localfy.app.ui.art.AsyncImage
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
    fun all(): List<ArtTheme> = cached ?: synchronized(this) {
        cached ?: run {
            val rows = JSONArray(BundledResources.text("themes/theme-catalog.json"))
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
    AsyncImage(model = "res:themes/art/$id.png", contentDescription = null,
        modifier = modifier, contentScale = androidx.compose.ui.layout.ContentScale.Fit)
}

@Composable
fun ThemeScene(compact: Boolean = false) {
    val settings = LocalThemeSettings.current
    val theme = ArtThemes.all().firstOrNull { it.id == settings.artThemeID }
    if (!settings.hideThemeArt && theme != null) Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = if (compact) 2.dp else 8.dp), contentAlignment = Alignment.Center) {
        ThemeArtImage(theme.id, Modifier.widthIn(max = if (compact) 300.dp else 480.dp).fillMaxWidth().height(if (compact) 58.dp else 110.dp))
    }
}

private data class ThemeBorderMotif(val colors: List<Color>, val rows: List<String>)
private object ThemeBorderDrawings {
    private var cached: Map<String, ThemeBorderMotif>? = null
    fun all(): Map<String, ThemeBorderMotif> = cached ?: run {
        val data = JSONArray(BundledResources.text("themes/border-motifs.json"))
        buildMap {
            repeat(data.length()) { index ->
                val item = data.getJSONObject(index)
                val colors = item.getJSONArray("colors")
                val rows = item.getJSONArray("rows")
                put(item.getString("id"), ThemeBorderMotif(List(colors.length()) { Color(0xFF000000 or colors.getString(it).toLong(16)) }, List(rows.length()) { rows.getString(it) }))
            }
        }.also { cached = it }
    }
}

/** Theme drawings stay in the outer margin, away from text and controls. */
@Composable
fun ThemeFrame(modifier: Modifier = Modifier) {
    val settings = LocalThemeSettings.current

    val theme = ArtThemes.all().firstOrNull { it.id == settings.artThemeID }
    val motif = theme?.let { ThemeBorderDrawings.all()[it.id] }
    if (!settings.hideThemeArt && theme != null && motif != null) Canvas(modifier) {
        val pixel = 1.35.dp.toPx()
        val width = (motif.rows.firstOrNull()?.length ?: 0) * pixel
        for (side in 0..1) {
            var top = (if (side == 0) 22 else 67).dp.toPx()
            while (top < size.height - 28.dp.toPx()) {
                motif.rows.forEachIndexed { y, row -> row.forEachIndexed { x, value ->
                    val color = value.digitToIntOrNull()?.minus(1)?.let { motif.colors.getOrNull(it) }
                    val dy = top + y * pixel
                    if (color != null && dy + pixel <= size.height - 12.dp.toPx()) {
                        val dx = if (side == 0) 1.dp.toPx() + x * pixel else size.width - 1.dp.toPx() - width + (row.length - 1 - x) * pixel
                        drawRect(color.copy(alpha = if (theme.light) .66f else .72f), Offset(dx, dy), Size(pixel, pixel))
                    }
                } }
                top += 112.dp.toPx()
            }
        }
    }
}

@Composable
fun ArtThemeGallery(repository: ThemeRepository) {
    val settings = LocalThemeSettings.current
    val themes = ArtThemes.all()
    var group by remember { mutableStateOf("All") }
    val groups = listOf("All", "Cozy", "Nature", "Night", "Places", "Play", "Quiet")
    val largeText = settings.textSize == TextSize.Huge
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
