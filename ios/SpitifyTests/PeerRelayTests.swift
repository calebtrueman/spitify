import XCTest
import NostrSDK
@testable import Spitify

@MainActor
final class PeerRelayTests: XCTestCase {
    // Public test identities, never used by an installed user's account.
    private func sender() throws -> Keys { try Keys.parse(secretKey: String(repeating: "1", count: 64)) }
    private func receiver() throws -> Keys { try Keys.parse(secretKey: String(repeating: "2", count: 64)) }
    private func relay(_ keys: Keys) -> PeerRelay { PeerRelay(keys: keys, storage: "relay-test-" + UUID().uuidString, testMode: true) }
    private func wire(_ json: String) -> String { "[\"EVENT\",\"test\",\(json)]" }

    func testPrivateMessagesVerifySignatureRecipientAndDuplicateDelivery() async throws {
        let a = relay(try sender()), b = relay(try receiver()), other = relay(Keys.generate())
        let playlist = SharedPlaylist(id: "wire-small", owner: a.publicKey, name: "Private test", tracks: [.init(id: "one", title: "One", artist: "Artist")])
        var delivered = 0
        b.onPacket = { author, packet, encrypted in
            XCTAssertEqual(author, a.publicKey); XCTAssertTrue(encrypted)
            XCTAssertEqual(try? packet.decode(SharedPlaylist.self), playlist); delivered += 1
        }
        other.onPacket = { _, _, _ in XCTFail("Wrong recipient received private data") }
        try await a.send(.make("playlist", playlist), logical: "playlist:wire-small", to: b.publicKey)
        let event = try XCTUnwrap(a.outgoing.first)
        other.receive(wire(event.json)); b.receive(wire(event.json)); b.receive(wire(event.json))
        XCTAssertEqual(delivered, 1)
        var modified = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(event.json.utf8)) as? [String: Any])
        modified["content"] = "changed"
        let forged = String(data: try JSONSerialization.data(withJSONObject: modified), encoding: .utf8)!
        let fresh = relay(try receiver()); fresh.onPacket = { _, _, _ in XCTFail("Forged event accepted") }; fresh.receive(wire(forged))
    }
    func testLargePlaylistWaitsForAllPartsAndSurvivesOfflineRestart() async throws {
        let a = relay(try sender()), b = relay(try receiver())
        let tracks = (0..<1200).map { SharedTrack(id: "track-\($0)", title: "Song \($0)", artist: "Artist", album: "Large playlist", durationMs: 120000) }
        let playlist = SharedPlaylist(id: "wire-large", owner: a.publicKey, name: "Large test", tracks: tracks)
        var received: SharedPlaylist?
        b.onPacket = { _, packet, _ in received = try? packet.decode(SharedPlaylist.self) }
        try await a.send(.make("playlist", playlist), logical: "playlist:wire-large", to: b.publicKey)
        XCTAssertGreaterThan(a.outgoing.count, 1)
        let parts = a.outgoing
        for part in parts.dropLast().reversed() { b.receive(wire(part.json)) }
        XCTAssertNil(received)
        b.receive(wire(parts.last!.json))
        XCTAssertEqual(received, playlist)
        let storage = "relay-offline-" + UUID().uuidString
        let offline = PeerRelay(keys: try sender(), storage: storage, testMode: true)
        try await offline.send(.make("playlist", playlist), logical: "playlist:wire-large", to: b.publicKey)
        let reopened = PeerRelay(keys: try sender(), storage: storage, testMode: true)
        XCTAssertEqual(reopened.outgoing.map(\.id), offline.outgoing.map(\.id))
        for part in reopened.outgoing { reopened.receive("[\"OK\",\"\(part.id)\",true,\"saved\"]") }
        XCTAssertTrue(reopened.outgoing.isEmpty)
    }
    func testExportSwiftSignedMessagesForAndroid() async throws {
        let a = relay(try sender()), b = try receiver()
        let tracks = (0..<500).map { SharedTrack(id: "cross-\($0)", title: "Song \($0)", artist: "Cross platform", album: "Fixture", durationMs: 90000) }
        let playlist = SharedPlaylist(id: "cross-platform", owner: a.publicKey, name: "Swift to Android", tracks: tracks)
        try await a.send(.make("playlist", playlist), logical: "playlist:cross-platform", to: b.publicKey().toHex())
        let events = try a.outgoing.map { try JSONSerialization.jsonObject(with: Data($0.json.utf8)) }
        let fixture: [String: Any] = ["sender": a.publicKey, "recipient": b.publicKey().toHex(), "events": events]
        try JSONSerialization.data(withJSONObject: fixture).write(to: Store.directory.appendingPathComponent("swift-wire-fixture.json"), options: .atomic)
    }
    func testAndroidSignedMessagesIfFixturePresent() throws {
        let url = Store.directory.appendingPathComponent("android-wire-fixture.json")
        guard FileManager.default.fileExists(atPath: url.path) else { throw XCTSkip("Run Android fixture export first.") }
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let events = try XCTUnwrap(fixture["events"] as? [[String: Any]])
        let b = relay(try sender()); var playlist: SharedPlaylist?
        b.onPacket = { author, packet, encrypted in XCTAssertEqual(author, fixture["sender"] as? String); XCTAssertTrue(encrypted); playlist = try? packet.decode(SharedPlaylist.self) }
        for event in events.reversed() { b.receive(wire(String(data: try JSONSerialization.data(withJSONObject: event), encoding: .utf8)!)) }
        XCTAssertEqual(playlist?.name, "Android to Swift"); XCTAssertEqual(playlist?.tracks.count, 500)
    }
}
