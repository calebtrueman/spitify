// UI STUB — deleted at integration
package com.localfy.app

import com.localfy.app.data.LibraryRepository
import com.localfy.app.data.Song
import com.localfy.app.data.art.ArtworkStore
import com.localfy.app.data.art.OnlineArtRepository
import com.localfy.app.data.lyrics.LyricsRepository
import com.localfy.app.data.meta.MetadataRepository
import com.localfy.app.data.music.ArtistFollows
import com.localfy.app.data.music.FlacConversion
import com.localfy.app.data.music.MusicDownloads
import com.localfy.app.data.music.MusicStreams
import com.localfy.app.data.podcast.PodcastRepository
import com.localfy.app.data.social.ListeningRooms
import com.localfy.app.data.social.SocialRepository
import com.localfy.app.data.taste.ProfileRepository
import com.localfy.app.data.taste.TasteRepository
import com.localfy.app.playback.PlayerConnection
import com.localfy.app.ui.theme.ThemeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Desktop app container (stub): same property names as the phone's LocalfyApp. */
class LocalfyApp {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val metadata: MetadataRepository by lazy { MetadataRepository() }
    val library: LibraryRepository by lazy { LibraryRepository() }
    val player: PlayerConnection by lazy { PlayerConnection() }
    val lyrics: LyricsRepository by lazy { LyricsRepository() }
    val theme: ThemeRepository by lazy { ThemeRepository() }
    val onlineArt: OnlineArtRepository by lazy { OnlineArtRepository() }
    val artwork: ArtworkStore by lazy { ArtworkStore() }
    val profiles: ProfileRepository by lazy { ProfileRepository() }
    val taste: TasteRepository by lazy { TasteRepository() }
    val podcasts: PodcastRepository by lazy { PodcastRepository() }
    val musicStreams: MusicStreams by lazy { MusicStreams() }
    val artistFollows: ArtistFollows by lazy { ArtistFollows() }
    val rooms: ListeningRooms by lazy { ListeningRooms() }
    val social: SocialRepository by lazy { SocialRepository() }
    val flacConversion: FlacConversion by lazy { FlacConversion() }
    val musicDownloads: MusicDownloads by lazy { MusicDownloads() }

    fun resolve(id: Long): Song? = null
}
