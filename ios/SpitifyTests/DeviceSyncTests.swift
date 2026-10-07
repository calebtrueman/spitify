import XCTest
import NostrSDK
@testable import Spitify

/// Mirrors core/src/test/.../DeviceSyncTest.kt, plus wire compatibility with the Kotlin JSON and an
/// end-to-end pairing between two stores over two real (unconnected) relays.
@MainActor
final class DeviceSyncTests: XCTestCase {
    private let a = String(repeating: "a", count: 64)
    private let b = String(repeating: "b", count: 64)
    private let c = String(repeating: "c", count: 64)
    private let stranger = String(repeating: "d", count: 64)

    private func track(_ n: Int) -> SharedTrack { SharedTrack(id: "t\(n)", title: "Song \(n)", artist: "Artist", durationMs: 200_000) }
    private func state(_ device: String, _ revision: Int64, playing: Bool = true, position: Int64 = 10_000) -> DevicePlayback {
        DevicePlayback(device: device, name: "Phone", platform: "android", revision: revision, observedAt: revision, playing: playing, positionMs: position, queue: [track(1), track(2)], currentIndex: 0)
    }
    private func linked(_ me: String, _ others: [(String, String, String)]) -> DeviceSyncState {
        var s = DeviceSyncState(me: me)
        for (id, name, platform) in others { s.devices.append(LinkedDevice(id: id, name: name, platform: platform)) }
        return s
    }

    // MARK: Ported from DeviceSyncTest.kt

    func testPairingNeedsTheLiveCodeAndTheUsersApproval() {
        var host = DeviceSyncState(me: a)
        let code = host.newCode(now: 1_000)
        XCTAssertEqual(code.count, DeviceSyncState.tokenLength)
        let typed = DeviceSyncState.displayCode(code).lowercased().replacingOccurrences(of: " ", with: "-")
        XCTAssertEqual(DeviceSyncState.lookupTag(code), DeviceSyncState.lookupTag(typed))
        let request = DeviceLinkRequest(token: DeviceSyncState.normalizeCode(typed), name: "MacBook", platform: "macos")
        XCTAssertFalse(host.receiveLink(request, author: b, encrypted: false, now: 2_000), "not encrypted")
        var wrong = request; wrong.token = "ZZZZZZZZ"
        XCTAssertFalse(host.receiveLink(wrong, author: b, encrypted: true, now: 2_000), "wrong code")
        XCTAssertTrue(host.receiveLink(request, author: b, encrypted: true, now: 2_000))
        XCTAssertTrue(host.devices.isEmpty, "waits for the user")
        XCTAssertTrue(host.approve(b, now: 3_000))
        XCTAssertEqual(host.device(b)?.name, "MacBook")
        XCTAssertNil(host.currentCode(now: 3_000), "code used up")
        XCTAssertFalse(host.receiveLink(request, author: c, encrypted: true, now: 3_000))
        XCTAssertEqual(host.list(myName: "Phone", myPlatform: "android").devices.map(\.id), [a, b])

        var late = DeviceSyncState(me: a); let lateCode = late.newCode(now: 0)
        XCTAssertFalse(late.receiveLink(DeviceLinkRequest(token: lateCode, name: "PC", platform: "windows"), author: b, encrypted: true, now: DeviceSyncState.codeLifetime + 1))
        var declined = DeviceSyncState(me: a); let dc = declined.newCode(now: 0)
        XCTAssertTrue(declined.receiveLink(DeviceLinkRequest(token: dc, name: "PC", platform: "windows"), author: b, encrypted: true, now: 1))
        declined.decline(b)
        XCTAssertFalse(declined.approve(b))
    }

    func testDeviceListJoinsTheWholeGroupButOnlyFromTrustedSenders() {
        var newcomer = DeviceSyncState(me: b)
        let list = DeviceList(devices: [LinkedDevice(id: a, name: "Phone", platform: "android"), LinkedDevice(id: b, name: "Mac", platform: "macos"), LinkedDevice(id: c, name: "iPad", platform: "ios")])
        XCTAssertFalse(newcomer.acceptList(list, author: stranger, encrypted: true, pendingOwner: a), "not the code owner")
        XCTAssertTrue(newcomer.acceptList(list, author: a, encrypted: true, pendingOwner: a))
        XCTAssertEqual(Set(newcomer.devices.map(\.id)), [a, c])
        XCTAssertFalse(newcomer.acceptList(list, author: c, encrypted: true, pendingOwner: nil), "nothing new")
        // A list that doesn't include us is ignored.
        XCTAssertFalse(newcomer.acceptList(DeviceList(devices: [LinkedDevice(id: a, name: "Phone", platform: "android"), LinkedDevice(id: stranger, name: "X", platform: "linux")]), author: a, encrypted: true, pendingOwner: nil))
    }

    func testPlaybackOnlyFromLinkedDevicesAndOnlyNewer() throws {
        var me = linked(a, [(b, "Mac", "macos")])
        XCTAssertFalse(me.acceptPlayback(state(stranger, 5), author: stranger, encrypted: true))
        XCTAssertFalse(me.acceptPlayback(state(c, 5), author: b, encrypted: true), "spoofed device")
        XCTAssertTrue(me.acceptPlayback(state(b, 5), author: b, encrypted: true))
        XCTAssertFalse(me.acceptPlayback(state(b, 4), author: b, encrypted: true), "older")
        XCTAssertEqual(me.device(b)?.name, "Phone")
        let roundTrip = try JSONDecoder().decode(DevicePlayback.self, from: JSONEncoder().encode(state(b, 9)))
        XCTAssertEqual(roundTrip, state(b, 9))
    }

    func testActiveNeedsAFreshPlayingDeviceAndLatestPicksTheNewest() {
        var me = linked(a, [(b, "Mac", "macos"), (c, "iPad", "ios")])
        _ = me.acceptPlayback(state(b, 5), author: b, encrypted: true); _ = me.acceptPlayback(state(c, 7, playing: false), author: c, encrypted: true)
        let received: [String: Int64] = [b: 100_000, c: 150_000]
        XCTAssertEqual(me.active(received, now: 120_000)?.device, b)
        XCTAssertNil(me.active(received, now: 100_000 + DeviceSyncState.fresh + 1), "stale")
        XCTAssertEqual(me.latest(received, now: 200_000)?.device, c, "paused but newer")
    }

    func testCommandsAreForUsFromOurDevicesOnceAndRecent() {
        var me = linked(a, [(b, "Mac", "macos")])
        let pause = DeviceCommand(id: "cmd-00001", target: a, action: "pause", createdAt: 1_000_000)
        XCTAssertFalse(me.acceptCommand(pause, author: stranger, encrypted: true, now: 1_000_000))
        var other = pause; other.target = c
        XCTAssertFalse(me.acceptCommand(other, author: b, encrypted: true, now: 1_000_000))
        XCTAssertTrue(me.acceptCommand(pause, author: b, encrypted: true, now: 1_000_500))
        XCTAssertFalse(me.acceptCommand(pause, author: b, encrypted: true, now: 1_000_600), "duplicate")
        var old = pause; old.id = "cmd-00002"
        XCTAssertFalse(me.acceptCommand(old, author: b, encrypted: true, now: 1_000_000 + DeviceSyncState.commandLifetime + 1), "old")
    }

    func testUnlinkRemovesOneOrLeavesTheGroup() {
        var me = linked(a, [(b, "Mac", "macos"), (c, "iPad", "ios")])
        XCTAssertTrue(me.acceptUnlink(c, author: b, encrypted: true))
        XCTAssertEqual(me.devices.map(\.id), [b])
        XCTAssertTrue(me.acceptUnlink(a, author: b, encrypted: true))
        XCTAssertTrue(me.devices.isEmpty)
    }

    func testOldUnlinksDontUndoALaterLinkAndCodesCanBeCancelled() {
        var me = DeviceSyncState(me: a)
        me.devices = [LinkedDevice(id: b, name: "Mac", platform: "macos", linkedAt: 1_000_000), LinkedDevice(id: c, name: "iPad", platform: "ios", linkedAt: 5_000_000)]
        XCTAssertFalse(me.acceptUnlink(c, author: b, encrypted: true, createdAt: 2_000_000), "replayed from before c was linked again")
        XCTAssertFalse(me.acceptUnlink(a, author: b, encrypted: true, createdAt: 500_000), "replayed removal of us")
        XCTAssertTrue(me.acceptUnlink(c, author: b, encrypted: true, createdAt: 6_000_000))
        var host = DeviceSyncState(me: a); let code = host.newCode(now: 0); host.cancelCode()
        XCTAssertFalse(host.receiveLink(DeviceLinkRequest(token: code, name: "PC", platform: "windows"), author: b, encrypted: true, now: 1))
        var replay = state(b, 1); replay.observedAt = 0
        XCTAssertEqual(DeviceSyncState.receivedTime(replay, now: 60 * 60_000), 0)
        replay.observedAt = 900
        XCTAssertEqual(DeviceSyncState.receivedTime(replay, now: 1_000), 1_000)
    }

    func testWindowAndExpectedPosition() {
        let queue = Array(0..<100)
        let (window, index) = DevicePlayback.window(queue, current: 50)
        XCTAssertEqual(window.count, DevicePlayback.maxBefore + 1 + DevicePlayback.maxAfter)
        XCTAssertEqual(window[index], 50)
        let single = DevicePlayback.window([7], current: 0)
        XCTAssertEqual(single.0.count - 1, 0); XCTAssertEqual(single.1, 0)
        let playing = state(b, 1, position: 10_000)
        XCTAssertEqual(DeviceSyncState.expectedPosition(playing, receivedAt: 1_000, now: 6_000), 15_000)
        XCTAssertEqual(DeviceSyncState.expectedPosition(playing, receivedAt: 0, now: 9_000_000), 200_000)
        var paused = playing; paused.playing = false
        XCTAssertEqual(DeviceSyncState.expectedPosition(paused, receivedAt: 0, now: 60_000), 10_000)
    }

    func testSavedStateDropsUnlinkedDevices() throws {
        var me = linked(a, [(b, "Mac", "macos")])
        _ = me.acceptPlayback(state(b, 3), author: b, encrypted: true)
        var copy = DeviceSyncState(me: a)
        copy.load(try JSONDecoder().decode(DeviceSyncSnapshot.self, from: JSONEncoder().encode(me.snapshot)))
        XCTAssertEqual(copy.devices.map(\.id), me.devices.map(\.id))
        XCTAssertEqual(copy.playback, me.playback)
        // A saved state from another device's point of view drops ourselves and anything unlinked.
        var other = DeviceSyncState(me: b)
        other.load(DeviceSyncSnapshot(devices: [LinkedDevice(id: b, name: "Me", platform: "ios")], playback: [state(c, 3)]))
        XCTAssertTrue(other.devices.isEmpty); XCTAssertTrue(other.playback.isEmpty)
    }

    // MARK: Wire compatibility with Kotlin

    func testLookupTagMatchesTheSharedDefinition() {
        // printf '%s' "spitify-device-link:K7QXM2PA" | shasum -a 256 | cut -c1-32
        XCTAssertEqual(DeviceSyncState.lookupTag("k7qx-m2pa"), "spitify-link-c9080c44dd2300f13529a184c3239016")
        XCTAssertEqual(DeviceSyncState.lookupTag(" K7QX M2PA "), DeviceSyncState.lookupTag("K7QXM2PA"))
        XCTAssertEqual(DeviceSyncState.displayCode("K7QXM2PA"), "K7QX M2PA")
        var s = DeviceSyncState(me: a)
        for _ in 0..<50 { XCTAssertTrue(s.newCode().allSatisfy { "ABCDEFGHJKMNPQRSTUVWXYZ23456789".contains($0) }) }
    }

    /// Written by hand from Kotlin's `json()` (org.json drops keys whose value is null, and writes
    /// `speed` as a double, so 1.1f arrives as 1.100000023841858).
    func testDecodesJSONWrittenByKotlin() throws {
        let playback = """
        {"device":"\(b)","name":"Pixel 9","platform":"android","revision":1759800000123,"observedAt":1759800000100,"playing":true,"positionMs":61000,"speed":1.100000023841858,"queue":[{"id":"6f1c","title":"Song 1","artist":"Artist","album":"Album","durationMs":200000,"sourceID":"123456","releaseID":"98765","artwork":"https://resources.tidal.com/images/ab/cd/640x640.jpg"},{"id":"7a2d","title":"Song 2","artist":"Artist","album":"","durationMs":180000}],"currentIndex":1,"spoken":false}
        """
        let decoded = try JSONDecoder().decode(DevicePlayback.self, from: Data(playback.utf8))
        XCTAssertTrue(decoded.valid())
        XCTAssertEqual(decoded.revision, 1_759_800_000_123); XCTAssertEqual(decoded.positionMs, 61_000)
        XCTAssertEqual(decoded.speed, 1.1, accuracy: 0.0001)
        XCTAssertNil(decoded.source)
        XCTAssertEqual(decoded.current?.title, "Song 2"); XCTAssertEqual(decoded.queue[0].sourceID, "123456"); XCTAssertNil(decoded.queue[1].artwork)
        // Explicit null and a whole-number speed both decode too.
        let other = """
        {"device":"\(b)","name":"Pixel 9","platform":"android","revision":5,"observedAt":5,"playing":false,"positionMs":0,"speed":1,"queue":[],"currentIndex":-1,"source":null,"spoken":true}
        """
        let empty = try JSONDecoder().decode(DevicePlayback.self, from: Data(other.utf8))
        XCTAssertTrue(empty.valid()); XCTAssertNil(empty.current); XCTAssertEqual(empty.speed, 1); XCTAssertTrue(empty.spoken)
        let withSource = try JSONDecoder().decode(DevicePlayback.self, from: Data(other.replacingOccurrences(of: "\"source\":null", with: "\"source\":\"Liked Songs\"").utf8))
        XCTAssertEqual(withSource.source, "Liked Songs")

        let command = try JSONDecoder().decode(DeviceCommand.self, from: Data(#"{"id":"0123456789abcdef","target":"\#(a)","action":"seek","positionMs":42000,"createdAt":1759800000000}"#.utf8))
        XCTAssertTrue(command.valid()); XCTAssertEqual(command.positionMs, 42_000); XCTAssertEqual(command.createdAt, 1_759_800_000_000)
        let list = try JSONDecoder().decode(DeviceList.self, from: Data(#"{"devices":[{"id":"\#(a)","name":"Pixel 9","platform":"android","linkedAt":1759800000000},{"id":"\#(b)","name":"MacBook","platform":"macos","linkedAt":1759800000001}],"revision":1759800000002}"#.utf8))
        XCTAssertTrue(list.valid()); XCTAssertEqual(list.devices.map(\.name), ["Pixel 9", "MacBook"]); XCTAssertEqual(list.revision, 1_759_800_000_002)
        let offer = try JSONDecoder().decode(DeviceCodeOffer.self, from: Data(#"{"owner":"\#(a)","name":"Pixel 9","platform":"android","createdAt":1759800000000}"#.utf8))
        XCTAssertTrue(offer.valid())
        let request = try JSONDecoder().decode(DeviceLinkRequest.self, from: Data(#"{"token":"K7QXM2PA","name":"Pixel 9","platform":"android","createdAt":1759800000000}"#.utf8))
        XCTAssertTrue(request.valid())
        XCTAssertEqual(try JSONDecoder().decode(DeviceUnlink.self, from: Data(#"{"id":"\#(a)"}"#.utf8)).id, a)

        // DeviceSyncState.json() from Android/desktop loads into the Swift rules.
        let saved = #"{"devices":[{"id":"\#(b)","name":"Pixel 9","platform":"android","linkedAt":1759800000000}],"playback":[\#(playback)]}"#
        var restored = DeviceSyncState(me: a)
        restored.load(try JSONDecoder().decode(DeviceSyncSnapshot.self, from: Data(saved.utf8)))
        XCTAssertEqual(restored.playback[b]?.current?.title, "Song 2")

        // Through the packet envelope, as the relay hands it over.
        let packet = SocialPacket(type: "devicePlayback", body: Data(playback.utf8))
        XCTAssertEqual(try packet.decode(DevicePlayback.self), decoded)
    }

    func testEncodesTheSameFieldsAndTypesAsKotlin() throws {
        var playback = state(a, 1_759_800_000_123)
        playback.speed = 1.5
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(playback)) as? [String: Any])
        XCTAssertEqual(Set(object.keys), ["device", "name", "platform", "revision", "observedAt", "playing", "positionMs", "speed", "queue", "currentIndex", "source", "spoken"])
        XCTAssertEqual((object["revision"] as? NSNumber)?.int64Value, 1_759_800_000_123)
        XCTAssertEqual((object["speed"] as? NSNumber)?.doubleValue, 1.5)
        XCTAssertTrue(object["source"] is NSNull, "source is null when absent")
        XCTAssertEqual(object["playing"] as? Bool, true)
        let queue = try XCTUnwrap(object["queue"] as? [[String: Any]])
        XCTAssertEqual(queue.first?["durationMs"] as? Int, 200_000)
        let command = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(DeviceCommand(id: DeviceCommand.newID(), target: b, action: "play"))) as? [String: Any])
        XCTAssertEqual(Set(command.keys), ["id", "target", "action", "positionMs", "createdAt"])
        XCTAssertEqual((command["id"] as? String)?.count, 16)
        let list = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(DeviceList(devices: [LinkedDevice(id: a, name: "iPhone", platform: "ios")]))) as? [String: Any])
        XCTAssertEqual(Set(list.keys), ["devices", "revision"])
        XCTAssertEqual(Set((list["devices"] as? [[String: Any]])?.first?.keys.map { $0 } ?? []), ["id", "name", "platform", "linkedAt"])
    }

    // MARK: Two stores over two relays

    private func wire(_ store: DeviceSyncStore, _ relay: PeerRelay) {
        relay.onPacket = { [weak store] author, packet, encrypted in store?.receive(author, packet, encrypted) }
        store.connect(me: relay.publicKey, transport: .init(
            send: { packet, logical, recipient, expiresIn, tags in try await relay.send(packet, logical: logical, to: recipient, expiresIn: expiresIn, extraTags: tags) },
            lookup: { tag, timeout in await relay.lookup(tag, timeout: timeout) },
            found: { tag, author in relay.lookupFound(tag, author: author) },
            wanted: { _ in }))
    }
    private func pipe(_ from: PeerRelay, _ to: PeerRelay) { for event in from.outgoing { to.receive("[\"EVENT\",\"test\",\(event.json)]") } }
    private func until(_ what: String, pump: () -> Void, _ done: () -> Bool) async throws {
        for _ in 0..<100 {
            pump()
            if done() { return }
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTFail("Timed out: " + what)
    }

    func testTwoDevicesPairShareStateAndControl() async throws {
        let id = UUID().uuidString
        let relayA = PeerRelay(keys: Keys.generate(), storage: "device-relay-a-" + id, testMode: true)
        let relayB = PeerRelay(keys: Keys.generate(), storage: "device-relay-b-" + id, testMode: true)
        let phone = DeviceSyncStore(storage: "device-test-a-" + id, name: "Test iPhone")
        let tablet = DeviceSyncStore(storage: "device-test-b-" + id, name: "Test iPad")
        wire(phone, relayA); wire(tablet, relayB)
        tablet.lookupTimeout = .seconds(5)
        let pump = { self.pipe(relayA, relayB); self.pipe(relayB, relayA) }

        await phone.showCode()
        guard case .showing(let code, _) = phone.pairing else { return XCTFail("No code shown") }
        let offer = try XCTUnwrap(relayA.outgoing.first)
        XCTAssertTrue(offer.json.contains(DeviceSyncState.lookupTag(code)), "offer carries the lookup tag")
        XCTAssertFalse(offer.json.contains(code), "the code itself never leaves the device")

        tablet.enterCode(DeviceSyncState.displayCode(code).lowercased())
        XCTAssertEqual(tablet.pairing, .searching)
        try await Task.sleep(for: .milliseconds(200))
        // Someone else's offer for another code must not be taken.
        let other = PeerRelay(keys: Keys.generate(), storage: "device-relay-x-" + id, testMode: true)
        try await other.send(.make("deviceCode", DeviceCodeOffer(owner: other.publicKey, name: "Stranger", platform: "linux")), logical: "deviceCode",
                             expiresIn: DeviceSyncState.codeLifetime, extraTags: [["t", DeviceSyncState.lookupTag("AAAABBBB")]])
        pipe(other, relayB)
        XCTAssertEqual(tablet.pairing, .searching)
        try await until("link request", pump: pump) { phone.linkRequest != nil }
        XCTAssertEqual(tablet.pairing, .waiting(name: "Test iPhone"))
        XCTAssertEqual(phone.linkRequest?.request.name, "Test iPad")
        XCTAssertTrue(phone.devices.isEmpty, "waits for the user")

        phone.allow(try XCTUnwrap(phone.linkRequest))
        try await until("linked", pump: pump) { tablet.pairing == .linked("Test iPhone") }
        XCTAssertEqual(phone.devices.map(\.id), [relayB.publicKey])
        XCTAssertEqual(tablet.devices.map(\.id), [relayA.publicKey])
        XCTAssertEqual(tablet.devices.first?.platform, "ios")

        // Playback state reaches the other device and shows as "Playing on".
        let playing = DevicePlayback(device: relayA.publicKey, name: "Test iPhone", platform: "ios", revision: 1, observedAt: SocialRules.now, playing: true,
                                     positionMs: 5_000, queue: [track(1), track(2)], currentIndex: 0, source: "Liked Songs")
        await phone.sendState(playing)
        try await until("state", pump: pump) { tablet.active?.device == relayA.publicKey }
        XCTAssertEqual(tablet.active?.source, "Liked Songs")
        XCTAssertGreaterThanOrEqual(tablet.expectedPosition(try XCTUnwrap(tablet.active)), 5_000)

        // Remote control: shown state flips at once; the command arrives once.
        var obeyed: [String] = []
        phone.onCommand = { command, author in XCTAssertEqual(author, relayB.publicKey); obeyed.append(command.action) }
        await tablet.control(relayA.publicKey, "pause")
        XCTAssertEqual(tablet.playback(relayA.publicKey)?.playing, false)
        XCTAssertNil(tablet.active)
        try await until("command", pump: pump) { obeyed == ["pause"] }
        pump(); XCTAssertEqual(obeyed, ["pause"])

        // The group and last state survive a restart.
        await Store.flush()
        let reopened = DeviceSyncStore(storage: "device-test-b-" + id, name: "Test iPad")
        reopened.connect(me: relayB.publicKey, transport: .init(send: { _, _, _, _, _ in }, lookup: { _, _ in }, found: { _, _ in false }, wanted: { _ in }))
        XCTAssertEqual(reopened.devices.map(\.id), [relayA.publicKey])
        XCTAssertEqual(reopened.playback(relayA.publicKey)?.source, "Liked Songs")

        // Removing a device makes it forget the group too.
        await phone.remove(relayB.publicKey)
        XCTAssertTrue(phone.devices.isEmpty)
        try await until("unlinked", pump: pump) { tablet.devices.isEmpty }
    }

    func testWrongCodeFailsAfterTheLookup() async throws {
        let relay = PeerRelay(keys: Keys.generate(), storage: "device-relay-c-" + UUID().uuidString, testMode: true)
        let store = DeviceSyncStore(storage: "device-test-c-" + UUID().uuidString, name: "Test iPad")
        wire(store, relay)
        store.lookupTimeout = .milliseconds(200)
        store.enterCode("abc")
        XCTAssertEqual(store.pairing, .failed("Codes have 8 letters and numbers."))
        store.enterCode("ZZZZ-ZZZZ")
        try await until("lookup timeout", pump: {}) { if case .failed = store.pairing { return true }; return false }
        XCTAssertEqual(store.pairing, .failed("That code didn't match. Check it on your other device — codes last 10 minutes."))
    }
}
