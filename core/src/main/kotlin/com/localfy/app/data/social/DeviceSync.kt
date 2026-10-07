package com.localfy.app.data.social

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Your own devices, linked once with a short code, share what they're playing ("Playing on
 * Pixel 9") and can continue each other's playback. Each device keeps its own friend key; the
 * packets travel NIP-44 encrypted to each linked device over the same relays as friend shares.
 * The wire format is mirrored by ios/Spitify/Social/DeviceSync.swift (docs/device-sync.md).
 */
data class LinkedDevice(val id: String, val name: String, val platform: String, val linkedAt: Long = SocialRules.now) {
    fun valid() = SocialRules.key(id) && name.trim().length in 1..60 && platform in PLATFORMS
    fun json() = JSONObject().put("id", id).put("name", name).put("platform", platform).put("linkedAt", linkedAt)
    companion object {
        val PLATFORMS = listOf("android", "ios", "macos", "windows", "linux")
        fun parse(o: JSONObject) = LinkedDevice(o.getString("id"), o.getString("name"), o.getString("platform"), o.optLong("linkedAt", SocialRules.now))
    }
}

/** What a device is playing: the queue around the current song and where it is. Packet type "devicePlayback". */
data class DevicePlayback(
    val device: String,
    val name: String,
    val platform: String,
    /** Increases with every change; also the sender's clock at the change. */
    val revision: Long,
    /** When [positionMs] was read (sender's clock). */
    val observedAt: Long,
    val playing: Boolean,
    val positionMs: Long,
    val speed: Float = 1f,
    /** Up to [MAX_BEFORE] songs before the current one, the current one, then up to [MAX_AFTER] after. */
    val queue: List<SharedTrack> = emptyList(),
    val currentIndex: Int = -1,
    /** "Playing from" label, e.g. "Liked Songs". */
    val source: String? = null,
    /** Episodes and audiobooks: the phone's resume key; other devices only show these. */
    val spoken: Boolean = false,
) {
    val current: SharedTrack? get() = queue.getOrNull(currentIndex)
    fun valid() = SocialRules.key(device) && name.length in 1..60 && platform in LinkedDevice.PLATFORMS && revision > 0 && observedAt > 0 &&
        positionMs in 0..86_400_000 && speed in 0.25f..4f && queue.size <= MAX_BEFORE + 1 + MAX_AFTER && queue.all { it.valid() } &&
        (queue.isEmpty() && currentIndex == -1 || currentIndex in queue.indices) && (source?.length ?: 0) <= 200
    fun json() = JSONObject().put("device", device).put("name", name).put("platform", platform).put("revision", revision).put("observedAt", observedAt)
        .put("playing", playing).put("positionMs", positionMs).put("speed", speed.toDouble()).put("queue", JSONArray(queue.map { it.json() }))
        .put("currentIndex", currentIndex).put("source", source).put("spoken", spoken)
    companion object {
        const val MAX_BEFORE = 10
        const val MAX_AFTER = 40
        fun parse(o: JSONObject) = DevicePlayback(
            o.getString("device"), o.getString("name"), o.getString("platform"), o.getLong("revision"), o.getLong("observedAt"),
            o.getBoolean("playing"), o.getLong("positionMs"), o.optDouble("speed", 1.0).toFloat(), o.objects("queue", SharedTrack::parse),
            o.optInt("currentIndex", -1), o.text("source"), o.optBoolean("spoken"),
        )
        /** Cuts [queue] to the window that's sent; returns the window and the current song's index in it. */
        fun <T> window(queue: List<T>, current: Int): Pair<List<T>, Int> {
            if (current !in queue.indices) return emptyList<T>() to -1
            val from = (current - MAX_BEFORE).coerceAtLeast(0)
            val to = (current + MAX_AFTER + 1).coerceAtMost(queue.size)
            return queue.subList(from, to) to current - from
        }
    }
}

/** A remote-control request to one linked device. Packet type "deviceCommand". */
data class DeviceCommand(val id: String, val target: String, val action: String, val positionMs: Long = 0, val createdAt: Long = SocialRules.now) {
    fun valid() = id.length in 8..64 && SocialRules.key(target) && action in ACTIONS && positionMs in 0..86_400_000
    fun json() = JSONObject().put("id", id).put("target", target).put("action", action).put("positionMs", positionMs).put("createdAt", createdAt)
    companion object {
        /** "handoff" = pause because playback moved to the sender. */
        val ACTIONS = listOf("play", "pause", "next", "previous", "seek", "handoff")
        fun parse(o: JSONObject) = DeviceCommand(o.getString("id"), o.getString("target"), o.getString("action"), o.optLong("positionMs"), o.getLong("createdAt"))
    }
}

/**
 * Pairing, step 2: device B, having found A through the code's lookup tag, asks A to link.
 * Packet type "deviceLinkRequest", encrypted to A. A shows "Link <name>?" before accepting.
 */
data class DeviceLinkRequest(val token: String, val name: String, val platform: String, val createdAt: Long = SocialRules.now) {
    fun valid() = token.length == DeviceSyncState.TOKEN_LENGTH && name.trim().length in 1..60 && platform in LinkedDevice.PLATFORMS
    fun json() = JSONObject().put("token", token).put("name", name).put("platform", platform).put("createdAt", createdAt)
    companion object { fun parse(o: JSONObject) = DeviceLinkRequest(o.getString("token"), o.getString("name"), o.getString("platform"), o.getLong("createdAt")) }
}

/**
 * Pairing, step 1: device A shows an 8-character code and publishes this public packet
 * ("deviceCode") tagged `["t", DeviceSyncState.lookupTag(code)]`, so B can find A's key from the
 * code alone. The tag is a hash; the code itself never leaves A except through the user.
 */
data class DeviceCodeOffer(val owner: String, val name: String, val platform: String, val createdAt: Long = SocialRules.now) {
    fun valid() = SocialRules.key(owner) && name.trim().length in 1..60 && platform in LinkedDevice.PLATFORMS
    fun json() = JSONObject().put("owner", owner).put("name", name).put("platform", platform).put("createdAt", createdAt)
    companion object { fun parse(o: JSONObject) = DeviceCodeOffer(o.getString("owner"), o.getString("name"), o.getString("platform"), o.getLong("createdAt")) }
}

/** The whole group as the sender sees it (including the sender). Packet type "deviceList". */
data class DeviceList(val devices: List<LinkedDevice>, val revision: Long = SocialRules.now) {
    fun valid() = devices.size in 1..DeviceSyncState.MAX_DEVICES && devices.all { it.valid() } && devices.map { it.id }.distinct().size == devices.size
    fun json() = JSONObject().put("devices", JSONArray(devices.map { it.json() })).put("revision", revision)
    companion object { fun parse(o: JSONObject) = DeviceList(o.objects("devices", LinkedDevice::parse), o.optLong("revision", SocialRules.now)) }
}

/**
 * The rules, shared by Android and desktop (iOS mirrors them): which packets to trust, which
 * remote playback to show, and whether to offer "continue where you left off".
 */
class DeviceSyncState(val me: String) {
    val devices = linkedMapOf<String, LinkedDevice>()
    val playback = mutableMapOf<String, DevicePlayback>()
    private val handledCommands = ArrayDeque<String>()
    private var issued: Pair<String, Long>? = null
    /** Requests that matched our code, waiting for the user to allow them (author → request). */
    val pendingLinks = linkedMapOf<String, DeviceLinkRequest>()

    /** A new one-time code (8 characters, no look-alikes); replaces any earlier one. */
    fun newCode(now: Long = SocialRules.now): String {
        val random = SecureRandom()
        val token = (1..TOKEN_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        issued = token to now
        pendingLinks.clear()
        return token
    }

    /** The code currently shown, or null when none or it expired. */
    fun currentCode(now: Long = SocialRules.now): String? = issued?.takeIf { now - it.second <= CODE_LIFETIME }?.first

    /** A pairing request from [author]; true when it matched our live code. The user still has to [approve] it. */
    fun receiveLink(request: DeviceLinkRequest, author: String, encrypted: Boolean, now: Long = SocialRules.now): Boolean {
        val token = currentCode(now) ?: return false
        if (!encrypted || !request.valid() || author == me || !SocialRules.key(author) || normalizeCode(request.token) != token) return false
        if (author in devices || pendingLinks.size >= 4) return false
        pendingLinks[author] = request.copy(name = request.name.trim())
        return true
    }

    /** The user allowed [author]: link it and use up the code. False when there's no such request or no room. */
    fun approve(author: String, now: Long = SocialRules.now): Boolean {
        val request = pendingLinks.remove(author) ?: return false
        if (devices.size + 1 >= MAX_DEVICES) return false
        devices[author] = LinkedDevice(author, request.name, request.platform, now)
        issued = null; pendingLinks.clear()
        return true
    }

    fun decline(author: String) { pendingLinks.remove(author) }

    /** The group as we see it, us included, for a "deviceList" packet. */
    fun list(myName: String, myPlatform: String) = DeviceList(listOf(LinkedDevice(me, myName, myPlatform)) + devices.values)

    /**
     * The group from an already-linked device, or from the device whose code we just entered
     * ([pendingOwner]). Adds everyone except us; removal only happens through [unlink].
     */
    fun acceptList(list: DeviceList, author: String, encrypted: Boolean, pendingOwner: String?): Boolean {
        if (!encrypted || !list.valid() || (author !in devices && author != pendingOwner) || list.devices.none { it.id == author } || list.devices.none { it.id == me }) return false
        var changed = false
        list.devices.filter { it.id != me }.forEach { d -> if (devices[d.id] != d && devices.size < MAX_DEVICES) { devices[d.id] = devices[d.id]?.copy(name = d.name, platform = d.platform) ?: d; changed = true } }
        return changed
    }

    fun unlink(id: String) { devices.remove(id); playback.remove(id) }

    /**
     * "deviceUnlink" {id} from a linked device: [id] left the group. If it's us, we were removed
     * and forget the whole group. Returns true when something changed.
     */
    fun acceptUnlink(id: String, author: String, encrypted: Boolean): Boolean {
        if (!encrypted || author !in devices || !SocialRules.key(id)) return false
        if (id == me) { devices.clear(); playback.clear(); return true }
        if (id !in devices) return false
        unlink(id); return true
    }

    fun acceptPlayback(state: DevicePlayback, author: String, encrypted: Boolean): Boolean {
        if (!encrypted || author == me || author !in devices || state.device != author || !state.valid()) return false
        if ((playback[author]?.revision ?: 0) >= state.revision) return false
        playback[author] = state
        devices[author]?.let { if (it.name != state.name) devices[author] = it.copy(name = state.name) }
        return true
    }

    fun acceptCommand(command: DeviceCommand, author: String, encrypted: Boolean, now: Long = SocialRules.now): Boolean {
        if (!encrypted || author == me || author !in devices || command.target != me || !command.valid() || command.id in handledCommands) return false
        if (command.createdAt < now - COMMAND_LIFETIME || command.createdAt > now + 60_000) return false
        handledCommands += command.id; if (handledCommands.size > 200) handledCommands.removeFirst()
        return true
    }

    /**
     * The device to show as "Playing on …": playing, heard from recently and with a song.
     * Clocks differ between devices, so freshness uses when we *received* the state ([receivedAt]).
     */
    fun active(receivedAt: Map<String, Long>, now: Long = SocialRules.now): DevicePlayback? =
        playback.values.filter { it.playing && it.current != null && now - (receivedAt[it.device] ?: 0) <= FRESH }
            .maxByOrNull { receivedAt[it.device] ?: 0 }

    /** The most recent state from any device within [RESUME_WINDOW], for "continue where you left off". */
    fun latest(receivedAt: Map<String, Long>, now: Long = SocialRules.now): DevicePlayback? =
        playback.values.filter { it.current != null && !it.spoken && now - (receivedAt[it.device] ?: 0) <= RESUME_WINDOW }
            .maxByOrNull { receivedAt[it.device] ?: 0 }

    fun json() = JSONObject().put("devices", JSONArray(devices.values.map { it.json() }))
        .put("playback", JSONArray(playback.values.map { it.json() }))

    fun load(o: JSONObject) {
        devices.clear(); playback.clear()
        o.objects("devices", LinkedDevice::parse).filter { it.valid() && it.id != me }.forEach { devices[it.id] = it }
        o.objects("playback", DevicePlayback::parse).filter { it.valid() && it.device in devices }.forEach { playback[it.device] = it }
    }

    companion object {
        const val TOKEN_LENGTH = 8
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        /** Upper-cases and drops spaces/dashes, so "k7qx-m2pa" matches "K7QXM2PA". */
        fun normalizeCode(text: String) = text.uppercase().filter { it.isLetterOrDigit() }

        /** Shown as "K7QX M2PA". */
        fun displayCode(code: String) = code.chunked(4).joinToString(" ")

        /** The relay tag a code's "deviceCode" offer is published under and looked up by. */
        fun lookupTag(code: String) = "spitify-link-" + SocialRules.hash(("spitify-device-link:" + normalizeCode(code)).toByteArray()).take(32)
        const val CODE_LIFETIME = 10 * 60_000L
        const val MAX_DEVICES = 10
        const val COMMAND_LIFETIME = 60_000L
        /** Playing devices send at least every [HEARTBEAT]; after [FRESH] without one they're not shown. */
        const val HEARTBEAT = 30_000L
        const val FRESH = 75_000L
        const val RESUME_WINDOW = 14L * 24 * 60 * 60_000
        /** Changes are sent after this quiet period (seeks and skips come in bursts). */
        const val DEBOUNCE = 1_500L

        /** Where the remote device is now, from its last state. Uses receive time to avoid clock skew. */
        fun expectedPosition(state: DevicePlayback, receivedAt: Long, now: Long = SocialRules.now): Long {
            val elapsed = if (state.playing) (now - receivedAt).coerceIn(0, 10 * 60_000) else 0
            val value = state.positionMs + (elapsed * state.speed).toLong()
            val duration = state.current?.durationMs ?: 0
            return if (duration > 0) value.coerceAtMost(duration) else value
        }
    }
}
