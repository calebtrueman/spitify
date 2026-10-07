package com.localfy.app.data.social

import com.localfy.app.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.nostrdevkit.sdk.Keys
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

/**
 * Two linked computers over the real public relays: code lookup, approval, playback state and a
 * pause command. Runs only with SPITIFY_LIVE=1.
 */
class LiveDeviceSyncCheck {
    @Test fun linkAndControlOverRealRelays() = runBlocking {
        assumeTrue(System.getenv("SPITIFY_LIVE") == "1")
        val dir = Files.createTempDirectory("live-devices").toFile()
        val ui = Executors.newSingleThreadExecutor { r -> Thread(r, "live-ui").apply { isDaemon = true } }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + ui)
        val library = (1L..3L).map { Song(it, "Live song $it", "Band", "Album", 1, "Band", 200_000, it.toInt(), 1, 2020, null, "/m", 0, 0, null) }
        fun computer(name: String, platform: String): Pair<DeviceSyncRepository, DeviceSyncRepositoryTest.FakeDevicePlayer> {
            val folder = File(dir, name).apply { mkdirs() }
            val relay = PeerRelay(Keys.generate(), scope, outboxFile = File(folder, "outbox.json"))
            val social = SocialRepository(scope, folder, relay)
            val player = DeviceSyncRepositoryTest.FakeDevicePlayer()
            return DeviceSyncRepository(social, player, scope, folder, resolveTrack = { t -> library.first { it.title == t.title } }, platform = platform, hostName = { name }) to player
        }
        try {
            val started = System.currentTimeMillis()
            val (mac, macPlayer) = withContext(ui) { computer("LiveMac", "macos") }
            val (pc, _) = withContext(ui) { computer("LivePC", "windows") }
            val code = withContext(ui) { mac.showCode(); (mac.pairing as DevicePairing.Showing).code }
            Thread.sleep(3_000) // let the offer reach the relays
            withContext(ui) { pc.enterCode(code) }
            eventually(60_000) { runBlocking(ui) { mac.requests.isNotEmpty() } }
            println("LIVE request after ${System.currentTimeMillis() - started} ms")
            withContext(ui) { mac.approve(mac.requests.single().first) }
            eventually(60_000) { runBlocking(ui) { pc.pairing == DevicePairing.Linked("LiveMac") } }
            println("LIVE linked after ${System.currentTimeMillis() - started} ms")

            val playAt = System.currentTimeMillis()
            withContext(ui) { macPlayer.playSongs(library, "Live check", 12_000) }
            eventually(60_000) { runBlocking(ui) { pc.shown()?.current?.title == "Live song 1" } }
            println("LIVE state arrived after ${System.currentTimeMillis() - playAt} ms")

            val pauseAt = System.currentTimeMillis()
            withContext(ui) { pc.control(mac.me, "pause") }
            eventually(60_000) { runBlocking(ui) { !macPlayer.state.value.isPlaying } }
            println("LIVE pause obeyed after ${System.currentTimeMillis() - pauseAt} ms")
            assertEquals(listOf(pc.me), withContext(ui) { mac.devices.map { it.id } })
            withContext(ui) { pc.leave() }
            Thread.sleep(3_000)
        } finally {
            scope.cancel(); ui.close(); dir.deleteRecursively()
        }
    }
}
