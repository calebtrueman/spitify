package com.localfy.app.data.podcast

import android.util.Xml
import androidx.core.text.HtmlCompat
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

data class ParsedFeed(
    val title: String,
    val author: String,
    val description: String,
    val artworkUrl: String?,
    val episodes: List<ParsedEpisode>,
)

data class ParsedEpisode(
    val guid: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val mimeType: String?,
    val pubDate: Long,
    val durationMs: Long,
    val artworkUrl: String?,
    val position: Int = 0,
)

data class PodcastSearchResult(val title: String, val author: String, val feedUrl: String, val artworkUrl: String?, val genre: String?, val explicit: Boolean? = null)

internal object Http {
    /** GET with manual redirect handling (HttpURLConnection won't follow http -> https). */
    fun <T> open(url: String, block: (InputStream) -> T): T? {
        var current = url
        repeat(6) {
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", "Spitify/1.0 (Android podcast player)")
                when (conn.responseCode) {
                    in 300..399 -> current = URL(URL(current), conn.getHeaderField("Location") ?: return null).toString()
                    200 -> return conn.inputStream.use(block)
                    else -> return null
                }
            } finally {
                conn.disconnect()
            }
        }
        return null
    }
}

/** RSS 2.0 + iTunes namespace parser, tolerant of the many ways feeds get things wrong. */
object FeedParser {
    private const val MAX_EPISODES = 300

    fun parse(input: InputStream): ParsedFeed {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        var title = ""; var author = ""; var description = ""; var art: String? = null
        val episodes = ArrayList<ParsedEpisode>()
        var inItem = false
        var inImage = false
        var e = MutableEpisode()
        var text = StringBuilder()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (p.name) {
                        "item", "entry" -> { inItem = true; e = MutableEpisode() }
                        "image" -> inImage = true
                        "itunes:image" -> p.getAttributeValue(null, "href")?.let { if (inItem) e.art = it else if (art == null || !inImage) art = it }
                        "media:thumbnail" -> if (inItem && e.art == null) e.art = p.getAttributeValue(null, "url")
                        "enclosure" -> if (inItem) {
                            e.url = p.getAttributeValue(null, "url") ?: e.url
                            e.type = p.getAttributeValue(null, "type") ?: e.type
                        }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> text.append(p.text)
                XmlPullParser.END_TAG -> {
                    val v = text.toString().trim()
                    if (inItem) when (p.name) {
                        "title" -> if (e.title.isEmpty()) e.title = v
                        "guid", "id" -> e.guid = v
                        "pubDate", "published" -> e.date = parseDate(v)
                        "itunes:duration" -> e.duration = parseDuration(v)
                        "description", "itunes:summary" -> if (e.description.isEmpty()) e.description = v
                        "content:encoded" -> if (v.length > e.description.length) e.description = v
                        "item", "entry" -> {
                            inItem = false
                            val url = e.url
                            if (url != null && episodes.size < MAX_EPISODES) episodes += ParsedEpisode(
                                guid = e.guid.ifEmpty { url }, title = e.title.ifEmpty { "Untitled episode" },
                                description = cleanHtml(e.description), audioUrl = url, mimeType = e.type,
                                pubDate = e.date, durationMs = e.duration, artworkUrl = e.art, position = episodes.size,
                            )
                        }
                    } else when (p.name) {
                        "title" -> if (!inImage && title.isEmpty()) title = v
                        "itunes:author" -> if (author.isEmpty()) author = v
                        "managingEditor" -> if (author.isEmpty()) author = v
                        "description", "itunes:summary" -> if (description.isEmpty()) description = cleanHtml(v)
                        "url" -> if (inImage && art == null) art = v
                        "image" -> inImage = false
                    }
                }
            }
        }
        return ParsedFeed(title.ifEmpty { "Podcast" }, author, description, art, episodes)
    }

    private class MutableEpisode {
        var title = ""; var guid = ""; var description = ""; var url: String? = null; var type: String? = null
        var date = 0L; var duration = 0L; var art: String? = null
    }

    fun cleanHtml(s: String): String =
        HtmlCompat.fromHtml(s, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().replace(Regex("\\n{3,}"), "\n\n").trim()

    private val dateFormats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, d MMM yyyy HH:mm:ss Z",
        "dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm Z", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'",
    )

    fun parseDate(s: String): Long {
        for (f in dateFormats) runCatching { return SimpleDateFormat(f, Locale.US).parse(s.trim())!!.time }
        return 0L
    }

    /** "HH:MM:SS", "MM:SS" or plain seconds. */
    fun parseDuration(s: String): Long {
        val parts = s.trim().split(':').map { it.trim().toDoubleOrNull() ?: return 0L }
        val seconds = parts.fold(0.0) { acc, v -> acc * 60 + v }
        return (seconds * 1000).toLong()
    }
}

/** Apple's public podcast directory (no key needed). */
object PodcastDirectory {
    fun search(term: String): List<PodcastSearchResult> {
        val q = java.net.URLEncoder.encode(term, "UTF-8")
        val body = Http.open("https://itunes.apple.com/search?media=podcast&entity=podcast&limit=30&term=$q") { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw java.io.IOException("Podcast search is unavailable")
        val results = org.json.JSONObject(body).optJSONArray("results") ?: throw java.io.IOException("Podcast search returned an unreadable reply")
        return (0 until results.length()).mapNotNull { i ->
            val o = results.getJSONObject(i)
            val feed = o.optString("feedUrl").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            PodcastSearchResult(
                o.optString("collectionName"), o.optString("artistName"), feed,
                o.optString("artworkUrl600").takeIf { it.startsWith("http") } ?: o.optString("artworkUrl100").takeIf { it.startsWith("http") },
                o.optString("primaryGenreName").takeIf { it.isNotBlank() },
                if (o.has("collectionExplicitness")) o.optString("collectionExplicitness") == "explicit" else null,
            )
        }
    }
}

data class BookSearchResult(
    val id: String,
    val title: String,
    val author: String,
    val description: String,
    val totalSeconds: Long,
    val sections: Int,
    val rssUrl: String,
    val coverUrl: String?,
    val language: String,
)

/**
 * LibriVox: 20,000+ free public-domain audiobooks read by volunteers. Discovery goes through the
 * Internet Archive (which hosts every LibriVox recording) because its search is full-text and
 * ranked by popularity; chapters come from the item's file list.
 */
object LibriVox {
    private fun json(url: String) = Http.open(url) { it.readBytes().toString(Charsets.UTF_8) }?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }

    private fun org.json.JSONObject.str(key: String): String = when (val v = opt(key)) {
        is org.json.JSONArray -> (0 until v.length()).joinToString(", ") { v.optString(it) }
        null -> ""
        else -> v.toString()
    }

    fun search(term: String): List<BookSearchResult> {
        val q = java.net.URLEncoder.encode("collection:librivoxaudio AND (${term.trim()})", "UTF-8")
        val fields = listOf("identifier", "title", "creator", "description", "runtime", "language").joinToString("") { "&fl%5B%5D=$it" }
        val docs = json("https://archive.org/advancedsearch.php?q=$q$fields&sort%5B%5D=downloads+desc&rows=30&output=json")
            ?.optJSONObject("response")?.optJSONArray("docs") ?: throw java.io.IOException("Book search is unavailable")
        return (0 until docs.length()).map { i ->
            val d = docs.getJSONObject(i)
            val id = d.optString("identifier")
            BookSearchResult(
                id = id,
                title = d.str("title").removeSuffix(" (LibriVox)"),
                author = d.str("creator").ifBlank { "Various" },
                description = FeedParser.cleanHtml(d.str("description")),
                totalSeconds = FeedParser.parseDuration(d.str("runtime")) / 1000,
                sections = 0,
                rssUrl = "archive:$id",
                coverUrl = "https://archive.org/services/img/$id",
                language = d.str("language").let { if (it == "eng") "English" else it },
            )
        }
    }

    /** Chapters of an Archive item, in order (prefers the 64 kbps LibriVox MP3s). */
    fun chapters(identifier: String): ParsedFeed? {
        val root = json("https://archive.org/metadata/$identifier") ?: return null
        val meta = root.optJSONObject("metadata") ?: return null
        val files = root.optJSONArray("files") ?: return null
        val all = (0 until files.length()).map { files.getJSONObject(it) }
        val mp3s = all.filter { it.optString("name").endsWith("_64kb.mp3") }.ifEmpty {
            all.filter { it.optString("name").endsWith(".mp3", true) && it.optString("source") == "original" }
        }.sortedWith(compareBy<org.json.JSONObject> { it.optString("track").substringBefore('/').toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.optString("name") })
        val episodes = mp3s.mapIndexed { i, f ->
            val name = f.optString("name")
            ParsedEpisode(
                guid = name,
                title = f.optString("title").ifBlank { name.substringBeforeLast('.').replace('_', ' ') },
                description = "",
                audioUrl = "https://archive.org/download/$identifier/" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20"),
                mimeType = "audio/mpeg",
                pubDate = 0L,
                durationMs = FeedParser.parseDuration(f.optString("length")),
                artworkUrl = null,
                position = i,
            )
        }
        return ParsedFeed(meta.str("title"), meta.str("creator"), FeedParser.cleanHtml(meta.str("description")), "https://archive.org/services/img/$identifier", episodes)
    }
}

/** Open Library: free book metadata + covers, used to fix up your own audiobook files. */
object OpenLibrary {
    data class Book(val title: String, val author: String, val year: Int?, val coverUrl: String?)

    fun search(query: String): List<Book> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val body = Http.open("https://openlibrary.org/search.json?q=$q&limit=8&fields=title,author_name,first_publish_year,cover_i") { it.readBytes().toString(Charsets.UTF_8) }
            ?: return emptyList()
        val docs = runCatching { org.json.JSONObject(body).optJSONArray("docs") }.getOrNull() ?: return emptyList()
        return (0 until docs.length()).map { i ->
            val d = docs.getJSONObject(i)
            Book(
                d.optString("title"),
                d.optJSONArray("author_name")?.optString(0).orEmpty(),
                d.optInt("first_publish_year").takeIf { it > 0 },
                d.optInt("cover_i").takeIf { it > 0 }?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" },
            )
        }
    }
}
