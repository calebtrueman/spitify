package com.localfy.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.rounded.Download
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.Routes
import com.localfy.app.ui.SongMenuExtras
import com.localfy.app.ui.art.ArtKey
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.art.artKey
import com.localfy.app.ui.components.SongRow
import com.localfy.app.data.Song
import com.localfy.app.data.Album
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.LocalfyApp
import com.localfy.app.data.music.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineMusicPanel(query: String) {
    val app = LocalContext.current.applicationContext as LocalfyApp
    val actions = LocalApp.current
    val library by actions.repo.library.collectAsStateWithLifecycle()
    val downloads = app.musicDownloads
    val jobs by downloads.jobs.collectAsStateWithLifecycle()
    val wifi by downloads.wifiOnly.collectAsStateWithLifecycle()
    val message by downloads.message.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tracks by remember { mutableStateOf(emptyList<OnlineTrack>()) }
    var albums by remember { mutableStateOf(emptyList<OnlineAlbum>()) }
    var albumMode by remember { mutableStateOf(false) }
    var selectedAlbum by remember { mutableStateOf<OnlineAlbum?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var albumNotice by remember { mutableStateOf<String?>(null) }
    var addingAlbum by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try { downloads.reconcile() }
        catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "Could not update downloads." }
    }

    LaunchedEffect(query, albumMode, selectedAlbum) {
        tracks = emptyList(); albums = emptyList(); error = null; loading = false
        if (query.trim().length < 2 && selectedAlbum == null) return@LaunchedEffect
        loading = true
        try {
            delay(400)
            val selected = selectedAlbum
            if (selected != null) tracks = Monochrome.album(selected.id)
            else if (albumMode) albums = Monochrome.albums(query.trim())
            else tracks = Monochrome.search(query.trim())
        } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "Search failed. Try again." }
        finally { loading = false }
    }
    LaunchedEffect(query) { selectedAlbum = null; albumNotice = null }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Results", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { showDownloads = true }) { Text("Downloads") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !albumMode, onClick = { albumMode = false; selectedAlbum = null }, label = { Text("Songs") })
            FilterChip(selected = albumMode, onClick = { albumMode = true; selectedAlbum = null }, label = { Text("Albums") })
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text("Could not add online matches. Your library results are still available.", color = MaterialTheme.colorScheme.error) }
        if (query.trim().length < 2 && selectedAlbum == null) Text("Search for a song or album to download to your library.")

        selectedAlbum?.let { album ->
            Text(album.title, style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { addingAlbum = true; albumNotice = "Adding album…"; scope.launch {
                try { albumNotice = downloads.enqueue(tracks) } catch (e: Exception) { albumNotice = e.message ?: "Could not add this album. Try again." }
                finally { addingAlbum = false }
            } }, enabled = tracks.isNotEmpty() && !addingAlbum) {
                if (addingAlbum) CircularProgressIndicator(Modifier.size(24.dp))
                else Icon(androidx.compose.material.icons.Icons.Rounded.Download, "Download album", Modifier.size(32.dp))
            }
            albumNotice?.let { Text(it) }
        }
        if (selectedAlbum != null) {
            TextButton(onClick = { selectedAlbum = null }) { Text("Back to results") }
            tracks.forEach { track -> key(track.id) { OnlineMusicRow(track) } }
        } else if (albumMode) {
            val local = library.albums.mapNotNull { a -> SearchMatch.score(query, a.title, a.artist)?.let { AlbumMatch(a.title, a.artist, it, local = a) } }
            val remote = albums.filter { a -> local.none { SearchMatch.fold(it.title) == SearchMatch.fold(a.title) && SearchMatch.fold(it.artist) == SearchMatch.fold(a.artist) } }
                .mapNotNull { a -> SearchMatch.score(query, a.title, a.artist)?.let { AlbumMatch(a.title, a.artist, it, remote = a) } }
            val results = (local + remote).sortedWith(compareByDescending<AlbumMatch> { it.score }.thenBy { SearchMatch.fold(it.title + " " + it.artist) })
            if (results.isEmpty() && !loading) Text("No albums found.")
            results.take(60).forEach { result -> key(result.local?.id?.let { "local:$it" } ?: "online:${result.remote!!.id}") {
                Row(Modifier.fillMaxWidth().clickable {
                    result.local?.let { actions.navigate(Routes.album(it.id)) } ?: run { selectedAlbum = result.remote }
                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (result.local != null) Artwork(result.local.cover.artKey, Modifier.size(52.dp), RoundedCornerShape(6.dp))
                    else SearchCover("album:${result.remote!!.id}", result.title, result.artist, result.remote.artwork)
                    Column(Modifier.weight(1f)) { Text(result.title); Text(result.artist, style = MaterialTheme.typography.bodySmall) }
                }
            } }
        } else {
            val local = library.songs.mapNotNull { s -> SearchMatch.score(query, s.title, s.artist, s.album)?.let { SongMatch(s.title, s.artist, it, local = s) } }
            val remote = tracks.filter { t -> t.playable && library.songs.none { SearchMatch.sameSong(it.title, it.artist, it.durationMs, t.title, t.artist, t.durationMs) } }
                .mapNotNull { t -> SearchMatch.score(query, t.title, t.artist, t.album)?.let { SongMatch(t.title, t.artist, it, remote = t) } }
            val results = (local + remote).sortedWith(compareByDescending<SongMatch> { it.score }.thenBy { SearchMatch.fold(it.title + " " + it.artist) })
            val playable = results.mapNotNull { it.local }
            if (results.isEmpty() && !loading) Text("No songs found.")
            results.take(60).forEach { result -> key(result.local?.id?.let { "local:$it" } ?: "online:${result.remote!!.id}") {
                result.local?.let { song -> SongRow(song = song, onClick = { actions.player.playSongs(playable, playable.indexOf(song), shuffle = false, source = "Search: $query") }, onMore = { actions.openSongMenu(song, SongMenuExtras()) }) }
                    ?: OnlineMusicRow(result.remote!!)
            } }
        }
    }
    if (showDownloads) ModalBottomSheet(onDismissRequest = { showDownloads = false }) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
            item {
                Text("Downloads", style = MaterialTheme.typography.headlineSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Wi-Fi only for new downloads")
                    Switch(checked = wifi, onCheckedChange = downloads::setWifiOnly)
                }
                Text("Completed songs stay in your library and play offline.")
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (jobs.isEmpty()) Text("No downloads yet.")
            }
            items(jobs.reversed(), key = { it.id }) { job -> OnlineMusicRow(job.track()) }
        }
    }
}

@Composable
internal fun OnlineMusicRow(track: OnlineTrack) {
    val downloads = (LocalContext.current.applicationContext as LocalfyApp).musicDownloads
    val jobs by downloads.jobs.collectAsStateWithLifecycle()
    val progress by downloads.progress.collectAsStateWithLifecycle()
    val job = jobs.firstOrNull { it.id == track.id }
    var preparing by remember(track.id) { mutableStateOf(false) }
    var error by remember(track.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchCover("track:${track.id}", track.album.ifBlank { track.title }, track.artist, track.artwork)
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleSmall)
                Text(track.artist, style = MaterialTheme.typography.bodySmall)
                job?.quality?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
            when {
                job?.state == "complete" -> Text("Downloaded", style = MaterialTheme.typography.labelSmall)
                job?.active == true -> TextButton(onClick = { downloads.cancel(track.id) }) { Text("Cancel") }
                else -> TextButton(enabled = !preparing, onClick = {
                    preparing = true; error = null
                    scope.launch {
                        try {
                            val enriched = if (track.album.isEmpty() && track.releaseId.isNotEmpty()) {
                                Monochrome.album(track.releaseId).firstOrNull { it.id == track.id }
                                    ?: error("This song is no longer in that album.")
                            } else track
                            error = downloads.enqueue(listOf(enriched))
                        } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "Could not start the download." }
                        finally { preparing = false }
                    }
                }) { Text(if (preparing) "Preparing…" else "Add to library") }
            }
        }
        if (job?.state == "downloading") LinearProgressIndicator(progress = { progress[track.id] ?: 0f }, modifier = Modifier.fillMaxWidth())
        if (job?.state == "queued") Text("Queued", style = MaterialTheme.typography.labelSmall)
        if (job?.state == "checking") Text("Checking audio…", style = MaterialTheme.typography.labelSmall)
        (error ?: job?.error)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

private data class SongMatch(val title: String, val artist: String, val score: Int, val local: Song? = null, val remote: OnlineTrack? = null)
private data class AlbumMatch(val title: String, val artist: String, val score: Int, val local: Album? = null, val remote: OnlineAlbum? = null)

@Composable
private fun SearchCover(id: String, album: String, artist: String, artwork: String?) {
    val app = LocalContext.current.applicationContext as LocalfyApp
    var source by remember(id, artwork) { mutableStateOf(artwork) }
    LaunchedEffect(id, artwork) {
        if (source.isNullOrBlank()) source = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.onlineArt.fetch(("search:$artist:$album").hashCode().toLong() - 20_000_000_000L, artist, album)?.toURI()?.toString()
        }
    }
    Artwork(ArtKey(id.hashCode().toLong(), id.hashCode().toLong(), source), Modifier.size(52.dp), RoundedCornerShape(6.dp))
}
