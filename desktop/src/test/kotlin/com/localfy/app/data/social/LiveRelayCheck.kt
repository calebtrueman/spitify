package com.localfy.app.data.social

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.assertTrue
import org.nostrdevkit.sdk.Event
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live network check, skipped unless SPITIFY_LIVE=1:
 *   SPITIFY_LIVE=1 ./gradlew :desktop:test --tests '*LiveRelayCheck*'
 * Opens a real WebSocket to a public default relay with [JdkRelaySockets], reads one profile
 * event (kind 0) and verifies its signature with the nostr SDK. It publishes nothing.
 */
class LiveRelayCheck {
    @Test fun readsAProfileFromAPublicRelay() = runBlocking {
        assumeTrue("Set SPITIFY_LIVE=1 to run", System.getenv("SPITIFY_LIVE") == "1")
        val messages = CopyOnWriteArrayList<String>(); val opened = AtomicBoolean(); val failed = CopyOnWriteArrayList<Throwable>()
        val socket = JdkRelaySockets().open(PeerRelay.DEFAULTS.first(), object : RelaySocketListener {
            override fun onOpen(socket: RelaySocket) { opened.set(true); socket.send(JSONArray().put("REQ").put("live-check").put(JSONObject().put("kinds", JSONArray(listOf(0))).put("limit", 1)).toString()) }
            override fun onMessage(socket: RelaySocket, text: String) { messages += text }
            override fun onClosed(socket: RelaySocket) {}
            override fun onFailure(socket: RelaySocket, error: Throwable) { failed += error }
        })
        try {
            eventually(20_000) { failed.isNotEmpty() || messages.any { JSONArray(it).optString(0) == "EVENT" } }
            assertTrue("connection failed: ${failed.firstOrNull()}", failed.isEmpty() && opened.get())
            val event = JSONArray(messages.first { JSONArray(it).optString(0) == "EVENT" }).getJSONObject(2)
            assertTrue(Event.fromJson(event.toString()).verify())
            println("Live relay OK: profile ${event.getString("pubkey").take(8)}… ${JSONObject(event.getString("content")).optString("name").take(40)}")
        } finally { socket.close() }
    }
}
