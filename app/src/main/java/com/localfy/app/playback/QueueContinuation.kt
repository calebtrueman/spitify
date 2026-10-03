package com.localfy.app.playback

/** Prefer unheard picks. Once a small library runs out, revisit the least recently played songs. */
internal fun continuationIds(candidates: List<Long>, recent: List<Long>, queued: Set<Long>, count: Int): List<Long> {
    val eligible = candidates.distinct().filter { it !in queued }
    val lastPlayed = recent.withIndex().associate { it.value to it.index }
    return eligible.sortedBy { lastPlayed[it] ?: -1 }.take(count)
}
