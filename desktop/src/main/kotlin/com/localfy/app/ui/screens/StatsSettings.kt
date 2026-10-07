package com.localfy.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.Routes
import com.localfy.app.ui.art.ArtKey
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.art.artKey
import com.localfy.app.ui.components.EmptyState
import com.localfy.app.ui.components.SectionHeader
import com.localfy.app.ui.components.formatLongDuration
import com.localfy.app.ui.player.PlaybackSettings
import com.localfy.app.ui.player.SwitchRow
import com.localfy.app.ui.theme.LocalfyColors
import kotlinx.coroutines.launch

@Composable
private fun BackHeader(title: String) {
    val app = LocalApp.current
    com.localfy.app.ui.components.PageHeader(title, onBack = { app.nav.popBackStack() })
}

/** A year-round "Wrapped", computed entirely on-device. */
@Composable
fun StatsScreen() {
    val app = LocalApp.current
    val library by app.repo.library.collectAsStateWithLifecycle()
    val stats by app.repo.stats.collectAsStateWithLifecycle()
    val plays = stats.values.sumOf { it.playCount }
    val listenedMs = stats.values.sumOf { s -> (library.songById[s.songId]?.durationMs ?: 0) * s.playCount }
    val topSongs = library.songs.filter { (stats[it.id]?.playCount ?: 0) > 0 }.sortedByDescending { stats[it.id]!!.playCount }.take(10)
    val topArtists = library.artists.map { a -> a to a.songs.sumOf { stats[it.id]?.playCount ?: 0 } }.filter { it.second > 0 }.sortedByDescending { it.second }.take(10)
    val topAlbums = library.albums.map { a -> a to a.songs.sumOf { stats[it.id]?.playCount ?: 0 } }.filter { it.second > 0 }.sortedByDescending { it.second }.take(10)
    val skipped = library.songs.filter { (stats[it.id]?.skipCount ?: 0) >= 2 }.sortedByDescending { stats[it.id]!!.skipCount }.take(5)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item { BackHeader("Your stats") }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Plays", plays.toString(), Modifier.weight(1f))
                StatCard("Listening time", formatLongDuration(listenedMs), Modifier.weight(1f))
                StatCard("Library", "${library.songs.size} songs", Modifier.weight(1f))
            }
        }
        if (plays == 0) { item { EmptyState("No listening yet", "Plays count after 30 seconds (or half a short track).") }; return@LazyColumn }
        item { SectionHeader("Top songs") }
        itemsIndexed(topSongs) { i, s ->
            RankRow(i + 1, s.title, "${stats[s.id]!!.playCount} plays • ${s.artist}", s.artKey) { app.player.playSongs(topSongs, i, source = "Your top songs") }
        }
        item { SectionHeader("Top artists") }
        itemsIndexed(topArtists) { i, (a, n) -> RankRow(i + 1, a.name, "$n plays", a.cover.artKey, circle = true) { app.navigate(Routes.artist(a.name)) } }
        item { SectionHeader("Top albums") }
        itemsIndexed(topAlbums) { i, (a, n) -> RankRow(i + 1, a.title, "$n plays • ${a.artist}", a.cover.artKey) { app.navigate(Routes.album(a.id)) } }
        if (skipped.isNotEmpty()) {
            item { SectionHeader("Most skipped") }
            itemsIndexed(skipped) { i, s -> RankRow(i + 1, s.title, "Skipped ${stats[s.id]!!.skipCount}× • ${s.artist}", s.artKey) { app.openSongMenu(s, com.localfy.app.ui.SongMenuExtras()) } }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = LocalfyColors.SurfaceHigh) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary)
        }
    }
}

@Composable
private fun RankRow(rank: Int, title: String, subtitle: String, art: ArtKey, circle: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$rank", style = MaterialTheme.typography.titleMedium, color = LocalfyColors.TextSecondary, modifier = Modifier.width(32.dp))
        Artwork(art, Modifier.size(48.dp), if (circle) CircleShape else RoundedCornerShape(4.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SettingsScreen() {
    val app = LocalApp.current
    val nativeApp = com.localfy.app.ui.LocalContainer.current
    val window = com.localfy.app.ui.LocalWindow.current
    val library by app.repo.library.collectAsStateWithLifecycle()
    val folders by nativeApp.library.folders.collectAsStateWithLifecycle()
    val downloadFolder by nativeApp.musicDownloads.folder.collectAsStateWithLifecycle()
    val showRecommendations by app.repo.showRecommendations.collectAsStateWithLifecycle()
    val hideShort by app.repo.hideShortTracks.collectAsStateWithLifecycle()
    val scanning by app.repo.scanning.collectAsStateWithLifecycle()
    val online by app.lyrics.onlineEnabled.collectAsStateWithLifecycle()
    val lyricsFolder by app.lyrics.folder.collectAsStateWithLifecycle()
    val startMinimized by com.localfy.app.ui.DesktopSettings.startMinimized.collectAsStateWithLifecycle()
    val closeToTray by com.localfy.app.ui.DesktopSettings.closeToTray.collectAsStateWithLifecycle()
    val trayAvailable = androidx.compose.runtime.remember { runCatching { java.awt.SystemTray.isSupported() }.getOrDefault(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item { BackHeader("Settings") }
        item { SettingRow("Sharing connection", "Pause sharing or change connections") { app.navigate("friends-settings") } }
        item { SettingRow("Hidden artists", "Show artists you hid from Library") { app.navigate("hidden-artists") } }
        item {
            val deletedMixes by app.repo.hiddenMixCount.collectAsStateWithLifecycle()
            if (deletedMixes > 0) SettingRow("Bring back deleted mixes", "$deletedMixes generated ${if (deletedMixes == 1) "playlist" else "playlists"} you deleted") { app.repo.restoreDeletedMixes() }
        }
        item { SettingRow("Appearance", "Theme, accent colour, typeface, text size, artwork and player style") { app.navigate(Routes.APPEARANCE) } }
        item { SettingRow("Equaliser & sound", "10 presets, custom curve, bass boost, surround, loudness") { app.navigate(Routes.EQUALIZER) } }

        item { SectionHeader("Music folders") }
        item {
            Text(
                "Spitify plays the music, podcasts and audiobooks in these folders and keeps watching them for new files.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary,
            )
        }
        itemsIndexed(folders, key = { _, f -> "folder:" + f.path }) { _, folder ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(folder.name.ifEmpty { folder.path }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(folder.path, style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { com.localfy.app.ui.openFolder(folder) }) { Text("Show") }
                TextButton(onClick = { app.repo.removeFolder(folder) }, enabled = folders.size > 1) { Text("Remove") }
            }
        }
        item {
            TextButton(onClick = { com.localfy.app.ui.FilePickers.pickFolder(window, "Add a music folder")?.let { app.repo.addFolder(it) } }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Add folder…")
            }
        }
        item {
            SettingRow("Downloads folder", "${downloadFolder.path} — click to change") {
                com.localfy.app.ui.FilePickers.pickFolder(window, "Save downloads in")?.let { nativeApp.musicDownloads.setFolder(it) }
            }
        }

        item { SectionHeader("Home") }
        item {
            SwitchRow("Show recommendations", "Show suggested mixes, artist radio, throwbacks and top artists. Turn this off for a simpler Home screen. You can turn it back on anytime.", showRecommendations, app.repo::setShowRecommendations, Modifier.padding(horizontal = 16.dp))
        }
        item { SectionHeader("Playback") }
        item {
            val scope = rememberCoroutineScope()
            TextButton(onClick = { scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.localfy.app.data.music.ListeningCache.clear() } }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Clear listening cache") }
            Text("Streaming keeps up to 1 GB of temporary audio. Older streams are cleared automatically. Downloads stay until you remove them.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        }
        item { PlaybackSettings(Modifier.padding(horizontal = 16.dp)) }
        item { SettingRow("Gapless playback", "Always on — albums flow track-to-track with no gap") {} }

        item { SectionHeader("Lyrics") }
        item {
            SwitchRow(
                "Find lyrics online automatically",
                "When a song has no lyrics of its own, fetch synced lyrics from LRCLIB whenever you're online. Sends only artist, title, album and length.",
                online, app.lyrics::setOnlineEnabled, Modifier.padding(horizontal = 16.dp),
            )
        }
        item {
            SettingRow(
                "Lyrics folder",
                lyricsFolder?.let { "Using .lrc files from ${it.name.ifEmpty { it.path }} — click to change" }
                    ?: "Pick a folder with .lrc files (matched by file name or “Artist - Title”)",
            ) { com.localfy.app.ui.FilePickers.pickFolder(window, "Choose your lyrics folder")?.let { app.lyrics.setFolder(it) } }
        }
        if (lyricsFolder != null) item { SettingRow("Stop using lyrics folder", "Embedded and online lyrics keep working") { app.lyrics.setFolder(null) } }

        item { SectionHeader("Metadata & album art") }
        item {
            val onlineArt = nativeApp.onlineArt
            val artOnline by onlineArt.enabled.collectAsStateWithLifecycle()
            val autoFix by nativeApp.metadata.autoFix.collectAsStateWithLifecycle()
            val fixing by nativeApp.metadata.fixing.collectAsStateWithLifecycle()
            Column(Modifier.padding(horizontal = 16.dp)) {
                SwitchRow(
                    "Fill in missing song & book info",
                    (if (fixing) "Working… " else "") + "Missing details and covers are filled automatically from matching online results. Existing tags and covers stay in place.",
                    autoFix, nativeApp.metadata::setAutoFix,
                )
                SwitchRow(
                    "Fetch missing album art",
                    "When a file has no cover, look it up on Deezer / iTunes and keep it on this computer. Sends only artist and album names.",
                    artOnline, onlineArt::setEnabled,
                )
            }
            SettingRow("Clear downloaded artwork", "${onlineArt.downloadedCount()} covers saved — they'll be fetched again as needed") { onlineArt.clear() }
        }
        item { SectionHeader("Library") }
        item {
            SettingRow("Rescan folders", if (scanning) "Scanning…" else "${library.songs.size} songs • ${library.albums.size} albums • ${library.artists.size} artists") { app.repo.refresh() }
        }
        item {
            SwitchRow("Hide short audio", "Skip voice notes, ringtones and clips under 30 seconds", hideShort, app.repo::setHideShortTracks, Modifier.padding(horizontal = 16.dp))
        }
        item { FlacConversionRow() }

        item { SectionHeader("Desktop") }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SwitchRow("Start minimised", "Open Spitify in the background when it starts", startMinimized, com.localfy.app.ui.DesktopSettings::setStartMinimized)
                if (trayAvailable) SwitchRow("Keep playing in the tray when closed", "Closing the window keeps the music going; quit from the tray icon", closeToTray, com.localfy.app.ui.DesktopSettings::setCloseToTray)
            }
        }
        item {
            SettingRow("Keyboard shortcuts", "Space play/pause · ←/→ seek 5 s · ${shortcutKey()}+←/→ previous/next · ${shortcutKey()}+F search · ${shortcutKey()}+L like · Esc back") {}
        }
        item { SettingRow("Tip", "Drop audio files onto the window to play them, or folders to add them to your library.") {} }
        item { SectionHeader("Help") }
        item {
            SettingRow("Open data folder", "Settings, playlists, profiles and custom covers live here. Back it up to keep them.") {
                com.localfy.app.ui.openFolder(com.localfy.app.desktop.AppPaths.dataDir)
            }
        }
    }
}

private fun shortcutKey() = if (com.localfy.app.desktop.AppPaths.isMac) "⌘" else "Ctrl"

@Composable
internal fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary)
    }
}
