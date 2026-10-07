package com.localfy.app.data.sync

import com.localfy.app.data.LibraryRepository
import com.localfy.app.data.art.OnlineArtRepository
import com.localfy.app.data.lyrics.LyricsRepository
import com.localfy.app.data.meta.MetadataRepository
import com.localfy.app.data.music.ArtistFollows
import com.localfy.app.playback.PlayerConnection
import com.localfy.app.ui.theme.AccentSource
import com.localfy.app.ui.theme.AppFont
import com.localfy.app.ui.theme.ArtShape
import com.localfy.app.ui.theme.PlayerStyle
import com.localfy.app.ui.theme.TextSize
import com.localfy.app.ui.theme.ThemeMode
import com.localfy.app.ui.theme.ThemeRepository
import com.localfy.app.ui.theme.ThemeSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * The desktop's settings under their library-sync names ([LibrarySync.SYNCED_SETTINGS]): appearance,
 * playback and library choices that follow you. Enum settings travel by name (the same names as on
 * Android), the text size as its scale, the accent as an ARGB number. Everything else (EQ, folders,
 * volume, the window) stays on this computer.
 */
class AppSyncedSettings(
    private val theme: ThemeRepository,
    private val library: LibraryRepository,
    private val player: PlayerConnection,
    private val lyrics: LyricsRepository,
    private val onlineArt: OnlineArtRepository,
    private val metadata: MetadataRepository,
    private val follows: ArtistFollows,
) : SyncedSettings {
    override val changes: Flow<*> = merge(
        theme.settings, library.showRecommendations, library.hideShortTracks, player.savedSpeeds,
        player.state.map { listOf(it.autoplay, it.crossfadeMs, it.crossfadeKeepAlbums, it.normalizeAudio, it.skipSilence) }.distinctUntilChanged(),
        lyrics.onlineEnabled, onlineArt.enabled, metadata.autoFix, follows.revision,
    )

    override fun values(): Map<String, Any> = values(theme.settings.value) + mapOf(
        "showRecommendations" to library.showRecommendations.value,
        "hideShortTracks" to library.hideShortTracks.value,
        "autoplay" to player.state.value.autoplay,
        "crossfadeMs" to player.state.value.crossfadeMs,
        "crossfadeKeepAlbums" to player.state.value.crossfadeKeepAlbums,
        "normalizeAudio" to player.state.value.normalizeAudio,
        "skipSilence" to player.state.value.skipSilence,
        "speedMusic" to player.savedSpeeds.value.first,
        "speedPodcast" to player.savedSpeeds.value.second,
        "onlineLyrics" to lyrics.onlineEnabled.value,
        "onlineArt" to onlineArt.enabled.value,
        "autoFixMetadata" to metadata.autoFix.value,
        "releaseNotifications" to follows.notifications,
    )

    override fun defaults(): Map<String, Any> = values(ThemeSettings()) + mapOf(
        "showRecommendations" to true, "hideShortTracks" to true, "autoplay" to true, "crossfadeMs" to 0, "crossfadeKeepAlbums" to true,
        "normalizeAudio" to true, "skipSilence" to false, "speedMusic" to 1f, "speedPodcast" to 1f, "onlineLyrics" to true, "onlineArt" to true,
        "autoFixMetadata" to true, "releaseNotifications" to false,
    )

    private fun values(t: ThemeSettings): Map<String, Any> = mapOf(
        "themeMode" to t.mode.name, "accent" to t.accent, "accentFromArt" to (t.accentSource == AccentSource.Artwork),
        "font" to t.font.name, "textScale" to t.textSize.scale, "artShape" to t.artShape.name, "playerStyle" to t.playerStyle.name,
        "artworkTint" to t.artworkTint, "blurBackdrop" to t.blurBackdrop, "reduceMotion" to t.reduceMotion,
    )

    override fun apply(name: String, value: Any) {
        val bool = value as? Boolean
        val number = value as? Number
        val text = value as? String
        fun <E : Enum<E>> enum(values: Array<E>) = values.firstOrNull { it.name.equals(text, ignoreCase = true) }
        when (name) {
            "themeMode" -> enum(ThemeMode.entries.toTypedArray())?.let { v -> theme.update { it.copy(mode = v) } }
            "accent" -> number?.let { v -> theme.update { it.copy(accent = v.toLong()) } }
            "accentFromArt" -> bool?.let { v -> theme.update { it.copy(accentSource = if (v) AccentSource.Artwork else AccentSource.Preset) } }
            "font" -> enum(AppFont.entries.toTypedArray())?.let { v -> theme.update { it.copy(font = v) } }
            "textScale" -> number?.let { v -> theme.update { it.copy(textSize = TextSize.entries.minBy { s -> kotlin.math.abs(s.scale - v.toFloat()) }) } }
            "artShape" -> enum(ArtShape.entries.toTypedArray())?.let { v -> theme.update { it.copy(artShape = v) } }
            "playerStyle" -> enum(PlayerStyle.entries.toTypedArray())?.let { v -> theme.update { it.copy(playerStyle = v) } }
            "artworkTint" -> bool?.let { v -> theme.update { it.copy(artworkTint = v) } }
            "blurBackdrop" -> bool?.let { v -> theme.update { it.copy(blurBackdrop = v) } }
            "reduceMotion" -> bool?.let { v -> theme.update { it.copy(reduceMotion = v) } }
            "showRecommendations" -> bool?.let { if (it != library.showRecommendations.value) library.setShowRecommendations(it) }
            "hideShortTracks" -> bool?.let { if (it != library.hideShortTracks.value) library.setHideShortTracks(it) }
            "autoplay" -> bool?.let { if (it != player.state.value.autoplay) player.setAutoplay(it) }
            "crossfadeMs" -> number?.let { it.toInt().coerceIn(0, 12_000) }?.let { if (it != player.state.value.crossfadeMs) player.setCrossfade(it) }
            "crossfadeKeepAlbums" -> bool?.let { if (it != player.state.value.crossfadeKeepAlbums) player.setCrossfadeKeepAlbums(it) }
            "normalizeAudio" -> bool?.let { if (it != player.state.value.normalizeAudio) player.setNormalizeAudio(it) }
            "skipSilence" -> bool?.let { if (it != player.state.value.skipSilence) player.setSkipSilence(it) }
            "speedMusic" -> number?.let { player.setSavedSpeed(false, it.toFloat()) }
            "speedPodcast" -> number?.let { player.setSavedSpeed(true, it.toFloat()) }
            "onlineLyrics" -> bool?.let { if (it != lyrics.onlineEnabled.value) lyrics.setOnlineEnabled(it) }
            "onlineArt" -> bool?.let { if (it != onlineArt.enabled.value) onlineArt.setEnabled(it) }
            "autoFixMetadata" -> bool?.let { if (it != metadata.autoFix.value) metadata.setAutoFix(it) }
            "releaseNotifications" -> bool?.let { if (it != follows.notifications) follows.setNotifications(it) }
        }
    }
}
