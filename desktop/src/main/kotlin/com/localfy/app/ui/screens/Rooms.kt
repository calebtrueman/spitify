package com.localfy.app.ui.screens

import com.localfy.app.ui.typingFocus

import com.localfy.app.ui.components.*
import com.localfy.app.ui.theme.LocalfyColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.Alignment
import com.localfy.app.ui.art.ArtKey
import com.localfy.app.ui.art.Artwork
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.LocalfyApp
import com.localfy.app.data.music.*
import com.localfy.app.data.social.*
import com.localfy.app.ui.LocalApp
import kotlinx.coroutines.*

@Composable
fun RoomsScreen(invite: String? = null) {
    val app = com.localfy.app.ui.LocalContainer.current
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
    LazyColumn(contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Rooms", onBack = { actions.nav.popBackStack() }) }
        if (room != null) {
            insetItem {
                Text(room.name, style = MaterialTheme.typography.titleLarge)
                Text(if (app.rooms.isHost) "You host this Room" else "Listening with the host")
                Text("Everyone streams their own copy. An unavailable song may leave one person silent. Rooms need the host's app to stay connected.")
                TextButton(onClick = { com.localfy.app.ui.copyToClipboard(SocialLink("room", room.host, room.id).url); com.localfy.app.ui.Toasts.show("Link copied") }) { Text("Copy invite link") }
                Text("${room.members.size} guests")
                if (app.rooms.isHost) Row { Text("Let guests control playback", Modifier.weight(1f)); Switch(room.allowControls, { perform { app.rooms.setControls(it) } }, modifier = Modifier.semantics { contentDescription = "Let guests control playback" }) }
                if (app.rooms.isHost || room.allowControls) Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { perform { app.rooms.control(position = 0) } }) { Icon(Icons.Rounded.Replay, "Restart") }
                    IconButton(onClick = { perform { app.rooms.control(next = true) } }) { Icon(Icons.Rounded.SkipNext, "Next") }
                    Spacer(Modifier.weight(1f))
                    BigPlayButton(playing = room.playing, onClick = { perform { app.rooms.control(playing = !room.playing) } })
                }
                TextButton(onClick = { perform { app.rooms.leave() } }) { Text(if (app.rooms.isHost) "End Room" else "Leave Room") }
            }
            if (app.rooms.isHost) items(app.social.rooms.requests.filter { it.request.action == "join" && it.request.roomID == room.id }, key = { it.request.id }) { incoming ->
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(incoming.request.name.ifBlank { "Guest ${incoming.sender.take(8)}" })
                    Row { TextButton(onClick = { perform { app.rooms.approve(incoming, true) } }) { Text("Accept") }; TextButton(onClick = { perform { app.rooms.approve(incoming, false) } }) { Text("Decline") } }
                }
            }
            item { SectionHeader("Shared queue") }
            items(room.queue, key = { it.id }) { track ->
                MediaRow(track.title, (if (room.currentID == track.id) "Playing • " else "") + track.artist,
                    artwork = { SharedTrackCover(track, modifier = it) }, isCurrent = room.currentID == track.id, isPlaying = room.playing, trailing = {
                        if (app.rooms.isHost || room.allowControls) IconButton(onClick = { perform { app.rooms.remove(track.id) } }) { Icon(Icons.Rounded.RemoveCircleOutline, "Remove ${track.title}") }
                    })
            }
            insetItem { OutlinedTextField(query, { query = it }, label = { Text("Add a song") }, modifier = Modifier.typingFocus()) }
            items(results, key = { "result:${it.id}" }) { track ->
                val add = { perform { app.rooms.add(listOf(SharedTrack(title = track.title, artist = track.artist, album = track.album, durationMs = track.durationMs, sourceID = track.id, releaseID = track.releaseId, artwork = track.artwork))) } }
                MediaRow(track.title, track.artist, artwork = { Artwork(ArtKey(track.id.hashCode().toLong(), track.id.hashCode().toLong(), track.artwork), it, RoundedCornerShape(6.dp)) }, onClick = add, trailing = { IconButton(onClick = add) { Icon(Icons.Rounded.AddToQueue, "Add ${track.title}") } })
            }
        } else if (requested != null) {
            insetItem { Text("Waiting for the host to accept your request."); Button(onClick = { perform { app.rooms.leave() } }) { Text("Cancel join") } }
        } else {
            insetItem {
                Text("Host a Room from your current music queue or paste an invite. Each guest needs the host's approval.")
                OutlinedTextField(name, { name = it }, label = { Text("Room name") }, modifier = Modifier.typingFocus())
                Button(enabled = name.isNotBlank(), onClick = { perform { app.rooms.host(name) } }) { Text("Host Room") }
                OutlinedTextField(link, { link = it }, label = { Text("Room link") }, modifier = Modifier.typingFocus())
                Button(enabled = link.isNotBlank(), onClick = { perform { val parsed = SocialLink.parse(link); require(parsed != null && parsed.type == "room") { "Paste a Spitify Room link." }; app.social.configure(true); app.social.joinRoom(parsed) } }) { Text("Connect and ask to join") }
                if (!app.social.enabled) Text("Turn on Connect with friends in Friends before hosting.")
            }
        }
        message?.let { insetItem { Text(it) } }
        roomMessage?.let { insetItem { Text(it) } }
    }
}
