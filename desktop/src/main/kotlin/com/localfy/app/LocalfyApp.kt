package com.localfy.app

import com.localfy.app.data.LibraryRepository
import com.localfy.app.data.Song
import com.localfy.app.data.art.ArtworkStore
import com.localfy.app.data.art.OnlineArtRepository
import com.localfy.app.data.lyrics.LyricsRepository
import com.localfy.app.data.meta.MetadataRepository
import com.localfy.app.data.music.ArtistFollows
import com.localfy.app.data.music.FlacConversion
import com.localfy.app.data.music.ListeningCache
import com.localfy.app.data.music.Monochrome
import com.localfy.app.data.music.MusicDownloads
import com.localfy.app.data.music.MusicStreams
import com.localfy.app.data.podcast.PodcastRepository
import com.localfy.app.data.social.DevicePlayer
import com.localfy.app.data.social.DeviceSyncRepository
import com.localfy.app.data.social.ListeningRooms
import com.localfy.app.data.social.PlaylistMatches
import com.localfy.app.data.social.RoomPlayer
import com.localfy.app.data.social.SocialRepository
import com.localfy.app.data.taste.ProfileRepository
import com.localfy.app.data.taste.TasteRepository
import com.localfy.app.desktop.JsonStore
import com.localfy.app.data.DataFile
import com.localfy.app.playback.EqStore
import com.localfy.app.playback.MediaSessionController
import com.localfy.app.playback.PlayerConnection
import com.localfy.app.ui.theme.ThemeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Desktop app container: one instance for the whole process, with the same property names as the
 * phone's LocalfyApp so the ported screens read the same. Services are lazy and wired to each other
 * here; [start] kicks off the background work once the window is up and [shutdown] saves everything.
 */
class LocalfyApp {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val onlineArt: OnlineArtRepository by lazy {
        OnlineArtRepository().also { it.onDownloaded = { albumId, file -> metadata.embedArt(albumId, file) } }
    }
    val metadata: MetadataRepository by lazy {
        MetadataRepository(appScope, onlineArt).also { m ->
            m.librarySongs = { library.library.value.songs + library.localBooks.value }
            m.onFilesChanged = { library.refresh() }
        }
    }
    val musicStreams: MusicStreams by lazy {
        MusicStreams(listeningCache = ListeningCache(), cacheWhilePlaying = true).also { streams ->
            streams.localCopy = { track ->
                musicDownloads.jobsById.value[track.id]?.takeIf { it.state == "complete" }?.localUri?.let(::File)?.takeIf { it.isFile }
            }
        }
    }
    val library: LibraryRepository by lazy {
        LibraryRepository(appScope, metadata, savedStreams = musicStreams.saved, streamLookup = musicStreams::lookup).also { repo ->
            repo.onScanned = { music, books -> metadata.autoFixAll(music + books) }
        }
    }
    val artwork: ArtworkStore by lazy { ArtworkStore(metadata, onlineArt) }
    val lyrics: LyricsRepository by lazy { LyricsRepository(appScope) }
    val theme: ThemeRepository by lazy { ThemeRepository() }
    val profiles: ProfileRepository by lazy { ProfileRepository(appScope) }
    val taste: TasteRepository by lazy { TasteRepository(appScope, library, profiles).also { it.start() } }
    val podcasts: PodcastRepository by lazy { PodcastRepository(appScope) }
    val artistFollows: ArtistFollows by lazy { ArtistFollows() }
    val musicDownloads: MusicDownloads by lazy { MusicDownloads(ioScope, onImported = { library.refresh() }) }

    val player: PlayerConnection by lazy {
        PlayerConnection(
            resolve = ::resolve,
            recordPlay = { library.recordPlay(it) },
            recordSkip = { library.recordSkip(it) },
            recordListen = { taste.record(it) },
            radio = { taste.songRadio(it) },
            librarySongs = { library.library.value.songs },
            resumePosition = podcasts::resumePosition,
            saveProgress = { key, pos, dur -> podcasts.saveProgress(key, pos, dur) },
            setPlayed = { key, played, dur -> podcasts.setPlayed(key, played, dur) },
            streamUrl = { musicStreams.streamUrl(it) },
            streamFailed = { song, url -> musicStreams.sourceFailed(song, url) },
            scope = appScope,
            hiddenSongs = { taste.hiddenSongs.value },
            hiddenArtists = { taste.hiddenArtists.value },
            onlineStation = { seed -> Monochrome.search(seed.primaryArtist).filter { it.playable }.map(musicStreams::register) },
            awaitLibrary = { withTimeoutOrNull(5_000) { library.awaitScan() } },
        )
    }
    private val mediaSession by lazy {
        MediaSessionController(player, ::resolve, appScope)
    }

    val social: SocialRepository by lazy {
        SocialRepository(
            appScope,
            profileName = { profiles.profile.value.name },
            saveProfileName = { profiles.setName(it) },
            profilePhoto = { profiles.photoFile.takeIf { it.isFile } },
        )
    }
    val rooms: ListeningRooms by lazy {
        ListeningRooms(
            social, RoomPlayerAdapter(), appScope,
            librarySongs = { library.library.value.songs },
            registerStream = musicStreams::register,
            trackFor = musicStreams::track,
        )
    }
    /** Your devices: "Playing on …", remote control, Listen here and continue where you left off. */
    val deviceSync: DeviceSyncRepository by lazy {
        DeviceSyncRepository(social, DevicePlayerAdapter(), appScope, resolveTrack = { playlistMatches.resolve(it) })
    }
    val playlistMatches: PlaylistMatches by lazy {
        PlaylistMatches(appScope, librarySongs = { library.library.value.songs }, registerStream = musicStreams::register)
    }
    val flacConversion: FlacConversion by lazy {
        FlacConversion(
            appScope, library,
            remap = FlacConversion.standardRemap(library, metadata, lyrics, taste) { player.remapSongs(it) },
            playingSongId = player::playingSongId,
            confirmDelete = false,
        )
    }

    /** Resolves any queue id: library songs and local books/podcasts, streamed songs, podcast episodes (negative ids). */
    fun resolve(id: Long): Song? =
        if (id < 0) musicStreams.lookup(id) ?: podcasts.episodeSongs.value[id]
        else library.library.value.songById[id] ?: library.localPodcasts.value.firstOrNull { it.id == id }
            ?: library.localBooks.value.firstOrNull { it.id == id }

    private var started = false

    /** Starts scanning, playback and background work (once; safe to call again). */
    fun start() {
        if (started) return
        started = true
        EqStore.init()
        library.ensureStarted()
        player.connect()
        mediaSession.start()
        taste
        podcasts.start()
        appScope.launch { profiles.profile.collect { runCatching { social.syncProfile() } } }
        deviceSync.start()
        ioScope.launch { musicDownloads.start() }
    }

    /** Saves the queue position and every pending store before the process exits. */
    fun shutdown() {
        runCatching { player.saveNow() }
        if (started) runCatching { deviceSync.flush() }
        runCatching { mediaSession.close() }
        runCatching { player.release() }
        runCatching { library.flush(); lyrics.flush(); musicStreams.flush(); musicDownloads.flush() }
        runCatching { JsonStore.flushAll(); DataFile.flushAll() }
    }

    /** Lets a Listening Room drive the player through the queue of resolved songs. */
    private inner class RoomPlayerAdapter : RoomPlayer {
        override val queue: List<Song?> get() = player.state.value.queue.map(::resolve)
        override val currentIndex: Int get() = player.state.value.currentIndex
        override val isPlaying: Boolean get() = player.state.value.isPlaying
        override val speed: Float get() = player.state.value.speed
        override val source: String? get() = player.state.value.source
        override val positionMs: Long get() = player.positionMs.value
        override fun setPlaying(playing: Boolean) = player.setPlaying(playing)
        override fun setRoomPlayback(speed: Float?) = player.setRoomPlayback(speed)
        override fun next() = player.next()
        override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
        override fun removeAt(index: Int) = player.removeAt(index)
        override fun playSongs(songs: List<Song>, shuffle: Boolean, source: String) = player.playSongs(songs, shuffle = shuffle, source = source)
        override fun appendFromSource(songs: List<Song>) = player.appendFromSource(songs)
    }

    /** Lets device sync read and drive the player. */
    private inner class DevicePlayerAdapter : DevicePlayer {
        override val state get() = player.state
        override val positionMs get() = player.positionMs
        override fun song(id: Long) = resolve(id)
        override fun online(song: Song) = musicStreams.track(song)
        override fun setPlaying(playing: Boolean) = player.setPlaying(playing)
        override fun next() = player.next()
        override fun previous() = player.previous()
        override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
        override fun playSongs(songs: List<Song>, source: String, startPositionMs: Long) = player.playSongs(songs, 0, shuffle = false, source = source, startPositionMs = startPositionMs)
        override fun appendFromSource(songs: List<Song>) = player.appendFromSource(songs)
    }

    companion object {
        /** The one app instance (the desktop has no Application object to hang it on). */
        val instance: LocalfyApp by lazy { LocalfyApp() }
    }
}
