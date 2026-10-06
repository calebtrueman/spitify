package com.localfy.app.data.music

object DownloadRetry {
    fun delayMillis(attempt: Int): Long = when {
        attempt <= 1 -> 2_000; attempt == 2 -> 5_000; attempt == 3 -> 15_000; attempt == 4 -> 60_000; else -> 300_000
    }
    // DownloadManager uses these public reason codes for a broken/unfinished server response.
    fun isTemporary(reason: Int): Boolean = reason == 408 || reason == 429 || reason in 500..599 || reason in listOf(1000, 1002, 1004, 1005)
}
