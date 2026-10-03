package com.localfy.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localfy.app.LocalfyApp
import com.localfy.app.data.social.SharedPlaylist
import com.localfy.app.data.social.SocialLink
import com.localfy.app.ui.LocalApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun FriendsScreen() {
    val context = LocalContext.current
    val store = (context.applicationContext as LocalfyApp).social
    val revision by store.revision.collectAsStateWithLifecycle()
    val actions = LocalApp.current
    val scope = rememberCoroutineScope()
    var playlistName by remember { mutableStateOf("") }
    var mix by remember { mutableStateOf(false) }
    var relays by remember { mutableStateOf(store.relayAddresses.joinToString("\n")) }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(store.state.profiles[store.publicKey]?.name.orEmpty()) }
    var about by remember { mutableStateOf(store.state.profiles[store.publicKey]?.about.orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    val following = remember(revision) { store.state.following.sorted() }
    LazyColumn(contentPadding = PaddingValues(16.dp, 24.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = { actions.nav.popBackStack() }) { Text("Back") }; Text("Friends", style = MaterialTheme.typography.headlineMedium) }
        item {
            Row { Text("Connect with friends", Modifier.weight(1f)); Switch(store.enabled, { store.configure(it) }, modifier = Modifier.semantics { contentDescription = "Connect with friends" }) }
            Text("Friends connect through public relays. Anyone can read public profiles and playlists. Direct shares are encrypted. Your audio files stay on your device.")
            if (store.enabled) Text("${store.connected} relays connected · ${store.pending} messages waiting")
            TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, SocialLink("person", store.publicKey).url), "Share friend code")) }) { Text("Share your friend code") }
        }
        item { TextButton(onClick = { actions.navigate("rooms") }) { Text("Rooms — listen together") } }
        item {
            Text("New shared playlist", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(playlistName, { playlistName = it }, label = { Text("Playlist name") })
            Row { Text("Shared Mix", Modifier.weight(1f)); Switch(mix, { mix = it }, modifier = Modifier.semantics { contentDescription = "Shared Mix" }) }
            Text("Add songs, then invite friends from the playlist's sharing controls.")
            Button(enabled = playlistName.isNotBlank(), onClick = {
                runCatching { val list = SharedPlaylist(owner = store.publicKey, name = playlistName.trim(), kind = if (mix) "mix" else "playlist"); store.save(list); actions.navigate(com.localfy.app.ui.Routes.sharedPlaylist(list.key)) }.onFailure { message = it.message }
            }) { Text("Create") }
        }
        item {
            OutlinedTextField(relays, { relays = it }, label = { Text("Relay addresses, one per line") })
            TextButton(onClick = {
                val addresses = relays.split(Regex("\\s+")).filter { it.isNotBlank() }
                if (addresses.size in 1..4 && addresses.all { runCatching { java.net.URI(it).scheme == "wss" }.getOrDefault(false) }) { store.configure(store.enabled, relays = addresses); message = "Connection services saved." }
                else message = "Enter one to four secure wss:// relay addresses."
            }) { Text("Save relay addresses") }
        }
        item {
            OutlinedTextField(code, { code = it }, label = { Text("Friend code or Spitify link") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { runCatching { store.follow(code); code = "" }.onFailure { message = it.message } }, enabled = code.isNotBlank()) { Text("Follow") }
        }
        item {
            Text("Your public profile", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it }, label = { Text("Name") })
            OutlinedTextField(about, { about = it }, label = { Text("About you") })
            Button(onClick = { scope.launch { try { store.publishProfile(name, about); message = "Profile published." } catch (e: Exception) { if (e is CancellationException) throw e; message = e.message } } }, enabled = store.enabled) { Text("Publish profile") }
            Row { Text("Discover public profiles", Modifier.weight(1f)); Switch(store.discovery, { store.configure(store.enabled, it) }, modifier = Modifier.semantics { contentDescription = "Discover public profiles" }) }
        }
        item { Text("Following", style = MaterialTheme.typography.titleMedium) }
        items(following, key = { it }) { person -> Row { Text(store.state.profiles[person]?.name ?: "Friend ${person.take(8)}", Modifier.weight(1f)); TextButton(onClick = { store.unfollow(person) }) { Text("Unfollow") } } }
        if (store.discovery) items(store.state.profiles.values.filter { it.id != store.publicKey && it.id !in following }.sortedBy { it.name }, key = { "discover:${it.id}" }) { profile ->
            Row { Text(profile.name, Modifier.weight(1f)); TextButton(onClick = { runCatching { store.follow(profile.id) }.onFailure { message = it.message } }) { Text("Follow") } }
        }
        message?.let { item { Text(it) } }
    }
}

@Composable
fun PlaylistSharingControls(playlist: SharedPlaylist) {
    val context = LocalContext.current
    val store = (context.applicationContext as LocalfyApp).social
    val revision by store.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun send(action: suspend () -> Unit) { scope.launch { try { action(); message = "Queued for sharing. Delivery waits for a connected relay." } catch (e: Exception) { if (e is CancellationException) throw e; message = e.message } } }
    Column {
        TextButton(onClick = { expanded = !expanded }) { Text("Share with friends") }
        if (expanded) {
            Text("Your friend must follow your code to receive your private shares.")
            Text("Publishing makes the playlist name, description and songs readable by anyone.")
            TextButton(onClick = { send { store.share(playlist) } }) { Text("Publish playlist") }
            TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, SocialLink("playlist", playlist.owner, playlist.id).url), "Share playlist")) }) { Text("Share link") }
            val people = remember(revision) { store.state.following.sorted() }
            people.forEach { person ->
                Text(store.state.profiles[person]?.name ?: "Friend ${person.take(8)}")
                TextButton(onClick = { send { store.share(playlist, person) } }) { Text("Send privately") }
                Row { Text("Allow edits", Modifier.weight(1f)); Switch(person in (store.state.playlists[playlist.key] ?: playlist).editors, { allowed -> send { store.setEditor(person, playlist, allowed) } }, modifier = Modifier.semantics { contentDescription = "Allow ${store.state.profiles[person]?.name ?: "Friend ${person.take(8)}"} to edit" }) }
            }
            if (people.isEmpty()) Text("Follow someone in Friends to send a private share.")
            message?.let { Text(it) }
        }
    }
}

@Composable
fun IncomingShareScreen(value: String) {
    val store = (LocalContext.current.applicationContext as LocalfyApp).social
    val revision by store.revision.collectAsStateWithLifecycle()
    val link = remember(value) { SocialLink.parse(value) }
    val list = remember(revision, link) { link?.let { store.state.playlists["${it.owner}:${it.id}"] } }
    var message by remember { mutableStateOf<String?>(null) }
    if (link?.type == "room") { RoomsScreen(value); return }
    if (list != null) { PublicPlaylistScreen(list.key, true); return }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Shared with you", style = MaterialTheme.typography.headlineMedium)
        Text("Following connects to public relays to receive this person's shared music. Private playlists must be sent to your friend code first.")
        Button(enabled = link != null, onClick = { runCatching { store.follow(value); store.configure(true); message = if (link?.type == "person") "Following." else "Waiting for the playlist. Ask its owner to send it privately if it isn't public." }.onFailure { message = it.message } }) { Text("Connect and follow") }
        message?.let { Text(it) }
    }
}
