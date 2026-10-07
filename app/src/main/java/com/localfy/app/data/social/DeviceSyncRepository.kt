package com.localfy.app.data.social

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.SecureRandom

/** What [DeviceSync] needs from the relay. */
interface DeviceLink {
    val me: String
    suspend fun send(packet: SocialPacket, logical: String, recipient: String?, expiresIn: Long, extraTags: List<List<String>> = emptyList())
    /** Public packets tagged ["t", tag], with their authors. */
    suspend fun lookup(tag: String): List<Pair<String, SocialPacket>>
    /** Keep the relay connected (inbox only if friend sharing is off). */
    fun needRelay(needed: Boolean)
}

/** This device's playback, already cut to the window that's sent. */
data class LocalPlayback(val queue: List<SharedTrack>, val currentIndex: Int, val playing: Boolean, val positionMs: Long, val speed: Float = 1f, val source: String? = null, val spoken: Boolean = false)

/** The local player and storage, as [DeviceSync] sees them. Called on the main thread. */
interface DeviceHost {
    val platform: String
    fun snapshot(): LocalPlayback?
    fun isPlaying(): Boolean
    /** play, pause, next, previous or seek. */
    fun obey(action: String, positionMs: Long)
    /** In a Listening Room as a guest: don't share playback. */
    fun roomGuest(): Boolean
    fun inRoom(): Boolean
    /** Starts [state]'s queue here at [positionMs]; throws with a message when the song can't be found. */
    suspend fun listen(state: DevicePlayback, positionMs: Long)
    suspend fun load(): String?
    /** Writes off the main thread. */
    fun save(json: String)
}

/**
 * When this device's playback goes out: after a quiet [debounce] (bursts of seeks and skips send
 * once), never more than once per [debounce], and a [heartbeat] only while playing.
 */
class PlaybackSchedule(private val debounce: Long = DeviceSyncState.DEBOUNCE, private val heartbeat: Long = DeviceSyncState.HEARTBEAT) {
    var dueAt: Long? = null; private set
    private var lastSent = Long.MIN_VALUE / 2
    private var firstChange: Long? = null
    fun changed(now: Long) {
        val first = firstChange ?: now.also { firstChange = it }
        dueAt = maxOf(minOf(now + debounce, first + 4 * debounce), lastSent + debounce)
    }
    fun sent(now: Long, playing: Boolean) { lastSent = now; firstChange = null; dueAt = if (playing) now + heartbeat else null }
    fun stop() { dueAt = null; firstChange = null }
    fun due(now: Long) = dueAt?.let { now >= it } == true
}

/**
 * Your own linked devices (docs/device-sync.md): pairing, "Playing on …", remote control,
 * Listen here and continue where you left off. All state lives on [scope]'s thread (main).
 */
class DeviceSync(private val link: DeviceLink, private val host: DeviceHost, private val scope: CoroutineScope, defaultName: String, private val clock: () -> Long = { SocialRules.now }) {
    val me get() = link.me
    val state = DeviceSyncState(link.me)
    val receivedAt = mutableMapOf<String, Long>()
    private val changes = MutableStateFlow(0L)
    val revision: StateFlow<Long> = changes.asStateFlow()
    private val noteFlow = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Short notes for a toast, e.g. "Now playing on MacBook". */
    val notes: SharedFlow<String> = noteFlow.asSharedFlow()
    var name = cleanName(defaultName); private set
    var loaded = false; private set
    private val early = mutableListOf<Triple<String, SocialPacket, Boolean>>()

    // Pairing
    var code: String? = null; private set
    var codeIssuedAt = 0L; private set
    var entering = false; private set
    var pendingOwner: String? = null; private set
    private var pendingName: String? = null
    private var pendingAt = 0L
    /** Status line for Settings › Your devices ("Waiting for …", "That code didn't match…"). */
    var pairingMessage: String? = null; private set
    private var lingerUntil = 0L

    // Playback
    val schedule = PlaybackSchedule()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var lastRevision = 0L
    private var lastSentPlaying = false
    private var playedThisSession = false
    private var forceSend = false
    var lastLocalChange = 0L; private set
    private var obeyingUntil = 0L
    private var controlled: Pair<String, Long>? = null
    var offer: DevicePlayback? = null; private set
    private var lastOffered: String? = null
    private var foregroundAt = 0L
    private var expiry: Job? = null
    private var saving: Job? = null
    private var sender: Job? = null

    val devices get() = state.devices.values.toList()
    val approval get() = state.pendingLinks.entries.firstOrNull()?.toPair()
    fun active(now: Long = clock()) = state.active(receivedAt, now)
    /** The "Playing on …" device: the active one, or one you just controlled (so a paused device stays reachable). */
    fun shown(now: Long = clock()): DevicePlayback? = active(now) ?: controlled?.takeIf { now - it.second < 10 * 60_000 }?.let { state.playback[it.first] }?.takeIf { it.current != null }
    fun expectedPosition(state: DevicePlayback, now: Long = clock()) = DeviceSyncState.expectedPosition(state, receivedAt[state.device] ?: now, now)

    suspend fun start() {
        val text = runCatching { host.load() }.getOrNull()
        text?.let { runCatching { restore(JSONObject(it)) } }
        loaded = true
        early.toList().also { early.clear() }.forEach { (a, p, e) -> receive(a, p, e) }
        updateRelay(); changed()
        if (foregroundAt > 0) checkContinue(clock())
        if (sender == null) sender = scope.launch { sendLoop() }
    }

    private fun restore(o: JSONObject) {
        o.optJSONObject("state")?.let(state::load)
        o.optJSONObject("receivedAt")?.let { r -> r.keys().forEach { k -> if (k in state.devices) receivedAt[k] = r.getLong(k) } }
        o.text("name")?.let { name = cleanName(it) }
        lastOffered = o.text("lastOffered"); lastLocalChange = o.optLong("lastLocalChange"); lastRevision = o.optLong("lastRevision")
        o.text("pendingOwner")?.takeIf { SocialRules.key(it) && clock() - o.optLong("pendingAt") < DeviceSyncState.CODE_LIFETIME }?.let { pendingOwner = it; pendingName = o.text("pendingName"); pendingAt = o.optLong("pendingAt") }
    }

    fun json(): JSONObject = JSONObject().put("state", state.json()).put("receivedAt", JSONObject(receivedAt.filterKeys { it in state.devices } as Map<*, *>)).put("name", name)
        .put("lastOffered", lastOffered).put("lastLocalChange", lastLocalChange).put("lastRevision", lastRevision)
        .put("pendingOwner", pendingOwner).put("pendingName", pendingName).put("pendingAt", pendingAt)

    private fun changed() { changes.value += 1 }
    private fun persist() {
        changed()
        if (saving?.isActive == true) return
        saving = scope.launch { delay(1_000); host.save(json().toString()) }
    }

    private fun updateRelay() {
        val now = clock()
        if (code != null && state.currentCode(now) == null) code = null
        if (pendingOwner != null && now - pendingAt > DeviceSyncState.CODE_LIFETIME) { pendingOwner = null; pairingMessage = "No answer from ${pendingName ?: "your other device"}. Try a new code."; persist() }
        link.needRelay(state.devices.isNotEmpty() || code != null || entering || pendingOwner != null || now < lingerUntil)
    }

    /** Re-checks pairing timeouts and freshness when they run out, without polling. */
    private fun scheduleExpiry() {
        expiry?.cancel()
        val now = clock()
        val times = buildList {
            if (code != null) add(codeIssuedAt + DeviceSyncState.CODE_LIFETIME)
            if (pendingOwner != null) add(pendingAt + DeviceSyncState.CODE_LIFETIME)
            if (lingerUntil > now) add(lingerUntil)
            state.playback.values.filter { it.playing }.forEach { receivedAt[it.device]?.let { t -> add(t + DeviceSyncState.FRESH + 1) } }
            controlled?.let { add(it.second + 10 * 60_000) }
        }.filter { it > now }
        val next = times.minOrNull() ?: return
        expiry = scope.launch { delay(next - now); updateRelay(); changed(); scheduleExpiry() }
    }

    // ---- Settings › Your devices ----

    fun rename(value: String) {
        val clean = cleanName(value); if (clean == name) return
        name = clean; persist(); sendList(); localChanged()
    }

    /** Link a device: shows a new code for 10 minutes and publishes its offer. */
    fun showCode(): String {
        val now = clock()
        val fresh = state.newCode(now); code = fresh; codeIssuedAt = now; pairingMessage = null
        updateRelay(); scheduleExpiry(); changed()
        val offer = DeviceCodeOffer(me, name, host.platform, now)
        scope.launch {
            try { link.send(SocialPacket("deviceCode", offer.json()), "deviceCode", null, DeviceSyncState.CODE_LIFETIME, listOf(listOf("t", DeviceSyncState.lookupTag(fresh)))) }
            catch (e: Exception) { if (e is CancellationException) throw e; pairingMessage = e.message; changed() }
        }
        return fresh
    }

    fun hideCode() { code = null; state.pendingLinks.clear(); updateRelay(); changed() }

    /** Enter code: finds the other device by the code and asks it to link. */
    suspend fun enterCode(text: String) {
        val token = DeviceSyncState.normalizeCode(text)
        if (token.length != DeviceSyncState.TOKEN_LENGTH) { pairingMessage = "Codes have 8 letters and numbers."; changed(); return }
        entering = true; pairingMessage = "Looking for your other device…"; updateRelay(); changed()
        try {
            val now = clock()
            val offer = link.lookup(DeviceSyncState.lookupTag(token)).mapNotNull { (author, packet) ->
                if (packet.type != "deviceCode") null else runCatching { DeviceCodeOffer.parse(packet.body) }.getOrNull()
                    ?.takeIf { it.valid() && it.owner == author && author != me && it.createdAt in now - DeviceSyncState.CODE_LIFETIME..now + 300_000 }
            }.maxByOrNull { it.createdAt }
            if (offer == null) { pairingMessage = "That code didn't match. Check it on your other device — codes last 10 minutes."; return }
            pendingOwner = offer.owner; pendingName = offer.name.trim(); pendingAt = clock()
            link.send(SocialPacket("deviceLinkRequest", DeviceLinkRequest(token, name, host.platform, clock()).json()), "deviceLink", offer.owner, DeviceSyncState.CODE_LIFETIME)
            pairingMessage = "Waiting for ${offer.name.trim()} to allow this device…"
            persist(); scheduleExpiry()
        } catch (e: Exception) { if (e is CancellationException) throw e; pairingMessage = e.message ?: "Couldn't reach your other device." }
        finally { entering = false; updateRelay(); changed() }
    }

    /** "Link <name>?" → Allow. */
    fun allow(author: String) {
        val name = state.pendingLinks[author]?.name ?: return
        if (!state.approve(author, clock())) { pairingMessage = "You can link up to ${DeviceSyncState.MAX_DEVICES} devices."; changed(); return }
        code = null; pairingMessage = "Linked with $name"; noteFlow.tryEmit("Linked with $name")
        persist(); sendList(); updateRelay(); localChanged()
    }

    fun deny(author: String) { state.decline(author); changed() }

    /** Removes [id] from the group; every device (including it) is told. */
    fun remove(id: String) {
        val recipients = state.devices.keys.toList(); if (id !in recipients) return
        state.unlink(id); receivedAt.remove(id); if (controlled?.first == id) controlled = null
        sendUnlink(id, recipients); persist()
    }

    /** Leave this group: this device forgets the others and they forget it. */
    fun leave() {
        val recipients = state.devices.keys.toList()
        state.devices.clear(); state.playback.clear(); receivedAt.clear(); controlled = null; offer = null
        sendUnlink(me, recipients); persist()
    }

    private fun sendUnlink(id: String, recipients: List<String>) {
        lingerUntil = clock() + 30_000; updateRelay(); scheduleExpiry()
        val body = JSONObject().put("id", id)
        scope.launch { recipients.forEach { safely { link.send(SocialPacket("deviceUnlink", body), "deviceUnlink:$id", it, 30L * 24 * 60 * 60_000) } } }
    }

    private fun sendList() {
        if (state.devices.isEmpty()) return
        val list = state.list(name, host.platform); val recipients = state.devices.keys.toList()
        scope.launch { recipients.forEach { safely { link.send(SocialPacket("deviceList", list.json()), "deviceList", it, 30L * 24 * 60 * 60_000) } } }
    }

    // ---- Receiving ----

    fun receive(author: String, packet: SocialPacket, encrypted: Boolean) {
        if (!loaded) { if (early.size < 200) early += Triple(author, packet, encrypted); return }
        runCatching {
            val body = packet.body
            when (packet.type) {
                "deviceLinkRequest" -> if (code != null && state.receiveLink(DeviceLinkRequest.parse(body), author, encrypted, clock())) changed()
                "deviceList" -> {
                    val list = DeviceList.parse(body)
                    val joined = state.acceptList(list, author, encrypted, pendingOwner)
                    if (author == pendingOwner && author in state.devices) {
                        val owner = list.devices.first { it.id == author }.name
                        pendingOwner = null; pendingName = null; pairingMessage = "Linked with $owner"; noteFlow.tryEmit("Linked with $owner")
                        persist(); updateRelay(); sendList(); localChanged()
                    } else if (joined) { persist(); updateRelay() }
                }
                "deviceUnlink" -> {
                    val id = body.getString("id")
                    if (state.acceptUnlink(id, author, encrypted)) {
                        receivedAt.keys.retainAll(state.devices.keys); if (controlled?.first !in state.devices) controlled = null
                        if (id == me) { offer = null; noteFlow.tryEmit("This device was removed from your devices.") }
                        persist(); updateRelay()
                    }
                }
                "devicePlayback" -> {
                    val incoming = DevicePlayback.parse(body)
                    if (state.acceptPlayback(incoming, author, encrypted)) {
                        val now = clock()
                        // A state replayed from the relay long after it was sent shouldn't look live.
                        receivedAt[author] = if (now - incoming.observedAt > 10 * 60_000) incoming.observedAt.coerceAtMost(now) else now
                        if (offer?.device == author) offer = null
                        if (now - foregroundAt < 30_000) checkContinue(now)
                        persist(); scheduleExpiry()
                    }
                }
                "deviceCommand" -> {
                    val command = DeviceCommand.parse(body)
                    if (state.acceptCommand(command, author, encrypted, clock())) obey(command, author)
                }
            }
        }
    }

    private fun obey(command: DeviceCommand, author: String) {
        when (command.action) {
            "handoff" -> { host.obey("pause", 0); noteFlow.tryEmit("Now playing on ${state.devices[author]?.name ?: "your other device"}") }
            "play" -> { obeyingUntil = clock() + 5_000; host.obey("play", 0) }
            else -> host.obey(command.action, command.positionMs)
        }
        localChanged(force = true)
    }

    // ---- Remote control ----

    fun control(device: String, action: String, positionMs: Long = 0) {
        require(action in DeviceCommand.ACTIONS && device in state.devices)
        val now = clock()
        val command = DeviceCommand(newId(), device, action, positionMs.coerceIn(0, 86_400_000), now)
        state.playback[device]?.let { s ->
            val position = expectedPosition(s, now)
            state.playback[device] = when (action) {
                "play" -> s.copy(playing = true, positionMs = position)
                "pause", "handoff" -> s.copy(playing = false, positionMs = position)
                "seek" -> s.copy(positionMs = command.positionMs)
                else -> s.copy(positionMs = position)
            }
            receivedAt[device] = now
        }
        if (action != "handoff") controlled = device to now
        changed(); scheduleExpiry()
        scope.launch { safely { link.send(SocialPacket("deviceCommand", command.json()), "deviceCommand", device, 2 * 60_000) } }
    }

    /** Takes [device]'s playback over: plays its queue here and pauses it there. */
    suspend fun listenHere(device: String) {
        val remote = state.playback[device]?.takeIf { it.current != null && !it.spoken } ?: return
        obeyingUntil = clock() + 60_000
        try { host.listen(remote, expectedPosition(remote)) } finally { obeyingUntil = clock() + 5_000 }
        offer = null
        control(device, "handoff")
        controlled = null; changed()
    }

    // ---- Continue where you left off ----

    /** Call when the app launches or comes to the foreground. */
    fun foreground() { foregroundAt = clock(); if (loaded) checkContinue(foregroundAt) }

    private fun checkContinue(now: Long) {
        if (host.isPlaying() || offer != null) return
        val latest = state.latest(receivedAt, now) ?: return
        val key = "${latest.device}:${latest.revision}"
        if (key == lastOffered || (receivedAt[latest.device] ?: 0) <= lastLocalChange || active(now)?.device == latest.device) return
        offer = latest; lastOffered = key; persist()
    }

    fun dismissOffer() { offer = null; changed() }

    // ---- Sending this device's playback ----

    /** The player changed (song, play/pause, seek, queue, source). [force]: send even if nothing played yet. */
    fun localChanged(force: Boolean = false) {
        if (force) forceSend = true
        if (state.devices.isEmpty()) return
        schedule.changed(clock()); wake.trySend(Unit)
    }

    /** The user started playback here (not a remote "play"): the other active device stops. */
    fun localStarted() {
        playedThisSession = true
        if (offer != null) { offer = null; changed() }
        if (clock() < obeyingUntil || host.inRoom()) return
        val other = active() ?: return
        control(other.device, "handoff")
    }

    private suspend fun sendLoop() {
        while (scope.isActive) {
            val at = schedule.dueAt
            if (at == null) { wake.receive(); continue }
            val wait = at - clock()
            if (wait > 0) { withTimeoutOrNull(wait) { wake.receive() }; continue }
            sendPlayback()
        }
    }

    /** Builds and sends this device's state to every linked device now. */
    fun sendPlayback() {
        val now = clock()
        val local = if (state.devices.isEmpty() || host.roomGuest()) null else host.snapshot()
        if (local == null) { schedule.stop(); return }
        if (local.playing) playedThisSession = true
        // Opening the app restores a paused queue; that isn't news for the other devices.
        if (!local.playing && !lastSentPlaying && !playedThisSession && !forceSend) { schedule.stop(); return }
        forceSend = false
        val playback = playbackState(local, now)
        schedule.sent(now, local.playing); lastSentPlaying = local.playing
        if (!playback.valid()) return
        lastRevision = playback.revision; lastLocalChange = now; persist()
        val recipients = state.devices.keys.toList()
        scope.launch { recipients.forEach { safely { link.send(SocialPacket("devicePlayback", playback.json()), "devicePlayback", it, 14L * 24 * 60 * 60_000) } } }
    }

    fun playbackState(local: LocalPlayback, now: Long = clock()) = DevicePlayback(
        me, name, host.platform, revision = maxOf(now, lastRevision + 1), observedAt = now, playing = local.playing,
        positionMs = local.positionMs.coerceIn(0, 86_400_000), speed = local.speed.coerceIn(0.25f, 4f),
        queue = local.queue, currentIndex = if (local.queue.isEmpty()) -1 else local.currentIndex, source = local.source?.take(200), spoken = local.spoken,
    )

    private suspend fun safely(action: suspend () -> Unit) { try { action() } catch (e: Exception) { if (e is CancellationException) throw e } }

    companion object {
        fun cleanName(value: String) = value.trim().replace(Regex("\\s+"), " ").take(60).ifEmpty { "Android" }
        fun newId() = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

        /**
         * The window of [queue] around [current] that's sent, resolving each item; items that don't
         * resolve are dropped. Empty when the current item doesn't resolve.
         */
        fun <T : Any> window(queue: List<Long>, current: Int, resolve: (Long) -> T?): Pair<List<T>, Int> {
            val (ids, index) = DevicePlayback.window(queue, current)
            if (index < 0) return emptyList<T>() to -1
            val resolved = ids.mapIndexedNotNull { i, id -> resolve(id)?.let { i to it } }
            val at = resolved.indexOfFirst { it.first == index }
            return if (at < 0) emptyList<T>() to -1 else resolved.map { it.second } to at
        }

        /** A [SharedTrack] that passes [SharedTrack.valid], from possibly untidy tags. */
        fun tidy(track: SharedTrack): SharedTrack = track.copy(
            title = track.title.trim().ifEmpty { "Unknown song" }.take(500), artist = track.artist.trim().ifEmpty { track.album.trim().ifEmpty { "Unknown artist" } }.take(500),
            album = track.album.take(500), durationMs = track.durationMs.coerceIn(0, 86_400_000), sourceID = track.sourceID?.takeIf { it.length in 1..30 && it.all { c -> c in '0'..'9' } },
            artwork = track.artwork?.takeIf(SocialRules::publicURL),
        )
    }
}
