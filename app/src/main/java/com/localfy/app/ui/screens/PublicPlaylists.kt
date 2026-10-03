package com.localfy.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import com.localfy.app.ui.components.*
import com.localfy.app.ui.player.rememberPlayerState
import com.localfy.app.ui.theme.LocalfyColors
import com.localfy.app.ui.SongMenuExtras
import com.localfy.app.ui.art.ArtKey
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.art.artKey
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PublicPlaylistScreen(input: String, saved: Boolean) {
    val context = LocalContext.current
    val app = context.applicationContext as LocalfyApp
    val actions = LocalApp.current
    val revision by app.social.revision.collectAsStateWithLifecycle()
    var initial by remember(input) { mutableStateOf<SharedPlaylist?>(null) }
    var message by remember(input) { mutableStateOf<String?>(null) }
    var work by remember { mutableStateOf<Job?>(null) }
    var options by remember { mutableStateOf(false) }
    var showSharing by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    LaunchedEffect(input, saved) {
        try { initial = if (saved) app.social.state.playlists[input] ?: error("This playlist is no longer saved.") else SpotifyPlaylists.load(input, app.social.publicKey) }
        catch (e: Exception) { if (e is CancellationException) throw e; message = e.message }
    }
    val playlist = remember(initial, revision) { initial?.let { app.social.state.playlists[it.key] ?: it } }
    LaunchedEffect(playlist?.key, playlist?.revision) { playlist?.let { PlaylistMatches.prepare(it, app) } }
    fun play(from: Int, shuffle: Boolean = false) {
        val list = playlist ?: return
        work?.cancel()
        work = app.appScope.launch {
            message = if (shuffle) "Preparing shuffle…" else null; val shuffledSongs = mutableListOf<Song>(); var first: Long? = null; var expectedQueue = actions.player.queueVersion; val missing = mutableListOf<String>()
            for (track in if (shuffle) list.tracks else list.tracks.drop(from)) {
                    try {
                        val song = PlaylistMatches.resolve(track, app)
                        if (actions.player.queueVersion != expectedQueue) return@launch
                        if (shuffle) { shuffledSongs += song; continue }
                        if (first == null) { first = song.id; actions.player.playSongs(listOf(song), 0, false, list.name); expectedQueue = actions.player.queueVersion }
                        else {
                            actions.player.appendFromSource(listOf(song))
                        }
                    } catch (e: Exception) { if (e is CancellationException) throw e; missing += track.title }
                }
            if (shuffle) {
                if (shuffledSongs.isNotEmpty()) actions.player.playSongs(shuffledSongs, 0, true, list.name)
                message = null
            }
            if (missing.isNotEmpty()) message = "Couldn't match ${missing.size} songs: " + missing.take(4).joinToString(", ")
        }
    }
    val player = rememberPlayerState()
    val list = playlist
    if (list == null) {
        Column(Modifier.fillMaxSize()) {
            PageHeader("Playlist", onBack = { actions.nav.popBackStack() })
            if (message == null) CircularProgressIndicator(Modifier.padding(16.dp))
            else EmptyState("Couldn't load playlist", message.orEmpty())
        }
        return
    }
    val isSaved = app.social.state.playlists.containsKey(list.key)
    CollectionPage(
        title = list.name, kindLabel = if (list.kind == "mix") "Shared mix" else "Playlist",
        subtitle = list.description,
        summary = "${list.tracks.size} songs" + (list.sourceName?.let { " • From $it" } ?: ""),
        art = ArtKey(list.key.hashCode().toLong(), list.key.hashCode().toLong(), list.image ?: list.tracks.firstOrNull { it.artwork != null }?.artwork),
        playing = player.source == list.name && player.isPlaying, playEnabled = list.tracks.isNotEmpty(),
        onPlay = { if (player.source == list.name && player.hasMedia) actions.player.togglePlay() else play(0) },
        onShuffle = { play(0, shuffle = true) }, shuffleActive = player.shuffle,
        headerActions = {
            IconButton(onClick = { if (!isSaved) runCatching { app.social.save(list); PlaylistMatches.prepare(list, app); message = "Saved in Your Library." }.onFailure { message = it.message } }) {
                Icon(if (isSaved) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline, if (isSaved) "Saved in Your Library" else "Save to Your Library", tint = if (isSaved) MaterialTheme.colorScheme.primary else LocalfyColors.TextSecondary)
            }
            if (list.owner != app.social.publicKey || isSaved) Box {
                IconButton(onClick = { options = true }) { Icon(Icons.Rounded.MoreVert, "Playlist options", tint = LocalfyColors.TextSecondary) }
                DropdownMenu(options, { options = false }) {
                    if (list.owner != app.social.publicKey) DropdownMenuItem(text = { Text("Make your own copy") }, onClick = { options = false; runCatching { actions.navigate(Routes.sharedPlaylist(app.social.copy(list).key)) }.onFailure { message = it.message } })
                    if (isSaved && list.owner == app.social.publicKey) DropdownMenuItem(text = { Text("Share with friends") }, onClick = { options = false; showSharing = !showSharing })
                    if (isSaved && (list.owner == app.social.publicKey || app.social.publicKey in list.editors)) DropdownMenuItem(text = { Text(if (list.kind == "mix") "Contribute songs" else "Edit playlist") }, onClick = { options = false; showEditor = !showEditor })
                }
            }
        },
    ) {
        if (list.partial) item { Text("Only part of this playlist was available. Saved songs keep their original titles and order.", Modifier.padding(16.dp), color = LocalfyColors.TextSecondary) }
        message?.let { item { Text(it, Modifier.padding(16.dp), color = LocalfyColors.TextSecondary) } }
        if (showSharing && list.owner == app.social.publicKey && isSaved) item { Box(Modifier.padding(16.dp)) { PlaylistSharingControls(list) } }
        if (showEditor && isSaved && (list.owner == app.social.publicKey || app.social.publicKey in list.editors)) item { Box(Modifier.padding(16.dp)) { SharedPlaylistEditor(list, initiallyExpanded = true) } }
        itemsIndexed(list.tracks, key = { _, track -> track.id }) { index, track ->
            SharedPlaylistTrackRow(track, onPlay = { play(index) }, onError = { message = it })
        }
        if (list.tracks.isEmpty()) item { EmptyState("No songs yet", "Add songs from the playlist options.") }
    }
}

@Composable
internal fun SharedPlaylistTrackRow(track: SharedTrack, onPlay: () -> Unit, onError: (String?) -> Unit) {
    val app = LocalContext.current.applicationContext as LocalfyApp
    val actions = LocalApp.current
    val player = rememberPlayerState()
    val scope = rememberCoroutineScope()
    var matched by remember(track) { mutableStateOf<Song?>(null) }
    var openingMenu by remember(track) { mutableStateOf(false) }
    LaunchedEffect(track) {
        try { matched = PlaylistMatches.resolve(track, app) }
        catch (e: Exception) { if (e is CancellationException) throw e }
    }
    MediaRow(
        title = track.title, subtitle = track.artist, onClick = onPlay,
        isCurrent = matched != null && player.currentId == matched?.id, isPlaying = player.isPlaying,
        artwork = { SharedTrackCover(track, matched, it) },
        trailing = {
            IconButton(enabled = !openingMenu, onClick = {
                scope.launch {
                    openingMenu = true
                    try { val song = matched ?: PlaylistMatches.resolve(track, app); matched = song; actions.openSongMenu(song, SongMenuExtras()) }
                    catch (e: Exception) { if (e is CancellationException) throw e; onError(e.message) }
                    finally { openingMenu = false }
                }
            }) {
                if (openingMenu) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.MoreVert, "More options for ${track.title}", tint = LocalfyColors.TextSecondary)
            }
        },
    )
}

@Composable
internal fun SharedTrackCover(track: SharedTrack, matched: Song? = null, modifier: Modifier = Modifier) {
    val source = track.artwork?.let { ArtKey(track.recordingKey.hashCode().toLong(), track.recordingKey.hashCode().toLong(), it) }
    Artwork(source ?: matched?.artKey ?: ArtKey(track.recordingKey.hashCode().toLong(), track.recordingKey.hashCode().toLong()), modifier, RoundedCornerShape(6.dp))
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
    val player = rememberPlayerState()
    fun playArtist(shuffle: Boolean = false) {
        val songs = result.tracks.map(app.musicStreams::register)
        if (songs.isNotEmpty()) actions.player.playSongs(songs, 0, shuffle, name)
    }
    val followed = remember(followRevision, artistID) { app.artistFollows.contains(artistID) }
    CollectionPage(
        title = name, kindLabel = "Artist", subtitle = if (followed) "Following" else "", summary = "",
        art = ArtKey(artistID.hashCode().toLong(), artistID.hashCode().toLong(), artist?.optString("artwork")), hero = true,
        playing = player.source == name && player.isPlaying, playEnabled = result.tracks.isNotEmpty(),
        onPlay = { if (player.source == name && player.hasMedia) actions.player.togglePlay() else playArtist() },
        onShuffle = { playArtist(true) }, shuffleActive = player.shuffle,
        headerActions = {
            IconButton(enabled = loaded, onClick = { runCatching { if (followed) app.artistFollows.unfollow(artistID) else app.artistFollows.follow(OnlineArtist(artistID, name, artist?.optString("artwork")), result.albums) }.onFailure { message = it.message } }) {
                Icon(if (followed) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline, if (followed) "Unfollow artist" else "Follow artist", tint = if (followed) MaterialTheme.colorScheme.primary else LocalfyColors.TextSecondary)
            }
        },
    ) {
        message?.let { item { Text(it, Modifier.padding(16.dp), color = LocalfyColors.TextSecondary) } }
        item { SectionHeader("Songs") }
        itemsIndexed(result.tracks, key = { _, track -> track.id }) { index, track ->
            OnlineMusicRow(track, onPlay = {
                actions.player.playSongs(result.tracks.map(app.musicStreams::register), index, shuffle = false, source = name)
            })
        }
        item { if (loadingMore) CircularProgressIndicator(Modifier.padding(16.dp)) else if (albumCursor < result.albums.size) TextButton(modifier = Modifier.padding(horizontal = 8.dp), onClick = { scope.launch { loadMore() } }) { Text("Show more songs") } }
        item { SectionHeader("Albums & singles") }
        items(result.albums, key = { it.id }) { album ->
            MediaRow(album.title, album.artist, artwork = { Artwork(ArtKey(album.id.hashCode().toLong(), album.id.hashCode().toLong(), album.artwork), it, RoundedCornerShape(6.dp)) }, onClick = { actions.navigate(Routes.catalogAlbum(album)) }, subtitleContent = { SearchSubtitle("Album", album.artist, album.explicit == true) })
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
