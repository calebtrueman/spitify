package com.localfy.app.data.sync

import com.localfy.app.data.social.SharedTrack
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Writes real Kotlin sync documents for the iPhone tests (ios/SpitifyTests/Fixtures/library-sync-kotlin.json):
 * Swift must get the same digests, keys and shards from them. Regenerate with SPITIFY_WRITE_FIXTURES=1;
 * otherwise this checks the committed file is current.
 */
class SwiftFixtureTest {
    @Test fun kotlinDocumentsForSwift() {
        var now = 1_760_000_000_000L
        val sync = LibrarySync("a".repeat(64)) { now }
        fun t(title: String, artist: String, d: Long = 200_000, src: String? = null) = SharedTrack(title = title, artist = artist, album = "Album", durationMs = d, sourceID = src)
        val songs = listOf(t("Song (feat. Guest)", "Band feat. Guest", src = "123"), t("Beyoncé", "BEYONCÉ"), t("Mötley", "Mötley Crüe"),
            t("Back in Black", "AC/DC"), t("The Boxer", "Simon & Garfunkel"), t("夜に駆ける", "YOASOBI"), t("Dreams", "Florence + the Machine & Guest"),
            t("Slash/Path \"quoted\"", "Tab\tArtist"))
        sync.report(LibrarySync.LIKED, songs.associate { LibrarySync.trackKey(it) to LibrarySync.trackValue(it).put("_addedAt", 5) })
        now += 1_000
        val list = songs.take(3) + songs[0]
        sync.report(LibrarySync.PLAYLISTS, mapOf("pl-1" to JSONObject().put("name", "Road trip").put("description", "")))
        sync.report(LibrarySync.playlist("pl-1"), LibrarySync.entryKeys(list).zip(list).mapIndexed { i, (k, s) -> k to LibrarySync.entryValue(s, i) }.toMap())
        sync.report(LibrarySync.PROGRESS, mapOf("e:https://feed.example/rss#guid-1" to JSONObject().put("positionMs", 65_000).put("durationMs", 1_800_000).put("played", false).put("_at", now)))
        sync.report(LibrarySync.SETTINGS, mapOf("themeMode" to JSONObject().put("value", "Dark"), "textScale" to JSONObject().put("value", 1.12), "crossfadeMs" to JSONObject().put("value", 6000)))
        sync.add(LibrarySync.HISTORY, "aaaaaaaa:$now:${LibrarySync.trackKey(songs[1])}", LibrarySync.trackValue(songs[1]).put("playedAt", now).put("listenedMs", 180_000).put("durationMs", 200_000).put("skipped", false))
        now += 1_000
        // A removal (tombstone).
        sync.report(LibrarySync.LIKED, songs.drop(1).associate { LibrarySync.trackKey(it) to LibrarySync.trackValue(it).put("_addedAt", 5) })

        val out = JSONObject()
            .put("docs", JSONArray(sync.docNames().sorted().map { sync.doc(it) }))
            .put("digest", JSONObject(sync.digest() as Map<*, *>))
            .put("trackKeys", JSONArray(songs.map { JSONArray(listOf(it.title, it.artist, LibrarySync.trackKey(it))) }))
            .put("shards", JSONArray(songs.flatMap { s -> listOf(LibrarySync.LIKED, LibrarySync.HISTORY, LibrarySync.PLAYLISTS, "stats:" + "b".repeat(64)).map { c -> JSONArray(listOf(c, LibrarySync.trackKey(s), LibrarySync.shardOf(c, LibrarySync.trackKey(s)))) } }))
            .put("meanings", JSONArray(listOf(
                JSONObject().put("pos", 3).put("track", JSONObject()).put("_x", 1),
                JSONObject().put("positionMs", 65_000).put("played", false).put("durationMs", 1_800_000),
                JSONObject().put("name", "Road \"trip\"/2").put("description", ""),
                JSONObject().put("value", 1.12),
            ).map { JSONArray(listOf(it, LibrarySync.meaning(it))) }))
        val text = out.toString(2) + "\n"
        val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "ios/SpitifyTests/Fixtures/library-sync-kotlin.json") }.first { it.parentFile.parentFile.isDirectory }
        if (System.getenv("SPITIFY_WRITE_FIXTURES") == "1") file.writeText(text)
        assertEquals("Fixture is stale: run with SPITIFY_WRITE_FIXTURES=1", text, file.readText().replace("\r\n", "\n")) // Windows checkouts use CRLF
    }
}
