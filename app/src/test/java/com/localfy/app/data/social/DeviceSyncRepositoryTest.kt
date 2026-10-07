package com.localfy.app.data.social

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DeviceSyncRepositoryTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)

    /** An in-memory relay: private packets go straight to their recipient, public ones are kept for lookups. */
    private class Bus {
        val devices = mutableMapOf<String, DeviceSync>()
        val public = mutableListOf<Triple<String, SocialPacket, List<List<String>>>>()
        val sent = mutableListOf<Triple<String, String, String?>>()
        var online = true
    }
    private class Host(override val platform: String) : DeviceHost {
        var playback: LocalPlayback? = null
        val obeyed = mutableListOf<String>()
        var listened: Pair<DevicePlayback, Long>? = null
        var guest = false
        var saved: String? = null
        var stored: String? = null
        override fun snapshot() = playback
        override fun isPlaying() = playback?.playing == true
        override fun obey(action: String, positionMs: Long) { obeyed += if (action == "seek") "seek:$positionMs" else action; playback = playback?.let { if (action == "pause") it.copy(playing = false) else if (action == "play") it.copy(playing = true) else it } }
        override fun roomGuest() = guest
        override fun inRoom() = guest
        override suspend fun listen(state: DevicePlayback, positionMs: Long) { listened = state to positionMs; playback = LocalPlayback(state.queue, state.currentIndex, true, positionMs) }
        override suspend fun load() = stored
        override fun save(json: String) { saved = json }
    }
    private class Link(override val me: String, val bus: Bus) : DeviceLink {
        var relayNeeded = false
        override suspend fun send(packet: SocialPacket, logical: String, recipient: String?, expiresIn: Long, extraTags: List<List<String>>) {
            bus.sent += Triple(me, packet.type, recipient)
            if (recipient == null) bus.public += Triple(me, packet, extraTags)
            else if (bus.online) bus.devices[recipient]?.receive(me, SocialPacket.parse(org.json.JSONObject(packet.json().toString())), true)
        }
        override suspend fun lookup(tag: String) = bus.public.filter { listOf("t", tag) in it.third }.map { it.first to it.second }
        override fun needRelay(needed: Boolean) { relayNeeded = needed }
    }

    private class Device(val sync: DeviceSync, val host: Host, val link: Link)
    private fun CoroutineScope.device(bus: Bus, id: String, name: String, platform: String = "android", clock: () -> Long = { SocialRules.now }, stored: String? = null): Device {
        val host = Host(platform).apply { this.stored = stored }; val link = Link(id, bus)
        val sync = DeviceSync(link, host, this, name, clock); bus.devices[id] = sync
        return Device(sync, host, link)
    }
    private suspend fun settle() = repeat(50) { yield() }
    private fun track(n: Int) = SharedTrack(id = "t$n", title = "Song $n", artist = "Artist", durationMs = 200_000)
    private fun playing(position: Long = 30_000, playing: Boolean = true) = LocalPlayback((1..5).map(::track), 1, playing, position, source = "Liked Songs")

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try { scope.block() } finally { scope.cancel() }
    }

    private suspend fun CoroutineScope.linked(bus: Bus): Pair<Device, Device> {
        val phone = device(bus, a, "Pixel 9"); val mac = device(bus, b, "MacBook", "macos")
        phone.sync.start(); mac.sync.start()
        val code = phone.sync.showCode(); settle()
        mac.sync.enterCode(DeviceSyncState.displayCode(code).lowercase()); settle()
        assertEquals(b, phone.sync.approval?.first)
        phone.sync.allow(b); settle()
        return phone to mac
    }

    @Test fun scheduleDebouncesBurstsAndBeatsOnlyWhilePlaying() {
        val s = PlaybackSchedule(debounce = 1_500, heartbeat = 30_000)
        assertNull(s.dueAt)
        s.changed(0); s.changed(1_000); s.changed(2_000)
        assertEquals("trailing quiet period", 3_500L, s.dueAt)
        repeat(10) { s.changed(2_000L + it * 1_000) }
        assertEquals("a long burst still goes out", 6_000L, s.dueAt)
        assertFalse(s.due(5_999)); assertTrue(s.due(6_000))
        s.sent(6_000, playing = true)
        assertEquals("heartbeat", 36_000L, s.dueAt)
        s.changed(6_100)
        assertEquals("never twice within the debounce window", 7_600L, s.dueAt)
        s.sent(7_600, playing = false)
        assertNull("no heartbeat once paused", s.dueAt)
    }

    @Test fun windowKeepsTheCurrentSongAndDropsWhatDoesNotResolve() {
        val queue = (0L until 100L).toList()
        val (window, index) = DeviceSync.window(queue, 50) { it.takeIf { id -> id != 45L && id != 60L } }
        assertEquals(40L, window.first()); assertEquals(90L, window.last())
        assertEquals(50L, window[index])
        assertEquals(DevicePlayback.MAX_BEFORE + 1 + DevicePlayback.MAX_AFTER - 2, window.size)
        assertEquals(emptyList<Long>() to -1, DeviceSync.window(queue, 50) { it.takeIf { id -> id != 50L } })
        assertEquals(listOf(0L, 1L) to 0, DeviceSync.window(listOf(0L, 1L), 0) { it })
        assertEquals(emptyList<Long>() to -1, DeviceSync.window(emptyList<Long>(), -1) { it })
    }

    @Test fun untidyTagsStillMakeAValidState() {
        val messy = DeviceSync.tidy(SharedTrack(title = " ", artist = "", album = "Podcast", durationMs = -5, sourceID = "abc", artwork = "http://x"))
        assertTrue(messy.valid()); assertEquals("Podcast", messy.artist); assertNull(messy.sourceID); assertNull(messy.artwork)
        assertEquals("Android", DeviceSync.cleanName("   ")); assertEquals(60, DeviceSync.cleanName("x".repeat(80)).length)
    }

    @Test fun pairingLinksBothDevicesAfterApproval() = test {
        val bus = Bus()
        val (phone, mac) = linked(bus)
        assertEquals(listOf(b), phone.sync.devices.map { it.id })
        assertEquals("MacBook", phone.sync.devices.single().name)
        assertEquals(listOf(a), mac.sync.devices.map { it.id })
        assertEquals("Pixel 9", mac.sync.devices.single().name)
        assertEquals("Linked with Pixel 9", mac.sync.pairingMessage)
        assertNull(phone.sync.code); assertNull(mac.sync.pendingOwner)
        assertTrue(phone.link.relayNeeded && mac.link.relayNeeded)
        val offer = bus.public.single()
        assertEquals("deviceCode", offer.second.type)
        assertFalse("the code itself is never published", offer.second.body.toString().contains(DeviceSyncState.normalizeCode(phone.sync.state.currentCode() ?: "none")))
    }

    @Test fun wrongCodeTellsTheUserAndDeclineLinksNothing() = test {
        val bus = Bus()
        val phone = device(bus, a, "Pixel 9"); val mac = device(bus, b, "MacBook", "macos")
        phone.sync.start(); mac.sync.start()
        phone.sync.showCode(); settle()
        mac.sync.enterCode("ZZZZ-ZZZZ")
        assertTrue(mac.sync.pairingMessage!!.startsWith("That code didn't match"))
        mac.sync.enterCode(phone.sync.code!!); settle()
        phone.sync.deny(b)
        assertNull(phone.sync.approval)
        assertTrue(phone.sync.devices.isEmpty() && mac.sync.devices.isEmpty())
    }

    @Test fun thirdDeviceJoinsTheWholeGroup() = test {
        val bus = Bus()
        val (phone, mac) = linked(bus)
        val pad = device(bus, c, "iPad", "ios"); pad.sync.start()
        val code = phone.sync.showCode(); settle()
        pad.sync.enterCode(code); settle(); phone.sync.allow(c); settle()
        assertEquals(setOf(a, b), pad.sync.devices.map { it.id }.toSet())
        assertEquals(setOf(a, c), mac.sync.devices.map { it.id }.toSet())
        mac.sync.remove(c); settle()
        assertEquals(setOf(a), mac.sync.devices.map { it.id }.toSet())
        assertEquals(setOf(b), phone.sync.devices.map { it.id }.toSet())
        assertTrue("the removed device forgets the group", pad.sync.devices.isEmpty())
    }

    @Test fun playbackReachesTheOtherDeviceAndControlsComeBack() = test {
        val bus = Bus()
        val (phone, mac) = linked(bus)
        phone.host.playback = playing()
        phone.sync.sendPlayback(); settle()
        val shown = mac.sync.active()
        assertEquals(a, shown?.device); assertEquals("Song 2", shown?.current?.title); assertEquals("Liked Songs", shown?.source)
        assertEquals(30_000L, mac.sync.expectedPosition(shown!!, mac.sync.receivedAt.getValue(a)))
        mac.sync.control(a, "pause"); settle()
        assertEquals(listOf("pause"), phone.host.obeyed)
        assertFalse("optimistic", mac.sync.state.playback[a]!!.playing)
        assertNotNull("still reachable after pausing it", mac.sync.shown())
        mac.sync.control(a, "seek", 90_000); settle()
        assertEquals("seek:90000", phone.host.obeyed.last())
        // Obeying replies with a fresh state.
        assertNotNull(phone.sync.schedule.dueAt)
    }

    @Test fun startingPlaybackHereHandsOffAndListenHereTakesOver() = test {
        val bus = Bus()
        val (phone, mac) = linked(bus)
        mac.host.playback = playing(); mac.sync.sendPlayback(); settle()
        assertEquals(b, phone.sync.active()?.device)
        phone.host.playback = playing(position = 0)
        phone.sync.localStarted(); settle()
        assertEquals(listOf("pause"), mac.host.obeyed)
        assertNull(phone.sync.active())

        mac.host.playback = playing(position = 50_000); mac.sync.sendPlayback(); settle()
        phone.host.playback = null
        phone.sync.listenHere(b); settle()
        assertEquals("Song 2", phone.host.listened?.first?.current?.title)
        assertTrue(phone.host.listened!!.second >= 50_000)
        assertEquals(listOf("pause", "pause"), mac.host.obeyed)
        // Starting because of Listen here doesn't send a second handoff.
        phone.sync.localStarted(); settle()
        assertEquals(2, mac.host.obeyed.size)
    }

    @Test fun obeyedPlayDoesNotHandOffBack() = test {
        val bus = Bus()
        val (phone, mac) = linked(bus)
        mac.host.playback = playing(); mac.sync.sendPlayback(); settle()
        phone.host.playback = playing(playing = false)
        mac.sync.control(a, "play"); settle()
        assertEquals(listOf("play"), phone.host.obeyed)
        phone.sync.localStarted(); settle()
        assertTrue(mac.host.obeyed.isEmpty())
    }

    @Test fun nothingIsSentWithoutDevicesOrAsARoomGuestOrForARestoredQueue() = test {
        val bus = Bus()
        val alone = device(bus, c, "Tablet"); alone.sync.start()
        alone.host.playback = playing(); alone.sync.localChanged(); alone.sync.sendPlayback(); settle()
        assertNull(alone.sync.schedule.dueAt)
        assertTrue(bus.sent.isEmpty())
        val (phone, _) = linked(bus)
        val before = bus.sent.count { it.second == "devicePlayback" }
        phone.host.playback = playing(playing = false)
        phone.sync.sendPlayback(); settle()
        assertEquals("a paused queue restored at launch isn't news", before, bus.sent.count { it.second == "devicePlayback" })
        phone.host.playback = playing(); phone.host.guest = true
        phone.sync.sendPlayback(); settle()
        assertEquals(before, bus.sent.count { it.second == "devicePlayback" })
        phone.host.guest = false
        phone.sync.sendPlayback(); settle()
        assertEquals(before + 1, bus.sent.count { it.second == "devicePlayback" })
        phone.host.playback = playing(playing = false)
        phone.sync.sendPlayback(); settle()
        assertEquals("the final paused state goes out", before + 2, bus.sent.count { it.second == "devicePlayback" })
    }

    @Test fun continueIsOfferedOnceForNewerPlaybackAndSurvivesRestart() = test {
        var now = 1_000_000L
        val bus = Bus()
        val phone = device(bus, a, "Pixel 9", clock = { now }); val mac = device(bus, b, "MacBook", "macos", clock = { now })
        phone.sync.start(); mac.sync.start()
        val code = phone.sync.showCode(); settle(); mac.sync.enterCode(code); settle(); phone.sync.allow(b); settle()
        mac.host.playback = playing(); mac.sync.sendPlayback(); settle()
        now += 5_000; mac.host.playback = playing(playing = false); mac.sync.sendPlayback(); settle()
        now += 60_000
        phone.sync.foreground()
        assertEquals(b, phone.sync.offer?.device)
        phone.sync.dismissOffer(); phone.sync.foreground()
        assertNull("the same revision is offered once", phone.sync.offer)

        val saved = phone.sync.json().toString()
        val again = device(Bus(), a, "Pixel 9", clock = { now }, stored = saved); again.sync.start()
        assertEquals(listOf(b), again.sync.devices.map { it.id })
        assertEquals(phone.sync.receivedAt, again.sync.receivedAt)
        again.sync.foreground(); assertNull(again.sync.offer)

        // Playing here after the other device stopped means there's nothing to continue.
        now += 1_000; mac.host.playback = playing(playing = true); mac.sync.sendPlayback(); settle()
        now += 2_000; mac.host.playback = playing(playing = false); mac.sync.sendPlayback(); settle()
        now += 1_000; phone.host.playback = playing(); phone.sync.localStarted(); phone.sync.sendPlayback(); phone.host.playback = playing(playing = false)
        phone.sync.foreground()
        assertNull(phone.sync.offer)
    }

    @Test fun handoffPausesAndNotes() = test {
        val bus = Bus()
        val (phone, mac) = linked(bus)
        val notes = mutableListOf<String>()
        val job = launch { phone.sync.notes.collect { notes += it } }; settle()
        phone.host.playback = playing()
        mac.sync.control(a, "handoff"); settle()
        assertEquals(listOf("pause"), phone.host.obeyed)
        assertEquals("Now playing on MacBook", notes.last())
        job.cancel()
    }

    @Test fun leavingUnlinksEveryone() = test {
        val bus = Bus()
        val (phone, mac) = linked(bus)
        mac.sync.leave(); settle()
        assertTrue(mac.sync.devices.isEmpty() && phone.sync.devices.isEmpty())
        assertTrue("relay stays up briefly to deliver the unlink", mac.link.relayNeeded)
    }
}
