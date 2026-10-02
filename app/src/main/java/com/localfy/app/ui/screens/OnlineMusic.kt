package com.localfy.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    LaunchedEffect(query) { selectedAlbum = null }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("From Monochrome", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { showDownloads = true }) { Text("Downloads") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !albumMode, onClick = { albumMode = false; selectedAlbum = null }, label = { Text("Songs") })
            FilterChip(selected = albumMode, onClick = { albumMode = true; selectedAlbum = null }, label = { Text("Albums") })
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (query.trim().length < 2 && selectedAlbum == null) Text("Search for a song or album to download to your library.")
        else if (!loading && error == null && tracks.isEmpty() && albums.isEmpty()) Text("No matches found.")
        selectedAlbum?.let { album ->
            Text(album.title, style = MaterialTheme.typography.titleMedium)
            Button(onClick = { scope.launch {
                try { downloads.enqueue(tracks) } catch (e: Exception) { error = e.message }
            } }, enabled = tracks.isNotEmpty()) { Text("Download album") }
        }
        albums.forEach { album -> TextButton(onClick = { selectedAlbum = album }) { Text("${album.title} — ${album.artist}") } }
        tracks.forEach { track -> key(track.id) { OnlineMusicRow(track) } }
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
private fun OnlineMusicRow(track: OnlineTrack) {
    val downloads = (LocalContext.current.applicationContext as LocalfyApp).musicDownloads
    val jobs by downloads.jobs.collectAsStateWithLifecycle()
    val progress by downloads.progress.collectAsStateWithLifecycle()
    val job = jobs.firstOrNull { it.id == track.id }
    var preparing by remember(track.id) { mutableStateOf(false) }
    var error by remember(track.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleSmall)
                Text(track.artist, style = MaterialTheme.typography.bodySmall)
                job?.quality?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
            when {
                job?.state == "complete" -> Text("Downloaded", style = MaterialTheme.typography.labelSmall)
                job?.active == true -> TextButton(onClick = { downloads.cancel(track.id) }) { Text("Cancel") }
                else -> TextButton(enabled = track.playable && !preparing, onClick = {
                    preparing = true; error = null
                    scope.launch {
                        try {
                            val enriched = if (track.album.isEmpty() && track.releaseId.isNotEmpty()) {
                                Monochrome.album(track.releaseId).firstOrNull { it.id == track.id }
                                    ?: error("This song is no longer in that album.")
                            } else track
                            downloads.enqueue(listOf(enriched))
                        } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "Could not start the download." }
                        finally { preparing = false }
                    }
                }) { Text(if (preparing) "Preparing…" else "Download") }
            }
        }
        if (job?.state == "downloading") LinearProgressIndicator(progress = { progress[track.id] ?: 0f }, modifier = Modifier.fillMaxWidth())
        if (job?.state == "queued") Text("Queued", style = MaterialTheme.typography.labelSmall)
        if (job?.state == "checking") Text("Checking audio…", style = MaterialTheme.typography.labelSmall)
        (error ?: job?.error)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (!track.playable) Text("Unavailable from this source", style = MaterialTheme.typography.labelSmall)
    }
}
