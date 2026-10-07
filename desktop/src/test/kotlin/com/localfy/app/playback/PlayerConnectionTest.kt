package com.localfy.app.playback

import com.localfy.app.data.Song
import com.localfy.app.data.db.PlayEventEntity
import com.localfy.app.data.podcast.resumeKey
import com.localfy.app.desktop.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PlayerConnectionTest {
    init { TestEnv.ensure() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val engine = FakeEngine()
    private val library = (1L..20L).map { song(it) }
    private val songs = library.associateBy { it.id }.toMutableMap()
    private var radioResult: List<Song> = emptyList()
    private var hidden: Set<Long> = emptySet()
    private val plays = mutableListOf<Long>()
    private val skips = mutableListOf<Long>()
    private val listens = mutableListOf<PlayEventEntity>()
    private val resumeRequests = mutableListOf<String>()
    private val messages = mutableListOf<String>()

    private val player = PlayerConnection(
        resolve = { songs[it] },
        recordPlay = { plays += it },
        recordSkip = { skips += it },
        recordListen = { listens += it },
        radio = { radioResult },
        librarySongs = { library },
        resumePosition = { key -> resumeRequests += key; 42_000L },
        saveProgress = { _, _, _ -> },
        setPlayed = { _, _, _ -> },
        streamUrl = { "https://example.com/${it.id}.m4a" },
        scope = scope,
        hiddenSongs = { hidden },
        engine = engine,
        prefs = Prefs("player-test-${UUID.randomUUID()}"),
        computeDispatcher = Dispatchers.Unconfined,
    )

    init {
        scope.launch { player.messages.collect { messages += it } }
        // The radio continuation is exercised on its own; keep the other queues predictable.
        player.setAutoplay(false)
    }

    @After fun tearDown() { scope.cancel() }

    private val st get() = player.state.value
    private fun play(list: List<Song>, start: Int = 0, shuffle: Boolean? = false) {
        player.playSongs(list, start, shuffle = shuffle)
        engine.ready()
    }

    @Test fun playSongsLoadsTheStartSongAndQueuesTheNext() {
        play(library.take(5), start = 2)
        assertEquals(listOf(1L, 2, 3, 4, 5), st.queue)
        assertEquals(2, st.currentIndex)
        assertEquals(3L, engine.current?.songId)
        assertEquals(4L, engine.next?.songId)
        assertTrue(st.isPlaying)
    }

    @Test fun autoTransitionAdvancesTheQueue() {
        play(library.take(3))
        engine.finishTrack()
        assertEquals(1, st.currentIndex)
        assertEquals(3L, engine.next?.songId)
        engine.finishTrack()
        assertEquals(2, st.currentIndex)
        assertNull(engine.next)
        engine.finishTrack()
        assertEquals(PlaybackState.ENDED, st.playbackState)
    }

    @Test fun previousRestartsAfterThreeSeconds() {
        play(library.take(3), start = 1)
        engine.positionMs = 5_000
        player.previous()
        assertEquals(listOf(0L), engine.seeks)
        assertEquals(1, st.currentIndex)
        engine.positionMs = 1_000
        player.previous()
        assertEquals(0, st.currentIndex)
        assertEquals(1L, engine.current?.songId)
        // Nothing before the first song: restart it.
        engine.positionMs = 500
        player.previous()
        assertEquals(0, st.currentIndex)
        assertEquals(listOf(0L, 0L), engine.seeks)
    }

    @Test fun nextSkipsAndRecordsAnEarlySkip() {
        play(library.take(3))
        engine.positionMs = 10_000
        player.next()
        assertEquals(1, st.currentIndex)
        assertEquals(listOf(1L), skips)
    }

    @Test fun repeatCyclesOffAllOne() {
        play(library.take(2))
        player.cycleRepeat()
        assertEquals(RepeatMode.ALL, st.repeatMode)
        player.cycleRepeat()
        assertEquals(RepeatMode.ONE, st.repeatMode)
        // Repeat one: the engine is told to play the same slot again.
        assertEquals(engine.current?.uid, engine.next?.uid)
        engine.finishTrack()
        assertEquals(0, st.currentIndex)
        player.cycleRepeat()
        assertEquals(RepeatMode.OFF, st.repeatMode)
    }

    @Test fun repeatAllWrapsToTheStart() {
        play(library.take(2), start = 1)
        player.cycleRepeat() // ALL
        assertEquals(1L, engine.next?.songId)
        engine.finishTrack()
        assertEquals(0, st.currentIndex)
    }

    @Test fun shuffleKeepsCurrentAndUnshuffleRestoresOrder() {
        play(library.take(10), start = 3)
        player.toggleShuffle()
        assertTrue(st.shuffle)
        assertEquals(0, st.currentIndex)
        assertEquals(4L, st.queue[0])
        assertEquals(library.take(10).map { it.id }.toSet(), st.queue.toSet())
        assertEquals(1, engine.loads.size) // the playing song is untouched
        player.toggleShuffle()
        assertFalse(st.shuffle)
        assertEquals(library.take(10).map { it.id }, st.queue)
        assertEquals(3, st.currentIndex)
    }

    @Test fun addToQueueGoesAfterOtherQueuedSongs() {
        play(library.take(4))
        assertTrue(player.addToQueue(listOf(songs[10]!!)))
        assertTrue(player.addToQueue(listOf(songs[11]!!)))
        assertEquals(listOf(1L, 10, 11, 2, 3, 4), st.queue)
        assertEquals(setOf(1, 2), st.manualQueueIndices)
        player.playNext(listOf(songs[12]!!))
        assertEquals(listOf(1L, 12, 10, 11, 2, 3, 4), st.queue)
        assertTrue(messages.any { it == "Added 1 song to queue" })
    }

    @Test fun autoplayContinuesWithRadioPicks() {
        player.setAutoplay(true)
        hidden = setOf(7L)
        radioResult = listOf(songs[6]!!, songs[7]!!, songs[8]!!, songs[9]!!)
        play(library.take(2))
        engine.finishTrack() // now on the last song: refill
        val auto = st.autoplayQueueIndices.sorted()
        assertEquals((2..6).toList(), auto)
        val added = auto.map { st.queue[it] }
        assertEquals(listOf(6L, 8, 9), added.take(3)) // radio order first, hidden song left out
        assertFalse(7L in added)
        assertFalse(2L in added)
        // Turning autoplay off removes the continuation.
        player.setAutoplay(false)
        assertEquals(listOf(1L, 2L), st.queue)
    }

    @Test fun errorsSkipToTheNextSongWithAMessage() {
        val bad = song(30, fileName = "broken.xyz")
        songs[30] = bad
        play(listOf(bad, songs[2]!!))
        engine.fail(EngineError.Kind.UNSUPPORTED)
        assertEquals(1, st.currentIndex)
        assertEquals(2L, engine.current?.songId)
        assertEquals("Can't play “T30” (XYZ isn't supported) — skipping", messages.last())
        engine.fail(EngineError.Kind.NETWORK)
        assertEquals("No connection. Downloads can play offline.", messages.last())
    }

    @Test fun crossfadeSkipsConsecutiveAlbumTracksAndSpokenWord() {
        val a1 = song(40, album = "LP", track = 1); val a2 = song(41, album = "LP", track = 2); val other = song(42)
        listOf(a1, a2, other).forEach { songs[it.id] = it }
        player.setAutoplay(false)
        player.setCrossfade(6_000)
        play(listOf(a1, a2, other))
        assertEquals(0, engine.nextCrossfadeMs)
        player.setCrossfadeKeepAlbums(false)
        assertEquals(6_000, engine.nextCrossfadeMs)
        player.setCrossfadeKeepAlbums(true)
        engine.finishTrack()
        assertEquals(6_000, engine.nextCrossfadeMs) // a2 -> other: different albums
        val ep = song(43, podcast = true, episodeId = 9)
        songs[43] = ep
        player.addToQueue(listOf(ep))
        assertEquals(0, engine.nextCrossfadeMs) // spoken word never crossfades
    }

    @Test fun sleepAtEndOfTrackStopsAfterTheCurrentSong() {
        play(library.take(3))
        player.sleepAtEndOfTrack()
        assertNull(engine.next)
        engine.finishTrack()
        assertEquals(1, st.currentIndex)
        assertFalse(engine.playWhenReady)
        assertNull(player.sleepTimer.value)
        assertEquals(2L, engine.current?.songId)
    }

    @Test fun episodesResumeByEpisodeKey() {
        val ep = song(-9, podcast = true, episodeId = 9, sourceUri = "https://feed.example/ep9.mp3")
        assertEquals("ep:9", ep.resumeKey)
        assertEquals("12", song(12).resumeKey)
        songs[-9] = ep
        player.playEpisode(ep)
        assertEquals(listOf("ep:9"), resumeRequests)
        assertEquals(42_000L, engine.currentStartMs)
        assertTrue(engine.current!!.isRemote)
        assertTrue(engine.current!!.spoken)
    }

    @Test fun clearUpNextAndRemoveAndMove() {
        play(library.take(5), start = 1)
        player.move(4, 0)
        assertEquals(listOf(5L, 1, 2, 3, 4), st.queue)
        assertEquals(2, st.currentIndex)
        player.removeAt(0)
        assertEquals(1, st.currentIndex)
        player.removeAt(1) // the playing song: the next one takes over
        assertEquals(listOf(1L, 3, 4), st.queue)
        assertEquals(3L, engine.current?.songId)
        player.clearUpNext()
        assertEquals(listOf(1L, 3), st.queue)
        assertNull(engine.next)
    }

    @Test fun streamedSongsAskForAUrl() {
        val s = song(50, sourceUri = "spitify://music/50")
        songs[50] = s
        play(listOf(s))
        val url = kotlinx.coroutines.runBlocking { engine.current!!.url() }
        assertEquals("https://example.com/50.m4a", url)
        assertEquals(50L, player.playingSongId())
    }

    @Test fun listenIsLoggedWhenSkipping() {
        play(library.take(3))
        // Simulate 10 s of ticks without waiting for the real ticker.
        repeat(40) { tickListen() }
        player.next()
        assertEquals(1, listens.size)
        assertEquals(1L, listens.single().songId)
        assertTrue(listens.single().skipped)
    }

    private fun tickListen() {
        val f = PlayerConnection::class.java.getDeclaredField("listenedMs").apply { isAccessible = true }
        f.setLong(player, f.getLong(player) + 250)
    }
}
