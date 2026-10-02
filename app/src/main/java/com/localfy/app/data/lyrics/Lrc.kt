package com.localfy.app.data.lyrics

data class LyricLine(val timeMs: Long, val text: String)

/** Parses LRC (incl. multiple stamps per line, [offset:], and enhanced <mm:ss.xx> word tags). */
object Lrc {
    private val stamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val wordStamp = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

    fun isSynced(text: String): Boolean = stamp.containsMatchIn(text)

    fun parse(text: String): List<LyricLine> {
        val offset = offsetTag.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val out = ArrayList<LyricLine>()
        text.lineSequence().forEach { raw ->
            val stamps = stamp.findAll(raw).toList()
            if (stamps.isEmpty()) return@forEach
            val body = raw.substring(stamps.last().range.last + 1).replace(wordStamp, "").trim()
            stamps.forEach { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                out += LyricLine((min * 60_000 + sec * 1000 + ms - offset).coerceAtLeast(0), body)
            }
        }
        return out.sortedBy { it.timeMs }
    }

    fun plainLines(text: String): List<LyricLine> =
        text.replace("\r", "").lines().map { LyricLine(-1, it.trim()) }.dropWhile { it.text.isEmpty() }.dropLastWhile { it.text.isEmpty() }

    /** Index of the line that should be highlighted at [positionMs], or -1 before the first line. */
    fun activeIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var lo = 0
        var hi = lines.lastIndex
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}
