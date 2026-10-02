package com.localfy.app.data.taste

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.localfy.app.data.LibraryRepository
import com.localfy.app.data.Song
import com.localfy.app.data.db.LocalfyDatabase
import com.localfy.app.data.db.PlayEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

class ProfileRepository(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
    val photoFile: File get() = File(context.filesDir, "profile.jpg")
    private val _profile = MutableStateFlow(load())
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    private fun load() = Profile(
        name = prefs.getString("name", "").orEmpty(),
        hasPhoto = photoFile.isFile,
        photoVersion = prefs.getLong("photoVersion", 0),
        onboarded = prefs.getBoolean("onboarded", false),
        seedArtists = prefs.getStringSet("seedArtists", emptySet()).orEmpty(),
        createdAt = prefs.getLong("createdAt", 0),
    )

    fun setName(name: String) {
        prefs.edit { putString("name", name.trim()); if (!prefs.contains("createdAt")) putLong("createdAt", System.currentTimeMillis()) }
        _profile.value = load()
    }

    fun setPhoto(uri: Uri?) = scope.launch(Dispatchers.IO) {
        if (uri == null) photoFile.delete()
        else if (!com.localfy.app.data.saveSquareImage(android.graphics.ImageDecoder.createSource(context.contentResolver, uri), photoFile, 512)) return@launch
        prefs.edit { putLong("photoVersion", System.currentTimeMillis()) }
        _profile.value = load()
    }

    fun setSeedArtists(artists: Set<String>) { prefs.edit { putStringSet("seedArtists", artists) }; _profile.value = load() }

    fun completeOnboarding() { prefs.edit { putBoolean("onboarded", true) }; _profile.value = load() }
}

/**
 * Keeps the taste model fresh: recomputes generated playlists when you listen, like/hide things,
 * or the library changes - and at least every 30 minutes so time-based ones (daylist) move along.
 */
@OptIn(FlowPreview::class)
class TasteRepository(
    private val context: Context,
    private val db: LocalfyDatabase,
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
    private val profiles: ProfileRepository,
) {
    private val prefs = context.getSharedPreferences("taste", Context.MODE_PRIVATE)
    private val _hiddenSongs = MutableStateFlow(prefs.getStringSet("hiddenSongs", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet())
    val hiddenSongs: StateFlow<Set<Long>> = _hiddenSongs.asStateFlow()
    private val _hiddenArtists = MutableStateFlow(prefs.getStringSet("hiddenArtists", emptySet()).orEmpty())
    val hiddenArtists: StateFlow<Set<String>> = _hiddenArtists.asStateFlow()
    private val _model = MutableStateFlow<TasteModel?>(null)
    val model: StateFlow<TasteModel?> = _model.asStateFlow()
    private val tick = MutableStateFlow(0L)

    fun start() {
        val yearAgo = System.currentTimeMillis() - 400L * 86_400_000
        scope.launch {
            combine(
                library.library, db.events().observeSince(yearAgo), library.likedIds, profiles.profile,
                combine(_hiddenSongs, _hiddenArtists, tick) { a, b, _ -> a to b },
            ) { lib, events, liked, profile, hidden ->
                TasteInput(
                    songs = lib.songs,
                    listens = events.map { Listen(it.songId, it.startedAt, it.listenedMs, it.durationMs, it.completed, it.skipped) },
                    liked = liked, seedArtists = profile.seedArtists,
                    hiddenSongs = hidden.first, hiddenArtists = hidden.second, userName = profile.name,
                )
            }.debounce(800).collect { input ->
                if (input.songs.isEmpty()) return@collect
                val (model, mixes) = withContext(Dispatchers.Default) { TasteModel(input).let { it to PlaylistGenerator.generate(it) } }
                _model.value = model
                library.publishMixes(mixes)
            }
        }
        scope.launch { while (isActive) { delay(30 * 60_000L); tick.value = System.currentTimeMillis() } }
        scope.launch(Dispatchers.IO) { db.events().prune(System.currentTimeMillis() - 400L * 86_400_000) }
    }

    fun record(event: PlayEventEntity) = scope.launch(Dispatchers.IO) { db.events().insert(event) }

    fun hideSong(id: Long) { _hiddenSongs.value = _hiddenSongs.value + id; prefs.edit { putStringSet("hiddenSongs", _hiddenSongs.value.map { it.toString() }.toSet()) } }
    fun hideArtist(name: String) { _hiddenArtists.value = _hiddenArtists.value + name; prefs.edit { putStringSet("hiddenArtists", _hiddenArtists.value) } }
    fun unhideAll() { _hiddenSongs.value = emptySet(); _hiddenArtists.value = emptySet(); prefs.edit { remove("hiddenSongs"); remove("hiddenArtists") } }

    fun songRadio(seed: Song): List<Song> = _model.value?.let { PlaylistGenerator.songRadio(it, seed) } ?: listOf(seed)
    fun artistRadio(artist: String): List<Song> = _model.value?.let { PlaylistGenerator.artistRadio(it, artist) }.orEmpty()
}
