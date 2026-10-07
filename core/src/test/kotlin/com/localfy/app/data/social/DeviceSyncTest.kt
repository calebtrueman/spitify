package com.localfy.app.data.social

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSyncTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    private val stranger = "d".repeat(64)

    private fun track(n: Int) = SharedTrack(id = "t$n", title = "Song $n", artist = "Artist", durationMs = 200_000)
    private fun state(device: String, revision: Long, playing: Boolean = true, position: Long = 10_000) =
        DevicePlayback(device, "Phone", "android", revision, revision, playing, position, queue = listOf(track(1), track(2)), currentIndex = 0)

    @Test fun pairingNeedsTheLiveCodeAndTheUsersApproval() {
        val host = DeviceSyncState(a)
        val code = host.newCode(now = 1_000)
        assertEquals(DeviceSyncState.TOKEN_LENGTH, code.length)
        val typed = DeviceSyncState.displayCode(code).lowercase().replace(" ", "-")
        assertEquals(DeviceSyncState.lookupTag(code), DeviceSyncState.lookupTag(typed))
        val request = DeviceLinkRequest(DeviceSyncState.normalizeCode(typed), "MacBook", "macos")
        assertFalse("not encrypted", host.receiveLink(request, b, encrypted = false, now = 2_000))
        assertFalse("wrong code", host.receiveLink(request.copy(token = "ZZZZZZZZ"), b, true, now = 2_000))
        assertTrue(host.receiveLink(request, b, true, now = 2_000))
        assertTrue("waits for the user", host.devices.isEmpty())
        assertTrue(host.approve(b, now = 3_000))
        assertEquals("MacBook", host.devices[b]?.name)
        assertNull("code used up", host.currentCode(now = 3_000))
        assertFalse(host.receiveLink(request, c, true, now = 3_000))
        assertEquals(listOf(a, b), host.list("Phone", "android").devices.map { it.id })

        val late = DeviceSyncState(a); val lateCode = late.newCode(now = 0)
        assertFalse(late.receiveLink(DeviceLinkRequest(lateCode, "PC", "windows"), b, true, now = DeviceSyncState.CODE_LIFETIME + 1))
        val declined = DeviceSyncState(a); val dc = declined.newCode(now = 0)
        assertTrue(declined.receiveLink(DeviceLinkRequest(dc, "PC", "windows"), b, true, now = 1))
        declined.decline(b)
        assertFalse(declined.approve(b))
    }

    @Test fun deviceListJoinsTheWholeGroupButOnlyFromTrustedSenders() {
        val newcomer = DeviceSyncState(b)
        val list = DeviceList(listOf(LinkedDevice(a, "Phone", "android"), LinkedDevice(b, "Mac", "macos"), LinkedDevice(c, "iPad", "ios")))
        assertFalse("not the code owner", newcomer.acceptList(list, stranger, true, pendingOwner = a))
        assertTrue(newcomer.acceptList(list, a, true, pendingOwner = a))
        assertEquals(setOf(a, c), newcomer.devices.keys)
        assertFalse("nothing new", newcomer.acceptList(list, c, true, pendingOwner = null))
        // A list that doesn't include us is ignored.
        assertFalse(newcomer.acceptList(DeviceList(listOf(LinkedDevice(a, "Phone", "android"), LinkedDevice(stranger, "X", "linux"))), a, true, null))
    }

    @Test fun playbackOnlyFromLinkedDevicesAndOnlyNewer() {
        val me = DeviceSyncState(a).apply { devices[b] = LinkedDevice(b, "Mac", "macos") }
        assertFalse(me.acceptPlayback(state(stranger, 5), stranger, true))
        assertFalse("spoofed device", me.acceptPlayback(state(c, 5), b, true))
        assertTrue(me.acceptPlayback(state(b, 5), b, true))
        assertFalse("older", me.acceptPlayback(state(b, 4), b, true))
        assertEquals("Phone", me.devices[b]?.name)
        val roundTrip = DevicePlayback.parse(JSONObject(state(b, 9).json().toString()))
        assertEquals(state(b, 9), roundTrip)
    }

    @Test fun activeNeedsAFreshPlayingDeviceAndLatestPicksTheNewest() {
        val me = DeviceSyncState(a).apply { devices[b] = LinkedDevice(b, "Mac", "macos"); devices[c] = LinkedDevice(c, "iPad", "ios") }
        me.acceptPlayback(state(b, 5), b, true); me.acceptPlayback(state(c, 7, playing = false), c, true)
        val received = mapOf(b to 100_000L, c to 150_000L)
        assertEquals(b, me.active(received, now = 120_000)?.device)
        assertNull("stale", me.active(received, now = 100_000 + DeviceSyncState.FRESH + 1))
        assertEquals("paused but newer", c, me.latest(received, now = 200_000)?.device)
    }

    @Test fun commandsAreForUsFromOurDevicesOnceAndRecent() {
        val me = DeviceSyncState(a).apply { devices[b] = LinkedDevice(b, "Mac", "macos") }
        val pause = DeviceCommand("cmd-00001", a, "pause", createdAt = 1_000_000)
        assertFalse(me.acceptCommand(pause, stranger, true, now = 1_000_000))
        assertFalse(me.acceptCommand(pause.copy(target = c), b, true, now = 1_000_000))
        assertTrue(me.acceptCommand(pause, b, true, now = 1_000_500))
        assertFalse("duplicate", me.acceptCommand(pause, b, true, now = 1_000_600))
        assertFalse("old", me.acceptCommand(pause.copy(id = "cmd-00002"), b, true, now = 1_000_000 + DeviceSyncState.COMMAND_LIFETIME + 1))
    }

    @Test fun unlinkRemovesOneOrLeavesTheGroup() {
        val me = DeviceSyncState(a).apply { devices[b] = LinkedDevice(b, "Mac", "macos"); devices[c] = LinkedDevice(c, "iPad", "ios") }
        assertTrue(me.acceptUnlink(c, b, true))
        assertEquals(setOf(b), me.devices.keys)
        assertTrue(me.acceptUnlink(a, b, true))
        assertTrue(me.devices.isEmpty())
    }

    @Test fun windowAndExpectedPosition() {
        val queue = (0 until 100).toList()
        val (window, index) = DevicePlayback.window(queue, 50)
        assertEquals(DevicePlayback.MAX_BEFORE + 1 + DevicePlayback.MAX_AFTER, window.size)
        assertEquals(50, window[index])
        assertEquals(0 to 0, DevicePlayback.window(listOf(7), 0).let { it.first.size - 1 to it.second })
        val playing = state(b, 1, position = 10_000)
        assertEquals(15_000, DeviceSyncState.expectedPosition(playing, receivedAt = 1_000, now = 6_000))
        assertEquals(200_000, DeviceSyncState.expectedPosition(playing, receivedAt = 0, now = 9_000_000))
        assertEquals(10_000, DeviceSyncState.expectedPosition(playing.copy(playing = false), receivedAt = 0, now = 60_000))
    }

    @Test fun savedStateDropsUnlinkedDevices() {
        val me = DeviceSyncState(a).apply { devices[b] = LinkedDevice(b, "Mac", "macos") }
        me.acceptPlayback(state(b, 3), b, true)
        val copy = DeviceSyncState(a).apply { load(JSONObject(me.json().toString())) }
        assertEquals(me.devices.keys, copy.devices.keys)
        assertEquals(me.playback, copy.playback)
    }
}
