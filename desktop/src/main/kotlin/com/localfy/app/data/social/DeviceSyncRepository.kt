package com.localfy.app.data.social

import com.localfy.app.data.Song
import com.localfy.app.data.music.OnlineTrack
import com.localfy.app.desktop.AppPaths
import com.localfy.app.desktop.JsonStore
import com.localfy.app.playback.PlayerUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * What device sync needs from the player. The app adapts its PlayerConnection; tests use a fake.
 * Called on the repository's scope (the UI thread).
 */
interface DevicePlayer {
    val state: StateFlow<PlayerUiState>
    val positionMs: StateFlow<Long>
    /** The song behind a queue id, or null when it can no longer be resolved. */
    fun song(id: Long): Song?
    /** The catalogue track behind a streamed song, if any. */
    fun online(song: Song): OnlineTrack?
    fun setPlaying(playing: Boolean)
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun playSongs(songs: List<Song>, source: String, startPositionMs: Long)
    fun appendFromSource(songs: List<Song>)
}

/** Where linking a device stands, for Settings › Your devices. */
sealed interface DevicePairing {
    data object Idle : DevicePairing
    /** This device shows [code] until [expiresAt]. */
    data class Showing(val code: String, val expiresAt: Long) : DevicePairing
    /** A typed code is being looked up on the relays. */
    data object Looking : DevicePairing
    /** The code's device was found; waiting for the user there to allow this one. */
    data class Waiting(val name: String) : DevicePairing
    data class Failed(val message: String) : DevicePairing
    data class Linked(val name: String) : DevicePairing
}

/**
 * Your devices (docs/device-sync.md): links this computer with your other devices through a
 * short code, shares what's playing with them, shows and controls what they're playing, and
 * offers to continue where you left off. Rules live in core's [DeviceSyncState]; this class
 * wires them to the relay (through [social]) and the [player], and keeps them in a JSON file.
 *
 * Call it from one thread, normally the UI thread, which must also be [scope]'s dispatcher.
 */
class DeviceSyncRepository(
    private val social: SocialRepository,
    private val player: DevicePlayer,
    private val scope: CoroutineScope,
    dir: File = AppPaths.dataDir,
    /** A playable local song for a remote track (the app passes `playlistMatches.resolve`). */
    private val resolveTrack: suspend (SharedTrack) -> Song,
    val platform: String = defaultPlatform(),
    private val hostName: () -> String? = ::systemHostName,
) {
    val me: String = social.publicKey
    private val sync = DeviceSyncState(me)
    private val receivedAt = mutableMapOf<String, Long>()
    private val store = JsonStore(File(dir, "devices.json"))
    private var customName: String? = null
    private var defaultName = fallbackName(platform)
    private var lastOfferedRevision = 0L
    private var lastLocalChange = 0L
    private var lastRevision = 0L

    private val changes = MutableStateFlow(0L)
    /** Ticks on every change; read the properties below after it does. */
    val revision = changes.asStateFlow()
    private val notes = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Short notes for a snackbar ("Now playing on Pixel 9", "Linked with MacBook"). */
    val messages: SharedFlow<String> = notes.asSharedFlow()

    var pairing: DevicePairing = DevicePairing.Idle; private set
    /** "Continue from <device>": the other device's state, shown until played or dismissed. */
    var continueOffer: DevicePlayback? = null; private set

    private var codeShown = false
    private var codeJob: Job? = null
    private var typedCode: String? = null
    private var pendingOffer: DeviceCodeOffer? = null
    private var pendingOwner: String? = null
    private var lookupJob: Job? = null
    private var lingering = 0
    /** The device last shown as "Playing on", kept on the bar while paused from here. */
    private var focus: String? = null

    /** Until this time, local playback starts come from obeying a remote device, not the user. */
    private var obeyingUntil = 0L
    /** Nothing is shared until this device plays (or obeys a command): a restored, paused queue isn't news. */
    private var sharing = false
    private var sendJob: Job? = null
    private var heartbeat: Job? = null
    private var listenJob: Job? = null
    private var lastPlayingAt = 0L
    private val tracks = mutableMapOf<Long, SharedTrack>()

    val name: String get() = customName ?: defaultName
    val devices: List<LinkedDevice> get() = sync.devices.values.toList()
    /** Link requests that matched our code, waiting for Allow / Don't allow. */
    val requests: List<Pair<String, DeviceLinkRequest>> get() = sync.pendingLinks.toList()
    fun playback(device: String): DevicePlayback? = sync.playback[device]
    fun receivedAt(device: String): Long? = receivedAt[device]

    init {
        runCatching { store.readObject()?.let(::load) }
        social.onDevicePacket = { author, packet, encrypted -> receive(author, packet, encrypted) }
        updateRelay()
        scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { hostName() }.getOrNull() }?.let(::cleanName)
            if (found != null && found != defaultName) { defaultName = found; changed() }
        }
        scope.launch {
            player.state.map { Signature(it) }.distinctUntilChanged().drop(1).collect { localChanged(it) }
        }
        scope.launch {
            var last = player.positionMs.value; var lastAt = SocialRules.now
            player.positionMs.collect { position ->
                val now = SocialRules.now; val state = player.state.value
                val expected = if (state.isPlaying) last + ((now - lastAt) * state.speed).toLong() else last
                if (kotlin.math.abs(position - expected) > SEEK_JUMP && state.hasMedia) scheduleSend()
                last = position; lastAt = now
            }
        }
    }

    private data class Signature(val id: Long?, val index: Int, val playing: Boolean, val queue: List<Long>, val source: String?, val speed: Float) {
        constructor(s: PlayerUiState) : this(s.currentId, s.currentIndex, s.isPlaying, s.queue, s.source, s.speed)
    }

    // ---- Relay and storage

    private fun changed() { changes.value += 1 }

    private fun load(o: JSONObject) {
        o.optJSONObject("state")?.let(sync::load)
        o.optJSONObject("receivedAt")?.let { r -> r.keys().forEach { k -> if (k in sync.devices) receivedAt[k] = r.optLong(k) } }
        customName = o.text("name")?.let(::cleanName)
        lastOfferedRevision = o.optLong("lastOfferedRevision"); lastLocalChange = o.optLong("lastLocalChange"); lastRevision = o.optLong("lastRevision")
    }

    private fun persist() {
        receivedAt.keys.retainAll(sync.devices.keys)
        val text = JSONObject().put("state", sync.json()).put("receivedAt", JSONObject(receivedAt.toMap())).put("name", customName)
            .put("lastOfferedRevision", lastOfferedRevision).put("lastLocalChange", lastLocalChange).put("lastRevision", lastRevision).toString()
        store.save { text }
        changed()
    }

    /** Writes pending changes (app exit). */
    fun flush() = store.flush()

    private fun updateRelay() {
        social.devicesNeedRelay = sync.devices.isNotEmpty() || codeShown || typedCode != null || lingering > 0
    }

    private suspend fun send(type: String, body: JSONObject, logical: String, recipient: String?, expiresIn: Long, extraTags: List<List<String>> = emptyList()) {
        try { social.sendDevicePacket(SocialPacket(type, body), logical, recipient, expiresIn, extraTags) }
        catch (e: Exception) { if (e is CancellationException) throw e; e.message?.let { notes.tryEmit(it) } }
    }

    /** Sends to devices that may be about to leave the group: keeps the relay up until the outbox drains. */
    private fun sendThenSettle(block: suspend () -> Unit) {
        lingering++; updateRelay()
        scope.launch {
            try { block(); withTimeoutOrNull(20_000) { while (social.pending > 0) delay(250) } }
            finally { lingering--; updateRelay() }
        }
    }

    // ---- Pairing

    /** Settings › Link a device: shows a new code and publishes its offer for 10 minutes. */
    fun showCode() {
        cancelPairing()
        val code = sync.newCode()
        codeShown = true
        pairing = DevicePairing.Showing(code, SocialRules.now + DeviceSyncState.CODE_LIFETIME)
        updateRelay(); changed()
        val offer = DeviceCodeOffer(me, name, platform)
        codeJob = scope.launch {
            send("deviceCode", offer.json(), "deviceCode", null, DeviceSyncState.CODE_LIFETIME, listOf(listOf("t", DeviceSyncState.lookupTag(code))))
            delay(DeviceSyncState.CODE_LIFETIME)
            if (codeShown && sync.currentCode() == null) { codeShown = false; pairing = DevicePairing.Failed("That code expired. Show a new one to link a device."); updateRelay(); changed() }
        }
    }

    /** Settings › Enter code: finds the device showing [text] and asks it to link. */
    fun enterCode(text: String) {
        cancelPairing()
        val code = DeviceSyncState.normalizeCode(text)
        if (code.length != DeviceSyncState.TOKEN_LENGTH) { pairing = DevicePairing.Failed("Codes have 8 letters and numbers, like K7QX M2PA."); changed(); return }
        typedCode = code; pairing = DevicePairing.Looking
        updateRelay(); changed()
        social.lookupDeviceCode(DeviceSyncState.lookupTag(code))
        lookupJob = scope.launch {
            delay(PeerRelay.LOOKUP_TIMEOUT)
            if (typedCode == code && pendingOffer == null) {
                typedCode = null
                pairing = DevicePairing.Failed("That code didn't match. Check it on your other device — codes last 10 minutes.")
                updateRelay(); changed()
            }
            // Found: wait (up to the code's lifetime) for the other device to allow this one.
            delay(DeviceSyncState.CODE_LIFETIME)
            if (typedCode == code && pendingOwner != null) {
                val who = pendingOffer?.name ?: "Your other device"
                typedCode = null; pendingOwner = null; pendingOffer = null
                pairing = DevicePairing.Failed("$who didn't allow this device. Try again with a new code.")
                updateRelay(); changed()
            }
        }
    }

    /** Closes the code / stops waiting. A code that was shown no longer links anything. */
    fun cancelPairing() {
        codeJob?.cancel(); lookupJob?.cancel()
        codeShown = false; typedCode = null; pendingOffer = null; pendingOwner = null
        sync.pendingLinks.clear()
        pairing = DevicePairing.Idle
        updateRelay(); changed()
    }

    /** "Link <name>?" → Allow. */
    fun approve(author: String) {
        val request = sync.pendingLinks[author] ?: return
        if (!sync.approve(author)) { notes.tryEmit("You can link up to ${DeviceSyncState.MAX_DEVICES} devices."); changed(); return }
        codeJob?.cancel(); codeShown = false
        pairing = DevicePairing.Linked(request.name)
        persist(); updateRelay()
        notes.tryEmit("Linked with ${request.name}")
        scope.launch { sendList() }
        if (player.state.value.hasMedia) { sharing = true; scheduleSend() }
    }

    /** "Link <name>?" → Don't allow. */
    fun decline(author: String) { sync.decline(author); changed() }

    private suspend fun sendList() {
        val list = sync.list(name, platform)
        sync.devices.keys.toList().forEach { send("deviceList", list.json(), "deviceList", it, 30L * DAY) }
    }

    /** Removes [id] from the group, telling every device (the removed one too). */
    fun remove(id: String) {
        val targets = sync.devices.keys.toList()
        val body = JSONObject().put("id", id).put("createdAt", SocialRules.now)
        sync.unlink(id); receivedAt.remove(id); if (focus == id) focus = null
        if (continueOffer?.device == id) continueOffer = null
        persist()
        sendThenSettle { targets.forEach { send("deviceUnlink", body, "deviceUnlink:$id", it, 30L * DAY) } }
    }

    /** "Leave this group": every other device forgets this one, and this one forgets them. */
    fun leave() {
        val targets = sync.devices.keys.toList()
        val body = JSONObject().put("id", me).put("createdAt", SocialRules.now)
        sync.devices.keys.toList().forEach(sync::unlink); receivedAt.clear(); focus = null; continueOffer = null
        persist()
        sendThenSettle { targets.forEach { send("deviceUnlink", body, "deviceUnlink:$me", it, 30L * DAY) } }
    }

    /** Renames this device (blank = the computer's name) and tells the group. */
    fun setName(value: String) {
        customName = cleanName(value)?.takeIf { it != defaultName }
        persist()
        if (sync.devices.isNotEmpty()) scope.launch { sendList() }
    }

    // ---- Receiving

    private fun receive(author: String, packet: SocialPacket, encrypted: Boolean) {
        try {
            when (packet.type) {
                "deviceCode" -> receiveOffer(DeviceCodeOffer.parse(packet.body), author, encrypted)
                "deviceLinkRequest" -> if (codeShown && sync.receiveLink(DeviceLinkRequest.parse(packet.body), author, encrypted)) changed()
                "deviceList" -> {
                    val joining = pendingOwner != null && author == pendingOwner
                    if (sync.acceptList(DeviceList.parse(packet.body), author, encrypted, pendingOwner)) {
                        if (joining) {
                            val who = sync.devices[author]?.name ?: pendingOffer?.name ?: "your device"
                            lookupJob?.cancel(); typedCode = null; pendingOwner = null; pendingOffer = null
                            pairing = DevicePairing.Linked(who)
                            notes.tryEmit("Linked with $who")
                            if (player.state.value.hasMedia && sharing) scheduleSend()
                        }
                        persist(); updateRelay()
                    }
                }
                "deviceUnlink" -> {
                    val id = packet.body.getString("id")
                    // A removal older than our link with its sender is a leftover from before a re-link.
                    val sentAt = packet.body.optLong("createdAt", Long.MAX_VALUE)
                    if (sentAt < (sync.devices[author]?.linkedAt ?: 0) - 60_000) return
                    if (sync.acceptUnlink(id, author, encrypted)) {
                        if (id == me) { focus = null; continueOffer = null; notes.tryEmit("This computer was removed from your devices.") }
                        if (focus == id) focus = null
                        if (continueOffer?.device == id) continueOffer = null
                        persist(); updateRelay()
                    }
                }
                "devicePlayback" -> {
                    val state = DevicePlayback.parse(packet.body)
                    if (sync.acceptPlayback(state, author, encrypted)) {
                        receivedAt[author] = SocialRules.now
                        if (state.playing && !player.state.value.isPlaying) focus = author
                        if (continueOffer?.device == author && state.playing) continueOffer = null
                        persist()
                    }
                }
                "deviceCommand" -> {
                    val command = DeviceCommand.parse(packet.body)
                    if (sync.acceptCommand(command, author, encrypted)) obey(command, author)
                }
            }
        } catch (e: Exception) { if (e is CancellationException) throw e }
    }

    private fun receiveOffer(offer: DeviceCodeOffer, author: String, encrypted: Boolean) {
        val code = typedCode ?: return
        val now = SocialRules.now
        if (encrypted || author == me || offer.owner != author || !offer.valid() || now - offer.createdAt > DeviceSyncState.CODE_LIFETIME || offer.createdAt > now + 300_000) return
        if ((pendingOffer?.createdAt ?: -1) >= offer.createdAt) return
        pendingOffer = offer; pendingOwner = author
        pairing = DevicePairing.Waiting(offer.name.trim()); changed()
        scope.launch { send("deviceLinkRequest", DeviceLinkRequest(code, name, platform).json(), "deviceLink", author, DeviceSyncState.CODE_LIFETIME) }
    }

    private fun obey(command: DeviceCommand, author: String) {
        obeyingUntil = SocialRules.now + OBEY_WINDOW
        sharing = true
        when (command.action) {
            "play" -> player.setPlaying(true)
            "pause" -> player.setPlaying(false)
            "next" -> player.next()
            "previous" -> player.previous()
            "seek" -> player.seekTo(command.positionMs)
            "handoff" -> if (!roomListener) {
                val wasPlaying = player.state.value.isPlaying
                player.setPlaying(false)
                if (wasPlaying) notes.tryEmit("Now playing on ${sync.devices[author]?.name ?: "your other device"}")
            }
        }
        // The sender waits for our state to see the result, even when nothing changed here.
        scheduleSend()
    }

    // ---- Sharing this device's playback

    private val roomListener get() = social.rooms.activeKey?.let { social.rooms.rooms[it] }?.let { it.host != me } == true
    private val inRoom get() = social.rooms.activeKey != null

    private fun localChanged(signature: Signature) {
        val now = SocialRules.now
        if (signature.playing) {
            // A user start (not a blip of buffering, not obeying a command) moves playback here.
            if (now - lastPlayingAt > PLAY_BLIP && now >= obeyingUntil) handoffOthers()
            sharing = true; focus = null; continueOffer = null
            lastPlayingAt = now
            if (heartbeat?.isActive != true) heartbeat = scope.launch {
                while (isActive) { delay(DeviceSyncState.HEARTBEAT); if (player.state.value.isPlaying && sendJob?.isActive != true) sendState() }
            }
        } else {
            if (lastPlayingAt > 0) lastPlayingAt = now
            heartbeat?.cancel(); heartbeat = null
        }
        changed()
        scheduleSend()
    }

    private fun handoffOthers() {
        if (inRoom) return
        val active = sync.active(receivedAt) ?: return
        control(active.device, "handoff")
    }

    /** Sends this device's state after [DeviceSyncState.DEBOUNCE] of quiet. */
    private fun scheduleSend() {
        if (!sharing || sync.devices.isEmpty() || roomListener) return
        sendJob?.cancel()
        sendJob = scope.launch { delay(DeviceSyncState.DEBOUNCE); sendState() }
    }

    private suspend fun sendState() {
        if (sync.devices.isEmpty() || roomListener) return
        val state = currentState() ?: return
        lastRevision = state.revision; lastLocalChange = SocialRules.now
        persist()
        val body = state.json()
        sync.devices.keys.toList().forEach { send("devicePlayback", body, "devicePlayback", it, 14L * DAY) }
    }

    /** What this device is playing, as sent to the others. */
    internal fun currentState(): DevicePlayback? {
        val s = player.state.value
        val (ids, index) = DevicePlayback.window(s.queue, s.currentIndex)
        val songs = ids.map { player.song(it) }
        val current = songs.getOrNull(index)
        val entries = songs.mapIndexedNotNull { i, song ->
            song?.let { tracks.getOrPut(it.id) { SharedTrack.from(it, player.online(it)) } }?.takeIf { it.valid() }?.let { i to it }
        }
        tracks.keys.retainAll(songs.mapNotNull { it?.id }.toSet())
        val at = entries.indexOfFirst { it.first == index }
        val queue = if (at >= 0) entries.map { it.second } else emptyList()
        val now = SocialRules.now
        val revision = maxOf(now, lastRevision + 1)
        val state = DevicePlayback(
            device = me, name = name, platform = platform, revision = revision, observedAt = now,
            playing = s.isPlaying && at >= 0, positionMs = player.positionMs.value.coerceIn(0, 86_400_000), speed = s.speed.coerceIn(0.25f, 4f),
            queue = queue, currentIndex = at, source = s.source?.take(200), spoken = current?.let { it.isPodcast || it.isAudiobook } == true,
        )
        return state.takeIf { it.valid() }
    }

    // ---- Other devices: show, control, take over

    /**
     * The device for the "Playing on …" bar: the active one, or the one last shown while it's
     * paused from here and still fresh. Null when this device is playing.
     */
    fun shown(now: Long = SocialRules.now): DevicePlayback? {
        if (player.state.value.isPlaying) return null
        return sync.active(receivedAt, now) ?: focus?.let { sync.playback[it] }
            ?.takeIf { it.current != null && now - (receivedAt[it.device] ?: 0) <= DeviceSyncState.FRESH }
    }

    /** Where [state]'s device is now. */
    fun position(state: DevicePlayback, now: Long = SocialRules.now): Long =
        DeviceSyncState.expectedPosition(state, receivedAt[state.device] ?: now, now)

    /** Remote control: "play", "pause", "next", "previous", "seek" ([positionMs]) or "handoff". */
    fun control(device: String, action: String, positionMs: Long = 0) {
        if (device !in sync.devices || action !in DeviceCommand.ACTIONS) return
        val now = SocialRules.now
        sync.playback[device]?.let { state ->
            val at = position(state, now)
            sync.playback[device] = when (action) {
                "play" -> state.copy(playing = true, positionMs = at)
                "pause", "handoff" -> state.copy(playing = false, positionMs = at)
                "seek" -> state.copy(positionMs = positionMs)
                "next" -> if (state.currentIndex + 1 in state.queue.indices) state.copy(currentIndex = state.currentIndex + 1, positionMs = 0) else state
                "previous" -> if (at <= 3_000 && state.currentIndex - 1 in state.queue.indices) state.copy(currentIndex = state.currentIndex - 1, positionMs = 0) else state.copy(positionMs = 0)
                else -> state
            }
            receivedAt[device] = now
            if (action == "play") focus = device
        }
        changed()
        val command = DeviceCommand(randomId(), device, action, positionMs.coerceIn(0, 86_400_000), now)
        scope.launch { send("deviceCommand", command.json(), "deviceCommand", device, 2 * 60_000L) }
    }

    /** "Listen here": plays [device]'s queue on this computer from where it is, then pauses it there. */
    fun listenHere(device: String) {
        val state = sync.playback[device] ?: return
        val current = state.current ?: return
        if (state.spoken) { notes.tryEmit("Podcasts and audiobooks keep playing on ${state.name}."); return }
        listenJob?.cancel()
        listenJob = scope.launch {
            val song = try { resolveTrack(current) } catch (e: Exception) {
                if (e is CancellationException) throw e
                notes.tryEmit(e.message ?: "“${current.title}” couldn't be found to play here."); return@launch
            }
            val source = state.source ?: "From ${state.name}"
            obeyingUntil = SocialRules.now + OBEY_WINDOW
            sharing = true; focus = null; continueOffer = null
            player.playSongs(listOf(song), source, position(sync.playback[device] ?: state))
            control(device, "handoff")
            changed()
            for (track in state.queue.drop(state.currentIndex + 1)) {
                val next = try { resolveTrack(track) } catch (e: Exception) { if (e is CancellationException) throw e; null } ?: continue
                if (player.state.value.source != source) break
                player.appendFromSource(listOf(next))
            }
        }
    }

    /** On launch and whenever the window comes back: offer to continue another device's playback. */
    fun checkContinue() {
        if (player.state.value.isPlaying || continueOffer != null) return
        val latest = sync.latest(receivedAt) ?: return
        if (latest.revision == lastOfferedRevision || (receivedAt[latest.device] ?: 0) <= lastLocalChange) return
        // Still playing there: the "Playing on" bar already offers Listen here.
        if (sync.active(receivedAt)?.device == latest.device) return
        continueOffer = latest; lastOfferedRevision = latest.revision
        persist()
    }

    fun playContinue() { val offer = continueOffer ?: return; continueOffer = null; changed(); listenHere(offer.device) }
    fun dismissContinue() { continueOffer = null; changed() }

    /** Called once the app is up. */
    fun start() {
        checkContinue()
        // States from while we were away arrive just after connecting.
        scope.launch { delay(8_000); checkContinue() }
    }

    companion object {
        private const val DAY = 24 * 60 * 60_000L
        private const val SEEK_JUMP = 3_000L
        private const val OBEY_WINDOW = 3_000L
        private const val PLAY_BLIP = 3_000L
        private val random = SecureRandom()

        fun randomId(): String = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }

        fun defaultPlatform(): String = when { AppPaths.isMac -> "macos"; AppPaths.isWindows -> "windows"; else -> "linux" }
        fun fallbackName(platform: String) = when (platform) { "macos" -> "Mac"; "windows" -> "Windows PC"; else -> "Linux PC" }

        /** "Calebs-MacBook-Pro.local" → "Calebs-MacBook-Pro"; null when blank. */
        fun cleanName(value: String): String? = value.trim().removeSuffix(".local").trim().take(60).takeIf { it.isNotBlank() }

        /** The computer's network name (blocking; call off the UI thread). */
        fun systemHostName(): String? {
            System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }?.let { return it }
            fun run(vararg command: String) = runCatching {
                val process = ProcessBuilder(*command).redirectErrorStream(true).start()
                if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); null }
                else process.inputStream.bufferedReader().readText().trim().takeIf { process.exitValue() == 0 && it.isNotBlank() }
            }.getOrNull()
            if (AppPaths.isMac) run("scutil", "--get", "LocalHostName")?.let { return it }
            return run("hostname") ?: System.getenv("HOSTNAME")
        }
    }
}
