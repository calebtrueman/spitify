package com.localfy.app.data.social

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * An in-memory Nostr relay: stores EVENTs, answers OK, replays stored events on REQ and pushes
 * new ones to every open socket (no filtering — the client must reject what is not for it).
 * Callbacks run on a separate thread, like a real socket.
 */
class FakeRelay : RelaySocketFactory {
    val events = CopyOnWriteArrayList<JSONObject>()
    val sockets = CopyOnWriteArrayList<Socket>()
    val opened = AtomicInteger()
    val received = CopyOnWriteArrayList<String>()
    /** When false, new connections fail immediately (relay down). */
    @Volatile var up = true
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-relay").apply { isDaemon = true } }

    override fun open(url: String, listener: RelaySocketListener): RelaySocket {
        opened.incrementAndGet()
        val socket = Socket(listener)
        if (!up) { socket.open = false; io.execute { listener.onFailure(socket, java.io.IOException("down")) }; return socket }
        sockets += socket
        io.execute { listener.onOpen(socket) }
        return socket
    }

    /** Pushes [raw] to every client as if the relay sent it. */
    fun push(raw: String) = sockets.forEach { s -> io.execute { if (s.open) s.listener.onMessage(s, raw) } }

    /** Drops every connection as a network failure would. */
    fun dropAll() = sockets.toList().forEach { s -> sockets.remove(s); s.open = false; io.execute { s.listener.onFailure(s, java.io.IOException("dropped")) } }

    inner class Socket(val listener: RelaySocketListener) : RelaySocket {
        @Volatile var open = true
        override fun send(text: String): Boolean {
            if (!open) return false
            received += text
            io.execute {
                val message = JSONArray(text)
                when (message.getString(0)) {
                    "EVENT" -> {
                        val event = message.getJSONObject(1)
                        if (events.none { it.getString("id") == event.getString("id") }) events += event
                        listener.onMessage(this, JSONArray().put("OK").put(event.getString("id")).put(true).put("").toString())
                        sockets.filter { it.open }.forEach { s -> s.listener.onMessage(s, JSONArray().put("EVENT").put("spitify-v1").put(event).toString()) }
                    }
                    "REQ" -> {
                        events.forEach { listener.onMessage(this, JSONArray().put("EVENT").put(message.getString(1)).put(it).toString()) }
                        listener.onMessage(this, JSONArray().put("EOSE").put(message.getString(1)).toString())
                    }
                }
            }
            return true
        }
        override fun ping() {}
        override fun close(code: Int, reason: String) { open = false; sockets.remove(this) }
    }
}

suspend fun eventually(timeoutMs: Long = 10_000, check: () -> Boolean) {
    withTimeout(timeoutMs) { while (!check()) delay(20) }
}
