package com.localfy.app.playback

import com.localfy.app.data.Song
import kotlin.random.Random

/**
 * One queue slot. [uid] identifies the slot (a song can be queued more than once); [manual] marks
 * "Add to queue" / "Play next" items and [autoplay] the radio continuation, like the MediaItem
 * extras on Android.
 */
data class QueueEntry(val uid: Long, val song: Song, val manual: Boolean = false, val autoplay: Boolean = false)

/** Pure queue rules shared by [PlayerConnection] and its tests (Media3 semantics). */
internal object QueueRules {

    /** Where playback goes by itself when a track ends: repeat-one stays, repeat-all wraps. */
    fun autoNextIndex(size: Int, current: Int, repeatMode: Int): Int? = when {
        size == 0 || current !in 0 until size -> null
        repeatMode == RepeatMode.ONE -> current
        current + 1 < size -> current + 1
        repeatMode == RepeatMode.ALL -> 0
        else -> null
    }

    /** Next/previous buttons treat repeat-one as off (as Media3 does) but wrap with repeat-all. */
    fun nextIndex(size: Int, current: Int, repeatMode: Int): Int? = when {
        size == 0 || current !in 0 until size -> null
        current + 1 < size -> current + 1
        repeatMode == RepeatMode.ALL -> 0
        else -> null
    }

    fun previousIndex(size: Int, current: Int, repeatMode: Int): Int? = when {
        size == 0 || current !in 0 until size -> null
        current > 0 -> current - 1
        repeatMode == RepeatMode.ALL -> size - 1
        else -> null
    }

    /** Spotify behaviour: restart the song if more than 3 s in (or nothing before it), otherwise go back one. */
    fun previousRestarts(positionMs: Long, hasPrevious: Boolean) = positionMs > 3_000 || !hasPrevious

    fun nextRepeat(mode: Int) = when (mode) {
        RepeatMode.OFF -> RepeatMode.ALL
        RepeatMode.ALL -> RepeatMode.ONE
        else -> RepeatMode.OFF
    }

    /** Index of [current] after moving an item [from] -> [to]. */
    fun indexAfterMove(current: Int, from: Int, to: Int): Int = when {
        from == current -> to
        from < current && to >= current -> current - 1
        from > current && to <= current -> current + 1
        else -> current
    }

    data class Shuffled(val entries: List<QueueEntry>, val current: Int, val unshuffledOrder: List<Long>?)

    /**
     * Shuffle rewrites the real queue (current song stays, everything after it is shuffled),
     * so "Next up" is always truthful. Turning it off restores [unshuffledOrder].
     */
    fun toggleShuffle(entries: List<QueueEntry>, current: Int, enable: Boolean, unshuffledOrder: List<Long>?, random: Random = Random): Shuffled {
        if (entries.size < 2 || current !in entries.indices) return Shuffled(entries, current, if (enable) unshuffledOrder else null)
        val currentItem = entries[current]
        val manual = entries.drop(current + 1).filter { it.manual }
        val background = entries.filterIndexed { i, item -> i != current && !item.manual }
        val before: List<QueueEntry>
        val after: List<QueueEntry>
        val order: List<Long>?
        if (enable) {
            order = entries.filter { !it.manual }.map { it.song.id }
            before = emptyList()
            after = manual + background.shuffled(random)
        } else {
            // Remove one occurrence at a time: an album or playlist can repeat a song.
            val remaining = (background + if (currentItem.manual) emptyList() else listOf(currentItem)).toMutableList()
            val restored = unshuffledOrder.orEmpty().mapNotNull { id ->
                val i = remaining.indexOfFirst { it.song.id == id }
                if (i < 0) null else remaining.removeAt(i)
            } + remaining
            val at = if (currentItem.manual) -1 else restored.indexOf(currentItem)
            before = if (at < 0) emptyList() else restored.take(at)
            after = manual + if (at < 0) restored else restored.drop(at + 1)
            order = null
        }
        return Shuffled(before + currentItem + after, before.size, order)
    }

    /** Where "Add to queue" inserts: after the last manual item that follows the current one. */
    fun addToQueueIndex(entries: List<QueueEntry>, current: Int): Int =
        ((current + 1 until entries.size).lastOrNull { entries[it].manual } ?: current) + 1

    /** Where more of the source goes: before the radio continuation. */
    fun appendFromSourceIndex(entries: List<QueueEntry>, current: Int): Int =
        (current + 1 until entries.size).firstOrNull { entries[it].autoplay } ?: entries.size

    /** True when [b] is the next track of [a]'s album (kept gapless when crossfading). */
    fun isConsecutiveAlbumTrack(a: Song, b: Song): Boolean =
        a.track > 0 && b.track > 0 && a.album.isNotEmpty() && a.album == b.album && b.track == a.track + 1

    /** Crossfade length for the transition [from] -> [to]; 0 means a plain gapless change. */
    fun crossfadeFor(from: Song, to: Song, crossfadeMs: Int, keepAlbums: Boolean, repeatMode: Int): Int = when {
        crossfadeMs <= 0 || repeatMode == RepeatMode.ONE -> 0
        // Spoken word never crossfades.
        from.isSpoken || to.isSpoken -> 0
        keepAlbums && isConsecutiveAlbumTrack(from, to) -> 0
        else -> crossfadeMs
    }
}
