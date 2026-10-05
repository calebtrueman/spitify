package com.localfy.app.ui.components

import android.text.format.Formatter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.data.Song
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.Routes
import com.localfy.app.ui.SongMenuExtras
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.art.artKey
import com.localfy.app.ui.theme.LocalfyColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongMenuSheet(song: Song, extras: SongMenuExtras, onDismiss: () -> Unit, onNavigated: () -> Unit) {
    val app = LocalApp.current
    val nativeApp = LocalContext.current.applicationContext as com.localfy.app.LocalfyApp
    val streamTrack = nativeApp.musicStreams.track(song)
    val scope = rememberCoroutineScope()
    val liked = song.id in app.repo.likedIds.collectAsStateWithLifecycle().value
    var info by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LocalfyColors.SurfaceHigh,
        contentColor = LocalfyColors.TextPrimary,
    ) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(song.artKey, Modifier.size(50.dp), RoundedCornerShape(6.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary, maxLines = 1)
                }
            }
            HorizontalDivider(color = LocalfyColors.SurfaceHighest)
            if (streamTrack != null) {
                MenuItem(if (nativeApp.musicStreams.contains(streamTrack)) Icons.Rounded.RemoveCircleOutline else Icons.Rounded.Add, if (nativeApp.musicStreams.contains(streamTrack)) "Remove from Library" else "Add to Library") {
                    if (nativeApp.musicStreams.contains(streamTrack)) nativeApp.musicStreams.remove(listOf(streamTrack)) else nativeApp.musicStreams.save(listOf(streamTrack))
                    onDismiss()
                }
                MenuItem(Icons.Rounded.Download, "Download") {
                    nativeApp.musicStreams.save(listOf(streamTrack))
                    nativeApp.appScope.launch { nativeApp.musicDownloads.enqueue(listOf(streamTrack)) }; onDismiss()
                }
            }
            MenuItem(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next") { app.player.playNext(listOf(song)); onDismiss() }
            MenuItem(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue") { app.player.addToQueue(listOf(song)); onDismiss() }
            if (!song.isPodcast) MenuItem(Icons.Rounded.Radio, "Go to song radio") {
                onDismiss()
                app.player.playSongs(app.taste.songRadio(song), 0, shuffle = false, source = "${song.title} Radio")
            }
            if (!song.isPodcast && !song.isAudiobook) MenuItem(Icons.Rounded.Share, "Share with friends") {
                runCatching { val playlist = nativeApp.social.create(song.title, listOf(song), nativeApp.musicStreams, "song"); onDismiss(); onNavigated(); app.navigate(Routes.sharedPlaylist(playlist.key)) }
                    .onFailure { android.widget.Toast.makeText(nativeApp, it.message, android.widget.Toast.LENGTH_LONG).show() }
            }
            if (!song.isPodcast) MenuItem(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") { streamTrack?.let { nativeApp.musicStreams.save(listOf(it)) }; onDismiss(); app.addToPlaylist(listOf(song)) }
            if (extras.onRemove != null) {
                MenuItem(Icons.Rounded.RemoveCircleOutline, extras.removeLabel ?: "Remove") { extras.onRemove.invoke(); onDismiss() }
            }
            if (song.isPodcast) {
                MenuItem(Icons.Rounded.Album, if (song.isAudiobook) "Go to book" else "Go to show") {
                    onDismiss(); onNavigated()
                    val showId = app.podcasts.shows.value.firstOrNull { s -> s.episodes.any { it.id == song.episodeId } }?.id
                    app.navigate(
                        when {
                            song.isAudiobook && showId != null -> Routes.book(showId)
                            song.isAudiobook -> Routes.localBook(song.albumId)
                            showId != null -> Routes.show(showId)
                            else -> Routes.localShow(song.album)
                        },
                    )
                }
            } else {
                MenuItem(Icons.Rounded.Album, "Go to album") { onDismiss(); onNavigated(); app.navigate(streamTrack?.let { Routes.catalogAlbum(com.localfy.app.data.music.OnlineAlbum(it.releaseId, it.album, it.albumArtist ?: it.primaryArtist, it.artwork)) } ?: Routes.album(song.albumId)) }
                song.creditedArtists.forEach { name ->
                    MenuItem(Icons.Rounded.PersonOutline, if (song.creditedArtists.size == 1) "Go to artist" else "Go to $name") { onDismiss(); onNavigated(); app.navigate(Routes.artist(name)) }
                }
            }
            if (streamTrack == null && (!song.isPodcast || song.isAudiobook && song.episodeId == null)) MenuItem(Icons.Rounded.Edit, "Edit info & artwork") { onDismiss(); onNavigated(); app.editMetadata(listOf(app.rawFor(song)), false) }
            if (!song.isPodcast) {
                MenuItem(Icons.Rounded.Block, "Don't recommend this song") { app.taste.hideSong(song.id); onDismiss() }
                MenuItem(Icons.Rounded.PersonOff, "Don't recommend ${song.primaryArtist}") { app.taste.hideArtist(song.primaryArtist); onDismiss() }
            }
            MenuItem(Icons.Rounded.Info, "Song info") { info = true }
        }
    }
    if (info) SongInfoDialog(song) { info = false }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = LocalfyColors.TextSecondary)
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SongInfoDialog(song: Song, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val stat = app.repo.stats.collectAsStateWithLifecycle().value[song.id]
    val kbps = if (song.durationMs > 0) song.sizeBytes * 8 / song.durationMs else 0
    val rows = listOf(
        "Title" to song.title,
        "Artist" to song.artist,
        "Album" to song.album,
        "Album artist" to song.albumArtist,
        "Track" to listOf(song.disc.takeIf { it > 1 }?.let { "Disc $it" }, song.track.takeIf { it > 0 }?.let { "Track $it" }).filterNotNull().joinToString(", ").ifEmpty { "—" },
        "Year" to (song.year.takeIf { it > 0 }?.toString() ?: "—"),
        "Genre" to (song.genre ?: "—"),
        "Length" to formatDuration(song.durationMs),
        "Format" to (song.mimeType?.substringAfter('/')?.uppercase() ?: "—") + if (kbps > 0) " • ~$kbps kbps" else "",
        "Size" to Formatter.formatShortFileSize(context, song.sizeBytes),
        "Location" to "/${song.folder}",
        "Plays" to "${stat?.playCount ?: 0} (skipped ${stat?.skipCount ?: 0})",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Song info") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(rows) { (k, v) ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(k, style = MaterialTheme.typography.labelMedium, color = LocalfyColors.TextSecondary)
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
    )
}

@Composable
fun AddToPlaylistDialog(songs: List<Song>, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val playlists by app.repo.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        CreatePlaylistDialog(songs.map { it.id }, onDismiss = onDismiss)
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (songs.size == 1) "Add to playlist" else "Add ${songs.size} songs to playlist") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(50.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("New playlist", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                items(playlists, key = { it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !saving) {
                            saving = true
                            scope.launch {
                                try {
                                    app.repo.appendToPlaylist(p.id, songs.map { it.id })
                                    android.widget.Toast.makeText(context, "Added to ${p.name}", android.widget.Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                } catch (error: Exception) {
                                    if (error is kotlinx.coroutines.CancellationException) throw error
                                    android.widget.Toast.makeText(context, "Couldn't add songs. Please try again.", android.widget.Toast.LENGTH_LONG).show()
                                } finally { saving = false }
                            }
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(p.artKey, Modifier.size(50.dp), RoundedCornerShape(6.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(songCount(p.songs.size), style = MaterialTheme.typography.bodyMedium, color = LocalfyColors.TextSecondary)
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun CreatePlaylistDialog(songIds: List<Long> = emptyList(), onDismiss: () -> Unit, onCreated: (Long) -> Unit = {}) {
    val app = LocalApp.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Give your playlist a name") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("My playlist #${app.repo.playlists.value.size + 1}") }) },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saving = true
                val finalName = name.ifBlank { "My playlist #${app.repo.playlists.value.size + 1}" }
                scope.launch {
                    try {
                        val id = app.repo.createPlaylist(finalName, songIds)
                        android.widget.Toast.makeText(context, if (songIds.isEmpty()) "Created $finalName" else "Added to $finalName", android.widget.Toast.LENGTH_SHORT).show()
                        onDismiss(); onCreated(id)
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        android.widget.Toast.makeText(context, "Couldn't create the playlist. Please try again.", android.widget.Toast.LENGTH_LONG).show()
                    } finally { saving = false }
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
