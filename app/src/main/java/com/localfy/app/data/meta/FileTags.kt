package com.localfy.app.data.meta

import com.localfy.app.data.uri

import android.content.Context
import com.localfy.app.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.AndroidArtwork
import java.io.File
import java.util.UUID

object FileTags {
    init { org.jaudiotagger.tag.TagOptionSingleton.getInstance().isAndroid = true }
    private val lock = Mutex()
    private fun fields(edit: MetadataEdit) = mapOf(
        FieldKey.TITLE to edit.title, FieldKey.ARTIST to edit.artist, FieldKey.ALBUM to edit.album,
        FieldKey.ALBUM_ARTIST to edit.albumArtist, FieldKey.GENRE to edit.genre,
        FieldKey.YEAR to edit.year?.toString(), FieldKey.TRACK to edit.track?.toString(),
        FieldKey.DISC_NO to edit.disc?.toString(),
    ).filterValues { it != null }

    /** Only call on a temporary file. The caller publishes it after this read-back succeeds. */
    fun tag(file: File, edit: MetadataEdit, artwork: ByteArray? = null, onlyMissing: Boolean = false) {
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        val changes = fields(edit).filter { (key, _) -> !onlyMissing || tag.getFirst(key).isBlank() }
        val cover = artwork?.takeIf { !onlyMissing || tag.artworkList.isEmpty() }
        if (changes.isEmpty() && cover == null) return
        changes.forEach { (key, value) -> tag.setField(key, value!!) }
        if (cover != null) {
            val dimensions = jpegSize(cover)
            val image = object : AndroidArtwork() {
                override fun setImageFromData() = true // Already decoded and normalized by the caller.
            }.apply {
                width = dimensions.first
                height = dimensions.second
                binaryData = cover
                mimeType = "image/jpeg"
                pictureType = 3
                description = "Cover"
            }
            tag.setField(image)
        }
        audio.commit()
        val checked = AudioFileIO.read(file).tag
        check(changes.all { (key, value) -> checked.getFirst(key) == value }) { "The saved tags could not be checked. Your original file was kept." }
        check(cover == null || checked.artworkList.any { it.binaryData.contentEquals(cover) }) { "The saved cover could not be checked. Your original file was kept." }
    }

    private fun jpegSize(bytes: ByteArray): Pair<Int, Int> {
        fun u(i: Int) = bytes[i].toInt() and 255
        fun word(i: Int) = (u(i) shl 8) or u(i + 1)
        require(bytes.size > 4 && u(0) == 255 && u(1) == 216) { "The cover must be a JPEG image." }
        var p = 2
        while (p + 4 <= bytes.size) {
            require(u(p) == 255) { "The cover image is damaged." }
            while (p < bytes.size && u(p) == 255) p++
            require(p < bytes.size)
            val marker = u(p++)
            if (marker == 217 || marker == 218) break
            if (marker == 1 || marker in 208..215) continue
            require(p + 2 <= bytes.size)
            val size = word(p)
            require(size >= 2 && p + size <= bytes.size) { "The cover image is incomplete." }
            if (marker in setOf(192, 193, 194, 195, 197, 198, 199, 201, 202, 203, 205, 206, 207)) {
                require(size >= 8)
                return word(p + 5) to word(p + 3)
            }
            p += size
        }
        error("The cover image dimensions could not be read.")
    }

    data class Snapshot(val fields: MetadataEdit, val artwork: ByteArray?)

    fun read(context: Context, song: Song): Snapshot {
        android.media.MediaMetadataRetriever().use { reader ->
            reader.setDataSource(context, song.uri)
            fun text(key: Int) = reader.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() && it != "<unknown>" }
            fun number(key: Int) = text(key)?.substringBefore('/')?.take(4)?.toIntOrNull()?.takeIf { it > 0 }
            return Snapshot(MetadataEdit(
                title = text(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = text(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = text(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = text(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                genre = text(android.media.MediaMetadataRetriever.METADATA_KEY_GENRE),
                year = number(android.media.MediaMetadataRetriever.METADATA_KEY_YEAR)
                    ?: number(android.media.MediaMetadataRetriever.METADATA_KEY_DATE),
                track = number(android.media.MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                disc = number(android.media.MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
            ), reader.embeddedPicture)
        }
    }

    suspend fun recover(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            val dir = File(context.filesDir, "tag_backups")
            for (journal in dir.listFiles().orEmpty().filter { it.extension == "properties" }) {
                runCatching {
                    val record = java.util.Properties().apply { journal.inputStream().use { load(it) } }
                    val original = File(dir, record.getProperty("backup"))
                    require(original.parentFile?.canonicalFile == dir.canonicalFile && original.isFile)
                    val uri = android.net.Uri.parse(record.getProperty("uri"))
                    val descriptor = checkNotNull(context.contentResolver.openFileDescriptor(uri, "rw"))
                    android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                        output.channel.position(0)
                        original.inputStream().use { it.copyTo(output) }
                        output.channel.truncate(original.length())
                        output.fd.sync()
                    }
                    journal.delete()
                    original.delete()
                }.onFailure { android.util.Log.e("Spitify", "Could not restore interrupted file edit; original kept in $journal", it) }
            }
        }
    }

    suspend fun write(context: Context, song: Song, edit: MetadataEdit, artwork: ByteArray? = null, onlyMissing: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock {
            val dir = File(context.filesDir, "tag_backups").apply { mkdirs() }
            val original = File(dir, "${UUID.randomUUID()}.${song.fileName.substringAfterLast('.')}")
            val journal = File(dir, original.name + ".properties")
            val staged = File(dir, "${UUID.randomUUID()}.${song.fileName.substringAfterLast('.')}")
            var keepBackup = false
            try {
                // Opening without truncation first makes missing write permission harmless.
                val descriptor = checkNotNull(context.contentResolver.openFileDescriptor(song.uri, "rw")) { "Could not open ${song.fileName}." }
                descriptor.use { fd ->
                    context.contentResolver.openInputStream(song.uri).use { input ->
                        checkNotNull(input).use { source -> original.outputStream().use { source.copyTo(it); it.fd.sync() } }
                    }
                    original.copyTo(staged)
                    tag(staged, edit, artwork, onlyMissing)
                    withContext(NonCancellable) {
                        val record = java.util.Properties().apply {
                            setProperty("uri", song.uri.toString())
                            setProperty("backup", original.name)
                        }
                        journal.outputStream().use { record.store(it, "Interrupted tag save"); it.fd.sync() }
                        android.os.ParcelFileDescriptor.AutoCloseOutputStream(fd).use { output ->
                            fun replace(file: File) {
                                output.channel.position(0)
                                file.inputStream().use { it.copyTo(output) }
                                output.channel.truncate(file.length())
                                output.fd.sync()
                            }
                            try { replace(staged) } catch (error: Exception) {
                                try { replace(original) } catch (restore: Exception) {
                                    keepBackup = true
                                    throw IllegalStateException("Could not restore ${song.fileName}. Its original is kept at ${original.absolutePath}.", restore)
                                }
                                throw error
                            }
                        }
                    }
                }
                runCatching { context.contentResolver.notifyChange(song.uri, null) }
            } finally {
                staged.delete()
                if (!keepBackup) { journal.delete(); original.delete() }
            }
        }
    }
}
