package com.localfy.app.data.music

import kotlinx.coroutines.*
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class MusicVideo(val id: String, val title: String, val durationSeconds: Long)

object MusicVideoLookup {
    fun matches(title: String, artist: String, durationMs: Long, videoTitle: String, channel: String, videoDurationMs: Long): Boolean {
        val wanted = SearchMatch.fold(title.replace(Regex("""(?i)\s*[\(\[].*?(remaster|version|edition|feat\.|ft\.).*?[\)\]]"""), "")); val found = SearchMatch.fold(videoTitle)
        val creator = SearchMatch.fold(artist.split(';', ',').first())
        if (wanted.isEmpty() || creator.isEmpty() || videoDurationMs <= 0 || (durationMs > 0 && (videoDurationMs < durationMs / 2 || videoDurationMs > maxOf(durationMs * 3, durationMs + 600_000)))) return false
        val words = found.split(' ').toSet(); val wantedWords = wanted.split(' ').toSet()
        val otherVersions = setOf("live", "cover", "remix", "karaoke", "instrumental", "slowed", "sped", "reaction", "lyrics", "lyric", "audio", "visualizer")
        if ((otherVersions.intersect(words) - wantedWords).isNotEmpty()) return false
        val channelName = SearchMatch.fold(channel).replace(" ", "")
        val artistName = creator.replace(" ", "")
        return words.containsAll(wantedWords) && (words.containsAll(creator.split(' ')) || channelName in setOf(artistName, artistName + "vevo", artistName + "official"))
    }
    suspend fun find(title: String, artist: String, durationMs: Long): MusicVideo? = findAll(title, artist, durationMs).firstOrNull()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cache = mutableMapOf<String, Pair<Long, List<MusicVideo>>>()
    private val pending = mutableMapOf<String, Deferred<List<MusicVideo>>>()
    fun prepare(song: com.localfy.app.data.Song, context: android.content.Context) {
        if (song.isPodcast || song.isAudiobook) return
        scope.launch { try { findAll(song.title, song.artist, song.durationMs).firstOrNull()?.let { com.localfy.app.ui.player.VideoWebCache.prepare(context, it.id) } } catch (e: Exception) { if (e is CancellationException) throw e } }
    }
    suspend fun findAll(title: String, artist: String, durationMs: Long): List<MusicVideo> = withContext(Dispatchers.Main.immediate) {
        val key = "${SearchMatch.fold(title)}|${SearchMatch.fold(artist)}"
        cache[key]?.let { (time, videos) ->
            if (System.currentTimeMillis() - time < if (videos.isEmpty()) 600_000 else 86_400_000) return@withContext videos
        }
        pending[key]?.let { return@withContext it.await() }
        val task = scope.async { search(title, artist, durationMs) }
        pending[key] = task
        try {
            val result = task.await()
            if (cache.size >= 100) cache.minByOrNull { it.value.first }?.key?.let { cache.remove(it) }
            cache[key] = System.currentTimeMillis() to result
            result
        } finally { if (pending[key] === task) pending.remove(key) }
    }
    private suspend fun search(title: String, artist: String, durationMs: Long): List<MusicVideo> = withContext(Dispatchers.IO) {
        val root = AudioFallback.request("search", JSONObject().put("query", "$artist $title music video"))
        val candidates = mutableListOf<JSONObject>()
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> { value.optJSONObject("videoRenderer")?.let(candidates::add); value.keys().forEach { visit(value.opt(it)) } }
                is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it)) }
            }
        }
        visit(root)
        fun text(value: JSONObject?): String = value?.optString("simpleText")?.takeIf { it.isNotBlank() } ?: value?.optJSONArray("runs")?.let { a -> (0 until a.length()).joinToString("") { a.getJSONObject(it).optString("text") } }.orEmpty()
        fun seconds(item: JSONObject) = text(item.optJSONObject("lengthText")).split(':').fold(0L) { sum, number -> sum * 60 + (number.toLongOrNull() ?: 0) }
        candidates.filter { item -> matches(title, artist, durationMs, text(item.optJSONObject("title")), text(item.optJSONObject("ownerText")), seconds(item) * 1000) }
             .filter { it.optString("videoId").matches(Regex("[A-Za-z0-9_-]{11}")) }
             .map { MusicVideo(it.getString("videoId"), text(it.optJSONObject("title")), seconds(it)) }.distinctBy { it.id }
    }
}
