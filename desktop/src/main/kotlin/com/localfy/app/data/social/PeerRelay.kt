package com.localfy.app.data.social

import com.localfy.app.desktop.AppPaths
import com.localfy.app.desktop.JsonStore
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.nostrdevkit.sdk.*
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The friend identity: one Nostr secret key, kept as hex in a file only this user can read
 * (POSIX 600 on macOS/Linux, an owner-only ACL on Windows). It is never logged or sent anywhere.
 */
object PeerIdentity {
    val defaultFile: File get() = AppPaths.data("peer_identity.key")

    @Synchronized fun load(file: File = defaultFile): Keys {
        if (file.isFile) {
            restrict(file.toPath())
            val secret = file.readText().trim()
            require(Regex("[0-9a-f]{64}").matches(secret)) { "Your friend identity could not be opened on this computer." }
            return Keys.parse(secret)
        }
        val keys = Keys.generate()
        write(file, keys.secretKey().toHex())
        return keys
    }

    @Synchronized fun restore(secret: String, file: File = defaultFile) {
        require(Regex("[0-9a-f]{64}").matches(secret))
        write(file, Keys.parse(secret).secretKey().toHex())
    }

    private fun write(file: File, secret: String) {
        val dir = file.absoluteFile.parentFile.also { it.mkdirs() }.toPath()
        val posix = runCatching { Files.getFileStore(dir).supportsFileAttributeView("posix") }.getOrDefault(false)
        val tmp = if (posix) Files.createTempFile(dir, "identity", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            else Files.createTempFile(dir, "identity", ".tmp").also(::restrict)
        try {
            Files.write(tmp, secret.toByteArray(Charsets.US_ASCII))
            runCatching { Files.move(tmp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                .getOrElse { Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            restrict(file.toPath())
        } finally { Files.deleteIfExists(tmp) }
        check(file.isFile) { "Your friend identity could not be saved." }
    }

    /** Owner read/write only, where the file system supports it. */
    private fun restrict(path: java.nio.file.Path) {
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); return }
        runCatching {
            val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return
            val entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.owner)
                .setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA, AclEntryPermission.READ_ATTRIBUTES,
                    AclEntryPermission.WRITE_ATTRIBUTES, AclEntryPermission.READ_ACL, AclEntryPermission.WRITE_ACL, AclEntryPermission.DELETE, AclEntryPermission.SYNCHRONIZE, AclEntryPermission.READ_NAMED_ATTRS, AclEntryPermission.WRITE_NAMED_ATTRS)
                .build()
            view.acl = listOf(entry)
        }
    }
}

data class SocialPacket(val type: String, val body: JSONObject, val v: Int = 1) {
    fun json() = JSONObject().put("v", v).put("type", type).put("body", Base64.getEncoder().encodeToString(body.toString().toByteArray()))
    companion object {
        fun parse(o: JSONObject): SocialPacket {
            val bytes = Base64.getDecoder().decode(o.getString("body")); require(bytes.size <= 1_000_000)
            return SocialPacket(o.getString("type"), JSONObject(String(bytes, Charsets.UTF_8)), o.getInt("v"))
        }
    }
}

/**
 * Nostr transport, wire-compatible with the Android and iPhone apps: kind 30078 parameterised
 * events tagged `t=spitify`, `d=spitify:v1:…`, NIP-40 expiration, private packets NIP-44
 * encrypted to one `p` recipient, large packets split into `part` chunks. The Rust nostr SDK
 * (same build as Android) signs, verifies and encrypts; only public keys leave the computer.
 *
 * All relay state lives on one private worker thread, so nothing here blocks the caller's
 * (UI) thread. [onPacket] and [onStatus] are delivered on [scope]'s dispatcher.
 */
class PeerRelay(
    val keys: Keys,
    private val scope: CoroutineScope,
    storage: String = "peer_outbox",
    private val testMode: Boolean = false,
    private val socketFactory: RelaySocketFactory = JdkRelaySockets(),
    outboxFile: File = AppPaths.data("$storage.json"),
) {
    val publicKey: String = keys.publicKey().toHex()
    var onPacket: ((String, SocialPacket, Boolean) -> Unit)? = null
    var onStatus: ((Int, Int) -> Unit)? = null

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "spitify-relay").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val relayScope = CoroutineScope(SupervisorJob() + worker + CoroutineExceptionHandler { _, _ -> })
    private val store = JsonStore(outboxFile)

    private data class Outgoing(val id: String, val json: String, val logical: String, val expiresAt: Long) {
        fun json() = JSONObject().put("id", id).put("json", json).put("logical", logical).put("expiresAt", expiresAt)
    }
    private val outgoing = runCatching { val a = store.readArray() ?: JSONArray(); (0 until a.length()).map { val o = a.getJSONObject(it); Outgoing(o.getString("id"), o.getString("json"), o.getString("logical"), o.getLong("expiresAt")) }.toMutableList() }.getOrDefault(mutableListOf())
    @Volatile private var pendingSnapshot = outgoing.size
    val pendingCount get() = pendingSnapshot
    @Volatile private var connectedSnapshot = 0
    val connectedCount get() = connectedSnapshot

    private val sockets = mutableMapOf<String, RelaySocket>()
    private val connected = mutableSetOf<String>()
    private val retry = mutableMapOf<String, Job>()
    private val delays = mutableMapOf<String, Long>()
    private var keepAlive: Job? = null
    private var wanted = emptySet<String>()
    private var known = emptySet<String>()
    private val requestedPlaylists = linkedMapOf<String, SocialLink>()
    private var discover = false
    /** Devices linked while friend sharing is off: only our own encrypted inbox, nothing else. */
    private var inboxOnly = false
    /** One-off "#t" lookups (pairing codes) still open: tag → subscription id. */
    private val lookups = linkedMapOf<String, String>()
    private var lookupCount = 0
    private var enabled = false
    private val seen = linkedSetOf<String>()
    private val stamps = mutableMapOf<String, Long>()
    private data class Assembly(val author: String, val encrypted: Boolean, val firstAt: Long, val total: Int, val digest: String, val parts: MutableMap<Int, ByteArray> = mutableMapOf())
    private val assemblies = mutableMapOf<String, Assembly>()

    /**
     * Connects to [relays]. [inboxOnly] subscribes to nothing but packets encrypted to us (linked
     * devices while friend sharing is off); [authors] and [discover] are then ignored.
     */
    fun start(relays: List<String>, authors: Set<String>, discover: Boolean = false, inboxOnly: Boolean = false) { relayScope.launch {
        known = if (inboxOnly) emptySet() else authors.filter(SocialRules::key).toSet(); this@PeerRelay.discover = discover && !inboxOnly; this@PeerRelay.inboxOnly = inboxOnly; enabled = true
        wanted = relays.filter { runCatching { val u = URI(it); u.userInfo == null && ((u.scheme == "wss" && u.host.orEmpty().contains('.')) || testMode && u.scheme == "ws" && u.host in listOf("127.0.0.1", "localhost")) }.getOrDefault(false) }.take(4).toSet()
        sockets.keys.toList().filter { it !in wanted }.forEach { sockets.remove(it)?.close(1000, "Settings changed"); connected.remove(it); retry.remove(it)?.cancel() }
        wanted.forEach { url -> sockets[url]?.let(::subscribe) ?: connect(url) }
        if (keepAlive?.isActive != true) keepAlive = relayScope.launch { while (isActive) { delay(25_000); sockets.values.toList().forEach { it.ping() } } }
        report()
    } }

    fun stop() { relayScope.launch {
        enabled = false; keepAlive?.cancel(); keepAlive = null
        retry.values.forEach { it.cancel() }; retry.clear(); sockets.values.forEach { it.close(1000, "Closed") }; sockets.clear(); connected.clear(); report()
    } }

    /** Stops for good (app exit). */
    fun close() { stop(); relayScope.launch { store.flush(); relayScope.cancel(); worker.close() } }

    /** Waits until everything queued on the relay thread so far has run (tests, shutdown). */
    suspend fun idle() { withContext(worker) { yield() } }

    private fun connect(url: String) {
        if (!enabled || url !in wanted || sockets.containsKey(url)) return
        val listener = object : RelaySocketListener {
            override fun onOpen(socket: RelaySocket) { relayScope.launch { if (sockets[url] !== socket || !enabled) { socket.close(1000, "Closed"); return@launch }; connected += url; delays[url] = 1000; subscribe(socket); flush(socket); report() } }
            override fun onMessage(socket: RelaySocket, text: String) { if (text.length <= 128 * 1024) relayScope.launch { receiveNow(text) } }
            override fun onClosed(socket: RelaySocket) { relayScope.launch { disconnected(url, socket) } }
            override fun onFailure(socket: RelaySocket, error: Throwable) { relayScope.launch { disconnected(url, socket) } }
        }
        val socket = runCatching { socketFactory.open(url, listener) }.getOrNull()
        if (socket == null) { scheduleRetry(url); return }
        sockets[url] = socket
    }
    private fun disconnected(url: String, socket: RelaySocket) {
        if (sockets[url] !== socket) return
        sockets.remove(url); connected.remove(url); report()
        scheduleRetry(url)
    }
    private fun scheduleRetry(url: String) {
        if (!enabled || url !in wanted) return
        val wait = delays[url] ?: 1000; delays[url] = (wait * 2).coerceAtMost(60_000)
        retry[url]?.cancel(); retry[url] = relayScope.launch { delay(wait); connect(url) }
    }
    fun requestPlaylist(link: SocialLink) {
        if (link.type != "playlist" || link.id == null) return
        relayScope.launch {
            if (requestedPlaylists.size >= 32) requestedPlaylists.clear()
            requestedPlaylists["${link.owner}:${link.id}"] = link
            sockets.values.forEach(::subscribe)
        }
    }
    /**
     * Asks every connected relay, once, for up to 5 events tagged `["t", tag]` (a pairing code's
     * offer); they arrive through [onPacket] like any other. The request is closed after 15 s.
     * Only while a lookup is open are public "deviceCode" packets delivered at all.
     */
    fun lookup(tag: String) {
        require(tag.length in 1..100) { "That code is not valid." }
        relayScope.launch {
            lookups.remove(tag)?.let { old -> closeLookup(old) }
            val id = "spitify-lookup-${++lookupCount}"
            lookups[tag] = id
            sockets.filterKeys { it in connected }.values.forEach { requestLookup(it, tag, id) }
            relayScope.launch { delay(LOOKUP_TIMEOUT); if (lookups[tag] == id) { lookups.remove(tag); closeLookup(id) } }
        }
    }
    private fun requestLookup(socket: RelaySocket, tag: String, id: String) {
        socket.send(JSONArray().put("REQ").put(id).put(JSONObject().put("kinds", JSONArray(listOf(30078))).put("#t", JSONArray(listOf(tag))).put("limit", 5)).toString())
    }
    private fun closeLookup(id: String) { sockets.filterKeys { it in connected }.values.forEach { it.send(JSONArray().put("CLOSE").put(id).toString()) } }

    private fun subscribe(socket: RelaySocket) {
        lookups.forEach { (tag, id) -> requestLookup(socket, tag, id) }
        if (inboxOnly) {
            socket.send(JSONArray().put("REQ").put("spitify-v1").put(JSONObject().put("kinds", JSONArray(listOf(30078))).put("#p", JSONArray(listOf(publicKey))).put("#t", JSONArray(listOf("spitify"))).put("limit", 500)).toString())
            return
        }
        val req = JSONArray().put("REQ").put("spitify-v1")
            .put(JSONObject().put("kinds", JSONArray(listOf(30078))).put("authors", JSONArray((known + publicKey).sorted().take(129))).put("#d", JSONArray(listOf("spitify:v1:profile"))).put("limit", 129))
            .put(JSONObject().put("kinds", JSONArray(listOf(30078))).put("authors", JSONArray((known + publicKey).sorted().take(129))).put("#t", JSONArray(listOf("spitify"))).put("limit", 500))
            .put(JSONObject().put("kinds", JSONArray(listOf(30078))).put("#p", JSONArray(listOf(publicKey))).put("#t", JSONArray(listOf("spitify"))).put("limit", 500))
        requestedPlaylists.values.forEach { link ->
            val identifiers = (0 until 120).flatMap { listOf("spitify:v1:playlist:${link.id}:public:$it", "spitify:v1:playlist:${link.id}:$publicKey:$it") }
            req.put(JSONObject().put("kinds", JSONArray(listOf(30078))).put("authors", JSONArray(listOf(link.owner))).put("#d", JSONArray(identifiers)).put("limit", 240))
        }
        if (discover) req.put(JSONObject().put("kinds", JSONArray(listOf(30078))).put("#d", JSONArray(listOf("spitify:v1:profile"))).put("limit", 150))
        socket.send(req.toString())
    }
    private suspend fun flush(socket: RelaySocket) {
        outgoing.removeAll { it.expiresAt < SocialRules.now }; persist()
        for (event in outgoing.toList()) { if (sockets.values.none { it === socket }) return; socket.send("[\"EVENT\",${event.json}]"); delay(40) }
    }

    /**
     * Signs (and for a [recipient], encrypts) [packet] and queues it until a relay accepts it. Errors are user-readable.
     * [extraTags] are appended to the event's tags (a pairing code's `["t", lookupTag]`).
     */
    suspend fun send(packet: SocialPacket, logical: String, recipient: String? = null, expiresIn: Long = 30L * 24 * 60 * 60 * 1000, extraTags: List<List<String>> = emptyList()) = withContext(worker) {
        require(extraTags.size <= 4 && extraTags.all { it.size in 2..4 && it[0] !in listOf("d", "p", "expiration", "encrypted") }) { "This share could not be prepared." }
        require(packet.v == 1 && logical.length <= 220 && (recipient == null || SocialRules.key(recipient))) { "That friend code is not valid." }
        val data = packet.json().toString().toByteArray(); require(data.size <= 1_000_000) { "This share is too large. Try a smaller playlist." }
        val transfer = UUID.randomUUID().toString(); val digest = SocialRules.hash(data)
        val chunkSize = if (logical == "profile") 48000 else 9000
        require(logical != "profile" || data.size <= chunkSize) { "Your profile photo is too large." }
        val chunks = data.toList().chunked(chunkSize).map { it.toByteArray() }
        val prefix = "$logical:${recipient ?: "public"}"
        check(outgoing.count { it.logical != prefix } + chunks.size <= 1000) { "There are many shares waiting to send. Connect before adding more." }
        val created = maxOf(SocialRules.now / 1000, (stamps[prefix] ?: 0) + 1)
        check(created <= SocialRules.now / 1000 + 120) { "Please wait a moment before sharing again." }
        val target = recipient?.let { PublicKey.parse(it) }
        val prepared = mutableListOf<Outgoing>()
        chunks.forEachIndexed { index, bytes ->
            val contentPacket = if (chunks.size == 1) packet else SocialPacket("part", JSONObject().put("transfer", transfer).put("index", index).put("total", chunks.size).put("digest", digest).put("content", Base64.getEncoder().encodeToString(bytes)))
            val raw = contentPacket.json().toString()
            val content = if (target == null) raw else keys.nip44Encrypt(target, raw)
            val identifier = if (logical == "profile" && recipient == null) "spitify:v1:profile" else "spitify:v1:$prefix:$index"
            val tags = mutableListOf(listOf("d", identifier), listOf("t", "spitify"), listOf("expiration", ((SocialRules.now + expiresIn) / 1000).toString()))
            if (recipient != null) { tags += listOf("p", recipient); tags += listOf("encrypted", "nip44") }
            tags += extraTags
            val event = EventBuilder(Kind(30078u), content).tags(tags.map(Tag::parse)).customCreatedAt(Timestamp.fromSecs(created.toULong())).finalize(keys)
            prepared += Outgoing(event.id().toHex(), event.asJson(), prefix, SocialRules.now + expiresIn)
        }
        outgoing.removeAll { it.logical == prefix }; outgoing += prepared; stamps[prefix] = created
        persist(); sockets.values.toList().forEach { flush(it) }
    }

    /** Handles one raw relay message (any thread). Bad input is ignored. */
    fun receive(raw: String) { relayScope.launch { receiveNow(raw) } }

    private fun receiveNow(raw: String) { runCatching {
        if (raw.toByteArray().size > 128 * 1024) return
        val values = JSONArray(raw); val type = values.optString(0)
        if (type == "OK" && values.length() >= 3 && values.optBoolean(2)) { val id = values.getString(1); if (outgoing.removeAll { it.id == id }) persist(); return }
        if (type != "EVENT" || values.length() < 3) return
        val obj = values.getJSONObject(2); val event = Event.fromJson(obj.toString())
        if (!event.verify() || obj.getInt("kind") != 30078 || obj.getLong("created_at") > SocialRules.now / 1000 + 300) return
        val tags = obj.getJSONArray("tags").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { b -> (0 until b.length()).map { b.getString(it) } } } }
        if (listOf("t", "spitify") !in tags || tags.none { it.size > 1 && it[0] == "d" && it[1].startsWith("spitify:v1:") }) return
        if (tags.firstOrNull { it.size > 1 && it[0] == "expiration" }?.get(1)?.toLongOrNull()?.let { it < SocialRules.now / 1000 } == true) return
        val id = event.id().toHex(); if (id in seen) return
        val encrypted = listOf("encrypted", "nip44") in tags
        val recipients = tags.filter { it.size > 1 && it[0] == "p" }.map { it[1] }
        if (if (encrypted) recipients != listOf(publicKey) else recipients.isNotEmpty()) return
        val author = event.author().toHex()
        val content = if (encrypted) keys.nip44Decrypt(event.author(), event.content()) else event.content()
        if (content.toByteArray().size > 48_000) return
        val packet = SocialPacket.parse(JSONObject(content)); if (packet.v != 1 || packet.body.toString().toByteArray().size > 36_000) return
        // A pairing code's public offer only matters to someone who just typed that code.
        if (packet.type == "deviceCode" && (encrypted || lookups.keys.none { listOf("t", it) in tags })) return
        seen += id; if (seen.size > 4000) seen.remove(seen.first())
        if (packet.type != "part") { deliver(author, packet, encrypted); return }
        val part = packet.body; val transfer = part.getString("transfer"); val total = part.getInt("total"); val index = part.getInt("index"); val bytes = Base64.getDecoder().decode(part.getString("content")); val digest = part.getString("digest")
        if (transfer.length > 100 || total !in 2..120 || index !in 0 until total || bytes.size > 9000) return
        assemblies.entries.removeAll { SocialRules.now - it.value.firstAt > 600_000 }
        val key = "$author:$transfer"
        if (key !in assemblies && assemblies.size >= 24) return
        val assembly = assemblies.getOrPut(key) { Assembly(author, encrypted, SocialRules.now, total, digest) }
        if (assembly.total != total || assembly.digest != digest || assembly.encrypted != encrypted) return
        assembly.parts[index] = bytes
        if (assembly.parts.size == total) {
            val whole = java.io.ByteArrayOutputStream().also { output -> repeat(total) { output.write(assembly.parts.getValue(it)) } }.toByteArray()
            assemblies.remove(key)
            if (whole.size > 1_000_000 || SocialRules.hash(whole) != digest) return
            val joined = SocialPacket.parse(JSONObject(String(whole, Charsets.UTF_8)))
            if (joined.v == 1 && joined.type != "part") deliver(author, joined, encrypted)
        }
    } }

    private fun deliver(author: String, packet: SocialPacket, encrypted: Boolean) { scope.launch { runCatching { onPacket?.invoke(author, packet, encrypted) } } }
    private fun persist() { val text = JSONArray(outgoing.map { it.json() }).toString(); store.save { text }; report() }
    private fun report() {
        pendingSnapshot = outgoing.size; connectedSnapshot = connected.size
        val count = connected.size; val waiting = outgoing.size
        scope.launch { runCatching { onStatus?.invoke(count, waiting) } }
    }
    companion object {
        val DEFAULTS = listOf("wss://relay.damus.io", "wss://nos.lol")
        const val LOOKUP_TIMEOUT = 15_000L
    }
}
