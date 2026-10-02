package com.localfy.app.data.art

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Keep one checked cover for both the preview and the later file save. */
internal object CoverDownload {
    private const val MAX_BYTES = 20_000_000

    fun urls(source: String): List<String> {
        val url = URL(source)
        val smaller = when {
            url.host.endsWith(".mzstatic.com") -> source.replace(Regex("/\\d+x\\d+bb\\."), "/600x600bb.")
            url.host.endsWith(".dzcdn.net") -> source.replace("/1000x1000-", "/500x500-")
            else -> source
        }
        return listOf(source, smaller).distinct()
    }

    fun load(source: String, fetch: (String) -> ByteArray = ::fetch): ByteArray {
        var failure: Exception? = null
        // Retry a failed request, then try a smaller version of the same cover.
        for (url in urls(source)) repeat(2) {
            try {
                val bytes = fetch(url)
                check(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "The cover image is empty or too large." }
                return bytes
            } catch (error: Exception) { failure = error }
        }
        throw IOException("Could not load the cover. ${failure?.message ?: "Please try again."}", failure)
    }

    private fun fetch(source: String): ByteArray {
        val conn = URL(source).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000; conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Spitify/1.0 (Android local music player)")
            conn.setRequestProperty("Accept", "image/*")
            val status = conn.responseCode
            if (status != 200) throw IOException("The image server returned $status.")
            if (conn.contentLengthLong > MAX_BYTES) throw IOException("The cover image is too large.")
            return conn.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (output.size() + size > MAX_BYTES) throw IOException("The cover image is too large.")
                    output.write(buffer, 0, size)
                }
                output.toByteArray()
            }
        } finally { conn.disconnect() }
    }
}
