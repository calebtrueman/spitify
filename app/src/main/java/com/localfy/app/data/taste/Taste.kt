package com.localfy.app.data.taste

import com.localfy.app.data.CoverStyle
import com.localfy.app.data.Mix
import com.localfy.app.data.MixSection
import com.localfy.app.data.Song
import java.util.Calendar
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/** One listen, as the engine sees it. */
data class Listen(val songId: Long, val at: Long, val listenedMs: Long, val durationMs: Long, val completed: Boolean, val skipped: Boolean)

data class TasteInput(
    val songs: List<Song>,
    val listens: List<Listen>,
    val liked: Set<Long>,
    val seedArtists: Set<String> = emptySet(),
    val hiddenSongs: Set<Long> = emptySet(),
    val hiddenArtists: Set<String> = emptySet(),
    val userName: String? = null,
    val now: Long = System.currentTimeMillis(),
)

enum class Daypart(val label: String, val mood: String) {
    Morning("morning", "fresh"), Afternoon("afternoon", "upbeat"), Evening("evening", "mellow"), Night("late night", "dreamy");

    companion object {
        fun of(hour: Int) = when (hour) { in 5..11 -> Morning; in 12..16 -> Afternoon; in 17..21 -> Evening; else -> Night }
    }
}

/**
 * Everything the app has learned about the listener. Built fresh from the raw listens, so it keeps
 * adapting: recent listening counts more (30-day half-life), finishing a song counts more than
 * hearing half, skips count against, likes count for, and songs heard in the same session pull
 * together (co-listening), which is what makes recommendations feel "connected".
 */
class TasteModel(val input: TasteInput) {
    val byId = input.songs.associateBy { it.id }
    private val day = 24 * 60 * 60 * 1000.0

    /** Long-term affinity per song. */
    val songScore = HashMap<Long, Double>()
    /** Affinity over the last 30 days only ("on repeat"). */
    val recentScore = HashMap<Long, Double>()
    val artistScore = HashMap<String, Double>()
    val genreScore = HashMap<String, Double>()
    val decadeScore = HashMap<Int, Double>()
    /** Genre affinity per time of day (for the daylist). */
    val daypartGenre = HashMap<Daypart, HashMap<String, Double>>()
    val daypartSong = HashMap<Daypart, HashMap<Long, Double>>()
    /** Co-listening strength between songs, and between artists. */
    val co = HashMap<Long, HashMap<Long, Double>>()
    val artistCo = HashMap<String, HashMap<String, Double>>()
    val lastPlayed = HashMap<Long, Long>()
    val completedPlays = HashMap<Long, Int>()

    init { build() }

    private fun build() {
        val now = input.now
        val cal = Calendar.getInstance()
        for (l in input.listens) {
            val s = byId[l.songId] ?: continue
            val ageDays = (now - l.at).coerceAtLeast(0) / day
            val decay = 0.5.pow(ageDays / 30.0)
            val fraction = if (l.durationMs > 0) (l.listenedMs / l.durationMs.toDouble()).coerceIn(0.0, 1.0) else 0.5
            val w = when {
                l.skipped -> -0.6
                l.completed -> 1.0
                else -> 0.2 + 0.8 * fraction
            }
            songScore.merge(s.id, w * decay, Double::plus)
            if (ageDays <= 30) recentScore.merge(s.id, w, Double::plus)
            if (!l.skipped) {
                lastPlayed.merge(s.id, l.at, ::maxOf)
                if (l.completed) completedPlays.merge(s.id, 1, Int::plus)
                cal.timeInMillis = l.at
                val part = Daypart.of(cal.get(Calendar.HOUR_OF_DAY))
                s.genreKey()?.let { daypartGenre.getOrPut(part) { HashMap() }.merge(it, w * decay, Double::plus) }
                daypartSong.getOrPut(part) { HashMap() }.merge(s.id, w * decay, Double::plus)
            }
        }
        for (id in input.liked) if (id in byId) songScore.merge(id, 2.5, Double::plus)
        // Cold start: artists picked during setup count as a few listens each.
        if (input.seedArtists.isNotEmpty()) input.songs.filter { it.artist in input.seedArtists }.forEach { songScore.merge(it.id, 0.8, Double::plus) }

        for ((id, sc) in songScore) {
            val s = byId[id] ?: continue
            artistScore.merge(s.artist, sc, Double::plus)
            s.genreKey()?.let { genreScore.merge(it, sc, Double::plus) }
            s.decade()?.let { decadeScore.merge(it, sc, Double::plus) }
        }
        // Many songs by one artist shouldn't drown everyone else out.
        artistScore.replaceAll { a, v -> v / sqrt(input.songs.count { it.artist == a }.coerceAtLeast(1).toDouble()).coerceAtLeast(1.0) * 1.5 }

        // Sessions: listens less than 30 minutes apart; nearby positive listens are related.
        val positive = input.listens.filter { !it.skipped && it.songId in byId }.sortedBy { it.at }
        var session = ArrayList<Listen>()
        fun flush() {
            for (i in session.indices) for (j in i + 1 until minOf(session.size, i + 6)) {
                val a = session[i].songId; val b = session[j].songId
                if (a == b) continue
                val w = 1.0 / (j - i)
                co.getOrPut(a) { HashMap() }.merge(b, w, Double::plus)
                co.getOrPut(b) { HashMap() }.merge(a, w, Double::plus)
                val aa = byId[a]!!.artist; val ba = byId[b]!!.artist
                if (aa != ba) {
                    artistCo.getOrPut(aa) { HashMap() }.merge(ba, w, Double::plus)
                    artistCo.getOrPut(ba) { HashMap() }.merge(aa, w, Double::plus)
                }
            }
            session = ArrayList()
        }
        for (l in positive) {
            if (session.isNotEmpty() && l.at - session.last().at > 30 * 60_000) flush()
            session += l
        }
        flush()
    }

    private val maxSong by lazy { songScore.values.maxOrNull()?.coerceAtLeast(1e-6) ?: 1.0 }
    private val maxArtist by lazy { artistScore.values.maxOrNull()?.coerceAtLeast(1e-6) ?: 1.0 }
    private val maxGenre by lazy { genreScore.values.maxOrNull()?.coerceAtLeast(1e-6) ?: 1.0 }
    private val maxDecade by lazy { decadeScore.values.maxOrNull()?.coerceAtLeast(1e-6) ?: 1.0 }

    fun normSong(id: Long) = ((songScore[id] ?: 0.0) / maxSong).coerceIn(-1.0, 1.0)
    fun normArtist(a: String) = ((artistScore[a] ?: 0.0) / maxArtist).coerceIn(-1.0, 1.0)
    fun normGenre(g: String?) = g?.let { ((genreScore[it] ?: 0.0) / maxGenre).coerceIn(-1.0, 1.0) } ?: 0.0
    fun normDecade(d: Int?) = d?.let { ((decadeScore[it] ?: 0.0) / maxDecade).coerceIn(-1.0, 1.0) } ?: 0.0

    /** How alike two songs are (0..~1.5): shared artist/album/genre/era plus learned co-listening. */
    fun similarity(a: Song, b: Song): Double {
        var s = 0.0
        if (a.artist == b.artist) s += 0.45
        if (a.albumId == b.albumId) s += 0.2
        val ga = a.genreKey(); val gb = b.genreKey()
        if (ga != null && ga == gb) s += 0.25 else if (ga != null && gb != null && ga.split(' ').any { it.length > 2 && it in gb }) s += 0.12
        if (a.decade() != null && a.decade() == b.decade()) s += 0.08
        val c = co[a.id]?.get(b.id) ?: 0.0
        s += 0.6 * c / (c + 1)
        val ac = artistCo[a.artist]?.get(b.artist) ?: 0.0
        s += 0.3 * ac / (ac + 1)
        return s
    }

    fun artistSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val ga = input.songs.filter { it.artist == a }.mapNotNull { it.genreKey() }.toSet()
        val gb = input.songs.filter { it.artist == b }.mapNotNull { it.genreKey() }.toSet()
        val jaccard = if (ga.isEmpty() || gb.isEmpty()) 0.0 else ga.intersect(gb).size / ga.union(gb).size.toDouble()
        val c = artistCo[a]?.get(b) ?: 0.0
        return 0.6 * jaccard + 0.6 * c / (c + 1)
    }

    private val favourites by lazy { songScore.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(25).mapNotNull { byId[it.key] } }

    /** Predicted enjoyment for a song you may not have heard: taste for its artist/genre/era + resemblance to favourites. */
    fun predicted(s: Song): Double {
        val resemblance = favourites.maxOfOrNull { f -> similarity(s, f) * normSong(f.id).coerceAtLeast(0.0) } ?: 0.0
        return 0.5 * normArtist(s.artist) + 0.3 * normGenre(s.genreKey()) + 0.1 * normDecade(s.decade()) + 0.6 * resemblance
    }

    fun hidden(s: Song) = s.id in input.hiddenSongs || s.artist in input.hiddenArtists

    /** Top artists by affinity; falls back to library size when there's no history yet. */
    fun topArtists(): List<String> {
        val ranked = artistScore.entries.filter { it.value > 0 && it.key !in input.hiddenArtists }.sortedByDescending { it.value }.map { it.key }
        val rest = input.songs.groupBy { it.artist }.entries.sortedByDescending { it.value.size }.map { it.key }.filter { it !in ranked && it !in input.hiddenArtists }
        return (ranked + rest).filterNot { it.startsWith("Unknown", true) }
    }
}

fun Song.genreKey(): String? = genre?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "podcast" && it != "audiobook" }
fun Song.decade(): Int? = year.takeIf { it in 1900..2100 }?.let { it / 10 * 10 }

/** Builds every generated playlist from a [TasteModel]. Each has its own refresh period, like Spotify's. */
object PlaylistGenerator {
    private val moods = mapOf(
        "Chill" to listOf("chill", "lofi", "lo-fi", "ambient", "acoustic", "folk", "jazz", "soul", "downtempo", "bossa", "classical", "indie folk"),
        "Energy" to listOf("rock", "metal", "punk", "edm", "electronic", "dance", "house", "techno", "drum", "hip-hop", "hip hop", "rap", "trap", "synthwave", "pop"),
        "Focus" to listOf("classical", "ambient", "instrumental", "soundtrack", "score", "piano", "post-rock", "lofi", "minimal"),
    )

    fun generate(model: TasteModel): List<Mix> {
        val input = model.input
        val songs = input.songs.filter { it.playable && !model.hidden(it) }
        if (songs.isEmpty()) return emptyList()
        val cal = Calendar.getInstance().apply { timeInMillis = input.now }
        val daySeed = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        val weekSeed = cal.get(Calendar.YEAR) * 100 + cal.get(Calendar.WEEK_OF_YEAR)
        val part = Daypart.of(cal.get(Calendar.HOUR_OF_DAY))
        val out = ArrayList<Mix>()
        val forName = input.userName?.takeIf { it.isNotBlank() }

        val palette = listOf(0xFF1ED760, 0xFFE8115B, 0xFF509BF5, 0xFFF59B23, 0xFFAF2896, 0xFF27856A)

        // ---- Discover Weekly: songs you own but haven't really heard, that fit your taste. Mondays.
        val rndW = Random(weekSeed)
        val unheard = songs.filter { (model.completedPlays[it.id] ?: 0) == 0 && it.id !in input.liked }
        val discover = diversify(unheard.sortedByDescending { model.predicted(it) + rndW.nextDouble() * 0.15 }, maxPerArtist = 2, maxPerAlbum = 2).take(30)
        if (discover.size >= 5) out += Mix(
            "discover", "Discover Weekly", "Your weekly mixtape of songs from your library you've never really listened to. Enjoy new music made for you${forName?.let { ", $it" } ?: ""}.",
            discover, MixSection.MadeForYou, CoverStyle.Bold, 0xFF2D46B9,
            why = "Picked because they sound like what you love — refreshed every Monday.", refresh = "New every Monday",
        )

        // ---- Release Radar: newest additions to your library, your favourite artists first. Fridays.
        val newest = songs.filter { input.now / 1000 - it.dateAddedSec < 30L * 86_400 }.ifEmpty { songs.sortedByDescending { it.dateAddedSec }.take(40) }
        val radar = newest.sortedByDescending { 0.6 * model.normArtist(it.artist) + 0.4 * (it.dateAddedSec / (input.now / 1000.0)) }.take(30)
        if (radar.size >= 4) out += Mix(
            "radar", "Release Radar", "Catch all the latest music added to your device, with the artists you play most up front.",
            radar, MixSection.MadeForYou, CoverStyle.Bold, 0xFF8D67AB, refresh = "New every Friday",
        )

        // ---- daylist: changes with the time of day, named after how you listen right now.
        val partGenres = (model.daypartGenre[part] ?: model.genreScore).entries.sortedByDescending { it.value }.map { it.key }.take(2)
            .ifEmpty { model.genreScore.entries.sortedByDescending { it.value }.map { it.key }.take(2) }
        val weekday = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, java.util.Locale.getDefault())?.lowercase() ?: ""
        val partSongs = model.daypartSong[part].orEmpty()
        val rndD = Random(daySeed * 10 + part.ordinal)
        val daylist = songs.filter { partGenres.isEmpty() || it.genreKey() in partGenres }
            .sortedByDescending { (partSongs[it.id] ?: 0.0) * 0.8 + model.predicted(it) * 0.5 + rndD.nextDouble() * 0.3 }
            .let { diversify(it, maxPerArtist = 4) }.take(40)
        if (daylist.size >= 5) out += Mix(
            "daylist", "daylist • ${part.mood} ${partGenres.joinToString(" ")} $weekday ${part.label}".replace(Regex("\\s+"), " ").trim(),
            "Your day in music, updated through the day based on what you play at this hour.",
            daylist, MixSection.MadeForYou, CoverStyle.Bold, when (part) { Daypart.Morning -> 0xFFFFB86B; Daypart.Afternoon -> 0xFFFF6B9D; Daypart.Evening -> 0xFF7A5CFF; Daypart.Night -> 0xFF2B2D6E },
            why = "You tend to play ${partGenres.joinToString(" & ").ifEmpty { "this" }} around ${part.label}s.", refresh = "Changes through the day",
        )

        // ---- On Repeat / Repeat Rewind
        val onRepeat = model.recentScore.entries.filter { it.value > 0.5 }.sortedByDescending { it.value }.mapNotNull { model.byId[it.key] }.filter { !model.hidden(it) }.take(30)
        if (onRepeat.size >= 5) out += Mix("onrepeat", "On Repeat", "Songs you can't stop playing right now.", onRepeat, MixSection.Throwbacks, CoverStyle.Bold, 0xFFE91429, refresh = "Updated daily")
        val sixtyDays = 60L * 86_400_000
        val rewind = model.songScore.entries.filter { it.value > 1.0 && input.now - (model.lastPlayed[it.key] ?: 0) > sixtyDays }
            .sortedByDescending { it.value }.mapNotNull { model.byId[it.key] }.filter { !model.hidden(it) }.take(30)
        if (rewind.size >= 5) out += Mix("rewind", "Repeat Rewind", "Past favourites you haven't played in a while.", rewind, MixSection.Throwbacks, CoverStyle.Bold, 0xFF148A08)

        // ---- This Is <artist> + <artist> Radio for your top artists.
        model.topArtists().take(3).forEachIndexed { i, artist ->
            val theirs = songs.filter { it.artist == artist }
            if (theirs.size >= 5) out += Mix(
                "thisis:$artist", "This Is $artist", "The essential tracks, ranked by how much you play them.",
                theirs.sortedByDescending { model.normSong(it.id) * 2 + model.predicted(it) }, MixSection.YourMixes, CoverStyle.Collage, palette[(i + 2) % palette.size],
            )
            if (i < 2) {
                val radio = artistRadio(model, artist, songs)
                if (radio.size >= 8) out += Mix(
                    "radio:$artist", "$artist Radio", "$artist and the artists you play alongside them.",
                    radio, MixSection.YourMixes, CoverStyle.Collage, palette[(i + 4) % palette.size],
                    why = "Artists you listen to in the same sessions as $artist, plus similar sounds.",
                )
            }
        }

        // ---- Mood mixes (from genre tags).
        moods.entries.forEachIndexed { i, (name, keys) ->
            val rnd = Random(daySeed + i * 7)
            val hits = songs.filter { s -> s.genreKey()?.let { g -> keys.any { g.contains(it) } } == true }
            if (hits.size >= 8) out += Mix(
                "mood:$name", "$name Mix", when (name) { "Chill" -> "Kick back to soft, easy sounds."; "Energy" -> "Turn it up."; else -> "Music to get things done." },
                diversify(hits.sortedByDescending { model.predicted(it) + rnd.nextDouble() * 0.4 }, maxPerArtist = 4).take(40),
                MixSection.YourMixes, CoverStyle.Bold, when (name) { "Chill" -> 0xFF477D95; "Energy" -> 0xFFDC148C; else -> 0xFF537AA1 }, refresh = "Updated daily",
            )
        }

        // ---- Genre mixes and decade "Time capsules".
        model.genreScore.entries.sortedByDescending { it.value }.map { it.key }.ifEmpty { songs.mapNotNull { it.genreKey() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
            .take(4).forEachIndexed { i, g ->
                val hits = songs.filter { it.genreKey() == g }
                if (hits.size >= 5) out += Mix(
                    "genre:$g", "${g.replaceFirstChar { it.uppercase() }} Mix", "The best ${g.replaceFirstChar { it.uppercase() }} in your library.",
                    diversify(hits.sortedByDescending { model.predicted(it) + Random(daySeed + i).nextDouble() * 0.3 }, maxPerArtist = 4).take(50),
                    MixSection.YourMixes, CoverStyle.Collage, palette[(i + 1) % palette.size],
                )
            }
        val decades = songs.mapNotNull { it.decade() }.groupingBy { it }.eachCount().filter { it.value >= 10 }
        if (decades.size >= 2) decades.keys.sortedDescending().forEach { d ->
            out += Mix(
                "decade:$d", "Your ${d % 100}s".let { if (d >= 2000) "Your ${d}s" else it }, "The songs from the ${d}s you love most.",
                songs.filter { it.decade() == d }.sortedByDescending { model.normSong(it.id) + model.predicted(it) * 0.5 }.take(50),
                MixSection.Throwbacks, CoverStyle.Bold, 0xFFBA5D07,
            )
        }

        // ---- Your Top Songs <year>
        val year = cal.get(Calendar.YEAR)
        val yearStart = Calendar.getInstance().apply { set(year, Calendar.JANUARY, 1, 0, 0, 0) }.timeInMillis
        val counts = input.listens.filter { it.at >= yearStart && !it.skipped }.groupingBy { it.songId }.eachCount()
        val top = counts.entries.sortedByDescending { it.value }.mapNotNull { model.byId[it.key] }.filter { !model.hidden(it) }.take(50)
        if (top.size >= 10) out += Mix("top:$year", "Your Top Songs $year", "The songs you loved most this year, all wrapped up.", top, MixSection.Throwbacks, CoverStyle.Bold, 0xFF1E3264)

        return out
    }

    /** Song radio: the seed, then the songs most like it (co-listened, same vibe), varied by artist. */
    fun songRadio(model: TasteModel, seed: Song, size: Int = 50): List<Song> {
        val pool = model.input.songs.filter { it.playable && it.id != seed.id && !model.hidden(it) }
        val ranked = pool.sortedByDescending { model.similarity(seed, it) + 0.2 * model.predicted(it) + Random(seed.id).nextDouble() * 0.05 }
        return listOf(seed) + diversify(ranked, maxPerArtist = 6).take(size - 1)
    }

    fun artistRadio(model: TasteModel, artist: String, songs: List<Song> = model.input.songs.filter { it.playable }): List<Song> {
        val related = model.topArtists().filter { it != artist }.sortedByDescending { model.artistSimilarity(artist, it) }.take(6)
        val pool = songs.filter { it.artist == artist || it.artist in related }
        return interleave(
            songs.filter { it.artist == artist }.sortedByDescending { model.normSong(it.id) + model.predicted(it) }.take(20),
            pool.filter { it.artist != artist }.sortedByDescending { model.predicted(it) + model.artistSimilarity(artist, it.artist) }.take(30),
        )
    }

    private fun interleave(a: List<Song>, b: List<Song>): List<Song> {
        val out = ArrayList<Song>(a.size + b.size)
        var i = 0; var j = 0
        while (i < a.size || j < b.size) {
            repeat(2) { if (i < a.size) out += a[i++] }
            if (j < b.size) out += b[j++]
        }
        return out.distinctBy { it.id }
    }

    /** Limits repeats per artist/album and avoids the same artist back-to-back. */
    private fun diversify(list: List<Song>, maxPerArtist: Int = 3, maxPerAlbum: Int = Int.MAX_VALUE): List<Song> {
        val perArtist = HashMap<String, Int>(); val perAlbum = HashMap<Long, Int>()
        val kept = list.filter { s ->
            val a = perArtist.merge(s.artist, 1, Int::plus)!!; val b = perAlbum.merge(s.albumId, 1, Int::plus)!!
            a <= maxPerArtist && b <= maxPerAlbum
        }.toMutableList()
        for (i in 1 until kept.size) if (kept[i].artist == kept[i - 1].artist) {
            val swap = (i + 1 until kept.size).firstOrNull { kept[it].artist != kept[i - 1].artist } ?: continue
            val t = kept[i]; kept[i] = kept[swap]; kept[swap] = t
        }
        return kept
    }

}
