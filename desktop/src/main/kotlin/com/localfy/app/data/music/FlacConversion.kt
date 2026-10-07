package com.localfy.app.data.music

import com.localfy.app.data.LibraryRepository
import com.localfy.app.data.Song
import com.localfy.app.data.file
import com.localfy.app.data.lyrics.LyricsRepository
import com.localfy.app.data.meta.FileTags
import com.localfy.app.data.meta.MetadataEdit
import com.localfy.app.data.meta.MetadataRepository
import com.localfy.app.data.stableSongId
import com.localfy.app.data.taste.TasteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Settings › Convert FLAC to AAC: re-encodes every FLAC in the library as AAC 256 kbit/s (.m4a)
 * with FFmpeg to save space, then — once the user confirms — removes the FLAC.
 *
 * Each new file is written beside its FLAC under a hidden name (ignored by the scanner) until the
 * user approves deleting the originals ([State.NeedsApproval] → [approvalResult]); declining
 * leaves the library exactly as it was. After approval the .m4a takes the FLAC's place, the FLAC
 * is deleted, and [remap] moves likes, playlists, play counts, listens, edits, lyrics and hidden
 * songs from the old song ids to the new ones (see [standardRemap]); the caller's remap should
 * also update the player queue.
 *
 * @param remap called with old id → new id and old id → new file name after files are replaced.
 * @param playingSongId the song playing right now keeps its file this time (converted next run).
 */
class FlacConversion(
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
    private val remap: suspend (ids: Map<Long, Long>, fileNames: Map<Long, String>) -> Unit,
    private val playingSongId: () -> Long? = { null },
) {
    sealed interface State {
        data object Idle : State
        data class Converting(val done: Int, val total: Int, val current: String) : State
        /** Converted; the user must confirm deleting these FLAC originals. */
        data class NeedsApproval(val originals: List<File>) : State
        data class Finished(val converted: Int, val skipped: Int, val savedBytes: Long) : State
    }

    private data class Converted(val oldId: Long, val original: File, val staged: File, val target: File, val savedBytes: Long)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null
    @Volatile private var waiting: List<Converted> = emptyList()
    private var convertedCount = 0
    private var skippedCount = 0
    private var savedSoFar = 0L

    /** FLAC files in the library (not podcasts or books). */
    fun candidates(): List<Song> = library.rawSongs.value.filter { s ->
        s.file != null && !s.isPodcast && !s.isAudiobook && (s.mimeType == "audio/flac" || s.fileName.endsWith(".flac", ignoreCase = true))
    }

    /** Rough AAC size: 256 kbit/s ≈ 32 kB per second. */
    fun estimatedSaving(songs: List<Song>): Long = songs.sumOf { (it.sizeBytes - it.durationMs * 32).coerceAtLeast(0) }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val playing = runCatching { playingSongId() }.getOrNull()
            val self = coroutineContext[Job]
            val songs = candidates().filter { it.id != playing }
            convertedCount = 0; skippedCount = 0; savedSoFar = 0
            val converted = mutableListOf<Converted>()
            songs.forEachIndexed { i, song ->
                if (!isActive) return@launch
                _state.value = State.Converting(i, songs.size, song.title)
                val c = try { convert(song) { self?.isActive != true } } catch (e: Exception) {
                    if (e is CancellationException || e is AacEncoder.Cancelled) { if (!isActive) return@launch }
                    System.err.println("Spitify: skipped converting ${song.fileName}: ${e.message}")
                    skippedCount++; null
                } ?: return@forEachIndexed
                converted += c
            }
            if (converted.isEmpty()) done()
            else { waiting = converted; _state.value = State.NeedsApproval(converted.map { it.original }) }
        }
    }

    fun cancel() {
        job?.cancel()
        scope.launch(Dispatchers.IO) {
            job?.join()
            waiting.forEach { it.staged.delete() }; waiting = emptyList()
            // Partial conversions from the cancelled run.
            done()
        }
    }

    /** The user's answer to [State.NeedsApproval]: true deletes the FLACs and keeps the AAC copies. */
    fun approvalResult(approved: Boolean) = scope.launch(Dispatchers.IO) {
        val batch = waiting; waiting = emptyList()
        if (approved) finish(batch) else { batch.forEach { it.staged.delete() }; skippedCount += batch.size }
        done()
    }

    private suspend fun done() {
        _state.value = State.Finished(convertedCount, skippedCount, savedSoFar)
        library.refresh()
    }

    private fun convert(song: Song, cancelled: () -> Boolean): Converted? {
        val original = song.file ?: return null
        if (!original.isFile) return null
        val dir = original.parentFile ?: return null
        check(dir.canWrite()) { "Spitify can't write to ${dir.path}." }
        val base = original.nameWithoutExtension
        var target = File(dir, "$base.m4a")
        var n = 1
        while (target.exists()) target = File(dir, "$base (${n++}).m4a")
        val staged = File(dir, ".$base.spitify-convert-${System.nanoTime()}.m4a")
        try {
            AacEncoder.transcode(original, staged, cancelled)
            val cover = runCatching { FileTags.artwork(original) }.getOrNull()?.let { art ->
                // Keep JPEG/PNG covers as they are; re-encode anything else to JPEG.
                if (com.localfy.app.data.Images.isJpeg(art) || com.localfy.app.data.Images.isPng(art)) art
                else com.localfy.app.data.Images.decode(art)?.let { com.localfy.app.data.Images.jpeg(it) }
            }
            val tags = runCatching { FileTags.read(original).fields }.getOrDefault(MetadataEdit())
            FileTags.tag(staged, MetadataEdit(
                // Untagged files show placeholders like "Unknown artist"; don't write those into the new file.
                title = tags.title ?: song.title, artist = tags.artist ?: song.artist.takeUnless(::placeholder),
                album = tags.album ?: song.album.takeUnless(::placeholder), albumArtist = tags.albumArtist ?: song.albumArtist.takeUnless(::placeholder),
                genre = tags.genre ?: song.genre, year = tags.year ?: song.year.takeIf { it > 0 },
                track = tags.track ?: song.track.takeIf { it > 0 }, disc = tags.disc ?: song.disc.takeIf { it > 0 },
            ), cover)
            // Copy lyrics too, so they survive the conversion.
            runCatching { FileTags.lyrics(original) }.getOrNull()?.let { lyrics ->
                runCatching {
                    val audio = org.jaudiotagger.audio.AudioFileIO.read(staged)
                    audio.tagOrCreateAndSetDefault.setField(org.jaudiotagger.tag.FieldKey.LYRICS, lyrics)
                    audio.commit()
                }
            }
            return Converted(song.id, original, staged, target, (song.sizeBytes - staged.length()).coerceAtLeast(0))
        } catch (e: Throwable) {
            staged.delete()
            throw e
        }
    }

    private fun placeholder(value: String) = value.isBlank() || value.lowercase() in setOf("unknown artist", "unknown album", "<unknown>", "unknown")

    /** Moves the AAC copies into place, deletes the FLACs, and remaps everything that pointed at them. */
    private suspend fun finish(batch: List<Converted>) = withContext(NonCancellable) {
        val ids = LinkedHashMap<Long, Long>()
        val names = LinkedHashMap<Long, String>()
        for (c in batch) {
            val ok = runCatching {
                if (!c.staged.isFile) return@runCatching false
                var target = c.target
                var n = 1
                while (target.exists()) target = File(c.target.parentFile, "${c.original.nameWithoutExtension} (${n++}).m4a")
                Files.move(c.staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                if (!c.original.delete() && c.original.exists()) {
                    // Couldn't remove the FLAC: undo, so the song isn't in the library twice.
                    target.delete(); false
                } else {
                    ids[c.oldId] = stableSongId(target); names[c.oldId] = target.name; true
                }
            }.getOrDefault(false)
            if (ok) { convertedCount++; savedSoFar += c.savedBytes } else { c.staged.delete(); skippedCount++ }
        }
        if (ids.isNotEmpty()) runCatching { remap(ids, names) }.onFailure { System.err.println("Spitify: remapping converted songs failed: $it") }
    }

    companion object {
        /**
         * The usual remap: library likes/stats/playlists, metadata edits, cached lyrics, hidden
         * songs and listens; then [player] (e.g. the queue) with the id map.
         */
        fun standardRemap(
            library: LibraryRepository,
            metadata: MetadataRepository?,
            lyrics: LyricsRepository?,
            taste: TasteRepository?,
            player: (Map<Long, Long>) -> Unit = {},
        ): suspend (Map<Long, Long>, Map<Long, String>) -> Unit = { ids, names ->
            library.remapSongs(ids)
            metadata?.remapSongs(ids, names)
            lyrics?.remapSongs(ids)
            taste?.remapSongs(ids)
            taste?.remapEvents(ids)
            player(ids)
        }
    }
}
