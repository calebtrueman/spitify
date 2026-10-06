package com.localfy.app.data.music

import java.text.Normalizer

object SearchMatch {
    fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** The same score applies to every result, regardless of where its audio lives. */
    fun score(query: String, title: String, artist: String, album: String = ""): Int? {
        val q = fold(query); val t = fold(title); val a = fold(artist); val b = fold(album)
        if (q.isEmpty()) return null
        val words = q.split(' ')
        if (!words.all { "$t $a $b".contains(it) }) return null
        return when {
            t == q -> 1000
            "$t $a".split(' ').filter { it != "the" }.sorted() == q.split(' ').filter { it != "the" }.sorted() -> 950
            "$t $a" == q || "$a $t" == q -> 950
            t.startsWith(q) -> 850
            t.contains(q) -> 750
            a == q -> 700
            words.all { t.contains(it) } -> 650
            a.contains(q) -> 550
            else -> 400 + words.count { t.contains(it) } * 10
        }
    }

    fun sameSong(title: String, artist: String, duration: Long, otherTitle: String, otherArtist: String, otherDuration: Long): Boolean =
        fold(title) == fold(otherTitle) && fold(artist) == fold(otherArtist) &&
            duration > 0 && otherDuration > 0 && kotlin.math.abs(duration - otherDuration) <= 3_000
}
