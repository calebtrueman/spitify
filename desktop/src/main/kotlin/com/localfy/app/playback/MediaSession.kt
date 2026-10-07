package com.localfy.app.playback

import com.localfy.app.data.Song
import com.localfy.app.desktop.AppPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/** What the OS "now playing" surfaces show. */
data class NowPlaying(
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    val isPlaying: Boolean,
    val speed: Float = 1f,
    /** Local image file for the cover, when the app has one. */
    val artworkPath: String? = null,
    val canGoNext: Boolean = true,
    val canGoPrevious: Boolean = true,
)

enum class MediaCommand { PLAY, PAUSE, TOGGLE, NEXT, PREVIOUS, STOP }

/**
 * OS media integration: the macOS Now Playing widget, Linux MPRIS, Windows media keys. Implementations
 * must never throw or block the caller; [create] falls back to [NoOpMediaSession].
 */
interface MediaSessionBridge : AutoCloseable {
    /** Starts listening for OS commands. Callbacks may arrive on any thread. */
    fun start(onCommand: (MediaCommand) -> Unit, onSeek: (positionMs: Long) -> Unit)
    /** Publishes what's playing (null = nothing). */
    fun update(info: NowPlaying?)
    override fun close()

    companion object {
        fun create(): MediaSessionBridge = runCatching {
            when {
                AppPaths.isMac -> MacNowPlaying()
                AppPaths.isLinux -> MprisMediaSession()
                AppPaths.isWindows -> WindowsMediaKeys()
                else -> NoOpMediaSession
            }
        }.getOrElse { NoOpMediaSession }
    }
}

object NoOpMediaSession : MediaSessionBridge {
    override fun start(onCommand: (MediaCommand) -> Unit, onSeek: (positionMs: Long) -> Unit) {}
    override fun update(info: NowPlaying?) {}
    override fun close() {}
}

/**
 * Wraps a bridge so every call runs on its own daemon thread and any failure only disables it.
 * The player and UI never wait on D-Bus or the Objective-C runtime.
 */
internal class SafeMediaSession(private val inner: MediaSessionBridge) : MediaSessionBridge {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "spitify-media-session").apply { isDaemon = true } }
    @Volatile private var broken = false

    private fun run(block: () -> Unit) {
        if (broken || executor.isShutdown) return
        runCatching { executor.execute { if (!broken) runCatching(block).onFailure { broken = true; runCatching { inner.close() } } } }
    }

    override fun start(onCommand: (MediaCommand) -> Unit, onSeek: (positionMs: Long) -> Unit) = run {
        inner.start({ c -> runCatching { onCommand(c) } }, { p -> runCatching { onSeek(p) } })
    }
    override fun update(info: NowPlaying?) = run { inner.update(info) }
    override fun close() { run { inner.close() }; executor.shutdown() }
}

/**
 * Connects a [PlayerConnection] to the OS media session: publishes the current song/position
 * and routes media keys back to the player.
 */
class MediaSessionController(
    private val player: PlayerConnection,
    private val resolve: (Long) -> Song?,
    private val scope: CoroutineScope,
    bridge: MediaSessionBridge = MediaSessionBridge.create(),
    private val artworkPath: (Song) -> String? = { null },
) : AutoCloseable {
    private val bridge = SafeMediaSession(bridge)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        bridge.start(
            onCommand = { c ->
                when (c) {
                    MediaCommand.PLAY -> player.setPlaying(true)
                    MediaCommand.PAUSE, MediaCommand.STOP -> player.setPlaying(false)
                    MediaCommand.TOGGLE -> player.togglePlay()
                    MediaCommand.NEXT -> player.next()
                    MediaCommand.PREVIOUS -> player.previous()
                }
            },
            onSeek = { player.seekTo(it) },
        )
        var last: NowPlaying? = null
        var lastAt = 0L
        job = scope.launch {
            combine(player.state, player.positionMs) { st, pos -> st to pos }
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .collect { (st, pos) ->
                    val song = st.currentId?.let(resolve)
                    val info = song?.let {
                        NowPlaying(
                            it.id, it.title, it.artist, it.album, st.durationMs, pos, st.isPlaying, st.speed,
                            artworkPath(it), canGoNext = st.currentIndex < st.queue.lastIndex, canGoPrevious = st.hasMedia,
                        )
                    }
                    val prev = last
                    val now = System.currentTimeMillis()
                    // The OS extrapolates the position from the rate; only re-send on changes or jumps.
                    val expected = prev?.let { p -> p.positionMs + if (p.isPlaying) ((now - lastAt) * p.speed).toLong() else 0L }
                    val changed = when {
                        info == null -> prev != null || lastAt == 0L
                        prev == null -> true
                        else -> info.copy(positionMs = 0) != prev.copy(positionMs = 0) || abs(info.positionMs - (expected ?: 0L)) > 1_500
                    }
                    if (changed) {
                        last = info; lastAt = now
                        bridge.update(info)
                    }
                }
        }
    }

    override fun close() {
        job?.cancel(); job = null
        bridge.update(null)
        bridge.close()
    }
}
