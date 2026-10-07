package com.localfy.app.data.sync

import com.localfy.app.data.music.SearchMatch
import com.localfy.app.data.social.SharedTrack
import com.localfy.app.data.social.SocialRules
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps the library identical on all linked devices, like Spotify: likes, saved music, playlists,
 * followed artists, hidden items, podcasts, listening progress, recently played and play counts.
 * Audio files never move; a song is identified by what it is ([trackKey]) and each device plays
 * its own copy, a download, or the stream. Wire format and rules: docs/library-sync.md.
 *
 * Every collection is a set of items, each with a [Stamp]; the newest stamp wins, removals are kept
 * as tombstones so they can't come back, and merging is order-independent. Platforms don't hook every
 * button: they [report] what a collection holds now and [applied] what they did with remote changes.
 */
data class Stamp(val at: Long, val device: String) : Comparable<Stamp> {
    override fun compareTo(other: Stamp) = compareValuesBy(this, other, Stamp::at, Stamp::device)
}

data class SyncItem(val key: String, val present: Boolean, val value: JSONObject?, val stamp: Stamp) {
    /** What counts as a change: everything but the song details, which differ between a file's tags and the catalogue. */
    val meaning: String? get() = if (present) LibrarySync.meaning(value) else null
    fun json(): JSONObject = JSONObject().put("k", key).put("p", present).put("t", stamp.at).put("d", stamp.device).also { if (present && value != null) it.put("v", value) }
    companion object {
        fun parse(o: JSONObject) = SyncItem(o.getString("k"), o.getBoolean("p"), o.optJSONObject("v"), Stamp(o.getLong("t"), o.getString("d")))
    }
}

/** A remote change the platform should make locally. [value] is null for removals. */
data class SyncChange(val collection: String, val key: String, val present: Boolean, val value: JSONObject?)

class LibrarySync(val me: String, private val clock: () -> Long = { SocialRules.now }) {
    /** collection → key → winning item. */
    private val items = HashMap<String, HashMap<String, SyncItem>>()
    /** collection → key → value text the platform has locally, as last reported or applied. */
    private val local = HashMap<String, HashMap<String, String>>()
    private var lastStamp = 0L

    private fun stamp(): Stamp { lastStamp = maxOf(clock(), lastStamp + 1); return Stamp(lastStamp, me) }
    private fun coll(name: String) = items.getOrPut(name) { HashMap() }
    private fun localOf(name: String) = local.getOrPut(name) { HashMap() }

    /**
     * What [collection] holds on this device now (key → value). New or changed items are stamped as
     * ours; items that were here last time and are gone now become removals. Returns the names of the
     * documents that changed (to send). A sudden loss of most of a big collection is treated as the
     * collection not being loaded yet and ignored, unless [allowMassRemoval].
     */
    fun report(collection: String, current: Map<String, JSONObject>, allowMassRemoval: Boolean = false): Set<String> {
        require(!isGrowOnly(collection)) { "Use add() for $collection" }
        val before = localOf(collection)
        val gone = before.keys - current.keys
        if (!allowMassRemoval && gone.size >= MASS_REMOVAL_MIN && gone.size * 2 > before.size) return emptySet()
        val items = coll(collection)
        val changed = HashSet<String>()
        current.forEach { (key, value) ->
            val text = meaning(value)
            if (before[key] == text) return@forEach
            val winner = items[key]
            if (winner == null || !winner.present || winner.meaning != text) {
                items[key] = SyncItem(key, true, value, stamp()); changed += docName(collection, key)
            }
            before[key] = text
        }
        gone.forEach { key ->
            before.remove(key)
            val winner = items[key]
            if (winner != null && winner.present) { items[key] = SyncItem(key, false, null, stamp()); changed += docName(collection, key) }
        }
        return changed
    }

    /** Adds to a grow-only collection (history): never removed except by age. */
    fun add(collection: String, key: String, value: JSONObject): Set<String> {
        require(isGrowOnly(collection))
        val items = coll(collection)
        if (items.containsKey(key)) return emptySet()
        items[key] = SyncItem(key, true, value, stamp()); localOf(collection)[key] = meaning(value)
        return setOf(docName(collection, key))
    }

    /**
     * Merges a document from another device. Returns the changes to make locally: items whose winner
     * changed and differs from what this device has. Call [applied] for each one actually made.
     */
    fun receive(doc: JSONObject): List<SyncChange> {
        val collection = doc.getString("collection")
        if (!validCollection(collection)) return emptyList()
        val items = coll(collection)
        val have = localOf(collection)
        val out = ArrayList<SyncChange>()
        val arr = doc.optJSONArray("items") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val incoming = runCatching { SyncItem.parse(arr.getJSONObject(i)) }.getOrNull() ?: continue
            if (incoming.key.length > 400 || isGrowOnly(collection) && !incoming.present) continue
            val current = items[incoming.key]
            if (current != null && current.stamp >= incoming.stamp) continue
            items[incoming.key] = incoming
            val localText = have[incoming.key]
            if (incoming.present && localText != incoming.meaning) out += SyncChange(collection, incoming.key, true, incoming.value)
            else if (!incoming.present && localText != null) out += SyncChange(collection, incoming.key, false, null)
        }
        return out
    }

    /** The platform made [change] locally (or found it already true). */
    fun applied(change: SyncChange) {
        val have = localOf(change.collection)
        if (change.present) have[change.key] = meaning(change.value) else have.remove(change.key)
    }

    /** Remote items this device doesn't have yet (e.g. a song that couldn't be matched); retry later. */
    fun pending(collection: String): List<SyncChange> {
        val all = items[collection] ?: return emptyList()
        val have = local[collection].orEmpty()
        return all.values.filter { it.present && have[it.key] != it.meaning }.map { SyncChange(collection, it.key, true, it.value) }
    }

    /** True when [key] was removed (a tombstone), as opposed to never seen. */
    fun isRemoved(collection: String, key: String): Boolean = items[collection]?.get(key)?.present == false

    fun present(collection: String): Map<String, JSONObject> = (items[collection] ?: return emptyMap()).values.filter { it.present && it.value != null }.associate { it.key to it.value!! }

    fun collections(): Set<String> = items.keys.toSet()

    /** Drops a whole collection (a deleted playlist's entries, a removed device's counts). */
    fun forget(collection: String) { items.remove(collection); local.remove(collection) }

    /** Grow-only collections older than [HISTORY_DAYS] are trimmed by age. */
    fun trim(now: Long = clock()) {
        val cutoff = now - HISTORY_DAYS * 24 * 60 * 60_000L
        items.filterKeys(::isGrowOnly).forEach { (name, map) ->
            val old = map.values.filter { (it.value?.optLong("playedAt", it.stamp.at) ?: it.stamp.at) < cutoff }.map { it.key }
            old.forEach { map.remove(it); local[name]?.remove(it) }
        }
    }

    // ---- documents: what travels. One collection is split into a few shards so a change resends little.

    fun docNames(): Set<String> = items.flatMap { (c, m) -> m.keys.map { docName(c, it) } }.toSet()

    fun doc(name: String): JSONObject {
        val (collection, shard) = splitDoc(name)
        val list = coll(collection).values.filter { shardOf(collection, it.key) == shard }.sortedBy { it.key }
        return JSONObject().put("collection", collection).put("shard", shard).put("items", JSONArray(list.map { it.json() }))
    }

    /**
     * A short fingerprint per document, so devices only send what differs. Built from keys and stamps
     * only (a stamp marks each version), so it's the same text on every platform.
     */
    fun digest(): Map<String, String> {
        val lines = HashMap<String, MutableList<String>>()
        items.forEach { (c, m) -> m.values.forEach { lines.getOrPut(docName(c, it.key)) { mutableListOf() } += "${it.key}|${it.present}|${it.stamp.at}|${it.stamp.device}" } }
        return lines.mapValues { (_, l) -> SocialRules.hash(l.sorted().joinToString("\n").toByteArray()).take(16) }
    }

    // ---- saving

    fun json(): JSONObject = JSONObject().put("me", me).put("lastStamp", lastStamp)
        .put("items", JSONObject().also { o -> items.forEach { (c, m) -> o.put(c, JSONArray(m.values.map { it.json() })) } })
        .put("local", JSONObject().also { o -> local.forEach { (c, m) -> o.put(c, JSONObject(m as Map<*, *>)) } })

    fun load(o: JSONObject) {
        if (o.optString("me") != me) return
        items.clear(); local.clear(); lastStamp = o.optLong("lastStamp")
        o.optJSONObject("items")?.let { all -> all.keys().forEach { c ->
            val arr = all.getJSONArray(c); val map = coll(c)
            for (i in 0 until arr.length()) runCatching { SyncItem.parse(arr.getJSONObject(i)) }.getOrNull()?.let { map[it.key] = it }
        } }
        o.optJSONObject("local")?.let { all -> all.keys().forEach { c ->
            val m = all.getJSONObject(c); val map = localOf(c); m.keys().forEach { k -> map[k] = m.getString(k) }
        } }
    }

    companion object {
        const val LIKED = "liked"
        const val SAVED_TRACKS = "savedTracks"
        const val SAVED_ALBUMS = "savedAlbums"
        const val FOLLOWED_ARTISTS = "followedArtists"
        const val HIDDEN_SONGS = "hiddenSongs"
        const val HIDDEN_ARTISTS = "hiddenArtists"
        const val HIDDEN_MIXES = "hiddenMixes"
        const val PODCASTS = "podcasts"
        const val PROGRESS = "progress"
        const val PLAYLISTS = "playlists"
        const val HISTORY = "history"
        const val PROFILE = "profile"
        const val FRIENDS = "friends"
        const val SAVED_SHARED = "savedShared"
        const val SETTINGS = "settings"
        /** One per playlist: "playlist:<global id>". */
        fun playlist(id: String) = "playlist:$id"
        /** One per device, written only by that device: "stats:<device key>". */
        fun stats(device: String) = "stats:$device"

        const val MASS_REMOVAL_MIN = 20
        const val HISTORY_DAYS = 180

        private val FIXED = setOf(LIKED, SAVED_TRACKS, SAVED_ALBUMS, FOLLOWED_ARTISTS, HIDDEN_SONGS, HIDDEN_ARTISTS, HIDDEN_MIXES, PODCASTS, PROGRESS, PLAYLISTS, HISTORY, PROFILE, FRIENDS, SAVED_SHARED, SETTINGS)

        /** Settings that follow you between devices. Everything else (EQ, folders, device name, window) stays per device. */
        val SYNCED_SETTINGS = setOf(
            "themeMode", "accent", "accentFromArt", "font", "textScale", "artShape", "playerStyle", "artworkTint", "blurBackdrop", "reduceMotion",
            "showRecommendations", "hideShortTracks", "autoplay", "crossfadeMs", "crossfadeKeepAlbums", "normalizeAudio", "skipSilence",
            "speedMusic", "speedPodcast", "onlineLyrics", "onlineArt", "autoFixMetadata", "releaseNotifications",
        )
        fun validCollection(name: String) = name in FIXED ||
            name.startsWith("playlist:") && name.length in 10..80 ||
            name.startsWith("stats:") && SocialRules.key(name.removePrefix("stats:"))
        fun isGrowOnly(name: String) = name == HISTORY

        private fun shards(collection: String) = when {
            collection == HISTORY -> 16
            collection == LIKED || collection == PROGRESS || collection.startsWith("stats:") -> 8
            collection == SAVED_TRACKS || collection.startsWith("playlist:") -> 4
            collection == PLAYLISTS -> 8 // covers ride along: up to 24 KB each
            collection == SAVED_ALBUMS || collection == PODCASTS -> 2
            else -> 1
        }
        fun shardOf(collection: String, key: String): Int {
            val n = shards(collection); if (n == 1) return 0
            val h = SocialRules.hash(key.toByteArray())
            return h.substring(0, 4).toInt(16) % n
        }
        fun docName(collection: String, key: String) = "$collection#${shardOf(collection, key)}"
        fun splitDoc(name: String): Pair<String, Int> = name.substringBeforeLast('#') to (name.substringAfterLast('#').toIntOrNull() ?: 0)

        /**
         * What a song *is*, the same on every device whether it's a local file, a download or a stream:
         * folded title without "(feat. …)", and the folded main artist. Title tags like "- Remastered"
         * stay, so different versions stay different.
         */
        fun trackKey(title: String, artist: String): String {
            val base = title.replace(Regex("\\s*[(\\[](feat\\.?|ft\\.?|featuring|with)\\s[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), "")
            // The first credited name, cut the same way everywhere: tags say "Band, Guest" or "Band & Guest"
            // where the catalogue says "Band". Bands with "&" or "," in their name are cut alike on every device.
            val first = artist.split(Regex("\\s*(,|&|;|/|\\bfeat\\.?|\\bft\\.?|\\bfeaturing\\b|\\bwith\\b|\\bx\\b|\\band\\b|\\bvs\\.?)\\s*", RegexOption.IGNORE_CASE)).firstOrNull { it.isNotBlank() } ?: artist
            return SearchMatch.fold(base) + "|" + SearchMatch.fold(first)
        }
        fun trackKey(track: SharedTrack) = trackKey(track.title, track.artist)

        /** The standard value for a song item: {"track": SharedTrack}. */
        fun trackValue(track: SharedTrack): JSONObject = JSONObject().put("track", track.copy(id = trackKey(track)).json())
        fun track(value: JSONObject?): SharedTrack? = value?.optJSONObject("track")?.let { runCatching { SharedTrack.parse(it) }.getOrNull() }?.takeIf { it.valid() }

        /**
         * Entry keys inside a playlist: the song's key plus which occurrence it is ("…#2" for the second
         * time the same song appears), so devices agree without sharing database ids. Value: {"track", "pos"}.
         */
        fun entryKeys(tracks: List<SharedTrack>): List<String> {
            val seen = HashMap<String, Int>()
            return tracks.map { t -> val k = trackKey(t); val n = (seen[k] ?: 0) + 1; seen[k] = n; "$k#$n" }
        }
        fun entryValue(track: SharedTrack, pos: Int): JSONObject = trackValue(track).put("pos", pos)

        /**
         * Canonical text of a value without "track" and "_…" (informational) fields: sorted keys, so it's identical on every platform
         * and every run (org.json and Swift order keys differently). Null-valued keys are dropped.
         */
        /**
         * Canonical string quoting, the same on every platform whatever its JSON library (Android's org.json
         * escapes "/", the desktop's doesn't): `"` and `\` escaped, control characters as \b \t \n \f \r or \u00xx.
         */
        fun quote(text: String): String = buildString {
            append('"')
            for (ch in text) when {
                ch == '"' || ch == '\\' -> append('\\').append(ch)
                ch == '\t' -> append("\\t"); ch == '\b' -> append("\\b"); ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r"); ch == '\u000C' -> append("\\f")
                ch.code <= 0x1F -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
            append('"')
        }

        fun meaning(value: JSONObject?): String {
            if (value == null) return "{}"
            fun canon(v: Any?): String = when (v) {
                null, JSONObject.NULL -> "null"
                is JSONObject -> v.keySet().filter { !v.isNull(it) }.sorted().joinToString(",", "{", "}") { quote(it) + ":" + canon(v.get(it)) }
                is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.get(it)) }
                is String -> quote(v)
                is Number -> if (v.toDouble() == Math.floor(v.toDouble()) && !v.toDouble().isInfinite()) v.toLong().toString() else v.toString()
                else -> v.toString()
            }
            val o = JSONObject(value.toString())
            o.keySet().toList().filter { it == "track" || it.startsWith("_") }.forEach(o::remove)
            return canon(o)
        }
    }
}
