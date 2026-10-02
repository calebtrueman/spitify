package com.localfy.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.Routes
import com.localfy.app.ui.components.Avatar
import com.localfy.app.ui.components.MediaTile
import com.localfy.app.ui.components.SectionHeader
import com.localfy.app.ui.components.TileData
import com.localfy.app.ui.components.formatLongDuration
import com.localfy.app.ui.components.pressable
import com.localfy.app.ui.art.artKey
import com.localfy.app.ui.theme.LocalfyColors

/** You: photo, name, and what Spitify has learned about your taste. */
@Composable
fun ProfileScreen() {
    val app = LocalApp.current
    val profile by app.profiles.profile.collectAsStateWithLifecycle()
    val model by app.taste.model.collectAsStateWithLifecycle()
    val library by app.repo.library.collectAsStateWithLifecycle()
    val mixes by app.repo.mixes.collectAsStateWithLifecycle()
    val hiddenSongs by app.taste.hiddenSongs.collectAsStateWithLifecycle()
    val hiddenArtists by app.taste.hiddenArtists.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) app.profiles.setPhoto(uri) }
    val accent = MaterialTheme.colorScheme.primary
    val monthAgo = System.currentTimeMillis() - 30L * 86_400_000
    val monthListens = model?.input?.listens?.filter { it.at > monthAgo && !it.skipped }.orEmpty()
    val topArtists = model?.topArtists()?.take(10).orEmpty()
    val topGenres = model?.genreScore?.entries?.filter { it.value > 0 }?.sortedByDescending { it.value }?.take(6).orEmpty()
    val maxGenre = topGenres.maxOfOrNull { it.value } ?: 1.0

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 140.dp)) {
        item {
            Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(lerp(accent, Color.Black, 0.4f), LocalfyColors.Background))).statusBarsPadding()) {
                IconButton(onClick = { app.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Avatar(116.dp, Modifier.pressable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                        Box(Modifier.align(Alignment.BottomEnd).size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PhotoCamera, "Change photo", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text("PROFILE", style = MaterialTheme.typography.labelSmall, color = LocalfyColors.TextSecondary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(profile.name.ifBlank { "Listener" }, style = MaterialTheme.typography.displaySmall, maxLines = 1)
                            IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, "Edit name", tint = LocalfyColors.TextSecondary) }
                        }
                        Text(
                            "${library.songs.size} songs · ${mixes.size} playlists made for you",
                            style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary,
                        )
                    }
                }
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatPill("This month", "${monthListens.size} plays", Modifier.weight(1f))
                    StatPill("Listening time", formatLongDuration(monthListens.sumOf { it.listenedMs }), Modifier.weight(1f))
                }
            }
        }
        if (topArtists.isNotEmpty()) {
            item { SectionHeader("Your top artists", eyebrow = "What Spitify has learned") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                    items(topArtists, key = { it }) { name ->
                        val a = library.artistByName[name] ?: return@items
                        MediaTile(TileData(name, name, "Artist", a.cover.artKey, circle = true) { app.navigate(Routes.artist(name)) }, 120.dp)
                    }
                }
            }
        }
        if (topGenres.isNotEmpty()) {
            item { SectionHeader("Your sound") }
            items(topGenres, key = { it.key }) { (genre, value) ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(genre.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge)
                    Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(8.dp).clip(CircleShape).background(LocalfyColors.Tint)) {
                        Box(Modifier.fillMaxWidth((value / maxGenre).toFloat().coerceIn(0.05f, 1f)).height(8.dp).clip(CircleShape).background(accent))
                    }
                }
            }
        } else item {
            Text(
                "Play some music and this fills in — Spitify learns from what you finish, skip, like and play together.",
                style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary, modifier = Modifier.padding(16.dp),
            )
        }
        item { SectionHeader("Shortcuts") }
        item { ProfileRow("Your stats", "Top songs, artists and albums") { app.navigate(Routes.STATS) } }
        item { ProfileRow("Settings", "Playback, lyrics, appearance and more") { app.navigate(Routes.SETTINGS) } }
        if (hiddenSongs.isNotEmpty() || hiddenArtists.isNotEmpty()) item {
            ProfileRow("Show hidden recommendations again", "${hiddenSongs.size} songs and ${hiddenArtists.size} artists are hidden from your mixes") { app.taste.unhideAll() }
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(profile.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = LocalfyColors.SurfaceHigh,
            title = { Text("Your name") },
            text = { OutlinedTextField(text, { text = it.take(30) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { app.profiles.setName(text); renaming = false }, enabled = text.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatPill(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(LocalfyColors.Tint).padding(14.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary)
    }
}

@Composable
private fun ProfileRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().pressable(pressedScale = 0.99f, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary)
    }
}
