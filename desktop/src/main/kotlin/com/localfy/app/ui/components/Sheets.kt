package com.localfy.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.data.Song
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.LocalContainer
import com.localfy.app.ui.PointerAnchor
import com.localfy.app.ui.Routes
import com.localfy.app.ui.SongMenuExtras
import com.localfy.app.ui.Toasts
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.art.artKey
import com.localfy.app.ui.formatFileSize
import com.localfy.app.ui.theme.LocalfyColors
import com.localfy.app.ui.typingFocus
import kotlinx.coroutines.launch

/** Opens the menu where the pointer pressed (right-click or the ⋮ button), kept inside the window. */
private class AnchorPositionProvider(private val anchor: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = if (anchor.x + popupContentSize.width <= windowSize.width) anchor.x else (anchor.x - popupContentSize.width).coerceAtLeast(0)
        val y = (if (anchor.y + popupContentSize.height <= windowSize.height) anchor.y else anchor.y - popupContentSize.height)
            .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}

/**
 * The song's context menu: the phone's bottom sheet, as a desktop pop-up menu at the pointer.
 * Same actions, same order.
 */
@Composable
fun SongMenuSheet(song: Song, extras: SongMenuExtras, onDismiss: () -> Unit, onNavigated: () -> Unit) {
    val app = LocalApp.current
    val nativeApp = LocalContainer.current
    val streamTrack = remember(song.id) { nativeApp.musicStreams.track(song) }
    var info by remember { mutableStateOf(false) }
    val anchor = remember { PointerAnchor.lastPress.let { IntOffset(it.x.toInt(), it.y.toInt()) } }
    val maxHeight = with(LocalDensity.current) { 640.dp }

    if (!info) Popup(
        popupPositionProvider = remember(anchor) { AnchorPositionProvider(anchor) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
        onKeyEvent = { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onDismiss(); true } else false },
    ) {
        Surface(
            Modifier.width(300.dp).heightIn(max = maxHeight).padding(4.dp),
            shape = RoundedCornerShape(12.dp),
            color = LocalfyColors.SurfaceHigh,
            contentColor = LocalfyColors.TextPrimary,
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(song.artKey, Modifier.size(44.dp), RoundedCornerShape(6.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider(color = LocalfyColors.SurfaceHighest, modifier = Modifier.padding(vertical = 4.dp))
                if (streamTrack != null) {
                    val saved = nativeApp.musicStreams.contains(streamTrack)
                    MenuItem(if (saved) Icons.Rounded.RemoveCircleOutline else Icons.Rounded.Add, if (saved) "Remove from Library" else "Add to Library") {
                        if (saved) nativeApp.musicStreams.remove(listOf(streamTrack)) else nativeApp.musicStreams.save(listOf(streamTrack))
                        onDismiss()
                    }
                    MenuItem(Icons.Rounded.Download, "Download") {
                        nativeApp.musicStreams.save(listOf(streamTrack))
                        nativeApp.appScope.launch { Toasts.show(nativeApp.musicDownloads.enqueue(listOf(streamTrack))) }; onDismiss()
                    }
                }
                MenuItem(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next") { app.player.playNext(listOf(song)); onDismiss() }
                MenuItem(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue") { if (app.player.addToQueue(listOf(song))) Toasts.show("Added to queue"); onDismiss() }
                if (!song.isPodcast) MenuItem(Icons.Rounded.Radio, "Go to song radio") {
                    onDismiss()
                    app.player.playSongs(app.taste.songRadio(song), 0, shuffle = false, source = "${song.title} Radio")
                }
                if (!song.isPodcast && !song.isAudiobook) MenuItem(Icons.Rounded.Share, "Share with friends") {
                    runCatching { val playlist = nativeApp.social.create(song.title, listOf(song), nativeApp.musicStreams, "song"); onDismiss(); onNavigated(); app.navigate(Routes.sharedPlaylist(playlist.key)) }
                        .onFailure { Toasts.show(it.message) }
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
    }
    if (info) SongInfoDialog(song) { info = false; onDismiss() }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = LocalfyColors.TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SongInfoDialog(song: Song, onDismiss: () -> Unit) {
    val app = LocalApp.current
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
        "Size" to formatFileSize(song.sizeBytes),
        "Location" to (com.localfy.app.ui.localFileOf(song.sourceUri)?.path ?: "/${song.folder}${song.fileName}"),
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
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(v, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun AddToPlaylistDialog(songs: List<Song>, onDismiss: () -> Unit) {
    val app = LocalApp.current
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
                                    Toasts.show("Added to ${p.name}")
                                    onDismiss()
                                } catch (error: Exception) {
                                    if (error is kotlinx.coroutines.CancellationException) throw error
                                    Toasts.show("Couldn't add songs. Please try again.")
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
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    fun create() {
        if (saving) return
        saving = true
        val finalName = name.ifBlank { "My playlist #${app.repo.playlists.value.size + 1}" }
        scope.launch {
            try {
                val id = app.repo.createPlaylist(finalName, songIds)
                Toasts.show(if (songIds.isEmpty()) "Created $finalName" else "Added to $finalName")
                onDismiss(); onCreated(id)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Toasts.show("Couldn't create the playlist. Please try again.")
            } finally { saving = false }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Give your playlist a name") },
        text = {
            OutlinedTextField(
                name, { name = it }, singleLine = true, modifier = Modifier.typingFocus(),
                placeholder = { Text("My playlist #${app.repo.playlists.value.size + 1}") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { create() }),
            )
        },
        confirmButton = { TextButton(enabled = !saving, onClick = { create() }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
