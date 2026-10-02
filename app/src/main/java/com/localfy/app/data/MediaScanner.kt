package com.localfy.app.data

import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.guava.await

/** Reads the device's audio collection straight from MediaStore (no file walking needed). */
class MediaScanner(private val context: Context) {

    enum class Kind { Music, Podcasts, Audiobooks }

    suspend fun scan(minDurationMs: Long, podcasts: Boolean = false, kind: Kind = if (podcasts) Kind.Podcasts else Kind.Music): List<Song> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.CD_TRACK_NUMBER,
            MediaStore.Audio.Media.DISC_NUMBER,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.GENRE,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        val args = Bundle().apply {
            putString(
                android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                // DURATION is NULL for formats MediaStore can't parse (AIFF, AC-3...): keep those and measure them ourselves.
                run {
                    val dur = "(${MediaStore.Audio.Media.DURATION} >= ? OR ${MediaStore.Audio.Media.DURATION} IS NULL)"
                    val book = "(${MediaStore.Audio.Media.IS_AUDIOBOOK} != 0 OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE '%.m4b' OR " +
                        "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE '%Audiobook%' OR ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE '%Audio book%')"
                    when (kind) {
                        Kind.Podcasts -> "${MediaStore.Audio.Media.IS_PODCAST} != 0 AND NOT $book AND $dur"
                        Kind.Audiobooks -> "$book AND $dur"
                        Kind.Music -> "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.IS_PODCAST} = 0 AND NOT $book AND $dur"
                    }
                },
            )
            putStringArray(
                android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(minDurationMs.toString()),
            )
        }
        val songs = ArrayList<Song>()
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, args, null)
            ?.use { cur ->
                fun idx(name: String) = cur.getColumnIndexOrThrow(name)
                val id = idx(MediaStore.Audio.Media._ID)
                val title = idx(MediaStore.Audio.Media.TITLE)
                val artist = idx(MediaStore.Audio.Media.ARTIST)
                val album = idx(MediaStore.Audio.Media.ALBUM)
                val albumId = idx(MediaStore.Audio.Media.ALBUM_ID)
                val albumArtist = idx(MediaStore.Audio.Media.ALBUM_ARTIST)
                val duration = idx(MediaStore.Audio.Media.DURATION)
                val track = idx(MediaStore.Audio.Media.TRACK)
                val cdTrack = idx(MediaStore.Audio.Media.CD_TRACK_NUMBER)
                val disc = idx(MediaStore.Audio.Media.DISC_NUMBER)
                val year = idx(MediaStore.Audio.Media.YEAR)
                val genre = idx(MediaStore.Audio.Media.GENRE)
                val path = idx(MediaStore.Audio.Media.RELATIVE_PATH)
                val added = idx(MediaStore.Audio.Media.DATE_ADDED)
                val size = idx(MediaStore.Audio.Media.SIZE)
                val mime = idx(MediaStore.Audio.Media.MIME_TYPE)
                val display = idx(MediaStore.Audio.Media.DISPLAY_NAME)
                while (cur.moveToNext()) {
                    val rawArtist = cur.getString(artist).cleanTag() ?: UNKNOWN_ARTIST
                    // TRACK is encoded as disc*1000 + track on many devices.
                    val rawTrack = cur.getInt(track)
                    val trackNo = cur.getString(cdTrack)?.substringBefore('/')?.toIntOrNull() ?: (rawTrack % 1000)
                    val discNo = cur.getString(disc)?.substringBefore('/')?.toIntOrNull()
                        ?: (rawTrack / 1000).coerceAtLeast(1)
                    songs += Song(
                        id = cur.getLong(id),
                        title = cur.getString(title).cleanTag()
                            ?: cur.getString(display)?.substringBeforeLast('.') ?: "Unknown",
                        artist = rawArtist,
                        album = cur.getString(album).cleanTag() ?: "Unknown album",
                        albumId = cur.getLong(albumId),
                        albumArtist = cur.getString(albumArtist).cleanTag() ?: rawArtist,
                        durationMs = cur.getLong(duration),
                        track = trackNo,
                        disc = discNo,
                        year = cur.getInt(year),
                        genre = cur.getString(genre).cleanTag(),
                        folder = cur.getString(path) ?: "",
                        dateAddedSec = cur.getLong(added),
                        sizeBytes = cur.getLong(size),
                        mimeType = cur.getString(mime),
                        fileName = cur.getString(display) ?: "",
                        isPodcast = kind != Kind.Music,
                        isAudiobook = kind == Kind.Audiobooks,
                    )
                }
            }
        fillMissingDurations(songs).filter { it.durationMs >= minDurationMs || !it.playable }
    }

    private val durationCache = context.getSharedPreferences("durations", Context.MODE_PRIVATE)

    /** Measures files MediaStore couldn't (using our own extractors, incl. AIFF); cached per file. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun fillMissingDurations(songs: List<Song>): List<Song> = songs.map { song ->
        if (song.durationMs > 0 || !song.playable) return@map song
        val key = "${song.id}:${song.sizeBytes}"
        val cached = durationCache.getLong(key, -1)
        if (cached > 0) return@map song.copy(durationMs = cached)
        val measured: Long = runCatching<Long?> {
            kotlinx.coroutines.withTimeoutOrNull(5_000) {
                androidx.media3.inspector.MetadataRetriever.Builder(context, androidx.media3.common.MediaItem.fromUri(song.uri))
                    .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context, com.localfy.app.playback.LocalfyExtractors))
                    .build()
                    .use { r -> r.retrieveDurationUs().await() }
            }
        }.getOrNull()?.takeIf { it > 0 }?.div(1000) ?: return@map song
        durationCache.edit().putLong(key, measured).apply()
        song.copy(durationMs = measured)
    }

    private fun String?.cleanTag(): String? =
        this?.trim()?.takeUnless { it.isEmpty() || it.equals("<unknown>", ignoreCase = true) }

    companion object {
        const val UNKNOWN_ARTIST = "Unknown artist"
    }
}
