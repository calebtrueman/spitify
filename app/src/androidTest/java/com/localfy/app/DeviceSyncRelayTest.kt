package com.localfy.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localfy.app.data.social.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.nostrdevkit.sdk.Keys
import java.util.UUID

/** Two DeviceSync instances over two real PeerRelays (signed, NIP-44 encrypted events), relayed by hand. */
@RunWith(AndroidJUnit4::class)
class DeviceSyncRelayTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun wire(event: String, sub: String = "spitify-v1") = JSONArray().put("EVENT").put(sub).put(JSONObject(event)).toString()

    private class Relay(val relay: PeerRelay, val storage: String)
    private inner class Link(val own: Relay, val peers: Map<String, Relay>, val published: MutableList<String>) : DeviceLink {
        val delivered = mutableSetOf<String>()
        override val me get() = own.relay.publicKey
        override suspend fun send(packet: SocialPacket, logical: String, recipient: String?, expiresIn: Long, extraTags: List<List<String>>) {
            own.relay.send(packet, logical, recipient, expiresIn, extraTags)
            val outbox = JSONArray(context.getSharedPreferences(own.storage, Context.MODE_PRIVATE).getString("events", "[]"))
            for (i in 0 until outbox.length()) {
                val item = outbox.getJSONObject(i); if (!delivered.add(item.getString("id"))) continue
                val event = item.getString("json")
                if (recipient == null) published += event else peers[recipient]?.relay?.receive(wire(event))
            }
        }
        override suspend fun lookup(tag: String): List<Pair<String, SocialPacket>> = coroutineScope {
            val found = async { own.relay.lookup(tag, 5_000) }
            yield()
            published.forEach { own.relay.receive(wire(it, "spitify-lookup-1")) }
            own.relay.receive(JSONArray().put("EOSE").put("spitify-lookup-1").toString())
            found.await()
        }
        override fun needRelay(needed: Boolean) {}
    }
    private class Host : DeviceHost {
        var playback: LocalPlayback? = null
        val obeyed = mutableListOf<String>()
        override val platform = "android"
        override fun snapshot() = playback
        override fun isPlaying() = playback?.playing == true
        override fun obey(action: String, positionMs: Long) { obeyed += action }
        override fun roomGuest() = false
        override fun inRoom() = false
        override suspend fun listen(state: DevicePlayback, positionMs: Long) {}
        override suspend fun load(): String? = null
        override fun save(json: String) {}
    }

    @Test fun pairsAndSharesPlaybackOverSignedEncryptedEvents() = runBlocking {
        withContext(Dispatchers.Main) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val storages = listOf("devices-a-${UUID.randomUUID()}", "devices-b-${UUID.randomUUID()}")
            try {
                val ra = Relay(PeerRelay(context, Keys.parse("1".repeat(64)), scope, storages[0], true), storages[0])
                val rb = Relay(PeerRelay(context, Keys.parse("2".repeat(64)), scope, storages[1], true), storages[1])
                val peers = mapOf(ra.relay.publicKey to ra, rb.relay.publicKey to rb)
                val published = mutableListOf<String>()
                val hostA = Host(); val hostB = Host()
                val phone = DeviceSync(Link(ra, peers, published), hostA, scope, "Pixel 9")
                val mac = DeviceSync(Link(rb, peers, published), hostB, scope, "MacBook")
                ra.relay.onPacket = phone::receive; rb.relay.onPacket = mac::receive
                phone.start(); mac.start()

                val code = phone.showCode()
                withTimeout(5_000) { while (published.isEmpty()) delay(20) }
                assertTrue("public offer carries the lookup tag", JSONObject(published.single()).getJSONArray("tags").toString().contains(DeviceSyncState.lookupTag(code)))
                mac.enterCode(code)
                withTimeout(5_000) { while (phone.approval == null) delay(20) }
                assertEquals("MacBook", phone.approval?.second?.name)
                phone.allow(rb.relay.publicKey)
                withTimeout(5_000) { while (mac.devices.isEmpty()) delay(20) }
                assertEquals(ra.relay.publicKey, mac.devices.single().id)

                hostA.playback = LocalPlayback(listOf(SharedTrack(title = "One", artist = "Artist", durationMs = 180_000), SharedTrack(title = "Two", artist = "Artist")), 0, true, 42_000, source = "Liked Songs")
                phone.sendPlayback()
                withTimeout(5_000) { while (mac.active() == null) delay(20) }
                assertEquals("One", mac.active()?.current?.title)
                mac.control(ra.relay.publicKey, "pause")
                withTimeout(5_000) { while (hostA.obeyed.isEmpty()) delay(20) }
                assertEquals(listOf("pause"), hostA.obeyed)
            } finally { scope.cancel(); storages.forEach(context::deleteSharedPreferences) }
        }
    }
}
