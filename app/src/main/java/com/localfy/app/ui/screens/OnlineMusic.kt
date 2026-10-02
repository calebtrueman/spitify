package com.localfy.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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

@Composable
fun OnlineMusicPanel(query: String) {
    val actions = LocalApp.current
    val library by actions.repo.library.collectAsStateWithLifecycle()
    var tracks by remember { mutableStateOf(emptyList<OnlineTrack>()) }
    var albums by remember { mutableStateOf(emptyList<OnlineAlbum>()) }
    var albumMode by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(query, albumMode) {
        tracks = emptyList(); albums = emptyList(); failed = false
        if (query.trim().length < 2) { loading = false; return@LaunchedEffect }
        loading = true
        try {
            delay(400)
            if (albumMode) albums = Monochrome.albums(query.trim()) else tracks = Monochrome.search(query.trim())
        } catch (e: Exception) { if (e is CancellationException) throw e; failed = true }
        finally { loading = false }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Results", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !albumMode, onClick = { albumMode = false }, label = { Text("Songs") })
            FilterChip(selected = albumMode, onClick = { albumMode = true }, label = { Text("Albums") })
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed) Text("Couldn't load more results. Your saved music is still here.", style = MaterialTheme.typography.bodySmall)
        if (albumMode) {
            val local = library.albums.mapNotNull { a -> SearchMatch.score(query, a.title, a.artist)?.let { score ->
                val remote = albums.firstOrNull { SearchMatch.fold(it.title) == SearchMatch.fold(a.title) && SearchMatch.fold(it.artist) == SearchMatch.fold(a.artist) }
                AlbumMatch(a.title, a.artist, score, local = a, remote = remote)
            } }
            val remote = albums.filter { a -> local.none { it.remote?.id == a.id } }
                .mapNotNull { a -> SearchMatch.score(query, a.title, a.artist)?.let { AlbumMatch(a.title, a.artist, it, remote = a) } }
            val results = (local + remote).sortedWith(compareByDescending<AlbumMatch> { it.score }.thenBy { SearchMatch.fold(it.title + " " + it.artist) })
            if (results.isEmpty() && !loading) Text("No albums found.")
            results.take(60).forEach { result -> key(result.local?.id?.let { "local:$it" } ?: "online:${result.remote!!.id}") {
                Row(Modifier.fillMaxWidth().clickable {
                    result.remote?.let { actions.navigate(Routes.catalogAlbum(it)) } ?: result.local?.let { actions.navigate(Routes.album(it.id)) }
                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (result.local != null) Artwork(result.local.cover.artKey, Modifier.size(52.dp), RoundedCornerShape(6.dp))
                    else SearchCover("album:${result.remote!!.id}", result.title, result.artist, result.remote.artwork)
                    Column(Modifier.weight(1f)) { Text(result.title); Text(result.artist, style = MaterialTheme.typography.bodySmall) }
                }
            } }
        } else {
            val local = library.songs.mapNotNull { song -> SearchMatch.score(query, song.title, song.artist, song.album)?.let { SongMatch(song.title, song.artist, it, local = song) } }
            val remote = tracks.filter { track -> library.songs.none { SearchMatch.sameSong(it.title, it.artist, it.durationMs, track.title, track.artist, track.durationMs) } }
                .mapNotNull { track -> SearchMatch.score(query, track.title, track.artist, track.album)?.let { SongMatch(track.title, track.artist, it, remote = track) } }
            val results = (local + remote).sortedWith(compareByDescending<SongMatch> { it.score }.thenBy { SearchMatch.fold(it.title + " " + it.artist) })
            val playable = results.mapNotNull { it.local }
            if (results.isEmpty() && !loading) Text("No songs found.")
            results.take(60).forEach { result -> key(result.local?.id?.let { "local:$it" } ?: "online:${result.remote!!.id}") {
                result.local?.let { song -> SongRow(song = song, onClick = { actions.player.playSongs(playable, playable.indexOf(song), shuffle = false, source = "Search: $query") }, onMore = { actions.openSongMenu(song, SongMenuExtras()) }) }
                    ?: OnlineMusicRow(result.remote!!)
            } }
        }
    }
}

internal fun savedSong(track: OnlineTrack, songs: List<Song>, jobs: List<MusicDownloadEntity>): Song? {
    val uri = jobs.firstOrNull { it.id == track.id && it.state == "complete" }?.localUri
    return songs.firstOrNull { uri != null && it.uri.toString() == uri } ?: songs.firstOrNull {
        SearchMatch.sameSong(it.title, it.artist, it.durationMs, track.title, track.artist, track.durationMs) &&
            (track.album.isEmpty() || AudioFallback.sameRelease(it.album, track.album))
    }
}

@Composable
private fun DownloadMark(complete: Boolean, active: Boolean, progress: Float = 0f) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (active) {
            if (progress > 0) {
                CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxSize(), strokeWidth = 2.dp)
                Icon(androidx.compose.material.icons.Icons.Rounded.Download, null, Modifier.size(12.dp))
            } else CircularProgressIndicator(Modifier.fillMaxSize(), strokeWidth = 2.dp)
        } else Icon(if (complete) androidx.compose.material.icons.Icons.Rounded.DownloadForOffline else androidx.compose.material.icons.Icons.Rounded.Downloading,
            null, tint = if (complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun OnlineMusicRow(track: OnlineTrack, trackNumber: Int? = null, onPlay: ((Song) -> Unit)? = null) {
    val actions = LocalApp.current
    val downloads = (LocalContext.current.applicationContext as LocalfyApp).musicDownloads
    val jobs by downloads.jobs.collectAsStateWithLifecycle()
    val progress by downloads.progress.collectAsStateWithLifecycle()
    val library by actions.repo.library.collectAsStateWithLifecycle()
    val job = jobs.firstOrNull { it.id == track.id }
    val song = savedSong(track, library.songs, jobs)
    var preparing by remember(track.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (song != null && onPlay != null) {
        val player = com.localfy.app.ui.player.rememberPlayerState()
        SongRow(song = song, trackNumber = trackNumber, subtitle = track.artist, downloaded = true,
            isCurrent = player.currentId == song.id, isPlaying = player.isPlaying,
            onClick = { onPlay(song) }, onMore = { actions.openSongMenu(song, SongMenuExtras()) })
        return
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = if (onPlay == null) 0.dp else 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(enabled = onPlay == null || song != null) {
            if (onPlay != null) song?.let(onPlay) else actions.navigate(Routes.catalogSong(track))
        }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val textColor = if (song == null) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface
            if (trackNumber != null) Text(trackNumber.toString(), Modifier.width(26.dp), color = textColor)
            else SearchCover("track:${track.id}", track.album.ifBlank { track.title }, track.artist, track.artwork)
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleSmall, color = textColor)
                Text(track.artist, style = MaterialTheme.typography.bodySmall, color = textColor)
            }
        }
        IconButton(enabled = song == null && !preparing, onClick = {
            if (job?.active == true) downloads.cancel(track.id)
            else {
                preparing = true
                scope.launch {
                    try {
                        val enriched = if (track.album.isEmpty() && track.releaseId.isNotEmpty())
                            runCatching { Monochrome.album(track.releaseId).firstOrNull { it.id == track.id } }.getOrNull() ?: track else track
                        downloads.enqueue(listOf(enriched))
                    } finally { preparing = false }
                }
            }
        }, modifier = Modifier.semantics { contentDescription = if (song != null) "Downloaded" else if (job?.active == true) "Cancel download" else "Download ${track.title}" }) {
            DownloadMark(song != null, preparing || job?.active == true, progress[track.id] ?: 0f)
        }
    }
}

@Composable
fun CatalogPage(payload: String, isSong: Boolean) {
    val data = remember(payload) { runCatching { org.json.JSONObject(payload) }.getOrNull() }
    if (data == null) { com.localfy.app.ui.components.EmptyState("Not found", "Please search again."); return }
    val track = remember(payload) { if (isSong) Monochrome.parseTrack(data) else null }
    val album = remember(payload) {
        if (track != null) OnlineAlbum(track.releaseId, track.album, track.artist, track.artwork)
        else OnlineAlbum(data.optString("id"), data.optString("title"), data.optString("artist"), data.optString("artwork").takeIf { it.startsWith("https://") })
    }
    CatalogAlbumScreen(album, track)
}

@Composable
fun CatalogAlbumScreen(album: OnlineAlbum, single: OnlineTrack? = null) {
    val actions = LocalApp.current
    val downloads = (LocalContext.current.applicationContext as LocalfyApp).musicDownloads
    val library by actions.repo.library.collectAsStateWithLifecycle()
    val jobs by downloads.jobs.collectAsStateWithLifecycle()
    val progress by downloads.progress.collectAsStateWithLifecycle()
    var tracks by remember(album.id, single?.id) { mutableStateOf(single?.let(::listOf) ?: jobs.map { it.track() }.filter { it.releaseId == album.id }.sortedWith(compareBy({ it.disc }, { it.track }))) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(album.id, single?.id, reload) {
        loading = true; failed = false
        try {
            val loaded = Monochrome.album(album.id)
            tracks = if (single == null) loaded else loaded.filter { it.id == single.id }.ifEmpty { listOf(single) }
        } catch (e: Exception) { if (e is CancellationException) throw e; failed = tracks.isEmpty() }
        finally { loading = false }
    }
    val songs = tracks.mapNotNull { savedSong(it, library.songs, jobs) }
    val albumJobs = jobs.filter { j -> tracks.any { it.id == j.id } }
    val active = albumJobs.any { it.active }
    val complete = tracks.isNotEmpty() && songs.size == tracks.size
    val art = songs.firstOrNull()?.artKey ?: ArtKey(album.id.hashCode().toLong(), album.id.hashCode().toLong(), single?.artwork ?: album.artwork)
    CollectionScreen(title = single?.title ?: album.title, kindLabel = if (single == null) "Album" else "Song", subtitle = album.artist,
        art = art, songs = songs, trackNumbers = single == null, catalogTracks = tracks,
        headerActions = {
            IconButton(enabled = tracks.isNotEmpty() && !complete && !adding, onClick = {
                if (active) albumJobs.filter { it.active }.forEach { downloads.cancel(it.id) }
                else { adding = true; scope.launch { try { downloads.enqueue(tracks) } finally { adding = false } } }
            }, modifier = Modifier.semantics { contentDescription = if (complete) "Downloaded" else if (active) "Cancel downloads" else if (single == null) "Download album" else "Download song" }) {
                DownloadMark(complete, active || adding, if (tracks.isEmpty()) 0f else (songs.size + albumJobs.filter { it.active }.sumOf { (progress[it.id] ?: 0f).toDouble() }).toFloat() / tracks.size)
            }
            if (songs.isNotEmpty()) IconButton(onClick = { actions.editMetadata(songs, single == null) }) { Icon(androidx.compose.material.icons.Icons.Rounded.Edit, "Edit song details") }
        }, beforeSongs = {
            if (loading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            if (failed) item { TextButton(onClick = { reload++ }) { Text("Couldn't load songs. Try again") } }
        })
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
