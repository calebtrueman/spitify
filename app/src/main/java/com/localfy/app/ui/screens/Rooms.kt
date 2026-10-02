package com.localfy.app.ui.screens

import android.content.Intent
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
import com.localfy.app.data.social.*
import com.localfy.app.ui.LocalApp
import kotlinx.coroutines.*

@Composable
fun RoomsScreen(invite: String? = null) {
    val context = LocalContext.current
    val app = context.applicationContext as LocalfyApp
    val actions = LocalApp.current
    val revision by app.social.revision.collectAsStateWithLifecycle()
    val roomMessage by app.rooms.message.collectAsStateWithLifecycle()
    val room = remember(revision) { app.rooms.room }
    val requested = remember(revision) { app.social.rooms.requestedKey }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var link by remember(invite) { mutableStateOf(invite.orEmpty()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<OnlineTrack>()) }
    var message by remember { mutableStateOf<String?>(null) }
    fun perform(action: suspend () -> Unit) { scope.launch { try { action(); message = null } catch (e: Exception) { if (e is CancellationException) throw e; message = e.message } } }
    LaunchedEffect(query) {
        results = emptyList()
        if (query.trim().length >= 2) try { delay(350); results = Monochrome.search(query) } catch (e: Exception) { if (e is CancellationException) throw e; message = e.message }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp, 24.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = { actions.nav.popBackStack() }) { Text("Back") }; Text("Rooms", style = MaterialTheme.typography.headlineMedium) }
        if (room != null) {
            item {
                Text(room.name, style = MaterialTheme.typography.titleLarge)
                Text(if (app.rooms.isHost) "You host this Room" else "Listening with the host")
                Text("Everyone streams their own copy. An unavailable song may leave one person silent. Rooms need the host's app to stay connected.")
                TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, SocialLink("room", room.host, room.id).url), "Invite to Room")) }) { Text("Invite someone") }
                Text("${room.members.size} guests")
                if (app.rooms.isHost) Row { Text("Let guests control playback", Modifier.weight(1f)); Switch(room.allowControls, { perform { app.rooms.setControls(it) } }) }
                if (app.rooms.isHost || room.allowControls) Row {
                    Button(onClick = { perform { app.rooms.control(playing = !room.playing) } }) { Text(if (room.playing) "Pause" else "Play") }
                    TextButton(onClick = { perform { app.rooms.control(next = true) } }) { Text("Next") }
                    TextButton(onClick = { perform { app.rooms.control(position = 0) } }) { Text("Restart") }
                }
                TextButton(onClick = { perform { app.rooms.leave() } }) { Text(if (app.rooms.isHost) "End Room" else "Leave Room") }
            }
            if (app.rooms.isHost) items(app.social.rooms.requests.filter { it.request.action == "join" && it.request.roomID == room.id }, key = { it.request.id }) { incoming ->
                Column {
                    Text(incoming.request.name.ifBlank { "Guest ${incoming.sender.take(8)}" })
                    Row { TextButton(onClick = { perform { app.rooms.approve(incoming, true) } }) { Text("Accept") }; TextButton(onClick = { perform { app.rooms.approve(incoming, false) } }) { Text("Decline") } }
                }
            }
            item { Text("Shared queue", style = MaterialTheme.typography.titleLarge) }
            items(room.queue, key = { it.id }) { track -> Column { Text((if (room.currentID == track.id) "Playing · " else "") + track.title); Text(track.artist); if (app.rooms.isHost || room.allowControls) TextButton(onClick = { perform { app.rooms.remove(track.id) } }) { Text("Remove") } } }
            item { OutlinedTextField(query, { query = it }, label = { Text("Add a song") }) }
            items(results, key = { "result:${it.id}" }) { track ->
                TextButton(onClick = { perform { app.rooms.add(listOf(SharedTrack(title = track.title, artist = track.artist, album = track.album, durationMs = track.durationMs, sourceID = track.id, releaseID = track.releaseId, artwork = track.artwork))) } }) { Column { Text(track.title); Text(track.artist) } }
            }
        } else if (requested != null) {
            item { Text("Waiting for the host to accept your request."); Button(onClick = { perform { app.rooms.leave() } }) { Text("Cancel join") } }
        } else {
            item {
                Text("Host a Room from your current music queue or paste an invite. Each guest needs the host's approval.")
                OutlinedTextField(name, { name = it }, label = { Text("Room name") })
                Button(enabled = name.isNotBlank(), onClick = { perform { app.rooms.host(name) } }) { Text("Host Room") }
                OutlinedTextField(link, { link = it }, label = { Text("Room link") })
                Button(enabled = link.isNotBlank(), onClick = { perform { val parsed = SocialLink.parse(link); require(parsed != null && parsed.type == "room") { "Paste a Spitify Room link." }; app.social.configure(true); app.social.joinRoom(parsed) } }) { Text("Connect and ask to join") }
                if (!app.social.enabled) Text("Turn on Connect with friends in Friends before hosting.")
            }
        }
        message?.let { item { Text(it) } }
        roomMessage?.let { item { Text(it) } }
    }
}
