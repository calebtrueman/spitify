package com.localfy.app.data.taste

import com.localfy.app.data.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteTest {
    private val now = 1_790_000_000_000L
    private var nextId = 1L

    private fun song(artist: String, genre: String, album: String = "$artist LP", year: Int = 2020) = Song(
        nextId++, "Song $nextId", artist, album, album.hashCode().toLong(), artist, 200_000, 1, 1, year, genre, "Music/", now / 1000 - 400 * 86_400, 1, "audio/mpeg", "s$nextId.mp3",
    )

    private val synth = (1..8).map { song("Neon Harbor", "Synthwave") } + (1..6).map { song("Mira Sol", "Synthwave") }
    private val folk = (1..8).map { song("Juniper Fields", "Folk") } + (1..5).map { song("Cedar & Pine", "Folk") }
    private val metal = (1..6).map { song("Iron Tide", "Metal") }
    private val all = synth + folk + metal

    private fun listen(s: Song, daysAgo: Double, completed: Boolean = true, skipped: Boolean = false, minuteOffset: Int = 0) =
        Listen(s.id, (now - daysAgo * 86_400_000 + minuteOffset * 60_000).toLong(), if (skipped) 5_000 else 200_000, 200_000, completed, skipped)

    /** A listener who mostly plays Neon Harbor + Mira Sol together, some folk, and skips metal. */
    private fun input(hiddenArtists: Set<String> = emptySet()): TasteInput {
        val listens = ArrayList<Listen>()
        repeat(10) { day -> synth.take(5).forEachIndexed { i, s -> listens += listen(s, day + 0.1, minuteOffset = i * 4) }
            listens += listen(synth[9], day + 0.1, minuteOffset = 22) } // Mira Sol in the same sessions
        folk.take(3).forEach { listens += listen(it, 2.0) }
        metal.take(3).forEach { listens += listen(it, 1.0, completed = false, skipped = true) }
        return TasteInput(all, listens, liked = setOf(synth[0].id), hiddenArtists = hiddenArtists, userName = "Sam", now = now)
    }

    @Test
    fun learnsFavouritesAndPenalisesSkips() {
        val m = TasteModel(input())
        assertEquals("Neon Harbor", m.topArtists().first())
        assertTrue(m.normSong(synth[0].id) > m.normSong(folk[0].id))
        assertTrue("skipped songs score below unheard ones", m.normSong(metal[0].id) < 0)
    }

    @Test
    fun coListeningMakesSongsSimilar() {
        val m = TasteModel(input())
        // Mira Sol was played in the same sessions as Neon Harbor, so it should feel closer than folk.
        assertTrue(m.similarity(synth[0], synth[9]) > m.similarity(synth[0], folk[0]))
        assertTrue(m.artistSimilarity("Neon Harbor", "Mira Sol") > m.artistSimilarity("Neon Harbor", "Juniper Fields"))
    }

    @Test
    fun generatesSpotifyStylePlaylists() {
        val mixes = PlaylistGenerator.generate(TasteModel(input()))
        val titles = mixes.map { it.title }
        assertFalse("no generic Daily Mixes", titles.any { it.startsWith("Daily Mix") })
        assertFalse(titles.contains("Discover Weekly"))
        assertFalse(titles.contains("Release Radar"))
        assertTrue(titles.any { it.startsWith("daylist") })
        assertTrue(titles.contains("This Is Neon Harbor"))

    }

    @Test
    fun radioStartsWithSeedAndStaysOnVibe() {
        val m = TasteModel(input())
        val radio = PlaylistGenerator.songRadio(m, synth[0])
        assertEquals(synth[0].id, radio.first().id)
        val synthShare = radio.take(10).count { it.genre == "Synthwave" }
        assertTrue("radio stays close to the seed ($synthShare/10 synthwave)", synthShare >= 6)
    }

    @Test
    fun hiddenArtistsNeverAppear() {
        val mixes = PlaylistGenerator.generate(TasteModel(input(hiddenArtists = setOf("Juniper Fields"))))
        assertTrue(mixes.flatMap { it.songs }.none { it.artist == "Juniper Fields" })
        assertNotNull(mixes.firstOrNull { it.title == "This Is Neon Harbor" })
    }

    @Test
    fun coldStartUsesPickedArtists() {
        val m = TasteModel(TasteInput(all, emptyList(), emptySet(), seedArtists = setOf("Juniper Fields"), now = now))
        assertEquals("Juniper Fields", m.topArtists().first())
        assertTrue(PlaylistGenerator.generate(m).any { it.title == "This Is Juniper Fields" })
    }

    /** Regression: random jitter inside sort keys crashed real-sized libraries ("Comparison method violates its general contract!"). */
    @Test
    fun bigLibrariesDontCrashTheGenerator() {
        val genres = listOf("Rock", "Pop", "Hip-Hop", "Jazz", "Electronic", "Folk")
        val big = (1..600).map { i -> song("Artist ${i % 60}", genres[i % genres.size], album = "Album ${i % 120}", year = 1970 + i % 55) }
        val listens = big.shuffled(kotlin.random.Random(1)).take(300).mapIndexed { i, s -> listen(s, i / 20.0, minuteOffset = i % 20 * 4) }
        repeat(20) { seed ->
            val mixes = PlaylistGenerator.generate(TasteModel(TasteInput(big, listens, emptySet(), userName = "Sam", now = now + seed * 3_600_000L)))
            assertTrue(mixes.isNotEmpty())
        }
    }
}
