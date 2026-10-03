package com.localfy.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.localfy.app.LocalfyApp
import com.localfy.app.data.Song
import com.localfy.app.data.music.*
import com.localfy.app.data.podcast.*
import com.localfy.app.data.social.*
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.Routes
import com.localfy.app.ui.art.Artwork
import com.localfy.app.ui.art.artKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

@Composable
fun SearchSubtitle(type: String, creator: String, explicit: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (explicit) Box(Modifier.size(13.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) {
            Text("E", color = MaterialTheme.colorScheme.surface, fontSize = 9.sp, fontWeight = FontWeight.Bold, lineHeight = 10.sp)
        }
        Text(type + if (creator.isEmpty()) "" else " · $creator", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

private data class MixedResult(val id: String, val title: String, val creator: String, val type: String, val score: Int,
    val artwork: String? = null, val explicit: Boolean = false, val local: Song? = null, val track: OnlineTrack? = null,
    val route: String? = null, val podcast: PodcastSearchResult? = null, val book: BookSearchResult? = null)

@Composable
fun MixedSearchPanel(query: String) {
    val actions = LocalApp.current
    val container = LocalContext.current.applicationContext as LocalfyApp
    val library by actions.repo.library.collectAsStateWithLifecycle()
    val localPlaylists by actions.repo.playlists.collectAsStateWithLifecycle()
    val localBooks by actions.repo.localBooks.collectAsStateWithLifecycle()
    val localPodcasts by actions.repo.localPodcasts.collectAsStateWithLifecycle()
    val savedShows by actions.podcasts.shows.collectAsStateWithLifecycle()
    val episodes by actions.podcasts.episodeSongs.collectAsStateWithLifecycle()
    val socialRevision by container.social.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var music by remember { mutableStateOf(OnlineSearch()) }
    var podcasts by remember { mutableStateOf(emptyList<PodcastSearchResult>()) }
    var books by remember { mutableStateOf(emptyList<BookSearchResult>()) }
    var playlists by remember { mutableStateOf(emptyList<SpotifyPlaylistResult>()) }
    var pending by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(query, retry) {
        music = OnlineSearch(); podcasts = emptyList(); books = emptyList(); playlists = emptyList(); failed = false
        if (query.trim().length < 2) { pending = 0; return@LaunchedEffect }
        pending = 4; delay(350)
        coroutineScope {
            suspend fun attempt(block: suspend () -> Unit) {
                try { block() } catch (e: Exception) { if (e is CancellationException) throw e; failed = true }
                finally { if (kotlinx.coroutines.currentCoroutineContext().isActive) pending -= 1 }
            }
            launch { attempt { music = Monochrome.searchAll(query.trim()) } }
            launch { attempt { podcasts = actions.podcasts.search(query.trim()) } }
            launch { attempt { books = actions.podcasts.searchBooks(query.trim()) } }
            launch { attempt {
                playlists = SpotifyPlaylists.playlistID(query)?.let { listOf(SpotifyPlaylistResult(it, "Open Spotify playlist", "", null, "Spotify")) } ?: SpotifyPlaylists.search(query.trim())
            } }
        }
    }
    val rows = remember(query, library, music, podcasts, books, playlists, socialRevision, localPlaylists, localBooks, localPodcasts, savedShows, episodes) {
        val result = mutableListOf<MixedResult>()
        fun score(title: String, creator: String, album: String = "") = SearchMatch.score(query, title, creator, album)
        library.songs.forEach { song -> score(song.title, song.artist, song.album)?.let {
            result += MixedResult("song:${song.id}", song.title, song.artist, if (song.isAudiobook) "Audiobook" else if (song.isPodcast) "Episode" else "Song", it, explicit = song.explicit == true || music.tracks.any { SearchMatch.sameSong(song.title, song.artist, song.durationMs, it.title, it.artist, it.durationMs) && it.explicit == true }, local = song)
        } }
        localPlaylists.forEach { playlist -> score(playlist.name, "")?.let {
            result += MixedResult("localPlaylist:${playlist.id}", playlist.name, "You", "Playlist", it, local = playlist.songs.firstOrNull(), route = Routes.playlist(playlist.id))
        } }
        localBooks.groupBy { it.albumId }.forEach { (id, chapters) -> chapters.firstOrNull()?.let { first -> score(first.album, first.albumArtist)?.let {
            result += MixedResult("localBook:$id", first.album, first.albumArtist, "Audiobook", it, local = first, route = Routes.localBook(id))
        } } }
        (localPodcasts + episodes.values.filter { !it.isAudiobook }).distinctBy { it.id }.forEach { episode -> score(episode.title, episode.album)?.let {
            result += MixedResult("episode:${episode.id}", episode.title, episode.album, "Episode", it, explicit = episode.explicit == true, local = episode)
        } }
        savedShows.forEach { show ->
            val p = show.podcast
            if (podcasts.none { it.feedUrl == p.feedUrl }) score(p.title, p.author)?.let {
                result += MixedResult("savedShow:${show.id}", p.title, p.author, if (p.kind == KIND_AUDIOBOOK) "Audiobook" else "Podcast", it, p.artworkUrl, route = if (p.kind == KIND_AUDIOBOOK) Routes.book(show.id) else Routes.show(show.id))
            }
        }
        music.tracks.filter { track -> library.songs.none { SearchMatch.sameSong(it.title, it.artist, it.durationMs, track.title, track.artist, track.durationMs) } }.forEach { track ->
            score(track.title, track.artist, track.album)?.let { result += MixedResult("track:${track.id}", track.title, track.artist, "Song", it, track.artwork, track.explicit == true, track = track) }
        }
        library.albums.forEach { album -> score(album.title, album.artist)?.let {
            val remote = music.albums.firstOrNull { SearchMatch.fold(it.title) == SearchMatch.fold(album.title) && SearchMatch.fold(it.artist) == SearchMatch.fold(album.artist) }
            result += MixedResult("album:${album.id}", album.title, album.artist, "Album", it, explicit = remote?.explicit == true || album.songs.any { it.explicit == true }, local = album.cover, route = remote?.let(Routes::catalogAlbum) ?: Routes.album(album.id))
        } }
        music.albums.filter { album -> library.albums.none { SearchMatch.fold(it.title) == SearchMatch.fold(album.title) && SearchMatch.fold(it.artist) == SearchMatch.fold(album.artist) } }.forEach { album ->
            score(album.title, album.artist)?.let { result += MixedResult("release:${album.id}", album.title, album.artist, "Album", it, album.artwork, album.explicit == true, route = Routes.catalogAlbum(album)) }
        }
        music.artists.forEach { artist -> score(artist.name, "")?.let {
            result += MixedResult("artist:${artist.id}", artist.name, "", "Artist", it + 20, artist.artwork, route = Routes.onlineArtist(artist))
        } }
        library.creditedArtists.filter { artist -> music.artists.none { SearchMatch.fold(it.name) == SearchMatch.fold(artist.name) } }.forEach { artist -> score(artist.name, "")?.let {
            result += MixedResult("localArtist:${artist.name}", artist.name, "", "Artist", it + 20, local = artist.ownCover, route = Routes.artist(artist.name))
        } }
        podcasts.forEach { podcast -> score(podcast.title, podcast.author)?.let { result += MixedResult("podcast:${podcast.feedUrl}", podcast.title, podcast.author, "Podcast", it, podcast.artworkUrl, podcast.explicit == true, podcast = podcast) } }
        books.forEach { book -> score(book.title, book.author)?.let { result += MixedResult("book:${book.id}", book.title, book.author, "Audiobook", it, book.coverUrl, book = book) } }
        container.social.playlists.forEach { playlist -> score(playlist.name, playlist.sourceName.orEmpty())?.let {
            result += MixedResult("shared:${playlist.key}", playlist.name, playlist.sourceName ?: container.social.state.profiles[playlist.owner]?.name ?: "Spitify", if (playlist.kind == "mix") "Shared Mix" else playlist.kind.replaceFirstChar { c -> c.uppercase() }, it, playlist.image, route = Routes.sharedPlaylist(playlist.key))
        } }
        playlists.forEach { playlist -> (if (SpotifyPlaylists.playlistID(query) != null) 1100 else score(playlist.name, playlist.owner))?.let {
            result += MixedResult("spotify:${playlist.id}", playlist.name, playlist.owner, "Playlist", it, playlist.image, route = Routes.spotifyPlaylist(playlist.id))
        } }
        result.sortedWith(compareByDescending<MixedResult> { it.score }.thenBy { it.title.lowercase() }).take(100)
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (pending > 0) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed) TextButton(onClick = { retry += 1 }) { Text("Some results couldn't load. Try again") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (rows.isEmpty() && pending == 0) Text("No matches yet. Try a title, artist, author or show.")
        rows.forEach { row -> key(row.id) {
            Row(Modifier.fillMaxWidth().clickable {
                when {
                    row.route != null -> actions.navigate(row.route)
                    row.track != null -> actions.player.playSongs(listOf(container.musicStreams.register(row.track)), 0, false, "Search: $query")
                    row.local != null -> actions.player.playSongs(listOf(row.local), 0, false, "Search: $query")
                    else -> scope.launch {
                        val id = row.podcast?.let { actions.podcasts.subscribe(it.feedUrl, it.artworkUrl, follow = false) }
                            ?: row.book?.let { actions.podcasts.subscribe(it.rssUrl, it.coverUrl, KIND_AUDIOBOOK, it.title, it.author, it.description) }
                        if (id == null) message = "Couldn't open that result. Please try again."
                        else actions.navigate(if (row.book != null) Routes.book(id) else Routes.show(id))
                    }
                }
            }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (row.local != null) Artwork(row.local.artKey, Modifier.size(50.dp), RoundedCornerShape(6.dp))
                else AsyncImage(row.artwork, null, Modifier.size(50.dp).clip(RoundedCornerShape(6.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    SearchSubtitle(row.type, row.creator, row.explicit)
                }
            }
        } }
    }
}
