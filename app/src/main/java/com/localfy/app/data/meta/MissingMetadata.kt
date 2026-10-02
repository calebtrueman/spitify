package com.localfy.app.data.meta

/** A value already in the file or chosen in the app wins over an online suggestion. */
internal object MissingMetadata {
    fun fill(stored: MetadataEdit, saved: MetadataEdit = MetadataEdit(), suggested: MetadataEdit = MetadataEdit()) = MetadataEdit(
        title = text(stored.title, saved.title, suggested.title),
        artist = text(stored.artist, saved.artist, suggested.artist),
        album = text(stored.album, saved.album, suggested.album),
        albumArtist = text(stored.albumArtist, saved.albumArtist, suggested.albumArtist),
        genre = text(stored.genre, saved.genre, suggested.genre),
        year = number(stored.year, saved.year, suggested.year),
        track = number(stored.track, saved.track, suggested.track),
        disc = number(stored.disc, saved.disc, suggested.disc),
    )

    fun incomplete(stored: MetadataEdit, saved: MetadataEdit): Boolean {
        val full = complete(stored, saved)
        return listOf(full.title, full.artist, full.album, full.albumArtist, full.genre).any { it.isNullOrBlank() } ||
            listOf(full.year, full.track, full.disc).any { it == null || it <= 0 }
    }

    fun complete(stored: MetadataEdit, additions: MetadataEdit) = MetadataEdit(
        stored.title ?: additions.title, stored.artist ?: additions.artist, stored.album ?: additions.album,
        stored.albumArtist ?: additions.albumArtist, stored.genre ?: additions.genre,
        stored.year ?: additions.year, stored.track ?: additions.track, stored.disc ?: additions.disc,
    )

    private fun text(present: String?, saved: String?, suggested: String?) =
        if (!present.isNullOrBlank()) null else saved?.takeIf { it.isNotBlank() } ?: suggested?.takeIf { it.isNotBlank() }
    private fun number(present: Int?, saved: Int?, suggested: Int?) =
        if (present != null && present > 0) null else saved?.takeIf { it > 0 } ?: suggested?.takeIf { it > 0 }
}
