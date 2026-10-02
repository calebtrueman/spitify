package com.localfy.app.data

object AlbumGrouping {
    fun albumArtist(credit: String): String = credit
        .split(Regex("(?i)\\s+(?:feat\\.?|ft\\.?|featuring)\\s+|\\s*;\\s*"), limit = 2).first().trim()

    fun merge(source: List<Song>): List<Song> {
        val songs = source.map { if (it.album.isBlank()) it.copy(album = "Unknown album") else it }
        val creditsByAlbum = songs.groupBy { it.album.trim().lowercase() }
            .mapValues { (_, tracks) -> tracks.map { albumArtist(it.albumArtist) }.distinct().sortedByDescending { it.length } }
        val normalized = songs.map { song ->
            val credit = albumArtist(song.albumArtist)
            // A comma can belong to an artist's name. Only remove a guest when this same
            // album also contains a track credited to that exact main artist.
            val main = creditsByAlbum[song.album.trim().lowercase()]?.firstOrNull { credit.startsWith("$it, ", ignoreCase = true) } ?: credit
            song.copy(albumArtist = main)
        }
        val groups = normalized.groupBy { it.album.trim().lowercase() + "\u0000" + it.albumArtist.lowercase() }
        val mapped = mutableMapOf<Long, Song>()
        for (tracks in groups.values) {
            val main = tracks.sortedWith(compareBy<Song> { it.albumId < 0 }.thenBy { it.albumId }).first()
            val id = main.albumId
            val artist = albumArtist(main.albumArtist)
            tracks.forEach { mapped[it.id] = it.copy(albumId = id, albumArtist = artist) }
        }
        return songs.map { mapped[it.id] ?: it }
    }
}
