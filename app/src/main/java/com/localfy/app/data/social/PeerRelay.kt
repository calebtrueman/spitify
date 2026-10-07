package com.localfy.app.data.social

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.nostrdevkit.sdk.*
import java.net.URI
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object PeerIdentity {
    fun load(context: Context): Keys {
        val prefs = context.getSharedPreferences("peer_identity", Context.MODE_PRIVATE)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "spitify.peer.identity"
        val stored = prefs.getString("key", null)
        if (stored != null) {
            val key = store.getKey(alias, null) as? SecretKey ?: error("Your friend identity could not be opened on this device.")
            val bytes = Base64.getDecoder().decode(stored)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return Keys.parse(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        }
        val keys = Keys.generate()
        store(context, keys)
        return keys
    }
    fun restore(context: Context, secret: String) {
        require(Regex("[0-9a-f]{64}").matches(secret))
        store(context, Keys.parse(secret))
    }
    private fun store(context: Context, keys: Keys) {
        val prefs = context.getSharedPreferences("peer_identity", Context.MODE_PRIVATE)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "spitify.peer.identity"
        if (!store.containsAlias(alias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, store.getKey(alias, null))
        check(prefs.edit().putString("key", Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(keys.secretKey().toHex().toByteArray()))).commit()) { "Your friend identity could not be saved." }
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

/** The SDK signs and verifies the standard Nostr envelope. Only public keys leave the device. */
class PeerRelay(private val context: Context, val keys: Keys, private val scope: CoroutineScope, storage: String = "peer_outbox", private val testMode: Boolean = false) {
    val publicKey: String get() = keys.publicKey().toHex()
    var onPacket: ((String, SocialPacket, Boolean) -> Unit)? = null
    var onStatus: ((Int, Int) -> Unit)? = null
    private val prefs = context.getSharedPreferences(storage, Context.MODE_PRIVATE)
    private data class Outgoing(val id: String, val json: String, val logical: String, val expiresAt: Long) {
        fun json() = JSONObject().put("id", id).put("json", json).put("logical", logical).put("expiresAt", expiresAt)
    }
    private val outgoing = runCatching { val a = JSONArray(prefs.getString("events", "[]")); (0 until a.length()).map { val o = a.getJSONObject(it); Outgoing(o.getString("id"), o.getString("json"), o.getString("logical"), o.getLong("expiresAt")) }.toMutableList() }.getOrDefault(mutableListOf())
    val pendingCount get() = outgoing.size
    private val client = OkHttpClient.Builder().pingInterval(25, java.util.concurrent.TimeUnit.SECONDS).build()
    private val sockets = mutableMapOf<String, WebSocket>()
    private val connected = mutableSetOf<String>()
    private val retry = mutableMapOf<String, Job>()
    private val delays = mutableMapOf<String, Long>()
    private var wanted = emptySet<String>()
    private var known = emptySet<String>()
    private val requestedPlaylists = linkedMapOf<String, SocialLink>()
    private var discover = false
    private var inboxOnly = false
    private var enabled = false
    private class Lookup(val tag: String, val found: MutableList<Pair<String, SocialPacket>> = mutableListOf(), var finished: Int = 0)
    private val lookups = mutableMapOf<String, Lookup>()
    private var lookupCount = 0
    private val seen = linkedSetOf<String>()
    private val stamps = mutableMapOf<String, Long>()
    private data class Assembly(val author: String, val encrypted: Boolean, val firstAt: Long, val total: Int, val digest: String, val parts: MutableMap<Int, ByteArray> = mutableMapOf())
    private val assemblies = mutableMapOf<String, Assembly>()

    /** [inboxOnly]: only this device's encrypted inbox (linked devices while friend sharing is off). */
    fun start(relays: List<String>, authors: Set<String>, discover: Boolean = false, inboxOnly: Boolean = false) {
        known = authors.filter(SocialRules::key).toSet(); this.discover = discover && !inboxOnly; this.inboxOnly = inboxOnly; enabled = true
        wanted = relays.filter { runCatching { val u = URI(it); u.userInfo == null && ((u.scheme == "wss" && u.host.orEmpty().contains('.')) || testMode && u.scheme == "ws" && u.host in listOf("127.0.0.1", "localhost", "10.0.2.2")) }.getOrDefault(false) }.take(4).toSet()
        sockets.keys.toList().filter { it !in wanted }.forEach { sockets.remove(it)?.close(1000, "Settings changed"); connected.remove(it); retry.remove(it)?.cancel() }
        wanted.forEach { url -> sockets[url]?.let(::subscribe) ?: connect(url) }; report()
    }
    fun stop() { enabled = false; retry.values.forEach { it.cancel() }; retry.clear(); sockets.values.forEach { it.close(1000, "Closed") }; sockets.clear(); connected.clear(); report() }
    private fun connect(url: String) {
        if (!enabled || url !in wanted || sockets.containsKey(url)) return
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { scope.launch { if (sockets[url] !== webSocket || !enabled) { webSocket.close(1000, "Closed"); return@launch }; connected += url; delays[url] = 1000; subscribe(webSocket); flush(webSocket); report() } }
            override fun onMessage(webSocket: WebSocket, text: String) { if (text.length <= 128 * 1024) scope.launch { receive(text) } }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (bytes.size <= 128 * 1024) onMessage(webSocket, bytes.utf8()) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { scope.launch { disconnected(url, webSocket) } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { scope.launch { disconnected(url, webSocket) } }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        }
        sockets[url] = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }
    private fun disconnected(url: String, socket: WebSocket) {
        if (sockets[url] !== socket) return
        sockets.remove(url); connected.remove(url); report()
        if (!enabled || url !in wanted) return
        val wait = delays[url] ?: 1000; delays[url] = (wait * 2).coerceAtMost(60_000)
        retry[url]?.cancel(); retry[url] = scope.launch { delay(wait); connect(url) }
    }
    fun requestPlaylist(link: SocialLink) {
        if (link.type != "playlist" || link.id == null) return
        if (requestedPlaylists.size >= 32) requestedPlaylists.clear()
        requestedPlaylists["${link.owner}:${link.id}"] = link
        sockets.values.forEach(::subscribe)
    }
    /**
     * One-off search for public events tagged ["t", tag] (a device-link code). Matching packets also
     * go through [onPacket]; they're returned too, so the caller knows which tag found them.
     */
    suspend fun lookup(tag: String, timeoutMs: Long = 15_000): List<Pair<String, SocialPacket>> {
        require(tag.length in 1..100)
        val id = "spitify-lookup-${++lookupCount}"; val lookup = Lookup(tag); lookups[id] = lookup
        try {
            connectedSockets().forEach { request(it, id, tag) }
            withTimeoutOrNull(timeoutMs) { while (lookup.found.isEmpty() || lookup.finished < connected.size.coerceAtLeast(1)) delay(100) }
            return lookup.found.toList()
        } finally { lookups.remove(id); connectedSockets().forEach { it.send(JSONArray().put("CLOSE").put(id).toString()) } }
    }
    private fun connectedSockets() = sockets.filterKeys { it in connected }.values.toList()
    private fun request(socket: WebSocket, id: String, tag: String) =
        socket.send(JSONArray().put("REQ").put(id).put(JSONObject().put("kinds", JSONArray(listOf(30078))).put("#t", JSONArray(listOf(tag))).put("limit", 5)).toString())
    private fun subscribe(socket: WebSocket) {
        lookups.forEach { (id, lookup) -> request(socket, id, lookup.tag) }
        if (inboxOnly) { socket.send(JSONArray().put("REQ").put("spitify-v1").put(JSONObject().put("kinds", JSONArray(listOf(30078))).put("#p", JSONArray(listOf(publicKey))).put("#t", JSONArray(listOf("spitify"))).put("limit", 500)).toString()); return }
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
    private suspend fun flush(socket: WebSocket) {
        outgoing.removeAll { it.expiresAt < SocialRules.now }; persist()
        outgoing.toList().forEach { socket.send("[\"EVENT\",${it.json}]"); delay(40) }
    }
    /** Relay state stays on the main thread; encrypting and signing run in the background. */
    suspend fun send(packet: SocialPacket, logical: String, recipient: String? = null, expiresIn: Long = 30L * 24 * 60 * 60 * 1000, extraTags: List<List<String>> = emptyList()) = withContext(Dispatchers.Main.immediate) {
        require(extraTags.size <= 4 && extraTags.all { it.size in 2..3 && it[0] != "d" && it[0] != "p" && it.all { v -> v.length <= 100 } })
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
        stamps[prefix] = created
        val prepared = withContext(Dispatchers.Default) { chunks.mapIndexed { index, bytes ->
            val contentPacket = if (chunks.size == 1) packet else SocialPacket("part", JSONObject().put("transfer", transfer).put("index", index).put("total", chunks.size).put("digest", digest).put("content", Base64.getEncoder().encodeToString(bytes)))
            val raw = contentPacket.json().toString()
            val content = if (recipient == null) raw else keys.nip44Encrypt(PublicKey.parse(recipient), raw)
            val identifier = if (logical == "profile" && recipient == null) "spitify:v1:profile" else "spitify:v1:$prefix:$index"
            val tags = mutableListOf(listOf("d", identifier), listOf("t", "spitify"), listOf("expiration", ((SocialRules.now + expiresIn) / 1000).toString()))
            if (recipient != null) { tags += listOf("p", recipient); tags += listOf("encrypted", "nip44") }
            tags += extraTags
            val event = EventBuilder(Kind(30078u), content).tags(tags.map(Tag::parse)).customCreatedAt(Timestamp.fromSecs(created.toULong())).finalize(keys)
            Outgoing(event.id().toHex(), event.asJson(), prefix, SocialRules.now + expiresIn)
        } }
        if ((stamps[prefix] ?: 0) != created) return@withContext
        outgoing.removeAll { it.logical == prefix }; outgoing += prepared
        persist(); sockets.values.toList().forEach { flush(it) }
    }
    fun receive(raw: String) { runCatching {
        if (raw.toByteArray().size > 128 * 1024) return
        val values = JSONArray(raw); val type = values.optString(0)
        if (type == "OK" && values.length() >= 3 && values.optBoolean(2)) { val id = values.getString(1); if (outgoing.removeAll { it.id == id }) persist(); return }
        if (type == "EOSE" || type == "CLOSED") { lookups[values.optString(1)]?.let { it.finished++ }; return }
        if (type != "EVENT" || values.length() < 3) return
        val obj = values.getJSONObject(2); val event = Event.fromJson(obj.toString())
        if (!event.verify() || obj.getInt("kind") != 30078 || obj.getLong("created_at") > SocialRules.now / 1000 + 300) return
        val tags = obj.getJSONArray("tags").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { b -> (0 until b.length()).map { b.getString(it) } } } }
        if (listOf("t", "spitify") !in tags || tags.none { it.size > 1 && it[0] == "d" && it[1].startsWith("spitify:v1:") }) return
        if (tags.firstOrNull { it.size > 1 && it[0] == "expiration" }?.get(1)?.toLongOrNull()?.let { it < SocialRules.now / 1000 } == true) return
        val encrypted = listOf("encrypted", "nip44") in tags
        lookups[values.optString(1)]?.let { lookup ->
            if (!encrypted && listOf("t", lookup.tag) in tags && tags.none { it.size > 1 && it[0] == "p" } && lookup.found.size < 20 && event.content().toByteArray().size <= 48_000)
                runCatching { SocialPacket.parse(JSONObject(event.content())) }.getOrNull()?.takeIf { it.v == 1 && it.type != "part" }?.let { lookup.found += event.author().toHex() to it }
        }
        val id = event.id().toHex(); if (id in seen) return
        val recipients = tags.filter { it.size > 1 && it[0] == "p" }.map { it[1] }
        if (if (encrypted) recipients != listOf(publicKey) else recipients.isNotEmpty()) return
        val author = event.author().toHex()
        val content = if (encrypted) keys.nip44Decrypt(event.author(), event.content()) else event.content()
        if (content.toByteArray().size > 48_000) return
        val packet = SocialPacket.parse(JSONObject(content)); if (packet.v != 1 || packet.body.toString().toByteArray().size > 36_000) return
        seen += id; if (seen.size > 4000) seen.remove(seen.first())
        if (packet.type != "part") { onPacket?.invoke(author, packet, encrypted); return }
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
            if (joined.v == 1 && joined.type != "part") onPacket?.invoke(author, joined, encrypted)
        }
    } }
    // apply(): the in-memory copy updates now and the disk write happens off the main thread.
    private fun persist() { prefs.edit().putString("events", JSONArray(outgoing.map { it.json() }).toString()).apply(); report() }
    private fun report() { onStatus?.invoke(connected.size, outgoing.size) }
    companion object { val DEFAULTS = listOf("wss://relay.damus.io", "wss://nos.lol") }
}
