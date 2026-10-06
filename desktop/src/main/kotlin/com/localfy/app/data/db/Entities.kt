package com.localfy.app.data.db

/*
 * Desktop equivalents of the Android app's Room entities (same names and fields, no annotations).
 * They're stored as JSON by the desktop repositories; sharing the shapes keeps the ported code
 * and UI identical to the phone app.
 */

data class PlaylistEntity(val id: Long = 0, val name: String, val createdAt: Long, val updatedAt: Long)

data class PlaylistEntryEntity(val entryId: Long = 0, val playlistId: Long, val songId: Long, val position: Int)

data class LikedEntity(val songId: Long, val likedAt: Long)

data class PlayStatEntity(val songId: Long, val playCount: Int, val lastPlayed: Long, val skipCount: Int)

/** Cached lyrics lookups (including "not found" so we don't hit the network repeatedly). */
data class LyricsEntity(
    val songId: Long, val synced: String?, val plain: String?, val source: String,
    val offsetMs: Int, val fetchedAt: Long, val notFound: Boolean,
)

data class PodcastEntity(
    val id: Long = 0, val feedUrl: String, val title: String, val author: String, val description: String,
    val artworkUrl: String?, val lastRefreshed: Long, val subscribedAt: Long,
    /** "podcast" or "audiobook" (LibriVox books reuse the feed machinery). */
    val kind: String = "podcast",
)

data class EpisodeEntity(
    val id: Long = 0, val podcastId: Long, val guid: String, val title: String, val description: String,
    val audioUrl: String, val mimeType: String?, val pubDate: Long, val durationMs: Long, val artworkUrl: String?,
    val downloadId: Long?, val localPath: String?,
    /** Order within the feed (audiobook chapter number). */
    val position: Int = 0,
)

/** Resume position + played state for anything long-form (episodes, local podcasts, audiobooks). */
data class ResumeEntity(val mediaKey: String, val positionMs: Long, val durationMs: Long, val played: Boolean, val updatedAt: Long)

/** Metadata corrections layered over the file's tags. */
data class MetadataOverrideEntity(
    val songId: Long, val fileName: String, val title: String?, val artist: String?, val album: String?,
    val albumArtist: String?, val genre: String?, val year: Int?, val track: Int?, val disc: Int?,
    /** "user" edits always win over "online" auto-fixes. */
    val source: String, val updatedAt: Long,
)

/** One listen: the raw signal the recommendation engine learns from. */
data class PlayEventEntity(
    val id: Long = 0, val songId: Long, val startedAt: Long, val listenedMs: Long, val durationMs: Long,
    val completed: Boolean, val skipped: Boolean, val source: String?,
)
