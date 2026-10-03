package com.localfy.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.LocalfyApp
import com.localfy.app.data.music.*
import com.localfy.app.data.social.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SharedPlaylistEditor(playlist: SharedPlaylist) {
    val app = LocalContext.current.applicationContext as LocalfyApp
    val scope = rememberCoroutineScope()
    val library by app.library.library.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    var name by remember(playlist.key) { mutableStateOf(playlist.name) }
    var description by remember(playlist.key) { mutableStateOf(playlist.description) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<OnlineTrack>()) }
    var selected by remember { mutableStateOf(emptyMap<String, SharedTrack>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun send(edit: SharedEdit) { scope.launch { busy = true; try { app.social.edit(edit); selected = emptyMap(); message = if (playlist.owner == app.social.publicKey) "Saved." else "Sent to the owner. Changes appear when their app accepts them." } catch (e: Exception) { if (e is CancellationException) throw e; message = e.message } finally { busy = false } } }
    LaunchedEffect(query) {
        results = emptyList()
        if (query.trim().length >= 2) try { delay(350); results = Monochrome.search(query) } catch (e: Exception) { if (e is CancellationException) throw e; message = e.message }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }) { Text(if (playlist.kind == "mix") "Contribute songs" else "Edit playlist") }
        if (expanded) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") })
            OutlinedTextField(description, { description = it }, label = { Text("Description") })
            Button(enabled = !busy, onClick = { send(SharedEdit(playlistID = playlist.id, owner = playlist.owner, action = "rename", name = name, description = description)) }) { Text("Save details") }
            if (playlist.kind == "mix") Text("Pick up to 100 songs. Sending replaces your previous contribution. The mix takes turns between each person's songs and skips repeats.")
            OutlinedTextField(query, { query = it }, label = { Text("Find a song") })
            results.forEach { track ->
                Row { Checkbox("remote:${track.id}" in selected, { checked -> selected = if (!checked) selected - "remote:${track.id}" else if (selected.size < 100) selected + ("remote:${track.id}" to SharedTrack(title = track.title, artist = track.artist, album = track.album, durationMs = track.durationMs, sourceID = track.id, releaseID = track.releaseId, artwork = track.artwork)) else selected }, modifier = Modifier.semantics { contentDescription = "Select ${track.title} by ${track.artist}" }); Column { Text(track.title); Text(track.artist) } }
            }
            Text("${selected.size} selected")
            if (selected.isNotEmpty()) TextButton(onClick = { selected = emptyMap() }) { Text("Clear selection") }
            library.songs.filter { query.isBlank() || SearchMatch.score(query, it.title, it.artist, it.album) != null }.take(30).forEach { song ->
                Row {
                    Checkbox("local:${song.id}" in selected, { checked -> selected = if (!checked) selected - "local:${song.id}" else if (selected.size < 100) selected + ("local:${song.id}" to SharedTrack.from(song, app.musicStreams)) else selected }, modifier = Modifier.semantics { contentDescription = "Select ${song.title} by ${song.artist}" })
                    Column { Text(song.title); Text("Your library · ${song.artist}") }
                }
            }
            Button(enabled = !busy && selected.isNotEmpty(), onClick = {
                val tracks = selected.keys.sorted().mapNotNull { selected[it] }
                send(SharedEdit(playlistID = playlist.id, owner = playlist.owner, action = if (playlist.kind == "mix") "mix" else "add", tracks = tracks))
            }) { Text(if (playlist.kind == "mix") "Send my contribution" else "Add selected songs") }
            if (playlist.kind != "mix") playlist.tracks.forEachIndexed { index, track ->
                Column {
                    Text(track.title)
                    Row {
                        TextButton(enabled = !busy && index > 0, onClick = { val ids = playlist.tracks.map { it.id }.toMutableList(); java.util.Collections.swap(ids, index, index - 1); send(SharedEdit(playlistID = playlist.id, owner = playlist.owner, action = "reorder", trackIDs = ids)) }) { Text("Move up") }
                        TextButton(enabled = !busy, onClick = { send(SharedEdit(playlistID = playlist.id, owner = playlist.owner, action = "remove", trackIDs = listOf(track.id))) }) { Text("Remove") }
                    }
                }
            }
            message?.let { Text(it) }
        }
    }
}
