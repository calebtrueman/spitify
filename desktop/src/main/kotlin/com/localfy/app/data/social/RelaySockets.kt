package com.localfy.app.data.social

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One relay connection. Implementations must be thread safe and must never block the caller. */
interface RelaySocket {
    /** Queues [text]; false when the socket is closed or too far behind. */
    fun send(text: String): Boolean
    /** Keep-alive; a socket whose peer stopped answering reports [RelaySocketListener.onFailure]. */
    fun ping()
    fun close(code: Int = 1000, reason: String = "Closed")
}

/** Callbacks may arrive on any thread. [onClosed] / [onFailure] are reported at most once per socket. */
interface RelaySocketListener {
    fun onOpen(socket: RelaySocket)
    fun onMessage(socket: RelaySocket, text: String)
    fun onClosed(socket: RelaySocket)
    fun onFailure(socket: RelaySocket, error: Throwable)
}

/** Opens relay sockets. Tests swap in a fake; the app uses [JdkRelaySockets]. */
fun interface RelaySocketFactory {
    fun open(url: String, listener: RelaySocketListener): RelaySocket
}

/**
 * WebSockets over the JDK's java.net.http client (the phones use OkHttp). Incoming messages over
 * [maxMessage] characters are dropped whole; outgoing frames are chained so they never overlap.
 */
class JdkRelaySockets(
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
    private val maxMessage: Int = 128 * 1024,
) : RelaySocketFactory {
    override fun open(url: String, listener: RelaySocketListener): RelaySocket = Connection(url, listener)

    private inner class Connection(url: String, private val listener: RelaySocketListener) : RelaySocket, WebSocket.Listener {
        private val finished = AtomicBoolean(false)
        private val queued = AtomicInteger(0)
        @Volatile private var lastPong = System.nanoTime()
        @Volatile private var socket: WebSocket? = null
        private val opening: CompletableFuture<WebSocket>
        private var chain: CompletableFuture<*>
        private val text = StringBuilder()
        private var binary = java.io.ByteArrayOutputStream()
        private var oversized = false

        init {
            opening = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15)).buildAsync(URI(url), this)
            chain = opening.whenComplete { _, error -> if (error != null) fail(error) }
        }

        private fun fail(error: Throwable) { if (finished.compareAndSet(false, true)) { socket?.abort(); listener.onFailure(this, error) } }

        override fun send(text: String): Boolean {
            if (finished.get() || queued.get() >= 2000) return false
            queued.incrementAndGet()
            synchronized(this) {
                chain = chain.thenCompose { (socket ?: error("Not connected")).sendText(text, true) }
                    .whenComplete { _, error -> queued.decrementAndGet(); if (error != null) fail(error) }
            }
            return true
        }

        override fun ping() {
            if (finished.get()) return
            val ws = socket ?: return
            if (System.nanoTime() - lastPong > TimeUnit.SECONDS.toNanos(70)) { fail(java.io.IOException("Relay stopped answering")); return }
            synchronized(this) { chain = chain.thenCompose { ws.sendPing(ByteBuffer.allocate(0)) }.whenComplete { _, error -> if (error != null) fail(error) } }
        }

        override fun close(code: Int, reason: String) {
            if (!finished.compareAndSet(false, true)) return
            val ws = socket ?: run { opening.thenAccept { it.abort() }; return }
            runCatching { ws.sendClose(code, reason).orTimeout(5, TimeUnit.SECONDS).whenComplete { _, _ -> ws.abort() } }.onFailure { ws.abort() }
        }

        override fun onOpen(webSocket: WebSocket) {
            socket = webSocket; lastPong = System.nanoTime()
            webSocket.request(1)
            if (finished.get()) webSocket.abort() else listener.onOpen(this)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            if (!oversized) { if (text.length + data.length > maxMessage) { oversized = true; text.setLength(0) } else text.append(data) }
            if (last) {
                val message = if (oversized) null else text.toString()
                text.setLength(0); oversized = false
                if (message != null && !finished.get()) runCatching { listener.onMessage(this, message) }
            }
            webSocket.request(1); return null
        }

        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            if (!oversized) {
                if (binary.size() + data.remaining() > maxMessage) { oversized = true; binary = java.io.ByteArrayOutputStream() }
                else { val bytes = ByteArray(data.remaining()); data.get(bytes); binary.write(bytes) }
            }
            if (last) {
                val message = if (oversized) null else String(binary.toByteArray(), StandardCharsets.UTF_8)
                binary = java.io.ByteArrayOutputStream(); oversized = false
                if (message != null && !finished.get()) runCatching { listener.onMessage(this, message) }
            }
            webSocket.request(1); return null
        }

        override fun onPong(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*>? { lastPong = System.nanoTime(); webSocket.request(1); return null }
        override fun onPing(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*>? { lastPong = System.nanoTime(); webSocket.request(1); return null }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?): CompletionStage<*>? {
            if (finished.compareAndSet(false, true)) { webSocket.abort(); listener.onClosed(this) }
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) { fail(error) }
    }
}
