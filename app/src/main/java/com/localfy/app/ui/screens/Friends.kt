package com.localfy.app.ui.screens

import com.localfy.app.ui.components.*
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.localfy.app.LocalfyApp
import com.localfy.app.data.social.*
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.Routes
import com.localfy.app.ui.art.ArtKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable fun FriendsScreen() {
    val app = LocalContext.current.applicationContext as LocalfyApp
    val store = app.social
    val revision by store.revision.collectAsStateWithLifecycle()
    val own by app.profiles.profile.collectAsStateWithLifecycle()
    val actions = LocalApp.current
    LaunchedEffect(own) { store.syncProfile() }
    val following = remember(revision) { store.state.following.sortedBy { store.state.profiles[it]?.name.orEmpty() } }
    LazyColumn(contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeader("Friends", onBack = { actions.nav.popBackStack() }) }
        insetItem {
            Text("Music is better together.", style = MaterialTheme.typography.headlineLarge)
            Row(Modifier.fillMaxWidth().clickable { actions.navigate("friend/${store.publicKey}") }.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                FriendPortrait(store.state.profiles[store.publicKey], Modifier.size(64.dp))
                Column { Text(own.name.ifBlank { "Your profile" }, style = MaterialTheme.typography.titleLarge); Text("Your profile & picture code") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { actions.navigate("add-friend") }) { Text("Add friend") }
                OutlinedButton(onClick = { actions.navigate("rooms") }) { Text("Listen together") }
            }
            TextButton(onClick = { actions.navigate("friends-settings") }) { Text("Friends settings") }
        }
        insetItem { Text("Your friends", style = MaterialTheme.typography.titleLarge) }
        if (following.isEmpty()) insetItem { Text("Add a friend's picture code or link to start sharing music.") }
        items(following, key = { it }) { person ->
            val profile = store.state.profiles[person]
            Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable { actions.navigate("friend/$person") }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    FriendPortrait(profile, Modifier.size(60.dp))
                    Column(Modifier.weight(1f)) { Text(profile?.name ?: "Profile not loaded", style = MaterialTheme.typography.titleMedium); Text(profile?.about ?: "Waiting for their profile…", style = MaterialTheme.typography.bodyMedium, maxLines = 2) }
                    Text("›")
                }
            }
        }
        insetItem { TextButton(onClick = { actions.navigate("new-shared-playlist") }) { Text("Create a shared playlist or mix") } }
        if (!store.enabled) insetItem { Text("Sharing is paused. Turn it on in Friends settings.") }
        store.message?.let { insetItem { Text(it) } }
    }
}

@Composable fun FriendPortrait(profile: FriendProfile?, modifier: Modifier = Modifier) {
    val photo = remember(profile?.photo) { profile?.photo?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull() } }
    Box(modifier.clip(CircleShape), contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) { Box(contentAlignment = Alignment.Center) { Text(profile?.name?.take(1)?.uppercase() ?: "?", style = MaterialTheme.typography.headlineLarge) } }
        if (photo != null || profile?.image != null) AsyncImage(model = photo ?: profile?.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable fun FriendProfileScreen(person: String) {
    val app = LocalContext.current.applicationContext as LocalfyApp
    val store = app.social
    val revision by store.revision.collectAsStateWithLifecycle()
    val actions = LocalApp.current
    var removing by remember { mutableStateOf(false) }
    val profile = remember(revision, person) { store.state.profiles[person] }
    val own = person == store.publicKey
    val shared = remember(revision, person) { store.playlists.filter { it.owner == person || person in store.state.recipients[it.key].orEmpty() || person in it.editors } }
    CollectionPage(title = profile?.name ?: "Profile not loaded", kindLabel = if (own) "Your profile" else "Friend", subtitle = profile?.about ?: "Their name and photo will appear when their profile arrives.", summary = "", art = profile?.image?.let { ArtKey(person.hashCode().toLong(), 0, it) }, hero = true,
        cover = { FriendPortrait(profile, it) }, playEnabled = false,
        headerActions = {
            if (own) {
                OutlinedButton(onClick = { actions.navigate(Routes.PROFILE) }) { Text("Edit profile") }
                Button(onClick = { actions.navigate("friend-code") }) { Text("Your picture code") }
            } else if (person in store.state.following) {
                var menu by remember { mutableStateOf(false) }
                Box { OutlinedButton(onClick = { menu = true }) { Text("Following") }; DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text("Unfollow") }, onClick = { menu = false; removing = true }) } }
            } else Button(onClick = { store.follow(person) }) { Text("Follow") }
        }) {
        item { SectionHeader(if (own) "Your shared music" else "Music you share") }
        if (shared.isEmpty()) item { Text("Shared playlists will appear here. Open one of your playlists and choose Share with friends.", Modifier.padding(20.dp)) }
        items(shared, key = { it.key }) { list -> ListItem(headlineContent = { Text(list.name) }, supportingContent = { Text(if (list.owner == store.publicKey) "Shared by you" else "Shared by them") }, leadingContent = { com.localfy.app.ui.art.Artwork(ArtKey(list.key.hashCode().toLong(), 0, list.image), Modifier.size(60.dp)) }, modifier = Modifier.clickable { actions.navigate(Routes.sharedPlaylist(list.key)) }) }
    }
    if (removing) AlertDialog(onDismissRequest = { removing = false }, title = { Text("Unfollow this friend?") }, text = { Text("Your saved playlists stay on this phone.") }, confirmButton = { TextButton(onClick = { store.unfollow(person); removing = false; actions.nav.popBackStack() }) { Text("Unfollow") } }, dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } })
}

@Composable fun FriendsSettingsScreen() {
    val app = LocalContext.current.applicationContext as LocalfyApp
    val store = app.social
    val revision by store.revision.collectAsStateWithLifecycle()
    val own by app.profiles.profile.collectAsStateWithLifecycle()
    val actions = LocalApp.current
    var about by remember { mutableStateOf(store.state.profiles[store.publicKey]?.about.orEmpty()) }
    var advanced by remember { mutableStateOf(false) }
    var relays by remember { mutableStateOf(store.relayAddresses.joinToString("\n")) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(about, own) { kotlinx.coroutines.delay(650); store.publishProfile(own.name, about) }
    LazyColumn(contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeader("Friends settings", onBack = { actions.nav.popBackStack() }) }
        insetItem {
            Text("Your profile", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { actions.navigate(Routes.PROFILE) }) { Text("Change name or photo") }
            OutlinedTextField(about, { about = it.take(500) }, label = { Text("About you") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Public profile", Modifier.weight(1f)); Switch(store.publicProfile, store::setPublicProfile) }
            Text(if (store.publicProfile) "Anyone with your code can see your name, photo and bio. Changes share automatically." else "Only people you follow receive your name, photo and bio. A private notice replaces your public profile. Copies already saved elsewhere may remain.")
        }
        insetItem {
            Text("Connection", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Connect with friends", Modifier.weight(1f)); Switch(store.enabled, { store.configure(it) }) }
            Text(if (store.enabled) "${store.connected} connections · ${store.pending} updates waiting" else "Sharing is paused. Your changes stay saved here.")
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Discover public profiles", Modifier.weight(1f)); Switch(store.discovery, { store.configure(store.enabled, it) }) }
            TextButton(onClick = { advanced = !advanced }) { Text("Advanced connection settings") }
            if (advanced) {
                OutlinedTextField(relays, { relays = it }, label = { Text("Relay addresses") })
                TextButton(onClick = {
                    val addresses = relays.split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (addresses.size in 1..4 && addresses.all { runCatching { java.net.URI(it).scheme == "wss" }.getOrDefault(false) }) { store.configure(store.enabled, relays = addresses); message = "Connections saved." } else message = "Enter one to four wss:// addresses."
                }) { Text("Save connections") }
            }
        }
        message?.let { insetItem { Text(it) } }
        store.message?.let { insetItem { Text(it) } }
    }
}

@Composable fun AddFriendScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as LocalfyApp
    val actions = LocalApp.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) scope.launch {
        val value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FriendPictureCode.read(context, uri) }
        if (value == null) message = "No Spitify friend code found in this image." else { code = value; message = "Code found. Tap Add friend to follow." }
    } }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader("Add friend", onBack = { actions.nav.popBackStack() })
        Button(onClick = { picker.launch("image/*") }) { Text("Import friend code image") }
        OutlinedTextField(code, { code = it }, label = { Text("Or paste a friend link") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = code.isNotBlank(), onClick = { runCatching { app.social.follow(code); actions.nav.popBackStack() }.onFailure { message = it.message } }) { Text("Add friend") }
        message?.let { Text(it) }
    }
}

@Composable fun FriendCodeScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as LocalfyApp
    val profile by app.profiles.profile.collectAsStateWithLifecycle()
    val code = remember(app.social.publicKey) { FriendPictureCode.make(SocialLink("person", app.social.publicKey).url) }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(profile.name, style = MaterialTheme.typography.headlineLarge)
        Image(code.asImageBitmap(), "Your Spitify friend code", Modifier.sizeIn(maxWidth = 300.dp, maxHeight = 300.dp).fillMaxWidth().aspectRatio(1f))
        Button(onClick = { FriendPictureCode.share(context, code) }) { Text("Share picture code") }
        Text("Your friend can import this image in Friends → Add friend.")
    }
}

@Composable fun NewSharedPlaylistScreen() {
    val store = (LocalContext.current.applicationContext as LocalfyApp).social
    val actions = LocalApp.current
    var name by remember { mutableStateOf("") }; var mix by remember { mutableStateOf(false) }; var message by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader("New shared playlist", onBack = { actions.nav.popBackStack() })
        OutlinedTextField(name, { name = it }, label = { Text("Name") })
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Shared mix", Modifier.weight(1f)); Switch(mix, { mix = it }) }
        Button(enabled = name.isNotBlank(), onClick = { runCatching { val list = SharedPlaylist(owner = store.publicKey, name = name.trim(), kind = if (mix) "mix" else "playlist"); store.save(list); actions.navigate(Routes.sharedPlaylist(list.key)) }.onFailure { message = it.message } }) { Text("Create") }
        message?.let { Text(it) }
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
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Allow edits", Modifier.weight(1f)); Switch(person in (store.state.playlists[playlist.key] ?: playlist).editors, { allowed -> send { store.setEditor(person, playlist, allowed) } }, modifier = Modifier.semantics { contentDescription = "Allow ${store.state.profiles[person]?.name ?: "Friend ${person.take(8)}"} to edit" }) }
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
