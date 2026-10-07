package com.localfy.app.data.taste

import com.localfy.app.data.LibraryRepository
import com.localfy.app.data.PrefsFile
import com.localfy.app.data.Song
import com.localfy.app.data.db.PlayEventEntity
import com.localfy.app.data.saveSquareImage
import com.localfy.app.data.writeAtomically
import com.localfy.app.desktop.AppPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Who's listening: name, photo, and the artists they picked when setting up. */
data class Profile(
    val name: String = "",
    val hasPhoto: Boolean = false,
    val photoVersion: Long = 0,
    val onboarded: Boolean = false,
    val seedArtists: Set<String> = emptySet(),
    val createdAt: Long = 0,
)

class ProfileRepository(private val scope: CoroutineScope, private val dataDir: File = AppPaths.dataDir) {
    private val prefs = PrefsFile(File(dataDir, "prefs-profile.json"))
    val photoFile: File get() = File(dataDir, "profile.jpg")
    private val _profile = MutableStateFlow(load())
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    private fun load() = Profile(
        name = prefs.getString("name", "").orEmpty(),
        hasPhoto = photoFile.isFile,
        photoVersion = prefs.getLong("photoVersion", 0),
        onboarded = prefs.getBoolean("onboarded", false),
        seedArtists = prefs.getStringSet("seedArtists", emptySet()),
        createdAt = prefs.getLong("createdAt", 0),
    )

    fun setName(name: String) {
        prefs.edit { putString("name", name.trim()); if (!prefs.contains("createdAt")) putLong("createdAt", System.currentTimeMillis()) }
        _profile.value = load()
    }

    /** Sets the profile photo from an image file (cropped to a square), or removes it (null). */
    fun setPhoto(image: File?) = scope.launch(Dispatchers.IO) {
        if (image == null) photoFile.delete()
        else if (!saveSquareImage(image, photoFile, 1536)) return@launch
        prefs.edit { putLong("photoVersion", System.currentTimeMillis()) }
        _profile.value = load()
    }

    fun setSeedArtists(artists: Set<String>) { prefs.edit { putStringSet("seedArtists", artists) }; _profile.value = load() }

    fun completeOnboarding() { prefs.edit { putBoolean("onboarded", true) }; _profile.value = load() }
}

/**
 * Keeps the taste model fresh: recomputes generated playlists when you listen, like/hide things,
 * or the library changes - and at least every 30 minutes so time-based ones (daylist) move along.
 * Listens are kept in an append-only `play_events.jsonl` (one JSON object per line), pruned to 400 days.
 */
@OptIn(FlowPreview::class)
class TasteRepository(
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
    private val profiles: ProfileRepository,
    dataDir: File = AppPaths.dataDir,
) {
    private val prefs = PrefsFile(File(dataDir, "prefs-taste.json"))
    private val eventsFile = File(dataDir, "play_events.jsonl")
    private val eventsLock = Mutex()
    private val _hiddenSongs = MutableStateFlow(prefs.getStringSet("hiddenSongs", emptySet()).mapNotNull { it.toLongOrNull() }.toSet())
    val hiddenSongs: StateFlow<Set<Long>> = _hiddenSongs.asStateFlow()
    private val _hiddenArtists = MutableStateFlow(prefs.getStringSet("hiddenArtists", emptySet()))
    val hiddenArtists: StateFlow<Set<String>> = _hiddenArtists.asStateFlow()
    private val _model = MutableStateFlow<TasteModel?>(null)
    val model: StateFlow<TasteModel?> = _model.asStateFlow()
    private val tick = MutableStateFlow(0L)

    private val _events = MutableStateFlow<List<PlayEventEntity>>(emptyList())
    /** Every listen in the last 400 days, oldest first. */
    val events: StateFlow<List<PlayEventEntity>> = _events.asStateFlow()
    @Volatile private var loaded = false
    private var nextEventId = 1L

    private var started = false

    @Synchronized fun start() {
        if (started) return
        started = true
        scope.launch {
            ensureLoaded()
            combine(
                library.library, _events, library.likedIds, profiles.profile,
                combine(_hiddenSongs, _hiddenArtists, tick) { a, b, _ -> a to b },
            ) { lib, events, liked, profile, hidden ->
                val yearAgo = System.currentTimeMillis() - 400L * 86_400_000
                TasteInput(
                    songs = lib.songs,
                    listens = events.filter { it.startedAt >= yearAgo }.map { Listen(it.songId, it.startedAt, it.listenedMs, it.durationMs, it.completed, it.skipped) },
                    liked = liked, seedArtists = profile.seedArtists,
                    hiddenSongs = hidden.first, hiddenArtists = hidden.second, userName = profile.name,
                )
            }.debounce(800).collect { input ->
                if (input.songs.isEmpty()) return@collect
                // A bug in one recommendation must never take the whole app down: log it and keep going.
                val result = withContext(Dispatchers.Default) { runCatching { TasteModel(input).let { it to PlaylistGenerator.generate(it) } } }
                result.onSuccess { (model, mixes) -> _model.value = model; library.publishMixes(mixes) }
                    .onFailure { System.err.println("Spitify: generating playlists failed: $it") }
            }
        }
        scope.launch { while (isActive) { delay(30 * 60_000L); tick.value = System.currentTimeMillis() } }
    }

    /** Records one listen (appended to disk immediately). */
    fun record(event: PlayEventEntity) = scope.launch(Dispatchers.IO) {
        ensureLoaded()
        eventsLock.withLock {
            val withId = event.copy(id = nextEventId++)
            _events.update { it + withId }
            runCatching {
                eventsFile.parentFile?.mkdirs()
                eventsFile.appendText(toJson(withId).toString() + "\n")
            }
        }
    }

    fun hideSong(id: Long) { _hiddenSongs.value = _hiddenSongs.value + id; prefs.edit { putStringSet("hiddenSongs", _hiddenSongs.value.map { it.toString() }.toSet()) } }
    fun hideArtist(name: String) { _hiddenArtists.value = _hiddenArtists.value + name; prefs.edit { putStringSet("hiddenArtists", _hiddenArtists.value) } }

    /** Follows songs to their new ids after a file was replaced (e.g. converted to AAC): hidden songs and listens. */
    fun remapSongs(ids: Map<Long, Long>) {
        if (_hiddenSongs.value.any { it in ids }) {
            _hiddenSongs.value = _hiddenSongs.value.map { ids[it] ?: it }.toSet()
            prefs.edit { putStringSet("hiddenSongs", _hiddenSongs.value.map { it.toString() }.toSet()) }
        }
        scope.launch(Dispatchers.IO) { remapEvents(ids) }
    }

    /** Suspending form of the listen remap (used by FLAC conversion so it finishes before reporting). */
    suspend fun remapEvents(ids: Map<Long, Long>) {
        ensureLoaded()
        eventsLock.withLock {
            if (_events.value.none { it.songId in ids }) return
            _events.update { list -> list.map { e -> ids[e.songId]?.let { e.copy(songId = it) } ?: e } }
            rewriteEvents()
        }
    }

    fun unhideAll() { _hiddenSongs.value = emptySet(); _hiddenArtists.value = emptySet(); prefs.edit { remove("hiddenSongs"); remove("hiddenArtists") } }

    fun songRadio(seed: Song): List<Song> = _model.value?.let { PlaylistGenerator.songRadio(it, seed) } ?: listOf(seed)
    fun artistRadio(artist: String): List<Song> = _model.value?.let { PlaylistGenerator.artistRadio(it, artist) }.orEmpty()

    fun flush() = prefs.flush()

    // ---------- listen log ----------

    private suspend fun ensureLoaded() {
        if (loaded) return
        eventsLock.withLock {
            if (loaded) return
            withContext(Dispatchers.IO) {
                val cutoff = System.currentTimeMillis() - 400L * 86_400_000
                val all = ArrayList<PlayEventEntity>()
                var dropped = false
                runCatching {
                    if (eventsFile.isFile) eventsFile.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            // A torn last line (power cut mid-append) is skipped, not fatal.
                            val e = runCatching { fromJson(JSONObject(line)) }.getOrNull()
                            if (e == null) { if (line.isNotBlank()) dropped = true }
                            else if (e.startedAt < cutoff) dropped = true
                            else all += e
                        }
                    }
                }
                nextEventId = (all.maxOfOrNull { it.id } ?: 0) + 1
                _events.value = all.sortedBy { it.startedAt }
                if (dropped) rewriteEvents()
            }
            loaded = true
        }
    }

    private fun rewriteEvents() {
        runCatching { writeAtomically(eventsFile, _events.value.joinToString("") { toJson(it).toString() + "\n" }) }
    }

    private fun toJson(e: PlayEventEntity) = JSONObject().apply {
        put("id", e.id); put("songId", e.songId); put("startedAt", e.startedAt); put("listenedMs", e.listenedMs)
        put("durationMs", e.durationMs); put("completed", e.completed); put("skipped", e.skipped); e.source?.let { put("source", it) }
    }

    private fun fromJson(o: JSONObject) = PlayEventEntity(
        id = o.optLong("id"), songId = o.getLong("songId"), startedAt = o.getLong("startedAt"), listenedMs = o.optLong("listenedMs"),
        durationMs = o.optLong("durationMs"), completed = o.optBoolean("completed"), skipped = o.optBoolean("skipped"),
        source = if (o.has("source") && !o.isNull("source")) o.optString("source") else null,
    )
}
