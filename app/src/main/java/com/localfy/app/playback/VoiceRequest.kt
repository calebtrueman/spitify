package com.localfy.app.playback

import com.localfy.app.data.Song
import java.text.Normalizer

/** Assistant requests can contain either plain words or separate song/artist/album fields. */
data class VoiceRequest(
    val query: String = "",
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val playlist: String? = null,
) {
    val empty get() = query.isBlank() && listOf(title, artist, album, genre, playlist).all { it.isNullOrBlank() }
}

data class VoiceSelection(val songs: List<Song>, val source: String)

internal object VoiceSearch {
    fun normalized(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun select(request: VoiceRequest, music: List<Song>, playlists: List<Pair<String, List<Song>>>, shows: List<Pair<String, List<Song>>>): VoiceSelection {
        val all = (music + shows.flatMap { it.second }).distinctBy { it.id }.filter { it.playable }
        fun equal(a: String, b: String) = normalized(a) == normalized(b)
        request.playlist?.takeIf { it.isNotBlank() }?.let { name ->
            return VoiceSelection(playlists.firstOrNull { equal(it.first, name) }?.second.orEmpty(), name)
        }
        val specified = listOf(request.title, request.artist, request.album, request.genre).any { !it.isNullOrBlank() }
        if (specified) {
            val matches = all.filter { song ->
                (request.title.isNullOrBlank() || equal(song.title, request.title)) &&
                    (request.artist.isNullOrBlank() || equal(song.artist, request.artist) || equal(song.albumArtist, request.artist)) &&
                    (request.album.isNullOrBlank() || equal(song.album, request.album)) &&
                    (request.genre.isNullOrBlank() || equal(song.genre.orEmpty(), request.genre))
            }
            return VoiceSelection(if (request.album.isNullOrBlank()) matches else matches.sortedWith(compareBy({ it.disc }, { it.track })),
                request.album ?: request.artist ?: request.title ?: request.genre ?: request.query)
        }
        val query = request.query
        playlists.firstOrNull { equal(it.first, query) }?.let { return VoiceSelection(it.second.filter { s -> s.playable }, it.first) }
        shows.firstOrNull { equal(it.first, query) }?.let { return VoiceSelection(it.second.filter { s -> s.playable }, it.first) }
        val albums = music.filter { equal(it.album, query) }
        if (albums.isNotEmpty()) return VoiceSelection(albums.sortedWith(compareBy({ it.disc }, { it.track })), albums[0].album)
        val artists = music.filter { equal(it.artist, query) || equal(it.albumArtist, query) }
        if (artists.isNotEmpty()) return VoiceSelection(artists, query)
        val titles = all.filter { equal(it.title, query) }
        if (titles.isNotEmpty()) return VoiceSelection(listOf(titles.first()), titles.first().title)
        val words = normalized(query).split(' ').filter { it.isNotBlank() && it != "by" }
        if (words.isEmpty()) return VoiceSelection(music.filter { it.playable }, "All songs")
        val matching = all.filter { song ->
            val text = normalized("${song.title} ${song.artist} ${song.album}")
            words.all { text.contains(it) }
        }
        return VoiceSelection(matching, query)
    }
}
