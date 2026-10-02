package com.localfy.app.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import com.localfy.app.LocalfyApp
import com.localfy.app.data.Song
import com.localfy.app.data.SmartCollection
import com.localfy.app.data.podcast.KIND_AUDIOBOOK
import com.localfy.app.data.podcast.KIND_PODCAST
import com.localfy.app.data.podcast.resumeKey
import com.localfy.app.data.podcast.toSong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer

/**
 * The browse tree Android Auto (and Android Automotive, Assistant, Wear, etc.) sees.
 *
 *   root ─ For you ─ Shuffle all · Recently played · On repeat · Daily mixes · Liked Songs
 *        ├ Library ─ Playlists · Albums · Artists · Songs · Genres
 *        ├ Podcasts ─ shows → episodes
 *        └ Books ─ books → chapters
 *
 * Playable items are "<parent>/<songId>" so a tap can queue the surrounding album/playlist
 * (expanded in [expand]); the queue itself always uses plain song ids like the phone UI.
 */
class AutoLibrary(private val context: Context) {
    private val app get() = context.applicationContext as LocalfyApp

    // ---------- tree ----------

    fun root(): MediaItem = folder(ROOT, "Spitify", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    suspend fun children(parentId: String): List<MediaItem> {
        awaitLibrary()
        val lib = app.library.library.value
        val smart = app.library.smart.value
        return when {
            parentId == ROOT -> listOf(
                folder(TAB_HOME, "For you", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                folder(TAB_LIBRARY, "Library", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                folder(TAB_PODCASTS, "Podcasts", MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS),
                folder(TAB_BOOKS, "Books", MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS),
            )
            parentId == TAB_HOME -> {
                withTimeoutOrNull(3_000) { app.library.mixes.first { it.isNotEmpty() } }
                buildList {
                add(action(SHUFFLE_ALL, "Shuffle all", "${lib.songs.size} songs"))
                smart[SmartCollection.Kind.RecentlyPlayed]?.songs?.takeIf { it.isNotEmpty() }?.let { add(folder("smart:${SmartCollection.Kind.RecentlyPlayed.name}", "Recently played", MediaMetadata.MEDIA_TYPE_PLAYLIST, it.first(), grid = true)) }
                smart[SmartCollection.Kind.MostPlayed]?.songs?.takeIf { it.isNotEmpty() }?.let { add(folder("smart:${SmartCollection.Kind.MostPlayed.name}", "On repeat", MediaMetadata.MEDIA_TYPE_PLAYLIST, it.first(), grid = true)) }
                app.library.mixes.value.forEach { m -> add(folder("mix:${m.key}", m.title, MediaMetadata.MEDIA_TYPE_PLAYLIST, m.cover, m.description, grid = true)) }
                smart[SmartCollection.Kind.AllSongs]?.songs?.takeIf { it.isNotEmpty() }?.let { add(folder("smart:${SmartCollection.Kind.AllSongs.name}", "All Songs", MediaMetadata.MEDIA_TYPE_PLAYLIST, it.first(), grid = true)) }
                smart[SmartCollection.Kind.RecentlyAdded]?.songs?.takeIf { it.isNotEmpty() }?.let { add(folder("smart:${SmartCollection.Kind.RecentlyAdded.name}", "Recently added", MediaMetadata.MEDIA_TYPE_PLAYLIST, it.first(), grid = true)) }
                }
            }
            parentId == TAB_LIBRARY -> listOf(
                folder(LIB_PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                folder(LIB_ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, childGrid = true),
                folder(LIB_ARTISTS, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, childGrid = true),
                folder(LIB_SONGS, "Songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                folder(LIB_GENRES, "Genres", MediaMetadata.MEDIA_TYPE_FOLDER_GENRES),
            )
            parentId == LIB_PLAYLISTS -> buildList {
                smart[SmartCollection.Kind.AllSongs]?.songs?.takeIf { it.isNotEmpty() }?.let { add(folder("smart:${SmartCollection.Kind.AllSongs.name}", "All Songs", MediaMetadata.MEDIA_TYPE_PLAYLIST, it.first())) }
                app.library.playlists.value.forEach { p -> add(folder("playlist:${p.id}", p.name, MediaMetadata.MEDIA_TYPE_PLAYLIST, p.songs.firstOrNull(), "${p.songs.size} songs")) }
            }
            parentId == LIB_ALBUMS -> lib.albums.map { a -> folder("album:${a.id}", a.title, MediaMetadata.MEDIA_TYPE_ALBUM, a.cover, a.artist) }
            parentId == LIB_ARTISTS -> lib.artists.map { a -> folder("artist:${a.name}", a.name, MediaMetadata.MEDIA_TYPE_ARTIST, a.cover, "${a.songs.size} songs") }
            parentId == LIB_GENRES -> lib.genres.map { g -> folder("genre:${g.name}", g.name, MediaMetadata.MEDIA_TYPE_GENRE, g.songs.first(), "${g.songs.size} songs") }
            parentId == TAB_PODCASTS -> buildList {
                app.podcasts.shows.value.filter { it.podcast.kind == KIND_PODCAST && it.podcast.subscribedAt > 0L }.forEach { s ->
                    add(folder("show:${s.id}", s.podcast.title, MediaMetadata.MEDIA_TYPE_PODCAST, null, s.podcast.author, artUrl = s.podcast.artworkUrl, grid = true))
                }
                if (app.library.localPodcasts.value.isNotEmpty()) add(folder(LOCAL_PODCASTS, "On this device", MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS))
            }
            parentId == TAB_BOOKS -> buildList {
                app.podcasts.shows.value.filter { it.podcast.kind == KIND_AUDIOBOOK }.forEach { s ->
                    add(folder("book:lv${s.id}", s.podcast.title, MediaMetadata.MEDIA_TYPE_AUDIO_BOOK, null, s.podcast.author, artUrl = s.podcast.artworkUrl, grid = true))
                }
                app.library.localBooks.value.groupBy { it.albumId }.forEach { (id, ch) ->
                    add(folder("book:lb$id", ch.first().album, MediaMetadata.MEDIA_TYPE_AUDIO_BOOK, ch.first(), ch.first().albumArtist, grid = true))
                }
            }
            else -> songsFor(parentId).filter { it.playable }.map { playable(parentId, it) }
        }
    }

    suspend fun item(id: String): MediaItem? {
        if (id == ROOT) return root()
        val slash = id.lastIndexOf('/')
        if (slash > 0) {
            val parent = id.substring(0, slash)
            return songsFor(parent).firstOrNull { it.id.toString() == id.substring(slash + 1) }?.let { playable(parent, it) }
        }
        return null
    }

    /** Song lists behind every browsable node (also used to expand a tap into a queue). */
    suspend fun songsFor(parent: String): List<Song> {
        awaitLibrary()
        val lib = app.library.library.value
        val (kind, arg) = parent.substringBefore(':') to parent.substringAfter(':', "")
        return when (kind) {
            "smart" -> runCatching { app.library.smart.value[SmartCollection.Kind.valueOf(arg)]?.songs }.getOrNull().orEmpty()
            "mix" -> app.library.mixes.value.firstOrNull { it.key == arg }?.songs.orEmpty()
            "playlist" -> app.library.playlists.value.firstOrNull { it.id.toString() == arg }?.songs.orEmpty()
            "album" -> lib.albumById[arg.toLongOrNull()]?.songs.orEmpty()
            "artist" -> lib.artistByName[arg]?.songs.orEmpty()
            "genre" -> lib.genres.firstOrNull { it.name == arg }?.songs.orEmpty()
            "songs" -> lib.songs
            "show" -> app.podcasts.shows.value.firstOrNull { it.id.toString() == arg }?.let { s -> s.episodes.map { it.toSong(s.podcast) } }.orEmpty()
            "localpodcasts" -> app.library.localPodcasts.value
            "book" -> if (arg.startsWith("lv")) {
                app.podcasts.shows.value.firstOrNull { "lv${it.id}" == arg }?.let { s -> s.episodes.sortedBy { it.position }.map { it.toSong(s.podcast) } }.orEmpty()
            } else {
                app.library.localBooks.value.filter { "lb${it.albumId}" == arg }
                    .sortedWith(compareBy<Song>({ it.disc }, { it.track }).thenBy { it.fileName })
            }
            "search" -> search(arg)
            else -> emptyList()
        }
    }

    // ---------- playback requests ----------

    /** Turns what a car/voice controller asked for into a real queue (+ start index/position). */
    suspend fun expand(items: List<MediaItem>, startIndex: Int, startPositionMs: Long): Triple<List<MediaItem>, Int, Long> {
        val first = items.firstOrNull() ?: return Triple(emptyList(), 0, 0)
        // Voice: "play <query> on Spitify" (empty query = just play something).
        first.requestMetadata.searchQuery?.let { q ->
            awaitLibrary()
            val extras = first.requestMetadata.extras
            val request = voiceRequest(q, extras)
            if (request.empty) resumption()?.let { return it }
            val selected = voice(request)
            val songs = if (request.empty) selected.songs.shuffled() else selected.songs
            require(songs.isNotEmpty()) { "Nothing in your Spitify library matches this request." }
            val playable = if (songs.first().isPodcast && !songs.first().isAudiobook) songs.take(1) else songs
            val position = if (playable.first().isPodcast || playable.first().isAudiobook) app.podcasts.resumePosition(playable.first().resumeKey) else 0L
            return Triple(playable.map { it.toMediaItem().withArt() }, 0, position)
        }
        if (first.mediaId == SHUFFLE_ALL) {
            awaitLibrary()
            return Triple(app.library.library.value.songs.filter { it.playable }.shuffled().map { it.toMediaItem().withArt() }, 0, 0)
        }
        val slash = first.mediaId.lastIndexOf('/')
        if (items.size == 1 && slash > 0) {
            val parent = first.mediaId.substring(0, slash)
            val songs = songsFor(parent).filter { it.playable }
            val idx = songs.indexOfFirst { it.id.toString() == first.mediaId.substring(slash + 1) }.coerceAtLeast(0)
            if (songs.isEmpty()) return Triple(emptyList(), 0, 0)
            val picked = songs[idx]
            return when {
                // Podcasts: just that episode, from where you left off.
                picked.isPodcast && !picked.isAudiobook -> Triple(listOf(picked.toMediaItem().withArt()), 0, app.podcasts.resumePosition(picked.resumeKey))
                // Books: that chapter onward, resumed.
                picked.isAudiobook -> Triple(songs.map { it.toMediaItem().withArt() }, idx, app.podcasts.resumePosition(picked.resumeKey))
                // Music: the whole album/playlist from the tapped song.
                else -> Triple(songs.map { it.toMediaItem().withArt() }, idx, 0)
            }
        }
        // Our own UI already sends complete queues of plain song ids.
        return Triple(items, startIndex, startPositionMs)
    }

    /** Last queue, for "resume" when the car connects / the play button is pressed cold. */
    suspend fun resumption(): Triple<List<MediaItem>, Int, Long>? {
        awaitLibrary()
        val prefs = context.getSharedPreferences(PlayerPrefs.FILE, Context.MODE_PRIVATE)
        val ids = prefs.getString("queue", null)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()
        if (ids.any { it < 0 && app.musicStreams.lookup(it) == null }) withTimeoutOrNull(3_000) { app.podcasts.episodeSongs.first { it.isNotEmpty() } }
        val manual = prefs.getString("manual_queue_indices", null)?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty().toSet()
        val items = ids.mapIndexedNotNull { i, id -> app.resolve(id)?.toMediaItem()?.withArt()?.let { if (i in manual) it.asManualQueueItem() else it } }
        if (items.isEmpty()) return null
        return Triple(items, prefs.getInt("index", 0).coerceIn(items.indices), prefs.getLong("position", 0))
    }

    // ---------- search ----------

    suspend fun voice(request: VoiceRequest): VoiceSelection {
        awaitLibrary()
        app.podcasts.start()
        val playlists = app.library.playlists.value.map { it.name to it.songs } +
            listOf("All songs" to app.library.library.value.songs)
        val shows = app.podcasts.shows.value.filter { it.podcast.subscribedAt > 0 || it.podcast.kind == KIND_AUDIOBOOK }.map { show ->
            show.podcast.title to show.episodes.sortedWith(compareBy({ if (show.podcast.kind == KIND_AUDIOBOOK) it.position else 0 }, { -it.pubDate })).map { it.toSong(show.podcast) }
        } + (app.library.localBooks.value + app.library.localPodcasts.value).groupBy { it.album }.map { (name, songs) ->
            name to songs.sortedWith(compareBy({ it.disc }, { it.track }))
        }
        return VoiceSearch.select(request, app.library.library.value.songs, playlists, shows)
    }

    suspend fun search(query: String): List<Song> {
        awaitLibrary()
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val all = app.library.library.value.songs + app.library.localBooks.value + app.library.localPodcasts.value + app.podcasts.episodeSongs.value.values
        fun score(s: Song): Int {
            val title = fold(s.title); val artist = fold(s.artist); val album = fold(s.album)
            val hay = "$title $artist $album"
            if (!words.all { hay.contains(it) }) return -1
            return (if (words.all { artist.contains(it) }) 3 else 0) + (if (words.all { title.contains(it) }) 2 else 0) + (if (words.all { album.contains(it) }) 1 else 0)
        }
        return all.map { it to score(it) }.filter { it.second >= 0 }.sortedByDescending { it.second }.map { it.first }.take(100)
    }

    fun searchResults(query: String, songs: List<Song>): List<MediaItem> = songs.map { playable("search:$query", it) }

    // ---------- item builders ----------

    private fun folder(
        id: String, title: String, type: Int, cover: Song? = null, subtitle: String? = null,
        artUrl: String? = null, grid: Boolean = false, childGrid: Boolean = false,
    ): MediaItem {
        val extras = Bundle().apply {
            if (childGrid) putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
            if (grid) putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        }
        val art = artUrl?.let { ArtProvider.remote(context, it) } ?: cover?.let { ArtProvider.uriFor(context, it) }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(art)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(type)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun action(id: String, title: String, subtitle: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setIsBrowsable(false).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST).build(),
        )
        .build()

    private fun playable(parent: String, song: Song): MediaItem {
        val base = song.toMediaItem().withArt()
        val extras = Bundle()
        if (song.isPodcast) {
            app.podcasts.resume.value[song.resumeKey].let { r ->
                val status = when {
                    r == null -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
                    r.played -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
                    else -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
                }
                extras.putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, status)
                if (r != null && !r.played && r.durationMs > 0) extras.putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, r.positionMs / r.durationMs.toDouble())
            }
        }
        return base.buildUpon()
            .setMediaId("$parent/${song.id}")
            .setMediaMetadata(base.mediaMetadata.buildUpon().setSubtitle(if (song.isPodcast) song.album else song.artist).setExtras(extras).build())
            .build()
    }

    private fun MediaItem.withArt(): MediaItem {
        val song = mediaId.toLongOrNull()?.let(app::resolve) ?: return this
        return buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setArtworkUri(ArtProvider.uriFor(context, song)).build()).build()
    }

    private suspend fun awaitLibrary() {
        app.library.ensureStarted()
        withTimeoutOrNull(6_000) { app.library.library.first { !it.isEmpty } }
    }

    private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

    companion object {
        fun voiceRequest(query: String, extras: Bundle?) = VoiceRequest(
            query, extras?.getString(android.provider.MediaStore.EXTRA_MEDIA_TITLE),
            extras?.getString(android.provider.MediaStore.EXTRA_MEDIA_ARTIST),
            extras?.getString(android.provider.MediaStore.EXTRA_MEDIA_ALBUM),
            extras?.getString(android.provider.MediaStore.EXTRA_MEDIA_GENRE),
            extras?.getString(android.provider.MediaStore.EXTRA_MEDIA_PLAYLIST),
        )
        const val ROOT = "root"
        const val TAB_HOME = "tab:home"
        const val TAB_LIBRARY = "tab:library"
        const val TAB_PODCASTS = "tab:podcasts"
        const val TAB_BOOKS = "tab:books"
        const val LIB_PLAYLISTS = "lib:playlists"
        const val LIB_ALBUMS = "lib:albums"
        const val LIB_ARTISTS = "lib:artists"
        const val LIB_SONGS = "songs:all"
        const val LIB_GENRES = "lib:genres"
        const val LOCAL_PODCASTS = "localpodcasts:"
        const val SHUFFLE_ALL = "action:shuffle_all"
    }
}
