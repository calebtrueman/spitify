@file:Suppress("FunctionName", "unused")

package com.localfy.app.playback

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.io.File

@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MprisRoot : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)
}

/**
 * Linux media keys / desktop "now playing" through MPRIS on the session D-Bus
 * (bus name org.mpris.MediaPlayer2.spitify), using the pure-JVM dbus-java.
 */
internal class MprisMediaSession : MediaSessionBridge {
    private var connection: DBusConnection? = null
    private var onCommand: (MediaCommand) -> Unit = {}
    private var onSeek: (Long) -> Unit = {}
    @Volatile private var info: NowPlaying? = null
    private var infoAt = System.currentTimeMillis()

    private val exported = object : MprisRoot, MprisPlayer, Properties {
        override fun getObjectPath() = OBJECT_PATH
        override fun isRemote() = false

        override fun Raise() {}
        override fun Quit() {}
        override fun Next() = onCommand(MediaCommand.NEXT)
        override fun Previous() = onCommand(MediaCommand.PREVIOUS)
        override fun Pause() = onCommand(MediaCommand.PAUSE)
        override fun PlayPause() = onCommand(MediaCommand.TOGGLE)
        override fun Stop() = onCommand(MediaCommand.STOP)
        override fun Play() = onCommand(MediaCommand.PLAY)
        override fun Seek(offset: Long) = onSeek((position() + offset / 1000).coerceAtLeast(0))
        override fun SetPosition(trackId: DBusPath, position: Long) {
            if (trackId.path == trackPath(info)) onSeek(position / 1000)
        }
        override fun OpenUri(uri: String) {}

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String?, propertyName: String?): A =
            properties(interfaceName)[propertyName] as A

        override fun <A : Any?> Set(interfaceName: String?, propertyName: String?, value: A) {}

        override fun GetAll(interfaceName: String?): Map<String, Variant<*>> = properties(interfaceName)
    }

    private fun position(): Long {
        val i = info ?: return 0
        return i.positionMs + if (i.isPlaying) ((System.currentTimeMillis() - infoAt) * i.speed).toLong() else 0L
    }

    private fun trackPath(i: NowPlaying?) = if (i == null) "/org/mpris/MediaPlayer2/TrackList/NoTrack" else "/com/spitify/track/${if (i.songId < 0) "n${-i.songId}" else i.songId}"

    private fun properties(iface: String?): Map<String, Variant<*>> = when (iface) {
        ROOT_IFACE -> mapOf(
            "CanQuit" to Variant(false),
            "CanRaise" to Variant(false),
            "HasTrackList" to Variant(false),
            "Identity" to Variant("Spitify"),
            "DesktopEntry" to Variant("spitify"),
            "SupportedUriSchemes" to Variant(arrayOf<String>(), "as"),
            "SupportedMimeTypes" to Variant(arrayOf<String>(), "as"),
        )
        PLAYER_IFACE -> {
            val i = info
            mapOf(
                "PlaybackStatus" to Variant(if (i == null) "Stopped" else if (i.isPlaying) "Playing" else "Paused"),
                "LoopStatus" to Variant("None"),
                "Rate" to Variant(i?.speed?.toDouble() ?: 1.0),
                "Shuffle" to Variant(false),
                "Metadata" to Variant(metadata(i), "a{sv}"),
                "Volume" to Variant(1.0),
                "Position" to Variant(position() * 1000),
                "MinimumRate" to Variant(0.5),
                "MaximumRate" to Variant(3.0),
                "CanGoNext" to Variant(i?.canGoNext ?: false),
                "CanGoPrevious" to Variant(i?.canGoPrevious ?: false),
                "CanPlay" to Variant(i != null),
                "CanPause" to Variant(i != null),
                "CanSeek" to Variant(i != null && i.durationMs > 0),
                "CanControl" to Variant(true),
            )
        }
        else -> emptyMap()
    }

    private fun metadata(i: NowPlaying?): Map<String, Variant<*>> {
        if (i == null) return mapOf("mpris:trackid" to Variant(DBusPath(trackPath(null))))
        return buildMap {
            put("mpris:trackid", Variant(DBusPath(trackPath(i))))
            if (i.durationMs > 0) put("mpris:length", Variant(i.durationMs * 1000))
            put("xesam:title", Variant(i.title))
            put("xesam:artist", Variant(arrayOf(i.artist), "as"))
            put("xesam:album", Variant(i.album))
            i.artworkPath?.let { put("mpris:artUrl", Variant(File(it).toURI().toString())) }
        }
    }

    override fun start(onCommand: (MediaCommand) -> Unit, onSeek: (positionMs: Long) -> Unit) {
        this.onCommand = onCommand
        this.onSeek = onSeek
        val c = DBusConnectionBuilder.forSessionBus().withShared(false).build()
        connection = c
        c.requestBusName(BUS_NAME)
        c.exportObject(OBJECT_PATH, exported)
    }

    override fun update(info: NowPlaying?) {
        this.info = info
        infoAt = System.currentTimeMillis()
        val c = connection ?: return
        val changed = properties(PLAYER_IFACE).filterKeys { it != "Position" }
        c.sendMessage(Properties.PropertiesChanged(OBJECT_PATH, PLAYER_IFACE, changed, emptyList()))
    }

    override fun close() {
        val c = connection ?: return
        connection = null
        runCatching { c.unExportObject(OBJECT_PATH) }
        runCatching { c.releaseBusName(BUS_NAME) }
        runCatching { c.close() }
    }

    companion object {
        private const val BUS_NAME = "org.mpris.MediaPlayer2.spitify"
        private const val OBJECT_PATH = "/org/mpris/MediaPlayer2"
        private const val ROOT_IFACE = "org.mpris.MediaPlayer2"
        private const val PLAYER_IFACE = "org.mpris.MediaPlayer2.Player"
    }
}
