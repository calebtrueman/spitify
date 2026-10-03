package com.localfy.app.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val albumArtist: String,
    val durationMs: Long,
    val track: Int,
    val disc: Int,
    val year: Int,
    val genre: String?,
    val folder: String,
    val dateAddedSec: Long,
    val sizeBytes: Long,
    val mimeType: String?,
    /** File name on disk, e.g. "01 - Intro.flac" (used to find matching .lrc files). */
    val fileName: String = "",
    /** Streams / downloaded files (podcast episodes); null means a MediaStore track. */
    val sourceUri: Uri? = null,
    /** Remote artwork (podcasts); null means artwork comes from MediaStore / tags. */
    val artUrl: String? = null,
    /** Spoken word (podcast episode or audiobook chapter): resume, skip buttons, no crossfade. */
    val isPodcast: Boolean = false,
    val isAudiobook: Boolean = false,
    /** Podcast episode row id (songs representing episodes use the negative of it as [id]). */
    val episodeId: Long? = null,
    /** Bumped when custom artwork changes, so image caches refresh. */
    val artVersion: Long = 0,
    val explicit: Boolean? = null,
    /** Separate artists when supplied by the source; artist retains the complete credit. */
    val artistNames: List<String>? = null,
) {
    val creditedArtists: List<String> get() = ArtistCredits.names(artist, artistNames, albumArtist)
    val primaryArtist: String get() = artistNames?.firstOrNull() ?: creditedArtists.firstOrNull() ?: artist

    val uri: Uri get() = sourceUri ?: ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

    /** Album URI; MediaStore can produce a thumbnail for it (used for notification / lock-screen art). */
    val albumArtUri: Uri get() = artUrl?.let(Uri::parse) ?: ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)

    /** Formats ExoPlayer has no container support for (shown greyed out, skipped in queues). */
    val playable: Boolean get() = fileName.substringAfterLast('.', "").lowercase() !in UNSUPPORTED_EXTENSIONS

    companion object {
        val UNSUPPORTED_EXTENSIONS = setOf("wma", "ape", "wv", "dsf", "dff", "mpc", "tta", "ra", "rm", "mid", "midi")
    }
}

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val songs: List<Song>,
) {
    val cover: Song get() = songs.first()
    val durationMs: Long get() = songs.sumOf { it.durationMs }
}

data class Artist(
    val name: String,
    val songs: List<Song>,
    val albums: List<Album>,
) {
    val cover: Song get() = ownCover ?: songs.first()
    val ownCover: Song? get() = songs.firstOrNull { it.primaryArtist.equals(name, true) || it.albumArtist.equals(name, true) }
}

data class Genre(val name: String, val songs: List<Song>)

data class Folder(val path: String, val songs: List<Song>) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
}

data class Playlist(
    val id: Long,
    val name: String,
    val songs: List<Song>,
    val updatedAt: Long,
)

data class PlayStat(val songId: Long, val playCount: Int, val lastPlayed: Long, val skipCount: Int)

/** Immutable snapshot of everything on the device, grouped once so the UI can just read it. */
data class Library(
    val songs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val genres: List<Genre> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val creditedArtists: List<Artist> = artists,
) {
    val songById: Map<Long, Song> by lazy { songs.associateBy { it.id } }
    val albumById: Map<Long, Album> by lazy { albums.associateBy { it.id } }
    val artistByName: Map<String, Artist> by lazy {
        buildMap {
            creditedArtists.forEach { artist ->
                put(artist.name, artist)
                artist.songs.flatMap { it.creditedArtists }.filter { it.equals(artist.name, true) }.forEach { put(it, artist) }
            }
        }
    }

    val isEmpty: Boolean get() = songs.isEmpty()

    companion object {
        fun from(source: List<Song>): Library {
            val known = source.flatMap { it.artistNames ?: listOf(AlbumGrouping.albumArtist(it.albumArtist), AlbumGrouping.albumArtist(it.artist)) }.filterNot { ',' in it }
            val songs = source.map { it.copy(artistNames = ArtistCredits.names(it.artist, it.artistNames, it.albumArtist, known)) }
            val titleOrder = compareBy<Song, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
            val albums = songs.groupBy { it.albumId }.map { (id, tracks) ->
                val sorted = tracks.sortedWith(compareBy<Song>({ it.disc }, { it.track }).then(titleOrder))
                val first = sorted.first()
                Album(
                    id = id,
                    title = first.album,
                    artist = first.albumArtist,
                    year = sorted.maxOf { it.year },
                    songs = sorted,
                )
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

            val artists = songs.flatMap { song -> song.creditedArtists.map { it to song } }.groupBy { it.first.lowercase() }.map { (key, entries) ->
                val tracks = entries.map { it.second }
                val ownAlbums = albums.filter { it.artist.lowercase() == key || it.songs.any { song -> song.primaryArtist.lowercase() == key } }
                Artist(entries.first().first, tracks.sortedWith(titleOrder), ownAlbums.sortedByDescending { it.year })
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

            val genres = songs.filter { !it.genre.isNullOrBlank() }
                .groupBy { it.genre!!.trim() }
                .map { (name, tracks) -> Genre(name, tracks.sortedWith(titleOrder)) }
                .sortedByDescending { it.songs.size }

            val folders = songs.groupBy { it.folder }
                .map { (path, tracks) -> Folder(path, tracks.sortedWith(titleOrder)) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.path })

            return Library(songs.sortedWith(titleOrder), albums, artists.filter { it.ownCover != null }, genres, folders, artists)
        }
    }
}
