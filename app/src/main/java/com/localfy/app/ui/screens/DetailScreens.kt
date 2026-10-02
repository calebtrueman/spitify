package com.localfy.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Radio
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.data.SmartCollection
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.Routes
import com.localfy.app.ui.SongMenuExtras
import com.localfy.app.ui.art.artKey
import com.localfy.app.ui.components.EmptyState
import com.localfy.app.ui.components.MediaTile
import com.localfy.app.ui.components.SectionHeader
import com.localfy.app.ui.components.FittedTileRow
import com.localfy.app.ui.components.TileData
import com.localfy.app.ui.components.songCount
import com.localfy.app.ui.theme.LocalfyColors

@Composable
fun AlbumScreen(albumId: Long) {
    val app = LocalApp.current
    val library by app.repo.library.collectAsStateWithLifecycle()
    val album = library.albumById[albumId] ?: return EmptyState("Album not found", "It may have been removed from this device.")
    val downloads = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.localfy.app.LocalfyApp).musicDownloads
    val jobs by downloads.jobs.collectAsStateWithLifecycle()
    val streams = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.localfy.app.LocalfyApp).musicStreams
    val catalog = (album.songs.mapNotNull(streams::track) + jobs.map { com.localfy.app.data.music.Monochrome.parseTrack(org.json.JSONObject(it.trackJson)) }.filterNotNull()).firstOrNull {
        com.localfy.app.data.music.SearchMatch.fold(it.album) == com.localfy.app.data.music.SearchMatch.fold(album.title) &&
            com.localfy.app.data.music.SearchMatch.fold(it.artist) == com.localfy.app.data.music.SearchMatch.fold(album.artist) && it.releaseId.isNotEmpty()
    }
    if (catalog != null) {
        CatalogAlbumScreen(com.localfy.app.data.music.OnlineAlbum(catalog.releaseId, album.title, album.artist, catalog.artwork))
        return
    }
    val more = library.artistByName[album.artist]?.albums?.filter { it.id != album.id }.orEmpty()
    CollectionScreen(
        title = album.title,
        kindLabel = "Album",
        subtitle = listOfNotNull(album.artist, album.year.takeIf { it > 0 }?.toString()).joinToString(" • "),
        art = album.cover.artKey,
        songs = album.songs,
        trackNumbers = true,
        songSubtitle = { it.artist },
        headerActions = {
            IconButton(onClick = { app.editMetadata(album.songs, true) }) { Icon(Icons.Rounded.Edit, "Edit album info & artwork", tint = LocalfyColors.TextSecondary) }
        },
        afterSongs = {
            if (more.isNotEmpty()) item(key = "more") {
                Column {
                    SectionHeader("More by ${album.artist}", action = "Artist") { app.navigate(Routes.artist(album.artist)) }
                    FittedTileRow(more, key = { it.id }, tileWidth = 148.dp) { a ->
                            TileData("a${a.id}", a.title, a.year.takeIf { it > 0 }?.toString() ?: "Album", a.cover.artKey) {
                                app.navigate(Routes.album(a.id))
                            }
}
                }
            }
        },
    )
}

@Composable
fun ArtistScreen(name: String) {
    val app = LocalApp.current
    val library by app.repo.library.collectAsStateWithLifecycle()
    val stats by app.repo.stats.collectAsStateWithLifecycle()
    val artist = library.artistByName[name] ?: return EmptyState("Artist not found", "")
    // "Popular" is computed from your own listening, not global charts.
    val popular = artist.songs.sortedByDescending { stats[it.id]?.playCount ?: 0 }
    CollectionScreen(
        title = artist.name,
        kindLabel = "Artist",
        subtitle = "${artist.albums.size} albums • ${songCount(artist.songs.size)}",
        art = artist.cover.artKey,
        hero = true,
        songs = popular,
        headerActions = {
            IconButton(onClick = { app.player.playSongs(app.taste.artistRadio(artist.name).ifEmpty { artist.songs }, 0, shuffle = false, source = "${artist.name} Radio") }) {
                Icon(Icons.Rounded.Radio, "${artist.name} Radio", tint = LocalfyColors.TextSecondary)
            }
        },
        songSubtitle = { s -> stats[s.id]?.playCount?.takeIf { it > 0 }?.let { "$it plays • ${s.album}" } ?: s.album },
        beforeSongs = {
            if (artist.albums.isNotEmpty()) item(key = "albums") {
                Column {
                    SectionHeader("Discography")
                    FittedTileRow(artist.albums, key = { it.id }, tileWidth = 148.dp) { a ->
                            TileData("a${a.id}", a.title, a.year.takeIf { it > 0 }?.toString() ?: "Album", a.cover.artKey) {
                                app.navigate(Routes.album(a.id))
                            }
}
                    SectionHeader("Popular in your library")
                }
            }
        },
    )
}

@Composable
fun PlaylistScreen(id: Long) {
    val app = LocalApp.current
    val playlists by app.repo.playlists.collectAsStateWithLifecycle()
    val playlist = playlists.firstOrNull { it.id == id } ?: return
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    CollectionScreen(
        title = playlist.name,
        kindLabel = "Playlist",
        subtitle = "Your playlist",
        art = playlist.songs.firstOrNull()?.artKey,
        songs = playlist.songs,
        extrasFor = { i, _ -> SongMenuExtras("Remove from this playlist") { app.repo.removeFromPlaylist(id, i) } },
        headerActions = {
            IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, "Rename", tint = LocalfyColors.TextSecondary) }
            IconButton(onClick = { deleting = true }) { Icon(Icons.Rounded.DeleteOutline, "Delete playlist", tint = LocalfyColors.TextSecondary) }
        },
        emptyText = "Add songs from any song’s ⋮ menu.",
    )

    if (renaming) {
        var text by remember { mutableStateOf(playlist.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename playlist") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { app.repo.renamePlaylist(id, text); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete “${playlist.name}”?") },
            text = { Text("The songs stay on your device; only the playlist is removed.") },
            confirmButton = {
                TextButton(onClick = { deleting = false; app.nav.popBackStack(); app.repo.deletePlaylist(id) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SmartScreen(kindName: String) {
    val app = LocalApp.current
    val smart by app.repo.smart.collectAsStateWithLifecycle()
    val kind = runCatching { SmartCollection.Kind.valueOf(kindName) }.getOrNull() ?: return
    var sort by rememberSaveable { mutableStateOf("Title") }
    var sortOpen by remember { mutableStateOf(false) }
    val stats by app.repo.stats.collectAsStateWithLifecycle()
    val songs = smart[kind]?.songs.orEmpty().let { source ->
        if (kind != SmartCollection.Kind.AllSongs) source else when (sort) {
            "Most Played" -> source.sortedWith(compareByDescending<com.localfy.app.data.Song> { stats[it.id]?.playCount ?: 0 }.thenBy { it.title.lowercase() }.thenBy { it.id })
            "Recently Added" -> source.sortedWith(compareByDescending<com.localfy.app.data.Song> { it.dateAddedSec }.thenBy { it.title.lowercase() })
            "Artist" -> source.sortedWith(compareBy<com.localfy.app.data.Song> { it.artist.lowercase() }.thenBy { it.title.lowercase() })
            "Album" -> source.sortedWith(compareBy<com.localfy.app.data.Song> { it.album.lowercase() }.thenBy { it.disc }.thenBy { it.track })
            else -> source.sortedWith(compareBy<com.localfy.app.data.Song> { it.title.lowercase() }.thenBy { it.id })
        }
    }
    CollectionScreen(
        title = kind.title,
        kindLabel = "Smart playlist",
        subtitle = kind.subtitle,
        art = songs.firstOrNull()?.artKey,
        songs = songs,
        headerActions = {
            if (kind == SmartCollection.Kind.AllSongs) androidx.compose.foundation.layout.Box {
                TextButton(onClick = { sortOpen = true }) { Text("Sort: $sort") }
                androidx.compose.material3.DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    listOf("Title", "Artist", "Album", "Recently Added", "Most Played").forEach { choice ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(choice) }, onClick = { sort = choice; sortOpen = false })
                    }
                }
            }
        },
        emptyText = when (kind) {
            SmartCollection.Kind.AllSongs -> "Download or import music to add it to your library."
            SmartCollection.Kind.MostPlayed, SmartCollection.Kind.RecentlyPlayed -> "Listen to a few songs and this fills itself in."
            else -> "Nothing matches yet."
        },
    )
}

@Composable
fun MixScreen(key: String) {
    val app = LocalApp.current
    val mixes by app.repo.mixes.collectAsStateWithLifecycle()
    val mix = mixes.firstOrNull { it.key == key } ?: return EmptyState("Mix not ready", "Keep listening — this one refreshes soon.")
    CollectionScreen(
        mix.title, "Made for you", mix.description, mix.cover.artKey, mix.songs,
        cover = { m -> com.localfy.app.ui.components.MixCover(mix, m) },
        beforeSongs = {
            if (mix.why != null || mix.refresh != null) item(key = "why") {
                Text(
                    listOfNotNull(mix.why, mix.refresh).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = LocalfyColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        },
    )
}

@Composable
fun GenreScreen(name: String) {
    val app = LocalApp.current
    val library by app.repo.library.collectAsStateWithLifecycle()
    val genre = library.genres.firstOrNull { it.name == name } ?: return
    CollectionScreen(genre.name, "Genre", "Every ${genre.name} track on this device", genre.songs.first().artKey, genre.songs)
}

@Composable
fun FolderScreen(path: String) {
    val app = LocalApp.current
    val library by app.repo.library.collectAsStateWithLifecycle()
    val folder = library.folders.firstOrNull { it.path == path } ?: return
    CollectionScreen(folder.name, "Folder", "/${folder.path}", folder.songs.first().artKey, folder.songs)
}

@Composable
fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = LocalfyColors.TextSecondary, modifier = Modifier.padding(16.dp))
    Spacer(Modifier.height(4.dp))
}
