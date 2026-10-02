package com.localfy.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import com.localfy.app.data.LibraryRepository
import com.localfy.app.data.db.LocalfyDatabase
import com.localfy.app.data.lyrics.LyricsRepository
import com.localfy.app.playback.PlayerConnection
import com.localfy.app.ui.art.ArtFetcher
import com.localfy.app.ui.art.ArtKeyer
import com.localfy.app.ui.theme.ThemeRepository
import com.localfy.app.data.art.OnlineArtRepository
import com.localfy.app.data.podcast.PodcastRepository
import com.localfy.app.data.meta.MetadataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LocalfyApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        com.localfy.app.playback.EqStore.init(this)
        com.localfy.app.playback.ArtContext.app = this
        musicDownloads.start()
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val database by lazy { LocalfyDatabase.create(this) }
    val metadata by lazy { MetadataRepository(this, database, appScope) }
    val library by lazy {
        LibraryRepository(this, database, appScope, metadata).also { repo ->
            repo.onScanned = { music, books ->
                metadata.autoFixAll(music) { albumId, url -> if (metadata.customArt(albumId) == null && onlineArt.cached(albumId) == null) metadata.setArt(albumId, url) }
                metadata.autoFixBooks(books)
            }
        }
    }
    val player by lazy { PlayerConnection(this, library, appScope, ::resolve, podcasts) { taste.record(it) } }
    val lyrics by lazy { LyricsRepository(this, database, appScope) }
    val theme by lazy { ThemeRepository(this) }
    val onlineArt by lazy { OnlineArtRepository(this) }
    val profiles by lazy { com.localfy.app.data.taste.ProfileRepository(this, appScope) }
    val taste by lazy { com.localfy.app.data.taste.TasteRepository(this, database, appScope, library, profiles).also { it.start() } }
    val podcasts by lazy { PodcastRepository(this, database, appScope) }
    val musicDownloads by lazy { com.localfy.app.data.music.MusicDownloads(this, database, appScope) }

    /** Resolves any queue id: MediaStore songs, local podcast files, or podcast episodes (negative ids). */
    fun resolve(id: Long): com.localfy.app.data.Song? =
        if (id < 0) podcasts.episodeSongs.value[id]
        else library.library.value.songById[id] ?: library.localPodcasts.value.firstOrNull { it.id == id }
            ?: library.localBooks.value.firstOrNull { it.id == id }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(ArtFetcher.Factory(this@LocalfyApp))
                add(ArtKeyer())
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .build()
}
