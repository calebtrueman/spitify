package com.localfy.app.data.podcast

import com.localfy.app.data.music.HttpGet
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

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
    /**
     * GET following redirects across protocols (podcast hosts and analytics prefixes bounce between http
     * and https); plain http is tried as https first. Returns null on a non-200 answer; network errors throw.
     */
    fun <T> open(url: String, block: (InputStream) -> T): T? {
        val conn = HttpGet.open(url, mapOf("User-Agent" to "Spitify/1.0 (Desktop podcast player)"), upgrade = true, connectTimeout = 8_000, readTimeout = 15_000)
        try {
            if (conn.responseCode != 200) return null
            return conn.inputStream.use(block)
        } finally { conn.disconnect() }
    }
}

/** RSS 2.0 + iTunes namespace parser (and enough Atom), tolerant of the many ways feeds get things wrong. */
object FeedParser {
    private const val MAX_EPISODES = 300

    private val factory: XMLInputFactory by lazy {
        XMLInputFactory.newFactory().apply {
            // Element names are matched with their prefixes ("itunes:image"), like the phone's pull parser.
            setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false)
            setProperty(XMLInputFactory.IS_COALESCING, true)
            // Feeds are untrusted: no DTDs, no external entities.
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }
    }

    private fun XMLStreamReader.qname(): String {
        val prefix = prefix
        return if (!prefix.isNullOrEmpty()) "$prefix:$localName" else localName
    }

    private fun XMLStreamReader.attr(name: String): String? {
        for (i in 0 until attributeCount) {
            val prefix = getAttributePrefix(i)
            val local = getAttributeLocalName(i)
            if (local == name || (!prefix.isNullOrEmpty() && "$prefix:$local" == name)) return getAttributeValue(i)
        }
        return null
    }

    /**
     * Parses a feed. A feed that breaks part-way (bad entity, truncated download) keeps what was read
     * before the error, as long as that includes a title or an episode; otherwise it throws IOException.
     */
    fun parse(input: InputStream): ParsedFeed {
        var title = ""; var author = ""; var description = ""; var art: String? = null
        val episodes = ArrayList<ParsedEpisode>()
        var inItem = false
        var inImage = false
        var e = MutableEpisode()
        var text = StringBuilder()
        val p = try { factory.createXMLStreamReader(input) } catch (ex: XMLStreamException) { throw IOException("Not a readable feed", ex) }
        try {
            while (p.hasNext()) {
                when (p.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        text = StringBuilder()
                        when (p.qname()) {
                            "item", "entry" -> { inItem = true; e = MutableEpisode() }
                            "image" -> inImage = true
                            "itunes:image" -> p.attr("href")?.let { if (inItem) e.art = it else if (art == null || !inImage) art = it }
                            "media:thumbnail" -> if (inItem && e.art == null) e.art = p.attr("url")
                            "enclosure" -> if (inItem) {
                                e.url = p.attr("url") ?: e.url
                                e.type = p.attr("type") ?: e.type
                            }
                            // Atom: <link rel="enclosure" href="..." type="audio/mpeg"/>
                            "link" -> if (inItem && p.attr("rel") == "enclosure" && e.url == null) {
                                e.url = p.attr("href"); e.type = p.attr("type") ?: e.type
                            }
                        }
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> text.append(p.text)
                    XMLStreamConstants.END_ELEMENT -> {
                        val v = text.toString().trim()
                        val name = p.qname()
                        if (inItem) when (name) {
                            "title" -> if (e.title.isEmpty()) e.title = v
                            "guid", "id" -> e.guid = v
                            "pubDate", "published" -> e.date = parseDate(v)
                            "updated" -> if (e.date == 0L) e.date = parseDate(v)
                            "itunes:duration" -> e.duration = parseDuration(v)
                            "description", "itunes:summary", "summary" -> if (e.description.isEmpty()) e.description = v
                            "content:encoded", "content" -> if (v.length > e.description.length) e.description = v
                            "item", "entry" -> {
                                inItem = false
                                val url = e.url?.trim()?.takeIf { it.isNotEmpty() }
                                if (url != null && episodes.size < MAX_EPISODES) episodes += ParsedEpisode(
                                    guid = e.guid.ifEmpty { url }, title = e.title.ifEmpty { "Untitled episode" },
                                    description = cleanHtml(e.description), audioUrl = url, mimeType = e.type,
                                    pubDate = e.date, durationMs = e.duration, artworkUrl = e.art, position = episodes.size,
                                )
                            }
                        } else when (name) {
                            "title" -> if (!inImage && title.isEmpty()) title = v
                            "itunes:author" -> if (author.isEmpty()) author = v
                            "managingEditor" -> if (author.isEmpty()) author = v
                            "description", "itunes:summary", "subtitle" -> if (description.isEmpty()) description = cleanHtml(v)
                            "url" -> if (inImage && art == null) art = v
                            "image" -> inImage = false
                        }
                        text = StringBuilder()
                    }
                }
            }
        } catch (ex: XMLStreamException) {
            if (title.isEmpty() && episodes.isEmpty()) throw IOException("Not a readable feed", ex)
        } finally { runCatching { p.close() } }
        return ParsedFeed(title.ifEmpty { "Podcast" }, author, description, art, episodes)
    }

    private class MutableEpisode {
        var title = ""; var guid = ""; var description = ""; var url: String? = null; var type: String? = null
        var date = 0L; var duration = 0L; var art: String? = null
    }

    private val entities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
        "copy" to "©", "reg" to "®", "trade" to "™", "bull" to "•", "middot" to "·", "deg" to "°", "eacute" to "é", "egrave" to "è",
        "aacute" to "á", "agrave" to "à", "iacute" to "í", "oacute" to "ó", "uacute" to "ú", "ntilde" to "ñ", "uuml" to "ü",
        "ouml" to "ö", "auml" to "ä", "ccedil" to "ç", "szlig" to "ß", "euro" to "€", "pound" to "£", "times" to "×",
    )

    private val blockTags = Regex("(?i)</?(p|div|h[1-6]|ul|ol|blockquote|section|article|tr|table|pre)\\b[^>]*>")

    /** Plain text from feed HTML: tags dropped, block elements become line breaks, entities decoded. */
    fun cleanHtml(s: String): String {
        if (s.isEmpty()) return s
        var t = s.replace(Regex("(?is)<(script|style)\\b.*?</\\1\\s*>"), "")
            .replace(Regex("(?is)<!--.*?-->"), "")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)<li\\b[^>]*>"), "\n• ")
            .replace(Regex("(?i)</li\\s*>"), "")
            .replace(blockTags, "\n")
            .replace(Regex("<[^>]*>"), "")
        t = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);").replace(t) { m ->
            val key = m.groupValues[1]
            when {
                key.startsWith("#x") || key.startsWith("#X") -> key.substring(2).toIntOrNull(16)?.let(::codePoint) ?: m.value
                key.startsWith("#") -> key.substring(1).toIntOrNull()?.let(::codePoint) ?: m.value
                else -> entities[key] ?: entities[key.lowercase()] ?: m.value
            }
        }
        return t.replace(' ', ' ').lines().joinToString("\n") { it.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ").trim() }
            .replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun codePoint(cp: Int): String? = if (Character.isValidCodePoint(cp) && cp != 0) String(Character.toChars(cp)) else null

    private val dateFormats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, d MMM yyyy HH:mm:ss Z",
        "dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm Z", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'",
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
            ?: throw IOException("Podcast search is unavailable")
        val results = runCatching { org.json.JSONObject(body).optJSONArray("results") }.getOrNull() ?: throw IOException("Podcast search returned an unreadable reply")
        return (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
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
            ?.optJSONObject("response")?.optJSONArray("docs") ?: throw IOException("Book search is unavailable")
        return (0 until docs.length()).mapNotNull { i ->
            val d = docs.optJSONObject(i) ?: return@mapNotNull null
            val id = d.optString("identifier").takeIf { it.isNotBlank() } ?: return@mapNotNull null
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
        if (!Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,199}").matches(identifier)) return null
        val root = json("https://archive.org/metadata/$identifier") ?: return null
        val meta = root.optJSONObject("metadata") ?: return null
        val files = root.optJSONArray("files") ?: return null
        val all = (0 until files.length()).mapNotNull { files.optJSONObject(it) }
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
        val body = runCatching {
            Http.open("https://openlibrary.org/search.json?q=$q&limit=8&fields=title,author_name,first_publish_year,cover_i") { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return emptyList()
        val docs = runCatching { org.json.JSONObject(body).optJSONArray("docs") }.getOrNull() ?: return emptyList()
        return (0 until docs.length()).mapNotNull { i ->
            val d = docs.optJSONObject(i) ?: return@mapNotNull null
            Book(
                d.optString("title"),
                d.optJSONArray("author_name")?.optString(0).orEmpty(),
                d.optInt("first_publish_year").takeIf { it > 0 },
                d.optInt("cover_i").takeIf { it > 0 }?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" },
            )
        }
    }
}
