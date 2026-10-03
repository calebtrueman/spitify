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
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
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
fun OnlineMusicPanel(query: String) { MixedSearchPanel(query) }

internal fun savedSong(track: OnlineTrack, songs: List<Song>, jobs: List<MusicDownloadEntity>): Song? {
    val uri = jobs.firstOrNull { it.id == track.id && it.state == "complete" }?.localUri
    return songs.filter { it.sourceUri?.scheme != "spitify" }.firstOrNull { uri != null && it.uri.toString() == uri } ?: songs.firstOrNull {
        it.sourceUri?.scheme != "spitify" && SearchMatch.sameSong(it.title, it.artist, it.durationMs, track.title, track.artist, track.durationMs) &&
            (track.album.isEmpty() || AudioFallback.sameRelease(it.album, track.album))
    }
}

@Composable
private fun DownloadMark(complete: Boolean, active: Boolean, progress: Float? = null) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        if (active) {
            if (progress != null) {
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
    val downloadsContext = LocalContext.current
    val downloads = (downloadsContext.applicationContext as LocalfyApp).musicDownloads
    val jobs by downloads.jobs.collectAsStateWithLifecycle()
    val progress by downloads.progress.collectAsStateWithLifecycle()
    val library by actions.repo.library.collectAsStateWithLifecycle()
    val job = jobs.firstOrNull { it.id == track.id }
    val song = savedSong(track, library.songs, jobs)
    val complete = song != null || job?.state == "complete"
    val fraction = DownloadProgress.fraction(job?.state, progress[track.id])
    var preparing by remember(track.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (onPlay != null) {
        val streams = (LocalContext.current.applicationContext as LocalfyApp).musicStreams
        val playable = song ?: streams.song(track)
        val player = com.localfy.app.ui.player.rememberPlayerState()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SongRow(song = playable, modifier = Modifier.weight(1f), trackNumber = trackNumber, subtitle = track.artist, downloaded = song != null,
                isCurrent = player.currentId == playable.id, isPlaying = player.isPlaying,
                onClick = { streams.register(track); onPlay(playable) }, onMore = { streams.register(track); actions.openSongMenu(playable, SongMenuExtras()) })
            if (song == null) IconButton(enabled = !complete, onClick = {
                if (job?.active == true) downloads.cancel(track.id)
                else { streams.save(listOf(track)); scope.launch { downloads.enqueue(listOf(track)) } }
            }, modifier = Modifier.semantics { contentDescription = if (complete) "Downloaded" else if (job?.active == true) "Cancel download" else "Download ${track.title}" }) {
                DownloadMark(complete, job?.active == true, fraction)
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable {
            actions.navigate(Routes.catalogSong(track))
        }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val textColor = MaterialTheme.colorScheme.onSurface
            if (trackNumber != null) Text(trackNumber.toString(), Modifier.width(26.dp), color = textColor)
            else SearchCover("track:${track.id}", track.album.ifBlank { track.title }, track.artist, track.artwork)
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleSmall, color = textColor)
                Text(track.artist, style = MaterialTheme.typography.bodySmall, color = textColor)
            }
        }
        IconButton(enabled = !complete && !preparing, onClick = {
            if (job?.active == true) downloads.cancel(track.id)
            else {
                preparing = true
                scope.launch {
                    try {
                        val enriched = if (track.album.isEmpty() && track.releaseId.isNotEmpty())
                            runCatching { Monochrome.album(track.releaseId).firstOrNull { it.id == track.id } }.getOrNull() ?: track else track
                        (downloadsContext.applicationContext as LocalfyApp).musicStreams.save(listOf(enriched))
                        downloads.enqueue(listOf(enriched))
                    } finally { preparing = false }
                }
            }
        }, modifier = Modifier.semantics { contentDescription = if (complete) "Downloaded" else if (job?.active == true) "Cancel download" else "Download ${track.title}" }) {
            DownloadMark(complete, preparing || job?.active == true, fraction)
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
        finally {
            tracks = tracks.map { it.copy(album = it.album.ifBlank { album.title }, releaseId = it.releaseId.ifBlank { album.id }, albumArtist = it.albumArtist ?: album.artist, artwork = it.artwork ?: album.artwork) }
            loading = false
        }
    }
    val streams = (LocalContext.current.applicationContext as LocalfyApp).musicStreams
    val saved by streams.saved.collectAsStateWithLifecycle()
    LaunchedEffect(tracks) { tracks.forEach(streams::register) }
    val songs = tracks.map { savedSong(it, library.songs, jobs) ?: streams.song(it) }
    val downloaded = tracks.count { savedSong(it, library.songs, jobs) != null }
    val inLibrary = tracks.isNotEmpty() && tracks.all { track -> saved.any { it.id == MusicStreams.streamId(track.id) } }
    val albumJobs = jobs.filter { j -> tracks.any { it.id == j.id } }
    val active = albumJobs.any { it.active }
    val complete = tracks.isNotEmpty() && tracks.all { track ->
        savedSong(track, library.songs, jobs) != null || albumJobs.any { it.id == track.id && it.state == "complete" }
    }
    val albumProgress = DownloadProgress.album(tracks.map { track ->
        if (savedSong(track, library.songs, jobs) != null) 1f
        else DownloadProgress.fraction(albumJobs.firstOrNull { it.id == track.id }?.state, progress[track.id])
    })
    val art = songs.firstOrNull()?.artKey ?: ArtKey(album.id.hashCode().toLong(), album.id.hashCode().toLong(), single?.artwork ?: album.artwork)
    CollectionScreen(title = single?.title ?: album.title, kindLabel = if (single == null) "Album" else "Song", subtitle = album.artist,
        art = art, songs = songs, trackNumbers = single == null, catalogTracks = tracks,
        headerActions = {
            IconButton(enabled = tracks.isNotEmpty(), onClick = { if (inLibrary) streams.remove(tracks) else streams.save(tracks) }) {
                Icon(if (inLibrary) androidx.compose.material.icons.Icons.Rounded.CheckCircle else androidx.compose.material.icons.Icons.Rounded.AddCircleOutline,
                    if (inLibrary) "Remove from Library" else "Add to Library")
            }
            IconButton(enabled = tracks.isNotEmpty() && !complete && !adding, onClick = {
                if (active) albumJobs.filter { it.active }.forEach { downloads.cancel(it.id) }
                else { streams.save(tracks); adding = true; scope.launch { try { downloads.enqueue(tracks) } finally { adding = false } } }
            }, modifier = Modifier.semantics { contentDescription = if (complete) "Downloaded" else if (active) "Cancel downloads" else if (single == null) "Download album" else "Download song" }) {
                DownloadMark(complete, active || adding, albumProgress)
            }
            if (downloaded > 0) IconButton(onClick = { actions.editMetadata(songs.filter { it.sourceUri?.scheme != "spitify" }, single == null) }) { Icon(androidx.compose.material.icons.Icons.Rounded.Edit, "Edit song details") }
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
