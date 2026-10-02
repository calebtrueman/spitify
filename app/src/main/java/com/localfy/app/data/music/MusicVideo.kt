package com.localfy.app.data.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class MusicVideo(val id: String, val title: String, val durationSeconds: Long)

object MusicVideoLookup {
    fun matches(title: String, artist: String, durationMs: Long, videoTitle: String, channel: String, videoDurationMs: Long): Boolean {
        val wanted = SearchMatch.fold(title); val found = SearchMatch.fold(videoTitle)
        val creator = SearchMatch.fold(artist.split(';', ',').first())
        if (wanted.isEmpty() || creator.isEmpty() || durationMs <= 0 || kotlin.math.abs(durationMs - videoDurationMs) > 30_000) return false
        val words = found.split(' ').toSet(); val wantedWords = wanted.split(' ').toSet()
        val otherVersions = setOf("live", "cover", "remix", "karaoke", "instrumental", "slowed", "sped", "reaction", "lyrics", "lyric", "audio", "visualizer")
        if ((otherVersions.intersect(words) - wantedWords).isNotEmpty()) return false
        val channelName = SearchMatch.fold(channel).replace(" ", "")
        val artistName = creator.replace(" ", "")
        return words.containsAll(wantedWords) && channelName in setOf(artistName, artistName + "vevo", artistName + "official")
    }
    suspend fun find(title: String, artist: String, durationMs: Long): MusicVideo? = withContext(Dispatchers.IO) {
        val root = AudioFallback.request("search", JSONObject().put("query", "$artist $title official music video"))
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
        candidates.firstOrNull { item -> matches(title, artist, durationMs, text(item.optJSONObject("title")), text(item.optJSONObject("ownerText")), seconds(item) * 1000) }
            ?.takeIf { it.optString("videoId").matches(Regex("[A-Za-z0-9_-]{11}")) }
            ?.let { MusicVideo(it.getString("videoId"), text(it.optJSONObject("title")), seconds(it)) }
    }
}
