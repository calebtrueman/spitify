package com.localfy.app.ui

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController
import com.localfy.app.data.LibraryRepository
import com.localfy.app.data.SmartCollection
import com.localfy.app.data.Song
import com.localfy.app.data.lyrics.LyricsRepository
import com.localfy.app.data.podcast.PodcastRepository
import com.localfy.app.playback.PlayerConnection

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

    fun album(id: Long) = "album/$id"
    fun catalogAlbum(album: com.localfy.app.data.music.OnlineAlbum) = "catalogalbum/" + Uri.encode(org.json.JSONObject().apply {
        put("id", album.id); put("title", album.title); put("artist", album.artist); put("artwork", album.artwork)
    }.toString())
    fun catalogSong(track: com.localfy.app.data.music.OnlineTrack) = "catalogsong/" + Uri.encode(track.json())
    fun artist(name: String) = "artist/${Uri.encode(name)}"
    fun playlist(id: Long) = "playlist/$id"
    fun smart(kind: SmartCollection.Kind) = "smart/${kind.name}"
    fun mix(key: String) = "mix/${Uri.encode(key)}"
    fun genre(name: String) = "genre/${Uri.encode(name)}"
    fun folder(path: String) = "folder/${Uri.encode(path)}"
    fun show(id: Long) = "show/$id"
    fun localShow(name: String) = "localshow/${Uri.encode(name)}"
    fun book(id: Long) = "book/$id"
    fun localBook(albumId: Long) = "localbook/$albumId"
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
    val nav: NavHostController,
    val openSongMenu: (Song, SongMenuExtras) -> Unit,
    val addToPlaylist: (List<Song>) -> Unit,
    /** Opens the tag/artwork editor; albumMode edits every song in the list together. */
    val editMetadata: (List<Song>, Boolean) -> Unit,
    val openPlayer: () -> Unit,
) {
    fun navigate(route: String) = nav.navigate(route) { launchSingleTop = true }

    /** The editor works on the song as currently shown (overrides applied), keyed by id. */
    fun rawFor(song: Song): Song = song

    /** Equaliser is a full screen; collapse any player overlay first so it's visible. */
    var collapsePlayer: () -> Unit = {}
    fun openEqualizer() { collapsePlayer(); navigate(Routes.EQUALIZER) }

    fun navigateTopLevel(route: String) = nav.navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

val LocalApp = staticCompositionLocalOf<AppActions> { error("AppActions not provided") }
