package com.localfy.app.data.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Reason codes for a failed transfer, matching Android's DownloadManager so [DownloadRetry.isTemporary]
 * and the retry policy port unchanged: HTTP status codes, or one of these.
 */
object DownloadErrors {
    const val UNKNOWN = 1000
    const val FILE_ERROR = 1001
    const val UNHANDLED_HTTP_CODE = 1002
    const val HTTP_DATA_ERROR = 1004
    const val TOO_MANY_REDIRECTS = 1005
    const val INSUFFICIENT_SPACE = 1006
    const val DEVICE_NOT_FOUND = 1007
}

/** A transfer failure with a DownloadManager-style [reason] (HTTP status or a [DownloadErrors] code). */
class TransferFailed(val reason: Int, message: String, cause: Throwable? = null) : IOException(message, cause)

/** Moves bytes from a URL into a file. Swappable so the download queue can be tested without a network. */
fun interface DownloadTransport {
    /**
     * Downloads [url] into [dest]. When [dest] already holds bytes from an earlier attempt at the same URL
     * the transfer resumes after them (if the server allows ranges). Returns the expected total size
     * (or -1 when the server didn't say). Throws [TransferFailed] on failure; honours cancellation.
     */
    suspend fun fetch(url: String, dest: File, onProgress: (received: Long, total: Long) -> Unit): Long
}

/** Plain HTTP(S) with manual redirects (HttpURLConnection never follows http <-> https itself). */
object HttpGet {
    const val USER_AGENT = "Spitify/1.0 (Desktop)"

    /**
     * Opens [url] following up to 6 redirects across protocols. Returns the connection with the final
     * response code available; the caller must disconnect it. An http:// URL is tried as https:// first
     * when [upgrade] is set (falling back to plain http when the secure host doesn't answer).
     */
    fun open(url: String, headers: Map<String, String> = emptyMap(), upgrade: Boolean = false,
             connectTimeout: Int = 10_000, readTimeout: Int = 20_000): HttpURLConnection {
        if (upgrade && url.startsWith("http://")) {
            try {
                val secure = open("https://" + url.removePrefix("http://"), headers, false, connectTimeout, readTimeout)
                if (secure.responseCode in 200..299) return secure
                secure.disconnect()
            } catch (_: IOException) { }
        }
        var current = url
        repeat(7) {
            val scheme = URI(current).scheme?.lowercase()
            if (scheme != "http" && scheme != "https") throw TransferFailed(DownloadErrors.UNHANDLED_HTTP_CODE, "Unsupported address.")
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeout
            conn.readTimeout = readTimeout
            conn.setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = try { conn.responseCode } catch (e: IOException) { conn.disconnect(); throw e }
            if (code in 300..399 && code != 304) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw TransferFailed(DownloadErrors.UNHANDLED_HTTP_CODE, "Bad redirect.")
                current = URL(URL(current), location).toString()
            } else return conn
        }
        throw TransferFailed(DownloadErrors.TOO_MANY_REDIRECTS, "Too many redirects.")
    }
}

/** The real transport: resumable HTTP downloads with Range requests. */
class HttpDownloadTransport(private val upgradeHttp: Boolean = false) : DownloadTransport {
    override suspend fun fetch(url: String, dest: File, onProgress: (Long, Long) -> Unit): Long = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val already = if (dest.isFile) dest.length() else 0L
        val conn = try {
            HttpGet.open(url, if (already > 0) mapOf("Range" to "bytes=$already-") else emptyMap(), upgradeHttp)
        } catch (e: TransferFailed) { throw e } catch (e: IOException) {
            throw TransferFailed(DownloadErrors.HTTP_DATA_ERROR, "Could not connect.", e)
        }
        // Cancelling the coroutine closes the socket, so a blocked read ends right away.
        val hook = coroutineContext[Job]?.invokeOnCompletion { conn.disconnect() }
        try {
            val code = conn.responseCode
            val append: Boolean
            val total: Long
            when (code) {
                206 -> {
                    val range = conn.getHeaderField("Content-Range").orEmpty()
                    val start = Regex("bytes (\\d+)-").find(range)?.groupValues?.get(1)?.toLongOrNull()
                    if (start != already) { dest.delete(); throw TransferFailed(DownloadErrors.HTTP_DATA_ERROR, "The server sent the wrong part of the file.") }
                    append = true
                    total = range.substringAfter('/', "").toLongOrNull() ?: conn.contentLengthLong.takeIf { it >= 0 }?.plus(already) ?: -1
                }
                200 -> { append = false; total = conn.contentLengthLong }
                416 -> {
                    val size = conn.getHeaderField("Content-Range")?.substringAfter('/', "")?.toLongOrNull()
                    if (size != null && size == already) { onProgress(already, size); return@withContext size }
                    dest.delete(); throw TransferFailed(DownloadErrors.HTTP_DATA_ERROR, "The partial download was out of date.")
                }
                else -> throw TransferFailed(code, "Download response $code")
            }
            if (total > 0) {
                val free = dest.absoluteFile.parentFile?.usableSpace ?: Long.MAX_VALUE
                if (free in 1 until (total - (if (append) already else 0)) + 16_000_000) throw TransferFailed(DownloadErrors.INSUFFICIENT_SPACE, "Not enough free space.")
            }
            val output = try { FileOutputStream(dest, append) } catch (e: IOException) { throw TransferFailed(DownloadErrors.FILE_ERROR, "Could not write the download.", e) }
            var received = if (append) already else 0L
            output.use { out ->
                val input = try { conn.inputStream } catch (e: IOException) { throw TransferFailed(DownloadErrors.HTTP_DATA_ERROR, "Could not read the download.", e) }
                input.use {
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = try { input.read(buffer) } catch (e: IOException) {
                            coroutineContext.ensureActive()
                            throw TransferFailed(DownloadErrors.HTTP_DATA_ERROR, "The connection dropped.", e)
                        }
                        if (n < 0) break
                        try { out.write(buffer, 0, n) } catch (e: IOException) { throw TransferFailed(DownloadErrors.FILE_ERROR, "Could not write the download.", e) }
                        received += n
                        val now = System.nanoTime()
                        if (now - lastReport > 200_000_000) { lastReport = now; onProgress(received, total) }
                    }
                }
            }
            onProgress(received, total)
            if (total > 0 && received != total) throw TransferFailed(DownloadErrors.HTTP_DATA_ERROR, "The download ended early.")
            total
        } finally {
            hook?.dispose()
            conn.disconnect()
        }
    }
}
