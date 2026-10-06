package com.localfy.app.data.music

/** Missing totals do not undo progress already measured for the same transfer. */
object DownloadProgress {
    fun measured(previous: Float?, received: Long, total: Long): Float? =
        if (total <= 0) previous else maxOf(previous ?: 0f, (received.toFloat() / total).coerceIn(0f, 1f))

    fun fraction(state: String?, measured: Float?): Float? = when (state) {
        "checking", "complete" -> 1f
        "downloading" -> measured?.coerceIn(0f, 1f)
        else -> null
    }

    fun album(fractions: List<Float?>): Float? =
        if (fractions.isEmpty() || fractions.none { it != null }) null
        else fractions.sumOf { (it ?: 0f).toDouble() }.toFloat() / fractions.size
}
