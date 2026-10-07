package com.localfy.app.playback

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * macOS Now Playing (Control Centre, menu bar, media keys, AirPods) through MediaPlayer.framework:
 * MPNowPlayingInfoCenter for what's playing and MPRemoteCommandCenter for the commands, called
 * through the Objective-C runtime with JNA. Command targets are a small runtime-built NSObject
 * subclass whose methods are JNA callbacks.
 */
internal class MacNowPlaying : MediaSessionBridge {
    private val objc = NativeLibrary.getInstance("objc")
    private val media = NativeLibrary.getInstance("/System/Library/Frameworks/MediaPlayer.framework/MediaPlayer")
    private val msgSend: Function = objc.getFunction("objc_msgSend")

    private fun cls(name: String): Pointer = objc.getFunction("objc_getClass").invokePointer(arrayOf(name))
        ?: error("No class $name")
    private fun sel(name: String): Pointer = objc.getFunction("sel_registerName").invokePointer(arrayOf(name))
    private fun send(receiver: Pointer?, selector: String, vararg args: Any?): Pointer? =
        msgSend.invokePointer(arrayOf(receiver, sel(selector), *args))
    private fun sendDouble(receiver: Pointer?, selector: String): Double = msgSend.invokeDouble(arrayOf(receiver, sel(selector)))
    private fun constant(name: String): Pointer = media.getGlobalVariableAddress(name).getPointer(0)
    private fun nsString(s: String): Pointer? = send(cls("NSString"), "stringWithUTF8String:", s)

    private inline fun <T> pool(block: () -> T): T {
        val p = objc.getFunction("objc_autoreleasePoolPush").invokePointer(emptyArray())
        try { return block() } finally { objc.getFunction("objc_autoreleasePoolPop").invokeVoid(arrayOf(p)) }
    }

    interface Handler : Callback { fun invoke(self: Pointer?, cmd: Pointer?, event: Pointer?): Long }

    private var target: Pointer? = null
    private val registered = mutableListOf<Pair<Pointer, Pointer>>() // command -> target

    override fun start(onCommand: (MediaCommand) -> Unit, onSeek: (positionMs: Long) -> Unit) = pool {
        handlers = mapOf(
            "spitifyPlay:" to handler { onCommand(MediaCommand.PLAY) },
            "spitifyPause:" to handler { onCommand(MediaCommand.PAUSE) },
            "spitifyToggle:" to handler { onCommand(MediaCommand.TOGGLE) },
            "spitifyNext:" to handler { onCommand(MediaCommand.NEXT) },
            "spitifyPrevious:" to handler { onCommand(MediaCommand.PREVIOUS) },
            "spitifyStop:" to handler { onCommand(MediaCommand.STOP) },
            "spitifySeek:" to handler { event -> onSeek((sendDouble(event, "positionTime") * 1000).toLong()) },
        )
        val targetClass = targetClass()
        val t = send(send(targetClass, "alloc"), "init") ?: error("Couldn't create the command target")
        target = t
        val center = send(cls("MPRemoteCommandCenter"), "sharedCommandCenter")
        listOf(
            "playCommand" to "spitifyPlay:",
            "pauseCommand" to "spitifyPause:",
            "togglePlayPauseCommand" to "spitifyToggle:",
            "nextTrackCommand" to "spitifyNext:",
            "previousTrackCommand" to "spitifyPrevious:",
            "stopCommand" to "spitifyStop:",
            "changePlaybackPositionCommand" to "spitifySeek:",
        ).forEach { (command, action) ->
            val c = send(center, command) ?: return@forEach
            send(c, "addTarget:action:", t, sel(action))
            send(c, "setEnabled:", true)
            registered += c to t
        }
    }

    private fun handler(body: (event: Pointer?) -> Unit) = object : Handler {
        override fun invoke(self: Pointer?, cmd: Pointer?, event: Pointer?): Long {
            runCatching { body(event) }
            return 0 // MPRemoteCommandHandlerStatusSuccess
        }
    }

    /** Registers (once per process) an NSObject subclass whose action methods call [handlers]. */
    private fun targetClass(): Pointer = synchronized(MacNowPlaying::class.java) {
        objc.getFunction("objc_getClass").invokePointer(arrayOf(CLASS_NAME))?.let { existing ->
            // Already registered by an earlier bridge: just point the shared callbacks at ours.
            liveHandlers = handlers
            return existing
        }
        val superclass = cls("NSObject")
        val c = objc.getFunction("objc_allocateClassPair").invokePointer(arrayOf(superclass, CLASS_NAME, 0L))
            ?: error("Couldn't allocate $CLASS_NAME")
        liveHandlers = handlers
        for (name in handlers.keys) {
            val trampoline = object : Handler {
                override fun invoke(self: Pointer?, cmd: Pointer?, event: Pointer?): Long =
                    liveHandlers[name]?.invoke(self, cmd, event) ?: 0L
            }
            trampolines += trampoline // keep the native thunk alive for the process lifetime
            objc.getFunction("class_addMethod").invokeInt(arrayOf(c, sel(name), trampoline, "q@:@"))
        }
        objc.getFunction("objc_registerClassPair").invokeVoid(arrayOf(c))
        c
    }

    private var handlers: Map<String, Handler> = emptyMap()

    override fun update(info: NowPlaying?) = pool {
        val center = send(cls("MPNowPlayingInfoCenter"), "defaultCenter")
        if (info == null) {
            send(center, "setNowPlayingInfo:", null)
            send(center, "setPlaybackState:", 0L) // unknown
            return@pool
        }
        val dict = send(cls("NSMutableDictionary"), "dictionary")
        fun put(key: String, value: Pointer?) { if (value != null) send(dict, "setObject:forKey:", value, constant(key)) }
        fun number(v: Double) = send(cls("NSNumber"), "numberWithDouble:", v)
        put("MPMediaItemPropertyTitle", nsString(info.title))
        put("MPMediaItemPropertyArtist", nsString(info.artist))
        put("MPMediaItemPropertyAlbumTitle", nsString(info.album))
        if (info.durationMs > 0) put("MPMediaItemPropertyPlaybackDuration", number(info.durationMs / 1000.0))
        put("MPNowPlayingInfoPropertyElapsedPlaybackTime", number(info.positionMs / 1000.0))
        put("MPNowPlayingInfoPropertyPlaybackRate", number(if (info.isPlaying) info.speed.toDouble() else 0.0))
        put("MPNowPlayingInfoPropertyDefaultPlaybackRate", number(1.0))
        send(center, "setNowPlayingInfo:", dict)
        send(center, "setPlaybackState:", if (info.isPlaying) 1L else 2L) // playing / paused
    }

    override fun close() {
        runCatching {
            pool {
                registered.forEach { (command, t) -> send(command, "removeTarget:", t) }
                registered.clear()
                target?.let { send(it, "release") }
                target = null
                update(null)
            }
        }
    }

    companion object {
        private const val CLASS_NAME = "SpitifyRemoteCommandTarget"
        @Volatile private var liveHandlers: Map<String, Handler> = emptyMap()
        private val trampolines = mutableListOf<Handler>()
    }
}
