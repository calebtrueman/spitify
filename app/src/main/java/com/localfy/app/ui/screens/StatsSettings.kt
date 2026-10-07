package com.localfy.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun SettingsScreen() {
    val app = LocalApp.current
    var autoHelp by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val library by app.repo.library.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val nativeApp = context.applicationContext as com.localfy.app.LocalfyApp
    val wifiDownloads by nativeApp.musicDownloads.wifiOnly.collectAsStateWithLifecycle()
    val wallpaper = nativeApp.lockScreenArt
    val lockScreenArt by wallpaper.enabled.collectAsStateWithLifecycle()
    val wallpaperAllowed by wallpaper.allowed.collectAsStateWithLifecycle()
    val wallpaperMessage by wallpaper.message.collectAsStateWithLifecycle()
    val showRecommendations by app.repo.showRecommendations.collectAsStateWithLifecycle()
    val hideShort by app.repo.hideShortTracks.collectAsStateWithLifecycle()
    val scanning by app.repo.scanning.collectAsStateWithLifecycle()
    val online by app.lyrics.onlineEnabled.collectAsStateWithLifecycle()
    val folder by app.lyrics.folder.collectAsStateWithLifecycle()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) app.lyrics.setFolder(uri) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item { BackHeader("Settings") }
        item { SettingRow("Sharing connection", "Pause sharing or change connections") { app.navigate("friends-settings") } }
        item { SettingRow("Your devices", "See what your other devices play, control them and continue where you left off") { app.navigate("devices") } }
        item { SettingRow("Hidden artists", "Show artists you hid from Library") { app.navigate("hidden-artists") } }
        item {
            val deletedMixes by app.repo.hiddenMixCount.collectAsStateWithLifecycle()
            if (deletedMixes > 0) SettingRow("Bring back deleted mixes", "$deletedMixes generated ${if (deletedMixes == 1) "playlist" else "playlists"} you deleted") { app.repo.restoreDeletedMixes() }
        }
        item { SettingRow("Appearance", "Theme, accent colour, typeface, text size, artwork and player style") { app.navigate(Routes.APPEARANCE) } }
        item { SettingRow("Equaliser & sound", "10 presets, custom curve, bass boost, surround, loudness") { app.navigate(Routes.EQUALIZER) } }
        item { SectionHeader("Home") }
        item {
            SwitchRow("Show recommendations", "Show suggested mixes, artist radio, throwbacks and top artists. Turn this off for a simpler Home screen. You can turn it back on anytime.", showRecommendations, app.repo::setShowRecommendations, Modifier.padding(horizontal = 16.dp))
        }
        item { SectionHeader("Playback") }
        item {
            val scope = rememberCoroutineScope()
            TextButton(onClick = { scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.localfy.app.data.music.ListeningCache.clear(nativeApp) } }) { Text("Clear listening cache") }
            Text("Streaming keeps up to 1 GB of temporary audio. Older streams are cleared automatically. Downloads stay until you remove them.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        }
        item { SwitchRow("Download over Wi-Fi only", "Use Wi-Fi when saving music.", wifiDownloads, nativeApp.musicDownloads::setWifiOnly, Modifier.padding(horizontal = 16.dp)) }
        item { SwitchRow("Full-screen lock-screen art", "Back up both still wallpapers before showing album art on Lock. Restore when playback stops, Spitify closes, or you pause for 10 minutes. Live wallpapers stay unchanged.", lockScreenArt, wallpaper::setEnabled, Modifier.padding(horizontal = 16.dp)) }
        if (lockScreenArt && !wallpaperAllowed) item {
            SettingRow("Allow wallpaper backup", "Android needs All files access to save and restore your wallpaper. Your music still works without it.") {
                runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))) }
            }
        }
        wallpaperMessage?.let { message ->
            item { Text(message, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
            item { SettingRow("Restore saved wallpaper", "Stop album art and put back the wallpaper Spitify saved.") {
                wallpaper.setEnabled(false)
                nativeApp.appScope.launch { wallpaper.restore(force = true) }
            } }
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
                folder?.let { "Using .lrc files from ${it.lastPathSegment?.substringAfter(':') ?: "selected folder"} — tap to change" }
                    ?: "Pick a folder with .lrc files (matched by file name or “Artist - Title”)",
            ) { pickFolder.launch(null) }
        }
        if (folder != null) item { SettingRow("Stop using lyrics folder", "Embedded and online lyrics keep working") { app.lyrics.setFolder(null) } }

        item { SectionHeader("Metadata & album art") }
        item {
            val container = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.localfy.app.LocalfyApp
            val onlineArt = container.onlineArt
            val artOnline by onlineArt.enabled.collectAsStateWithLifecycle()
            val autoFix by container.metadata.autoFix.collectAsStateWithLifecycle()
            val fixing by container.metadata.fixing.collectAsStateWithLifecycle()
            Column(Modifier.padding(horizontal = 16.dp)) {
                SwitchRow(
                    "Fill in missing song & book info",
                    (if (fixing) "Working… " else "") + "Missing details and covers are filled automatically from matching online results. Existing tags and covers stay in place. Android may ask once to allow saving changes to your files.",
                    autoFix, container.metadata::setAutoFix,
                )
                SwitchRow(
                    "Fetch missing album art",
                    "When a file has no cover, look it up on Deezer / iTunes and keep it on this device. Sends only artist and album names.",
                    artOnline, onlineArt::setEnabled,
                )
            }
            SettingRow("Clear downloaded artwork", "${onlineArt.downloadedCount()} covers saved — they'll be fetched again as needed") { onlineArt.clear() }
        }
        item { SectionHeader("Automatic backup") }
        item { Text("Settings, playlists, profiles and custom covers use Android's automatic backup and device transfer. Restore depends on device backup being enabled and using the same app signing key. Music files are separate.", Modifier.padding(horizontal = 16.dp)) }
        item { SectionHeader("Library") }
        item {
            SettingRow("Rescan device", if (scanning) "Scanning…" else "${library.songs.size} songs • ${library.albums.size} albums • ${library.artists.size} artists") { app.repo.refresh() }
        }
        item {
            SwitchRow("Hide short audio", "Skip voice notes, ringtones and clips under 30 seconds", hideShort, app.repo::setHideShortTracks, Modifier.padding(horizontal = 16.dp))
        }
        item { FlacConversionRow() }

        item { SectionHeader("Galaxy Z Fold8") }
        item {
            SettingRow(
                "Fold-aware layouts",
                "Closed: phone layout on the cover screen. Open: two screens split at the hinge — library on one half, player on the other; tap ⤢ for the dual-screen player. Half-fold in landscape for Flex Mode.",
            ) {}
        }
        item {
            SettingRow("Tip", "Settings › Display › Screen continuity — set Spitify to “Always” so it carries on seamlessly when you close the phone.") {}
        }
        item { SectionHeader("Help") }
        item { SettingRow("Android Auto", "Setup for an app installed from an APK") { autoHelp = true } }
        item {
            val context = androidx.compose.ui.platform.LocalContext.current
            val version = androidx.compose.runtime.remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
            SettingRow("Send problem report", "Spitify $version · shares any errors the app recovered from, so they can be fixed") {
                com.localfy.app.CrashReport.share(context, com.localfy.app.CrashReport.problems(context) ?: "No problems recorded — Spitify $version")
            }
        }
    }
    if (autoHelp) androidx.compose.material3.AlertDialog(
        onDismissRequest = { autoHelp = false }, title = { Text("Show Spitify in Android Auto") },
        text = { Text("1. Open Spitify and allow music access.\n\n2. Open Android Auto settings on your phone. Tap Version and permission info 10 times to enable developer mode.\n\n3. Open the top-right menu → Developer settings and enable Unknown sources. This is needed for media apps installed from an APK.\n\n4. Reconnect your car. In Customize launcher, enable Spitify if it is listed.\n\nDo this while parked. If it is still missing, send your phone model and Android Auto version with a problem report.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { autoHelp = false }) { Text("Done") } },
    )
}

@Composable
internal fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary)
    }
}
