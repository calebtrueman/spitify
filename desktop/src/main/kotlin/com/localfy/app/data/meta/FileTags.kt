package com.localfy.app.data.meta

import com.localfy.app.data.Images
import com.localfy.app.data.Song
import com.localfy.app.data.file
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.images.ArtworkFactory
import org.jaudiotagger.tag.mp4.Mp4FieldKey
import org.jaudiotagger.tag.mp4.Mp4Tag
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Reads and writes audio file tags with jaudiotagger (MP3/ID3, FLAC, Ogg, Opus, M4A/ALAC, WAV,
 * AIFF, WMA, DSF). Writes never touch the original in place: the tags are written to a hidden
 * copy next to it, checked by reading them back, then moved over the original atomically, so a
 * crash or a full disk can't leave a half-written song.
 */
object FileTags {
    /** Keeps jaudiotagger's very chatty java.util.logging output out of the console (held strongly so it sticks). */
    private val quiet: Logger = Logger.getLogger("org.jaudiotagger").apply { level = Level.OFF }
    private val lock = Mutex()

    /** Everything the library needs from one file. */
    data class Info(
        val title: String?, val artist: String?, val album: String?, val albumArtist: String?,
        val genre: String?, val year: Int?, val track: Int?, val disc: Int?,
        val durationMs: Long, val hasArtwork: Boolean, val explicit: Boolean?,
        /** "podcast" when the file says it's a podcast (iTunes pcst / genre Podcast). */
        val podcast: Boolean = false,
    )

    data class Snapshot(val fields: MetadataEdit, val artwork: ByteArray?)

    private fun fields(edit: MetadataEdit) = mapOf(
        FieldKey.TITLE to edit.title, FieldKey.ARTIST to edit.artist, FieldKey.ALBUM to edit.album,
        FieldKey.ALBUM_ARTIST to edit.albumArtist, FieldKey.GENRE to edit.genre,
        FieldKey.YEAR to edit.year?.toString(), FieldKey.TRACK to edit.track?.toString(),
        FieldKey.DISC_NO to edit.disc?.toString(),
    ).filterValues { it != null }

    private fun open(file: File): AudioFile {
        quiet.level = Level.OFF
        return AudioFileIO.read(file)
    }

    private fun Tag.text(key: FieldKey): String? = runCatching { getFirst(key) }.getOrNull()
        ?.replace("\u0000", "")?.trim()?.takeUnless { it.isEmpty() || it.equals("<unknown>", true) }

    private fun Tag.number(key: FieldKey): Int? = text(key)?.substringBefore('/')?.trim()?.take(4)?.toIntOrNull()?.takeIf { it > 0 }

    private fun Tag.year(): Int? = text(FieldKey.YEAR)?.let { Regex("(\\d{4})").find(it)?.value?.toIntOrNull() }?.takeIf { it in 1000..2999 }

    /** Reads tags + duration. Throws for unreadable files (the scanner falls back to FFmpeg). */
    fun info(file: File): Info {
        val audio = open(file)
        val tag = audio.tag
        val header = audio.audioHeader
        val duration = runCatching { (header.preciseTrackLength * 1000).toLong() }.getOrNull()?.takeIf { it > 0 }
            ?: runCatching { header.trackLength * 1000L }.getOrDefault(0L)
        if (tag == null) return Info(null, null, null, null, null, null, null, null, duration, false, null)
        val explicit = runCatching {
            when (tag) {
                is Mp4Tag -> when (tag.getFirst(Mp4FieldKey.RATING)) { "1", "4" -> true; "2" -> false; else -> null }
                else -> tag.getFirst("ITUNESADVISORY").takeIf { it.isNotBlank() }?.let { it == "1" }
            }
        }.getOrNull()
        val podcast = runCatching { tag is Mp4Tag && (tag.getFirst(Mp4FieldKey.PODCAST_URL).isNotBlank() || tag.getFirst(Mp4FieldKey.PODCAST_KEYWORD).isNotBlank()) }.getOrDefault(false)
        val hasArt = runCatching { tag.artworkList.isNotEmpty() }.getOrDefault(false)
        return Info(
            title = tag.text(FieldKey.TITLE), artist = tag.text(FieldKey.ARTIST), album = tag.text(FieldKey.ALBUM),
            albumArtist = tag.text(FieldKey.ALBUM_ARTIST), genre = tag.text(FieldKey.GENRE)?.let(::cleanGenre),
            year = tag.year(), track = tag.number(FieldKey.TRACK), disc = tag.number(FieldKey.DISC_NO),
            durationMs = duration, hasArtwork = hasArt, explicit = explicit, podcast = podcast,
        )
    }

    /** ID3v1-style numeric genres ("(17)" / "17") into names jaudiotagger knows. */
    private fun cleanGenre(raw: String): String? {
        val id = Regex("^\\((\\d{1,3})\\)$|^(\\d{1,3})$").find(raw)?.groupValues?.firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
        val name = id?.let { runCatching { org.jaudiotagger.tag.reference.GenreTypes.getInstanceOf().getValueForId(it) }.getOrNull() }
        return (name ?: raw).trim().takeIf { it.isNotEmpty() }
    }

    /** The file's own tags (no app overrides) and embedded cover. */
    fun read(file: File): Snapshot {
        val tag = runCatching { open(file).tag }.getOrNull() ?: return Snapshot(MetadataEdit(), null)
        return Snapshot(MetadataEdit(
            title = tag.text(FieldKey.TITLE), artist = tag.text(FieldKey.ARTIST), album = tag.text(FieldKey.ALBUM),
            albumArtist = tag.text(FieldKey.ALBUM_ARTIST), genre = tag.text(FieldKey.GENRE),
            year = tag.year(), track = tag.number(FieldKey.TRACK), disc = tag.number(FieldKey.DISC_NO),
        ), runCatching { tag.firstArtwork?.binaryData?.takeIf { it.isNotEmpty() } }.getOrNull())
    }

    fun read(song: Song): Snapshot = song.file?.let(::read) ?: Snapshot(MetadataEdit(), null)

    /** The embedded front cover (or first picture), or null. */
    fun artwork(file: File): ByteArray? = runCatching {
        val list = open(file).tag?.artworkList.orEmpty()
        (list.firstOrNull { it.pictureType == 3 } ?: list.firstOrNull())?.binaryData?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** Lyrics text from the generic LYRICS field (USLT / LYRICS / ©lyr), or null. */
    fun lyrics(file: File): String? = runCatching { open(file).tag?.getFirst(FieldKey.LYRICS) }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Tags [file] in place. Only call on a temporary/staged file: [write] is the safe way to
     * change a library file. [artwork] must be JPEG or PNG. With [onlyMissing], fields and the
     * cover already present are kept.
     */
    fun tag(file: File, edit: MetadataEdit, artwork: ByteArray? = null, onlyMissing: Boolean = false) {
        val audio = open(file)
        val tag = audio.tagOrCreateAndSetDefault
        val changes = fields(edit).filter { (key, _) -> !onlyMissing || tag.getFirst(key).isNullOrBlank() }
        val cover = artwork?.takeIf { !onlyMissing || tag.artworkList.isEmpty() }
        if (changes.isEmpty() && cover == null) return
        changes.forEach { (key, value) -> tag.setField(key, value!!) }
        if (cover != null) {
            require(Images.isJpeg(cover) || Images.isPng(cover)) { "The cover must be a JPEG or PNG image." }
            val image = Images.decode(cover) ?: throw IllegalArgumentException("The cover image could not be read.")
            val art = ArtworkFactory.getNew().apply {
                binaryData = cover
                mimeType = if (Images.isPng(cover)) "image/png" else "image/jpeg"
                pictureType = 3
                description = ""
                width = image.width
                height = image.height
            }
            tag.deleteArtworkField()
            tag.setField(art)
        }
        audio.commit()
        val checked = open(file).tag
        check(changes.all { (key, value) -> checked.getFirst(key) == value }) { "The saved tags could not be checked. Your original file was kept." }
        check(cover == null || checked.artworkList.any { it.binaryData.contentEquals(cover) }) { "The saved cover could not be checked. Your original file was kept." }
    }

    /**
     * Safely writes [edit] (and optionally a cover) into [song]'s file: stage a hidden copy beside
     * it, tag and verify the copy, then atomically replace the original. Throws with a readable
     * message when the file can't be changed (read-only, missing, unsupported format).
     */
    suspend fun write(song: Song, edit: MetadataEdit, artwork: ByteArray? = null, onlyMissing: Boolean = false) {
        val target = song.file ?: throw IllegalStateException("${song.title} isn't a file on this computer.")
        write(target, edit, artwork, onlyMissing)
    }

    suspend fun write(target: File, edit: MetadataEdit, artwork: ByteArray? = null, onlyMissing: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock {
            check(target.isFile) { "${target.name} no longer exists." }
            check(target.canWrite() && target.parentFile?.canWrite() == true) { "Spitify isn't allowed to change ${target.name}." }
            val ext = target.extension
            val staged = File(target.parentFile, ".${target.nameWithoutExtension}.spitify-${System.nanoTime()}.$ext")
            try {
                Files.copy(target.toPath(), staged.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
                tag(staged, edit, artwork, onlyMissing)
                try {
                    Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: Exception) {
                    Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                target.setLastModified(System.currentTimeMillis())
            } finally {
                staged.delete()
            }
        }
    }

    /** Removes staged copies left behind by a crash mid-save (the originals are untouched). */
    fun recover(folders: List<File>) {
        for (root in folders) runCatching {
            root.walkTopDown().maxDepth(12).filter { it.isFile && it.name.startsWith(".") && it.name.contains(".spitify-") }
                .forEach { it.delete() }
        }
    }
}
