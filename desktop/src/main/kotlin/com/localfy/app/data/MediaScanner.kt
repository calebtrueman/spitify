package com.localfy.app.data

import com.localfy.app.data.meta.FileTags
import com.localfy.app.data.music.AacEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.text.Normalizer
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Walks the library folders and reads each audio file's tags (the desktop stand-in for
 * MediaStore). Tag reads are cached by path + modification time + size, so rescans only open
 * new or changed files. Unreadable/corrupt files are skipped (and remembered, so they aren't
 * retried until they change); nothing a file contains can fail the whole scan.
 */
class MediaScanner(private val cacheFile: File) {

    enum class Kind { Music, Podcasts, Audiobooks }

    /** One walk's results, split like Android's three MediaStore queries. */
    data class Result(
        val music: List<Song>,
        val podcasts: List<Song>,
        val audiobooks: List<Song>,
        /** Every directory visited (the folder watcher registers these). */
        val directories: List<File>,
        /** Audio files that couldn't be read at all. */
        val skipped: Int,
    )

    /** Cached facts about one file; [ok] false means "unreadable at this mtime/size". */
    private data class Entry(
        val modified: Long, val size: Long, val created: Long, val ok: Boolean,
        val title: String? = null, val artist: String? = null, val album: String? = null, val albumArtist: String? = null,
        val genre: String? = null, val year: Int? = null, val track: Int? = null, val disc: Int? = null,
        val durationMs: Long = 0, val hasArt: Boolean = false, val explicit: Boolean? = null, val podcast: Boolean = false,
    )

    private val cache = ConcurrentHashMap<String, Entry>()
    @Volatile private var cacheLoaded = false
    private val _progress = MutableStateFlow(0)
    /** Files read so far in the running scan (cache hits don't count). */
    val progress: StateFlow<Int> = _progress.asStateFlow()

    /** Android-compatible single-kind scan. */
    suspend fun scan(roots: List<File>, minDurationMs: Long, kind: Kind = Kind.Music): List<Song> {
        val r = scanAll(roots, minDurationMs)
        return when (kind) { Kind.Music -> r.music; Kind.Podcasts -> r.podcasts; Kind.Audiobooks -> r.audiobooks }
    }

    /** Scans [roots] recursively; music shorter than [minMusicDurationMs] is left out (unplayable formats are kept). */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun scanAll(roots: List<File>, minMusicDurationMs: Long): Result = withContext(Dispatchers.IO) {
        loadCache()
        _progress.value = 0
        val files = LinkedHashMap<String, Pair<File, BasicFileAttributes>>()
        val dirs = ArrayList<File>()
        val seenRoots = HashSet<String>()
        for (root in roots) {
            val canonical = runCatching { root.canonicalPath }.getOrNull() ?: continue
            // Skip a folder that sits inside another library folder: it's already covered.
            if (seenRoots.any { canonical == it || canonical.startsWith(it + File.separator) }) continue
            seenRoots += canonical
            if (root.isDirectory) walk(root.toPath(), files, dirs)
        }
        ensureActive()

        val limited = Dispatchers.IO.limitedParallelism(4)
        val read = AtomicInteger()
        val entries = HashMap<String, Entry>(files.size * 2)
        for (chunk in files.entries.chunked(128)) {
            ensureActive()
            val results = coroutineScope {
                chunk.map { (path, pair) ->
                    async(limited) {
                        val (file, attrs) = pair
                        val modified = attrs.lastModifiedTime().toMillis()
                        val size = attrs.size()
                        val cached = cache[path]
                        val entry = if (cached != null && cached.modified == modified && cached.size == size) cached else {
                            val created = runCatching { attrs.creationTime().toMillis() }.getOrDefault(modified).takeIf { it > 0 } ?: modified
                            readEntry(file, modified, size, minOf(created, modified).takeIf { it > 0 } ?: created).also {
                                _progress.value = read.incrementAndGet()
                            }
                        }
                        path to entry
                    }
                }.awaitAll()
            }
            results.forEach { (p, e) -> entries[p] = e }
        }

        val changed = entries.size != cache.size || entries.any { (k, v) -> cache[k] != v }
        cache.clear(); cache.putAll(entries)
        if (changed) saveCache()

        val music = ArrayList<Song>(); val podcasts = ArrayList<Song>(); val books = ArrayList<Song>()
        var skipped = 0
        for ((path, entry) in entries) {
            if (!entry.ok) { skipped++; continue }
            val file = files[path]?.first ?: continue
            val kind = classify(file, entry)
            val song = runCatching { toSong(file, entry, kind) }.getOrNull() ?: continue
            val min = if (kind == Kind.Music) minMusicDurationMs else 1L
            if (song.durationMs < min && song.playable) continue
            when (kind) { Kind.Music -> music += song; Kind.Podcasts -> podcasts += song; Kind.Audiobooks -> books += song }
        }
        Result(music, podcasts, books, dirs, skipped)
    }

    private fun walk(root: Path, files: MutableMap<String, Pair<File, BasicFileAttributes>>, dirs: MutableList<File>) {
        runCatching {
            Files.walkFileTree(root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), 64, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = dir.fileName?.toString().orEmpty()
                    if (dir != root && skipDirectory(name)) return FileVisitResult.SKIP_SUBTREE
                    dirs += dir.toFile()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = file.fileName?.toString() ?: return FileVisitResult.CONTINUE
                    if (attrs.isRegularFile && !name.startsWith(".") && isAudio(name) && attrs.size() > 0) {
                        val f = file.toFile().absoluteFile
                        files.putIfAbsent(f.normalize().path, f to attrs)
                    }
                    return FileVisitResult.CONTINUE
                }

                // Unreadable folders, broken links and symlink loops are simply skipped.
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult = FileVisitResult.CONTINUE
            })
        }
    }

    private fun readEntry(file: File, modified: Long, size: Long, created: Long): Entry {
        val tags: FileTags.Info? = try {
            FileTags.info(file)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Corrupt or exotic files (and jaudiotagger bugs, even StackOverflow/OOM on bad frames) must not stop the scan.
            null
        }
        var entry = tags?.let {
            Entry(modified, size, created, true, it.title, it.artist, it.album, it.albumArtist, it.genre, it.year, it.track, it.disc,
                it.durationMs, it.hasArtwork, it.explicit, it.podcast)
        }
        if (entry == null || entry.durationMs <= 0) {
            val probe = try { AacEncoder.probe(file) } catch (_: Throwable) { null }
            if (probe != null) {
                val t = probe.tags
                fun num(key: String) = t[key]?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                entry = entry?.copy(durationMs = probe.durationMs) ?: Entry(
                    modified, size, created, true,
                    title = t["title"]?.clean(), artist = t["artist"]?.clean(), album = t["album"]?.clean(),
                    albumArtist = (t["album_artist"] ?: t["albumartist"] ?: t["album artist"])?.clean(), genre = t["genre"]?.clean(),
                    year = (t["date"] ?: t["year"])?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() },
                    track = num("track"), disc = num("disc"), durationMs = probe.durationMs, hasArt = probe.hasCover,
                )
            }
        }
        return entry ?: Entry(modified, size, created, ok = false)
    }

    private fun classify(file: File, e: Entry): Kind {
        val path = file.path.lowercase().replace('\\', '/')
        val genre = e.genre?.trim()?.lowercase()
        val book = file.extension.equals("m4b", true) || "audiobook" in path || "audio book" in path ||
            genre == "audiobook" || genre == "audiobooks" || genre == "audio book"
        if (book) return Kind.Audiobooks
        val podcast = e.podcast || genre == "podcast" || genre == "podcasts" ||
            file.parentFile?.let { p -> generateSequence(p) { it.parentFile }.take(6).any { it.name.equals("podcasts", true) } } == true
        return if (podcast) Kind.Podcasts else Kind.Music
    }

    private fun toSong(file: File, e: Entry, kind: Kind): Song {
        val folderName = file.parentFile?.name?.takeIf { it.isNotBlank() }
        val artist = e.artist ?: UNKNOWN_ARTIST
        val album = e.album ?: folderName ?: UNKNOWN_ALBUM
        val folder = (file.parentFile?.absolutePath ?: "").replace('\\', '/').trimEnd('/') + "/"
        return Song(
            id = stableSongId(file),
            title = e.title ?: file.nameWithoutExtension.ifBlank { "Unknown" },
            artist = artist,
            album = album,
            albumId = albumId(e.album, e.albumArtist, folder),
            albumArtist = e.albumArtist ?: artist,
            durationMs = e.durationMs,
            track = e.track ?: 0,
            disc = e.disc ?: 1,
            year = e.year ?: 0,
            genre = e.genre,
            folder = folder,
            dateAddedSec = e.created / 1000,
            sizeBytes = e.size,
            mimeType = mimeType(file.extension),
            fileName = file.name,
            sourceUri = file.toURI().toString(),
            isPodcast = kind != Kind.Music,
            isAudiobook = kind == Kind.Audiobooks,
            explicit = e.explicit,
        )
    }

    // ---------- cache ----------

    private fun loadCache() {
        if (cacheLoaded) return
        synchronized(this) {
            if (cacheLoaded) return
            runCatching {
                if (!cacheFile.isFile) return@runCatching
                val root = JSONObject(cacheFile.readText())
                if (root.optInt("version") != CACHE_VERSION) return@runCatching
                val arr = root.optJSONArray("entries") ?: return@runCatching
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val path = o.optString("p").takeIf { it.isNotEmpty() } ?: continue
                    cache[path] = Entry(
                        modified = o.optLong("m"), size = o.optLong("s"), created = o.optLong("c"), ok = o.optBoolean("ok"),
                        title = o.optStringOrNull("t"), artist = o.optStringOrNull("a"), album = o.optStringOrNull("al"),
                        albumArtist = o.optStringOrNull("aa"), genre = o.optStringOrNull("g"), year = o.optIntOrNull("y"),
                        track = o.optIntOrNull("tr"), disc = o.optIntOrNull("d"), durationMs = o.optLong("du"),
                        hasArt = o.optBoolean("art"), explicit = if (o.has("ex")) o.optBoolean("ex") else null, podcast = o.optBoolean("pc"),
                    )
                }
            }
            cacheLoaded = true
        }
    }

    private fun saveCache() {
        runCatching {
            val arr = JSONArray()
            for ((path, e) in cache) {
                arr.put(JSONObject().apply {
                    put("p", path); put("m", e.modified); put("s", e.size); put("c", e.created); put("ok", e.ok)
                    e.title?.let { put("t", it) }; e.artist?.let { put("a", it) }; e.album?.let { put("al", it) }
                    e.albumArtist?.let { put("aa", it) }; e.genre?.let { put("g", it) }; e.year?.let { put("y", it) }
                    e.track?.let { put("tr", it) }; e.disc?.let { put("d", it) }; put("du", e.durationMs)
                    if (e.hasArt) put("art", true); e.explicit?.let { put("ex", it) }; if (e.podcast) put("pc", true)
                })
            }
            writeAtomically(cacheFile, JSONObject().put("version", CACHE_VERSION).put("entries", arr).toString())
        }
    }

    /** Whether the file at [path] had embedded artwork when it was last scanned (null = not scanned). */
    fun hasEmbeddedArt(path: String): Boolean? = cache[path]?.hasArt

    private fun String.clean(): String? = replace("\u0000", "").trim().takeUnless { it.isEmpty() || it.equals("<unknown>", true) }

    companion object {
        const val UNKNOWN_ARTIST = "Unknown artist"
        const val UNKNOWN_ALBUM = "Unknown album"
        private const val CACHE_VERSION = 1

        val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "wave", "aif", "aiff", "aifc", "alac",
            "wma", "ape", "wv", "dsf", "dff", "mpc", "tta", "mka", "weba", "caf",
        )

        fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

        private val SKIPPED_DIRS = setOf("\$recycle.bin", "system volume information", "node_modules", "__macosx")
        private val PACKAGE_SUFFIXES = listOf(".musiclibrary", ".photoslibrary", ".tvlibrary", ".app", ".itlp", ".bundle", ".logicx", ".band")

        private fun skipDirectory(name: String): Boolean {
            val lower = name.lowercase()
            return lower.startsWith(".") || lower in SKIPPED_DIRS || PACKAGE_SUFFIXES.any { lower.endsWith(it) }
        }

        fun mimeType(ext: String): String? = when (ext.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "m4b", "alac", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/opus"
            "wav", "wave" -> "audio/wav"
            "aif", "aiff", "aifc" -> "audio/aiff"
            "wma" -> "audio/x-ms-wma"
            "ape" -> "audio/ape"
            "wv" -> "audio/wavpack"
            "dsf", "dff" -> "audio/dsd"
            "mka" -> "audio/x-matroska"
            "weba" -> "audio/webm"
            "caf" -> "audio/x-caf"
            else -> null
        }

        private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        /**
         * Album grouping like MediaStore: tagged albums group by album + album artist; without an
         * album artist tag the folder keeps same-named albums ("Greatest Hits") apart; untagged files
         * group by folder. Always positive (app-made albums use negative ids).
         */
        fun albumId(album: String?, albumArtist: String?, folder: String): Long {
            val key = when {
                album != null && albumArtist != null -> "a\u0000" + norm(album) + "\u0000" + norm(albumArtist)
                album != null -> "f\u0000" + norm(album) + "\u0000" + folder
                else -> "d\u0000" + folder
            }
            return stableSongId(key)
        }
    }
}
