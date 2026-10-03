package com.localfy.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.localfy.app.LocalfyApp
import com.localfy.app.data.Song
import com.localfy.app.data.music.*
import com.localfy.app.data.social.*
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.Routes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun PublicPlaylistScreen(input: String, saved: Boolean) {
    val context = LocalContext.current
    val app = context.applicationContext as LocalfyApp
    val actions = LocalApp.current
    val revision by app.social.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var initial by remember(input) { mutableStateOf<SharedPlaylist?>(null) }
    var message by remember(input) { mutableStateOf<String?>(null) }
    var work by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(input, saved) {
        try { initial = if (saved) app.social.state.playlists[input] ?: error("This playlist is no longer saved.") else SpotifyPlaylists.load(input, app.social.publicKey) }
        catch (e: Exception) { if (e is CancellationException) throw e; message = e.message }
    }
    val playlist = remember(initial, revision) { initial?.let { app.social.state.playlists[it.key] ?: it } }
    LaunchedEffect(playlist?.key, playlist?.revision) { playlist?.let { PlaylistMatches.prepare(it, app) } }
    fun play(from: Int) {
        val list = playlist ?: return
        work?.cancel()
        work = app.appScope.launch {
            message = null; var first: Long? = null; var expectedQueue = actions.player.queueVersion; val missing = mutableListOf<String>()
            for (track in list.tracks.drop(from)) {
                    try {
                        val song = PlaylistMatches.resolve(track, app)
                        if (actions.player.queueVersion != expectedQueue) break
                        if (first == null) { first = song.id; actions.player.playSongs(listOf(song), 0, false, list.name); expectedQueue = actions.player.queueVersion }
                        else {
                            actions.player.appendFromSource(listOf(song))
                        }
                    } catch (e: Exception) { if (e is CancellationException) throw e; missing += track.title }
                }
            if (missing.isNotEmpty()) message = "Couldn't match ${missing.size} songs: " + missing.take(4).joinToString(", ")
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = { actions.nav.popBackStack() }) { Text("Back") } }
        if (playlist == null && message == null) item { CircularProgressIndicator() }
        playlist?.let { list ->
            item { AsyncImage(list.image, null, Modifier.fillMaxWidth().height(220.dp)); Text(list.name, style = MaterialTheme.typography.headlineMedium) }
            item { if (list.description.isNotBlank()) Text(list.description); Text("${list.tracks.size} songs" + (list.sourceName?.let { " · From $it" } ?: "")) }
            if (list.partial) item { Text("Only part of this playlist was available. Saved songs keep their original titles and order.") }
            list.sourceURL?.let { source -> item { TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source))) }) { Text("Open original playlist") } } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { play(0) }, enabled = list.tracks.isNotEmpty()) { Text("Play") }
                    val isSaved = app.social.state.playlists.containsKey(list.key)
                    OutlinedButton(onClick = { runCatching { app.social.save(list); PlaylistMatches.prepare(list, app) }.onFailure { message = it.message } }, enabled = !isSaved) { Text(if (isSaved) "Saved" else "Save playlist") }
                }
            }
            if (list.owner != app.social.publicKey) item { TextButton(onClick = { runCatching { actions.navigate(Routes.sharedPlaylist(app.social.copy(list).key)) }.onFailure { message = it.message } }) { Text("Make your own copy") } }
            if (list.owner == app.social.publicKey && app.social.state.playlists.containsKey(list.key)) item { PlaylistSharingControls(list) }
            if (app.social.state.playlists.containsKey(list.key) && (list.owner == app.social.publicKey || app.social.publicKey in list.editors)) item { SharedPlaylistEditor(list) }
            itemsIndexed(list.tracks, key = { _, track -> track.id }) { index, track ->
                Row(Modifier.fillMaxWidth().clickable { play(index) }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${index + 1}"); Column { Text(track.title); SearchSubtitle("Song", track.artist) }
                }
            }
        }
        message?.let { item { Text(it) } }
    }
}

@Composable
fun OnlineArtistScreen(encoded: String) {
    val actions = LocalApp.current
    val artist = remember(encoded) { runCatching { JSONObject(encoded) }.getOrNull() }
    val name = artist?.optString("name").orEmpty()
    val app = LocalContext.current.applicationContext as LocalfyApp
    val followRevision by app.artistFollows.revision.collectAsStateWithLifecycle()
    val artistID = artist?.optString("id").orEmpty()
    var loaded by remember(encoded) { mutableStateOf(false) }
    var result by remember { mutableStateOf(OnlineSearch()) }
    var message by remember { mutableStateOf<String?>(null) }
    var albumCursor by remember(encoded) { mutableIntStateOf(0) }
    var loadingMore by remember(encoded) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun loadMore() {
        if (loadingMore) return
        loadingMore = true
        try {
            val end = minOf(albumCursor + 3, result.albums.size)
            while (albumCursor < end) {
                val tracks = Monochrome.album(result.albums[albumCursor].id)
                result = result.copy(tracks = (result.tracks + tracks).distinctBy { it.id })
                albumCursor++
            }
            message = null
        } catch (e: Exception) { if (e is CancellationException) throw e; message = "Couldn't load more songs. Tap Show more songs to retry." }
        finally { loadingMore = false }
    }
    LaunchedEffect(encoded) {
        try { result = Monochrome.artistPage(artistID); loaded = true; albumCursor = 0; loadMore() }
        catch (e: Exception) { if (e is CancellationException) throw e; message = e.message }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp, 24.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = { actions.nav.popBackStack() }) { Text("Back") }; Text(name, style = MaterialTheme.typography.headlineMedium); AsyncImage(artist?.optString("artwork"), null, Modifier.fillMaxWidth().height(200.dp)) }
        message?.let { item { Text(it) } }
        item {
            val followed = remember(followRevision, artistID) { app.artistFollows.contains(artistID) }
            Button(enabled = loaded, onClick = { runCatching { if (followed) app.artistFollows.unfollow(artistID) else app.artistFollows.follow(OnlineArtist(artistID, name, artist?.optString("artwork")), result.albums) }.onFailure { message = it.message } }) { Text(if (followed) "Following" else "Follow artist") }
        }
        item { Text("Songs", style = MaterialTheme.typography.titleLarge) }
        items(result.tracks, key = { it.id }) { OnlineMusicRow(it) }
        item { if (loadingMore) CircularProgressIndicator() else if (albumCursor < result.albums.size) TextButton(onClick = { scope.launch { loadMore() } }) { Text("Show more songs") } }
        item { Text("Albums & singles", style = MaterialTheme.typography.titleLarge) }
        items(result.albums, key = { it.id }) { album ->
            Row(Modifier.fillMaxWidth().clickable { actions.navigate(Routes.catalogAlbum(album)) }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(album.artwork, null, Modifier.size(56.dp)); Column { Text(album.title); SearchSubtitle("Album", album.artist, album.explicit == true) }
            }
        }
    }
}

@Composable
fun ArtistLandingScreen(name: String) {
    val actions = LocalApp.current
    val library by actions.repo.library.collectAsStateWithLifecycle()
    var artist by remember(name) { mutableStateOf<OnlineArtist?>(null) }
    var loading by remember(name) { mutableStateOf(true) }
    var failed by remember(name) { mutableStateOf(false) }
    var retry by remember(name) { mutableIntStateOf(0) }
    LaunchedEffect(name, retry) {
        loading = true; failed = false
        try { artist = Monochrome.searchAll(name).artists.firstOrNull { SearchMatch.fold(it.name) == SearchMatch.fold(name) } }
        catch (e: Exception) { if (e is CancellationException) throw e; failed = true }
        finally { loading = false }
    }
    val found = artist
    when {
        found != null -> OnlineArtistScreen(JSONObject().put("id", found.id).put("name", found.name).put("artwork", found.artwork).toString())
        library.artistByName[name] != null -> ArtistScreen(name)
        loading -> Column(Modifier.fillMaxSize(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator(); Text("Finding artist…", Modifier.padding(16.dp))
        }
        else -> Column(Modifier.fillMaxSize(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            com.localfy.app.ui.components.EmptyState(if (failed) "Couldn't load artist" else "No artist page found", if (failed) "Check your connection and try again." else "Try searching for the artist by name.")
            TextButton(onClick = { retry += 1 }) { Text("Try again") }
        }
    }
}
