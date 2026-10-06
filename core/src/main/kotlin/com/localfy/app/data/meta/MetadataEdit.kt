package com.localfy.app.data.meta

/** Fields a user (or the auto-fixer) can set; null = keep the file's value. */
data class MetadataEdit(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val track: Int? = null,
    val disc: Int? = null,
)
