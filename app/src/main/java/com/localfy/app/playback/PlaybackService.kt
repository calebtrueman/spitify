package com.localfy.app.playback

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.localfy.app.MainActivity
import java.util.concurrent.Executors

/**
 * Playback + media library service. Besides the phone UI it serves Android Auto / Automotive,
 * Assistant, Bluetooth and the lock screen: a browsable library ([AutoLibrary]), search, voice
 * "play X on Spitify", resumption, and car buttons (like / shuffle, or ±10/30 s for spoken word).
 */
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var library: AutoLibrary
    private val app get() = application as com.localfy.app.LocalfyApp
    private lateinit var player: ExoPlayer
    private var crossfader: Crossfader? = null
    private var effects: AudioEffectsEngine? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player = buildPlayer(this)
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        effects = AudioEffectsEngine(this)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) = publishAudioSession(audioSessionId)

            // Unsupported/corrupt file or dropped stream: move on instead of stalling the queue.
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })
        publishAudioSession(player.audioSessionId)
        crossfader = Crossfader(this, player, attributes, ::resolve).also { it.start() }

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        library = AutoLibrary(this)
        session = MediaLibrarySession.Builder(this, player, SessionCallback())
            .setSessionActivity(openApp)
            .setBitmapLoader(CacheBitmapLoader(MediaStoreBitmapLoader()))
            .build()

        // Started by a car/Bluetooth/Assistant with the phone UI closed: bring the rest of the app up
        // so play counts, resume positions and the saved queue keep working.
        app.library.ensureStarted()
        app.podcasts.start()
        app.taste // recommendation engine (Daily Mixes etc. in the car too)
        app.player.connect()
        // New/updated mixes: tell connected cars to reload "For you".
        scope.launch { app.library.mixes.collect { session?.notifyChildrenChanged(AutoLibrary.TAB_HOME, it.size + 4, null) } }
        scope.launch {
            combine(app.player.state, app.library.likedIds) { st, liked -> st to liked }.collect { (st, liked) -> refreshButtons(st.currentId, st.shuffle, liked) }
        }
    }

    private fun buttons(currentId: Long?, shuffle: Boolean, liked: Set<Long>): List<CommandButton> {
        val song = currentId?.let(app::resolve)
        return if (song?.isPodcast == true) listOf(
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10).setDisplayName("Back 10 seconds").setSessionCommand(SessionCommand(CMD_BACK, Bundle.EMPTY)).setSlots(CommandButton.SLOT_BACK_SECONDARY).build(),
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30).setDisplayName("Forward 30 seconds").setSessionCommand(SessionCommand(CMD_FORWARD, Bundle.EMPTY)).setSlots(CommandButton.SLOT_FORWARD_SECONDARY).build(),
        ) else listOf(
            CommandButton.Builder(if (song != null && song.id in liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName(if (song != null && song.id in liked) "Remove from Liked Songs" else "Like")
                .setSessionCommand(SessionCommand(CMD_LIKE, Bundle.EMPTY)).setSlots(CommandButton.SLOT_BACK_SECONDARY).build(),
            CommandButton.Builder(if (shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName(if (shuffle) "Shuffle on" else "Shuffle off")
                .setSessionCommand(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY)).setSlots(CommandButton.SLOT_FORWARD_SECONDARY).build(),
        )
    }

    private fun refreshButtons(currentId: Long?, shuffle: Boolean, liked: Set<Long>) {
        session?.setMediaButtonPreferences(buttons(currentId, shuffle, liked))
    }

    private fun publishAudioSession(id: Int) {
        if (id == C.AUDIO_SESSION_ID_UNSET || id == AudioSessionHolder.id) return
        AudioSessionHolder.id = id
        effects?.attach(id)
        // Lets system / Samsung SoundAlive equalisers attach to our output.
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, id)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC),
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** Swiping Spitify away from Recents stops playback and the service (like closing the app). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        app.player.saveNow()
        // The app's own MediaController keeps the service bound, so stopSelf() alone would leave it running.
        // Unbind it, and the stopped service is destroyed (releasing the session) once nothing else is bound.
        // Never release the session while the service lives: a restart with no session can't start in the
        // foreground and Android kills the app for it.
        app.player.disconnect()
        // Pauses, takes the service out of the foreground and stops it in one step; a plain pause() + stopSelf()
        // races Media3's notification update, which starts the service again.
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        val s = session ?: return
        session = null
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioSessionHolder.id)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName),
        )
        scope.cancel()
        crossfader?.release()
        effects?.release()
        s.player.release()
        s.release()
    }

    private inner class SessionCallback : MediaLibrarySession.Callback {
        @OptIn(UnstableApi::class)
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_LIKE, Bundle.EMPTY))
                .add(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(CMD_BACK, Bundle.EMPTY))
                .add(SessionCommand(CMD_FORWARD, Bundle.EMPTY))
                .apply { if (controller.packageName == packageName) add(SessionCommand(CMD_SKIP_SILENCE, Bundle.EMPTY)) }
                .build()
            val st = app.player.state.value
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(buttons(st.currentId, st.shuffle, app.library.likedIds.value))
                .build()
        }

        @OptIn(UnstableApi::class)
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_SKIP_SILENCE -> player.skipSilenceEnabled = args.getBoolean(EXTRA_ENABLED)
                CMD_LIKE -> player.currentMediaItem?.mediaId?.toLongOrNull()?.takeIf { it >= 0 }?.let { app.library.toggleLike(it) }
                CMD_SHUFFLE -> app.player.toggleShuffle()
                CMD_BACK -> player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                CMD_FORWARD -> player.seekTo(player.currentPosition + 30_000)
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // ---- browsing (Android Auto / Automotive / Assistant) ----

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val extras = Bundle().apply {
                putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                putInt(androidx.media3.session.MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, androidx.media3.session.MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                putInt(androidx.media3.session.MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, androidx.media3.session.MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            }
            return Futures.immediateFuture(LibraryResult.ofItem(library.root(), LibraryParams.Builder().setExtras(extras).build()))
        }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val all = library.children(parentId)
            val from = (page * pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, (from + pageSize).coerceAtMost(all.size))), params)
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
            scope.future {
                library.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionResult.RESULT_ERROR_BAD_VALUE)
            }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> =
            scope.future {
                session.notifySearchResultChanged(browser, query, library.search(query).size, params)
                LibraryResult.ofVoid()
            }

        override fun onGetSearchResult(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val all = library.searchResults(query, library.search(query))
            val from = (page * pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, (from + pageSize).coerceAtMost(all.size))), params)
        }

        // ---- playback requests ----

        @OptIn(UnstableApi::class)
        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val (items, index, position) = library.expand(mediaItems, startIndex, startPositionMs)
            MediaSession.MediaItemsWithStartPosition(items.map(::resolve), index, position)
        }

        // Items arriving from a controller may have lost their URI while being bundled; resolve it back.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.flatMap { item ->
                if (item.mediaId.contains('/') || item.requestMetadata.searchQuery != null) library.expand(listOf(item), 0, 0).first else listOf(item)
            }.map(::resolve).toMutableList()
        }

        @OptIn(UnstableApi::class)
        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            scope.future {
                val (items, index, position) = library.resumption() ?: throw UnsupportedOperationException("Nothing to resume")
                MediaSession.MediaItemsWithStartPosition(items.map(::resolve), index, position)
            }
    }

    private fun resolve(item: MediaItem): MediaItem {
        if (item.localConfiguration != null) return item
        val uri = item.requestMetadata.mediaUri
            ?: item.mediaId.toLongOrNull()?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
            ?: return item
        return item.buildUpon().setUri(uri).build()
    }

    /** Lock screen / notification / Bluetooth art straight from MediaStore's album thumbnails. */
    @UnstableApi
    private inner class MediaStoreBitmapLoader : BitmapLoader {
        private val fallback = DataSourceBitmapLoader.Builder(this@PlaybackService).build()
        private val executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

        override fun supportsMimeType(mimeType: String) = fallback.supportsMimeType(mimeType)
        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = fallback.decodeBitmap(data)
        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
            if (uri.authority == MediaStore.AUTHORITY || uri.authority == "$packageName.art") {
                executor.submit<Bitmap> {
                    if (uri.authority == "$packageName.art") {
                        return@submit contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                            ?: throw java.io.IOException("No artwork for $uri")
                    }
                    uri.lastPathSegment?.toLongOrNull()?.let { (application as com.localfy.app.LocalfyApp).metadata.customArt(it) }
                        ?.let { com.localfy.app.ui.art.decodeSampled(it, 720) }
                        ?: runCatching { contentResolver.loadThumbnail(uri, Size(720, 720), null) }.getOrNull()
                        // No embedded art: fall back to the cover Localfy fetched online.
                        ?: uri.lastPathSegment?.toLongOrNull()
                            ?.let { id -> (application as com.localfy.app.LocalfyApp).let { it.metadata.customArt(id) ?: it.onlineArt.cached(id) } }
                            ?.let { com.localfy.app.ui.art.decodeSampled(it, 720) }
                        ?: throw java.io.IOException("No artwork for $uri")
                }
            } else {
                fallback.loadBitmap(uri)
            }
    }

    companion object {
        const val CMD_SKIP_SILENCE = "localfy.skip_silence"
        const val CMD_LIKE = "spitify.like"
        const val CMD_SHUFFLE = "spitify.shuffle"
        const val CMD_BACK = "spitify.back10"
        const val CMD_FORWARD = "spitify.forward30"
        const val EXTRA_ENABLED = "enabled"
    }
}

/**
 * Player with every format we can decode: platform codecs first, then the bundled FFmpeg decoders
 * (ALAC, AC-3/E-AC-3, DTS, TrueHD, plus fallbacks) and our AIFF extractor on top of Media3's own.
 */
@OptIn(UnstableApi::class)
fun buildPlayer(context: android.content.Context): ExoPlayer.Builder =
    ExoPlayer.Builder(
        context,
        androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true),
        androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context, LocalfyExtractors),
    )

/** Same-process handoff of the ExoPlayer audio session so the UI can open the system equaliser. */
object AudioSessionHolder {
    @Volatile var id: Int = C.AUDIO_SESSION_ID_UNSET
}
