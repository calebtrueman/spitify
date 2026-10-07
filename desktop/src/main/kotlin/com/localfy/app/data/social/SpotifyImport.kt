package com.localfy.app.data.social

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import javax.imageio.ImageIO
import kotlin.coroutines.coroutineContext

/**
 * Spotify import for the desktop UI: public playlist links / search (core [SpotifyPlaylists]) and
 * Spotify codes read from a picture the user picks (local bar reading, then [SpotifyCodeLookup]).
 * Everything suspends on IO; nothing here touches a personal Spotify login.
 */
object SpotifyImport {
    /** True for a playlist link, `spotify:playlist:` URI, bare id or spotify.link short link. */
    fun accepts(input: String) = SpotifyPlaylists.accepts(input)

    suspend fun search(query: String): List<SpotifyPlaylistResult> = SpotifyPlaylists.search(query.trim())

    /** Loads a public playlist as a [SharedPlaylist] owned by [owner] (not saved). */
    suspend fun load(input: String, owner: String): SharedPlaylist = SpotifyPlaylists.load(input, owner)

    /** Loads and saves the playlist into the shared library ([SocialRepository.save] runs on the caller's thread). */
    suspend fun importPlaylist(input: String, social: SocialRepository): SharedPlaylist {
        val playlist = load(input, social.publicKey)
        coroutineContext.ensureActive()
        social.save(social.state.playlists[playlist.key]?.let { old -> playlist.copy(revision = old.revision + 1, isPublic = old.isPublic, editors = old.editors) } ?: playlist)
        return social.state.playlists.getValue(playlist.key)
    }

    /** Reads the Spotify code number from a picture; null when no clear code is found. */
    suspend fun readCode(file: File): Long? = withContext(Dispatchers.IO) {
        val image = runCatching { ImageIO.read(file) }.getOrNull() ?: error("This picture could not be opened. Choose another image.")
        SpotifyCodeImageReader.read(image)
    }

    /** Picture → what the code points to (playlist, track, album …). Errors are user-readable. */
    suspend fun scan(file: File): SpotifyCodeTarget {
        val reference = readCode(file) ?: error("No clear Spotify code found. Keep all the bars level and in view, avoid glare, and try a closer picture.")
        coroutineContext.ensureActive()
        return SpotifyCodeLookup.resolve(reference)
    }

    /** Picture of a playlist code → the loaded playlist (not saved). */
    suspend fun scanPlaylist(file: File, owner: String): SharedPlaylist {
        val target = scan(file)
        check(target.kind == "playlist") { "This code opens a Spotify ${target.kind}, not a playlist." }
        return load(target.url, owner)
    }

    /** The public title of a scanned non-playlist item (Spotify oEmbed); empty when unavailable. */
    suspend fun title(target: SpotifyCodeTarget): String = withContext(Dispatchers.IO) {
        runCatching {
            val c = URI("https://open.spotify.com/oembed?url=" + URLEncoder.encode(target.url, "UTF-8")).toURL().openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15_000; c.readTimeout = 15_000
                if (c.responseCode != 200) "" else c.inputStream.bufferedReader().use { JSONObject(it.readText().take(1_000_000)).optString("title").take(200) }
            } finally { c.disconnect() }
        }.getOrDefault("")
    }
}
