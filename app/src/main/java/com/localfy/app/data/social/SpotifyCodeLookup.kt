package com.localfy.app.data.social

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class SpotifyCodeTarget(val kind: String, val id: String) {
    val url get() = "https://open.spotify.com/$kind/$id"
    companion object {
        fun parse(value: String): SpotifyCodeTarget? {
            val p = value.split(':')
            if (p.firstOrNull() != "spotify") return null
            val pair = when { p.size == 5 && p[1] == "user" && p[3] == "playlist" -> p.takeLast(2); p.size == 3 -> p.takeLast(2); else -> return null }
            if (pair[0] !in listOf("playlist", "track", "album", "artist", "show", "episode", "audiobook", "user") || !pair[1].matches(Regex("[A-Za-z0-9_-]{1,100}"))) return null
            return SpotifyCodeTarget(pair[0], pair[1])
        }
    }
}

/** Short-lived anonymous session held in memory. Never reads or saves a personal login. */
object SpotifyCodeLookup {
    private val lock = Mutex()
    private var token: String? = null
    private var expires = 0L
    private const val VERSION = 61
    private const val KEY = "GM3TMMJTGYZTQNZVGM4DINJZHA4TGOBYGMZTCMRTGEYDSMJRHE4TEOBUG4YTCMRUGQ4DQOJUGQYTAMRRGA2TCMJSHE3TCMBY"
    private fun get(url: String, token: String? = null): Pair<Int, JSONObject> {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false; c.connectTimeout = 20_000; c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            val status = c.responseCode
            if (status != 200) return status to JSONObject()
            val text = c.inputStream.bufferedReader().use { reader ->
                val out = StringBuilder(); val buffer = CharArray(4096)
                while (true) { val count = reader.read(buffer); if (count < 0) break; check(out.length + count < 1_000_000); out.append(buffer, 0, count) }; out.toString()
            }
            return status to JSONObject(text)
        } finally { c.disconnect() }
    }
    private fun accessToken(): String {
        token?.takeIf { expires - System.currentTimeMillis() > 60_000 }?.let { return it }
        val (_, clock) = get("https://open.spotify.com/api/server-time")
        val now = clock.optLong("serverTime", System.currentTimeMillis()/1000)
        val code = totp(KEY, now)
        val (status, obj) = get("https://open.spotify.com/api/token?reason=init&productType=web-player&totp=$code&totpServer=$code&totpVer=$VERSION")
        check(status == 200 && obj.optBoolean("isAnonymous") && obj.optString("accessToken").isNotEmpty()) { "Spotify changed or blocked its code lookup. You can still paste a playlist link." }
        token = obj.getString("accessToken"); expires = obj.optLong("accessTokenExpirationTimestampMs", System.currentTimeMillis()+300_000)
        return token!!
    }
    suspend fun resolve(reference: Long): SpotifyCodeTarget = withContext(Dispatchers.IO) {
        lock.withLock {
            check(reference in 0 until (1L shl 37)) { "This code could not be read." }
            repeat(2) { attempt ->
                val (status, obj) = get("https://spclient.wg.spotify.com/scannable-id/id/$reference?format=json", accessToken())
                if (status == 401 && attempt == 0) { token = null }
                else return@withLock SpotifyCodeTarget.parse(obj.optString("target"))?.takeIf { status == 200 }
                    ?: error("Spotify couldn't open this code. It may have expired or point to something unavailable.")
            }
            error("Spotify's code lookup is unavailable. Try again shortly.")
        }
    }
    internal fun totp(base32: String, seconds: Long): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"; val bytes = ArrayList<Byte>(); var buffer = 0L; var count = 0
        for (c in base32) { val value = alphabet.indexOf(c); if (value < 0) continue; buffer = (buffer shl 5) or value.toLong(); count += 5
            if (count >= 8) { count -= 8; bytes += ((buffer shr count) and 255).toByte() }; buffer = buffer and ((1L shl count)-1)
        }
        val mac = Mac.getInstance("HmacSHA1"); mac.init(SecretKeySpec(bytes.toByteArray(), "HmacSHA1"))
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(seconds/30).array()); val offset = hash.last().toInt() and 15
        val value = ByteBuffer.wrap(hash, offset, 4).int and 0x7fffffff
        return (value % 1_000_000).toString().padStart(6, '0')
    }
}
