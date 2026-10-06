package com.localfy.app.data.social

/** Reads Spotify's 23 bar heights locally. The number is a lookup key, not a song ID.
 * Format: https://boonepeter.github.io/posts/spotify-codes-part-2/
 */
object SpotifyCodeDecoder {
    fun decode(bars: List<Int>): Long? {
        if (bars.size != 23 || bars.any { it !in 0..7 } || bars[0] != 0 || bars[11] != 7 || bars[22] != 0) return null
        val output = bars.filterIndexed { i, _ -> i != 0 && i != 11 && i != 22 }.flatMap { height ->
            val gray = height xor (height shr 1)
            listOf(gray shr 2, (gray shr 1) and 1, gray and 1)
        }
        val rows = LongArray(60) { matrix[it] or (output[it].toLong() shl 45) }
        for (col in 0 until 45) {
            val at = (col until 60).firstOrNull { ((rows[it] shr col) and 1) != 0L } ?: return null
            val old = rows[col]; rows[col] = rows[at]; rows[at] = old
            for (row in 0 until 60) if (row != col && ((rows[row] shr col) and 1) != 0L) rows[row] = rows[row] xor rows[col]
        }
        if ((45 until 60).any { rows[it] != 0L }) return null
        val bits = rows.take(45).map { it shr 45 }
        val value = (0 until 37).fold(0L) { n, i -> n or (bits[i] shl i) }
        var crc = 0
        for (byte in 0 until 5) {
            crc = crc xor ((value shr (byte * 8)) and 255).toInt()
            repeat(8) { crc = ((crc shl 1) xor (if (crc and 128 != 0) 7 else 0)) and 255 }
        }
        val check = (0 until 8).fold(0L) { n, i -> n or (bits[i + 37] shl i) }
        return value.takeIf { check == (crc xor 255).toLong() }
    }

    private val matrix: LongArray by lazy {
        val rows = LongArray(60)
        for (column in 0 until 45) {
            val bits = List(45) { if (it == column) 1 else 0 }
            val full = bits.takeLast(6) + bits
            val stream = (0 until 45).flatMap { i -> listOf(0b1011011, 0b1111001).map { mask ->
                (0 until 7).fold(0) { n, j -> n xor (full[i + j] * ((mask shr j) and 1)) }
            } }
            val short = stream.filterIndexed { i, _ -> i % 3 != 2 }
            for (row in 0 until 60) rows[row] = rows[row] or (short[(row * 7) % 60].toLong() shl column)
        }
        rows
    }
}
