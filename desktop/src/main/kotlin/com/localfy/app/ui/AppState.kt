package com.localfy.app.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import com.localfy.app.data.LibraryRepository
import com.localfy.app.data.SmartCollection
import com.localfy.app.data.Song
import com.localfy.app.data.lyrics.LyricsRepository
import com.localfy.app.data.podcast.PodcastRepository
import com.localfy.app.playback.PlayerConnection
import java.net.URLDecoder
import java.net.URLEncoder

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val APPEARANCE = "appearance"
    const val EQUALIZER = "equalizer"
    const val ALBUM = "album/{id}"
    const val CATALOG_ALBUM = "catalogalbum/{album}"
    const val CATALOG_SONG = "catalogsong/{track}"
    const val ONLINE_ARTIST = "onlineartist/{artist}"
    const val SPOTIFY_PLAYLIST = "spotifyplaylist/{id}"
    const val SHARED_PLAYLIST = "sharedplaylist/{key}"
    const val FRIENDS = "friends"
    const val ARTIST = "artist/{name}"
    const val PLAYLIST = "playlist/{id}"
    const val SMART = "smart/{kind}"
    const val MIX = "mix/{key}"
    const val GENRE = "genre/{name}"
    const val FOLDER = "folder/{path}"
    const val PODCASTS = "podcasts"
    const val SHOW = "show/{id}"
    const val LOCAL_SHOW = "localshow/{name}"
    const val BOOKS = "books"
    const val PROFILE = "profile"
    const val BOOK = "book/{id}"
    const val LOCAL_BOOK = "localbook/{album}"

    /** Route arguments travel percent-encoded, so "/" inside a name never splits the route. */
    fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
    fun decode(value: String): String = runCatching { URLDecoder.decode(value, Charsets.UTF_8) }.getOrDefault(value)

    fun album(id: Long) = "album/$id"
    fun catalogAlbum(album: com.localfy.app.data.music.OnlineAlbum) = "catalogalbum/" + encode(org.json.JSONObject().apply {
        put("id", album.id); put("title", album.title); put("artist", album.artist); put("artwork", album.artwork)
    }.toString())
    fun catalogSong(track: com.localfy.app.data.music.OnlineTrack) = "catalogsong/" + encode(track.json())
    fun onlineArtist(artist: com.localfy.app.data.music.OnlineArtist) = "onlineartist/" + encode(org.json.JSONObject().put("id", artist.id).put("name", artist.name).put("artwork", artist.artwork).toString())
    fun spotifyPlaylist(id: String) = "spotifyplaylist/" + encode(id)
    fun sharedPlaylist(key: String) = "sharedplaylist/" + encode(key)
    fun artist(name: String) = "artist/${encode(name)}"
    fun playlist(id: Long) = "playlist/$id"
    fun smart(kind: SmartCollection.Kind) = "smart/${kind.name}"
    fun mix(key: String) = "mix/${encode(key)}"
    fun genre(name: String) = "genre/${encode(name)}"
    /** The storage root has an empty path, which wouldn't match "folder/{path}"; it travels as "/". */
    fun folder(path: String) = "folder/${encode(path.ifEmpty { "/" })}"
    fun show(id: Long) = "show/$id"
    fun localShow(name: String) = "localshow/${encode(name)}"
    fun book(id: Long) = "book/$id"
    fun localBook(albumId: Long) = "localbook/$albumId"
    fun onlineArtistByName(name: String) = "artist-online/${encode(name)}"
    fun friend(person: String) = "friend/${encode(person)}"
    fun incoming(link: String) = "incoming/${encode(link)}"

    /** "album/12" → "album/{}", used to compare which kind of page is showing. */
    fun patternOf(route: String): String {
        val parts = route.split('/')
        return if (parts.size == 1) route else parts.first() + "/" + List(parts.size - 1) { "{}" }.joinToString("/")
    }

    /** Decoded arguments of a concrete route ("album/12" → ["12"]). */
    fun args(route: String): List<String> = route.split('/').drop(1).map(::decode)
}

/** Extra actions a song's "more" menu can offer depending on where it was opened. */
data class SongMenuExtras(val removeLabel: String? = null, val onRemove: (() -> Unit)? = null)

@Stable
class AppActions(
    val repo: LibraryRepository,
    val player: PlayerConnection,
    val lyrics: LyricsRepository,
    val podcasts: PodcastRepository,
    val taste: com.localfy.app.data.taste.TasteRepository,
    val profiles: com.localfy.app.data.taste.ProfileRepository,
    val nav: Navigator,
    val openSongMenu: (Song, SongMenuExtras) -> Unit,
    val addToPlaylist: (List<Song>) -> Unit,
    /** Opens the tag/artwork editor; albumMode edits every song in the list together. */
    val editMetadata: (List<Song>, Boolean) -> Unit,
    val openPlayer: () -> Unit,
) {

    /** Opens [route] unless it's exactly the page already showing (album → another album still stacks). */
    fun navigate(route: String) {
        if (nav.currentRoute == route) return
        nav.navigate(route)
    }

    /** The editor works on the song as currently shown (overrides applied), keyed by id. */
    fun rawFor(song: Song): Song = song

    /** Equaliser is a full screen; collapse any player overlay first so it's visible. */
    var collapsePlayer: () -> Unit = {}
    fun openEqualizer() { collapsePlayer(); navigate(Routes.EQUALIZER) }

    fun navigateTopLevel(route: String) = nav.navigateTopLevel(route, Routes.HOME, TopLevelRoutes)
}

/** Sidebar destinations; each keeps its own back stack. */
val TopLevelRoutes = setOf(Routes.HOME, Routes.SEARCH, Routes.PODCASTS, Routes.BOOKS, Routes.LIBRARY, Routes.FRIENDS)

val LocalApp = staticCompositionLocalOf<AppActions> { error("AppActions not provided") }
