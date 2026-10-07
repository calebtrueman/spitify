package com.localfy.app.data.sync

import com.localfy.app.data.social.SharedTrack
import com.localfy.app.data.sync.LibrarySync.Companion.LIKED
import com.localfy.app.data.sync.LibrarySync.Companion.HISTORY
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySyncTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    private var now = 1_000L

    private fun device(id: String) = LibrarySync(id) { now }
    private fun song(title: String, artist: String = "Band", duration: Long = 200_000, art: String? = null) =
        SharedTrack(title = title, artist = artist, durationMs = duration, artwork = art)
    private fun liked(vararg songs: SharedTrack) = songs.associate { LibrarySync.trackKey(it) to LibrarySync.trackValue(it) }

    /** Sends every document [from] has to [to]; [to] applies everything it's told. */
    private fun sync(from: LibrarySync, to: LibrarySync): List<SyncChange> =
        from.docNames().flatMap { to.receive(JSONObject(from.doc(it).toString())) }.onEach(to::applied)

    @Test fun likesMergeAndRemovalsWin() {
        val mac = device(a); val phone = device(b)
        mac.report(LIKED, liked(song("One"), song("Two")))
        now += 10; phone.report(LIKED, liked(song("Two"), song("Three")))
        // Linking: both libraries combine.
        val toPhone = sync(mac, phone); sync(phone, mac)
        assertEquals(setOf("one|band"), toPhone.map { it.key }.toSet())
        assertEquals(setOf("one|band", "two|band", "three|band"), mac.present(LIKED).keys)
        assertEquals(mac.present(LIKED).keys, phone.present(LIKED).keys)
        // The phone unlikes One; the Mac removes it too and it stays removed.
        now += 10; phone.report(LIKED, liked(song("Two"), song("Three")))
        val removal = sync(phone, mac)
        assertEquals(listOf(SyncChange(LIKED, "one|band", false, null)), removal)
        now += 10; assertTrue("nothing new to send", mac.report(LIKED, liked(song("Two"), song("Three"))).isEmpty())
        assertEquals(mac.digest(), phone.digest())
    }

    @Test fun tagsVersusCatalogueDetailsDontPingPong() {
        val mac = device(a); val phone = device(b)
        mac.report(LIKED, liked(song("Song (feat. Guest)", "Band, Guest", 200_123)))
        sync(mac, phone)
        // The phone has it as a stream with different details: same song, no new change.
        now += 10
        assertTrue(phone.report(LIKED, liked(song("Song", "Band", 200_000, "https://x.example/a.jpg"))).isEmpty())
        assertTrue(mac.report(LIKED, liked(song("Song (feat. Guest)", "Band, Guest", 200_123))).isEmpty())
    }

    @Test fun anUnloadedLibraryDoesntWipeEverything() {
        val mac = device(a)
        val many = (1..40).map { song("S$it") }.toTypedArray()
        mac.report(LIKED, liked(*many))
        now += 10
        assertTrue(mac.report(LIKED, emptyMap()).isEmpty())
        assertEquals(40, mac.present(LIKED).size)
        assertEquals(40, mac.report(LIKED, emptyMap(), allowMassRemoval = true).let { mac.present(LIKED).size.let { n -> 40 - n } })
    }

    @Test fun unmatchedSongsStayPendingAndArentRemoved() {
        val mac = device(a); val phone = device(b)
        mac.report(LIKED, liked(song("Rare"), song("Common")))
        val changes = phone.run { mac.docNames().flatMap { receive(JSONObject(mac.doc(it).toString())) } }
        // The phone can only find "Common".
        changes.filter { it.key == "common|band" }.forEach(phone::applied)
        now += 10
        assertTrue("absent but never had it: not a removal", phone.report(LIKED, liked(song("Common"))).isEmpty())
        assertEquals(listOf("rare|band"), phone.pending(LIKED).map { it.key })
    }

    @Test fun threeDevicesConvergeWhateverTheOrder() {
        val x = device(a); val y = device(b); val z = device(c)
        x.report(LIKED, liked(song("A"))); now += 1
        y.report(LIKED, liked(song("A"), song("B"))); now += 1
        z.report(LIKED, liked(song("C")))
        sync(z, y); sync(y, x); sync(x, z); sync(z, x); sync(x, y); sync(y, z)
        assertEquals(x.digest(), y.digest()); assertEquals(y.digest(), z.digest())
        assertEquals(setOf("a|band", "b|band", "c|band"), z.present(LIKED).keys)
    }

    @Test fun playlistEntriesMergeConcurrentAdditions() {
        val mac = device(a); val phone = device(b)
        val list = LibrarySync.playlist("p1")
        val base = listOf(song("One"), song("Two"))
        fun entries(tracks: List<SharedTrack>) = LibrarySync.entryKeys(tracks).zip(tracks).mapIndexed { i, (k, t) -> k to LibrarySync.entryValue(t, i) }.toMap()
        mac.report(list, entries(base)); sync(mac, phone)
        now += 10; mac.report(list, entries(base + song("Mac add")))
        now += 1; phone.report(list, entries(base + song("Phone add")))
        sync(mac, phone); sync(phone, mac)
        assertEquals(setOf("one|band#1", "two|band#1", "mac add|band#1", "phone add|band#1"), mac.present(list).keys)
        assertEquals(mac.digest(), phone.digest())
        // The same song twice gets distinct entries.
        assertEquals(listOf("x|band#1", "x|band#2"), LibrarySync.entryKeys(listOf(song("X"), song("X"))))
    }

    @Test fun historyIsGrowOnlyAndTrimmedByAge() {
        val mac = device(a); val phone = device(b)
        mac.add(HISTORY, "e1", LibrarySync.trackValue(song("One")).put("playedAt", now))
        assertTrue("same event twice", mac.add(HISTORY, "e1", JSONObject()).isEmpty())
        sync(mac, phone)
        assertEquals(setOf("e1"), phone.present(HISTORY).keys)
        now += (LibrarySync.HISTORY_DAYS + 1) * 24L * 60 * 60_000
        phone.trim()
        assertTrue(phone.present(HISTORY).isEmpty())
    }

    @Test fun meaningIgnoresTrackAndKeyOrder() {
        val one = JSONObject().put("pos", 3).put("track", JSONObject().put("title", "x"))
        val two = JSONObject("""{"track":{"title":"y"},"pos":3.0}""")
        assertEquals(LibrarySync.meaning(one), LibrarySync.meaning(two))
        assertEquals("{\"played\":true,\"positionMs\":5}", LibrarySync.meaning(JSONObject().put("positionMs", 5).put("played", true)))
        assertNotEquals(LibrarySync.meaning(one), LibrarySync.meaning(JSONObject().put("pos", 4)))
    }

    @Test fun savedStateRoundTrips() {
        val mac = device(a)
        mac.report(LIKED, liked(song("One")))
        val copy = LibrarySync(a) { now }.apply { load(JSONObject(mac.json().toString())) }
        assertEquals(mac.digest(), copy.digest())
        now += 10
        assertTrue("remembers what was reported", copy.report(LIKED, liked(song("One"))).isEmpty())
    }

    @Test fun trackKeys() {
        assertEquals("song|band", LibrarySync.trackKey("Song (feat. Guest)", "Band feat. Guest"))
        assertEquals("song|band", LibrarySync.trackKey("Song [ft. X]", "Band"))
        assertNotEquals(LibrarySync.trackKey("Song", "Band"), LibrarySync.trackKey("Song - Remastered 2011", "Band"))
        assertEquals("beyonce|beyonce", LibrarySync.trackKey("Beyoncé", "BEYONCÉ"))
        assertEquals(LibrarySync.trackKey("Dreams", "Florence + the Machine & Guest"), LibrarySync.trackKey("Dreams", "Florence + the Machine"))
        assertEquals("x|band", LibrarySync.trackKey("X", "Band x Other"))
    }
}

class LibrarySyncInfoFieldsTest {
    @Test fun underscoreFieldsTravelButDontCountAsChanges() {
        val v1 = JSONObject().put("_addedAt", 5).put("name", "Mix")
        val v2 = JSONObject().put("_addedAt", 9).put("name", "Mix")
        assertEquals(LibrarySync.meaning(v1), LibrarySync.meaning(v2))
        assertEquals("{\"name\":\"Mix\"}", LibrarySync.meaning(v1))
    }
}
