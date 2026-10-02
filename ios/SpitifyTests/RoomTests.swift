import XCTest
@testable import Spitify

final class RoomTests: XCTestCase {
    let host = String(repeating: "a", count: 64)
    let guest = String(repeating: "b", count: 64)
    let stranger = String(repeating: "c", count: 64)

    func testJoinRequiresExplicitRequestAndHostSignature() {
        var state = RoomState()
        let room = ListeningRoom(host: host, name: "Test", members: [guest])
        XCTAssertFalse(state.accept(room, author: host, me: guest, encrypted: true))
        state.requestedKey = room.key
        XCTAssertFalse(state.accept(room, author: stranger, me: guest, encrypted: true))
        XCTAssertFalse(state.accept(room, author: host, me: guest, encrypted: false))
        XCTAssertTrue(state.accept(room, author: host, me: guest, encrypted: true))
        XCTAssertEqual(state.activeKey, room.key)
    }
    func testLeavingAndEndingRejectDelayedPlayback() {
        var state = RoomState()
        var room = ListeningRoom(host: host, name: "Test", members: [guest])
        state.requestedKey = room.key
        XCTAssertTrue(state.accept(room, author: host, me: guest, encrypted: true))
        state.activeKey = nil
        room.revision += 1
        XCTAssertFalse(state.accept(room, author: host, me: guest, encrypted: true))
        state.requestedKey = room.key; room.ended = true
        XCTAssertTrue(state.accept(room, author: host, me: guest, encrypted: true))
        XCTAssertNil(state.activeKey)
        state.requestedKey = room.key; room.revision += 1; room.ended = false
        XCTAssertFalse(state.accept(room, author: host, me: guest, encrypted: true))
    }
    func testStaleExpiredAndRemovedMembersDoNotKeepPlaying() {
        var state = RoomState()
        var room = ListeningRoom(host: host, name: "Test", members: [guest])
        state.requestedKey = room.key
        room.observedAt = SocialRules.now - 180_000
        XCTAssertFalse(state.accept(room, author: host, me: guest, encrypted: true))
        room.observedAt = SocialRules.now; room.expiresAt = SocialRules.now - 1
        XCTAssertTrue(state.accept(room, author: host, me: guest, encrypted: true)); XCTAssertNil(state.activeKey)
        state.requestedKey = room.key; room.expiresAt = SocialRules.now + 10_000; room.revision += 1; room.members = []
        XCTAssertTrue(state.accept(room, author: host, me: guest, encrypted: true)); XCTAssertNil(state.activeKey)
    }
    func testRequestPermissionsDuplicatesAndCancellingJoin() {
        var state = RoomState()
        let room = ListeningRoom(host: host, name: "Test", members: [guest])
        state.rooms[room.key] = room; state.activeKey = room.key
        let control = RoomRequest(roomID: room.id, host: host, action: "control", playing: true)
        XCTAssertFalse(state.receive(control, author: stranger, me: host))
        XCTAssertTrue(state.receive(control, author: guest, me: host))
        XCTAssertFalse(state.receive(control, author: guest, me: host))
        let join = RoomRequest(roomID: room.id, host: host, action: "join")
        XCTAssertTrue(state.receive(join, author: stranger, me: host))
        XCTAssertTrue(state.receive(RoomRequest(roomID: room.id, host: host, action: "leave"), author: stranger, me: host))
    }
    func testPlaybackPositionAccountsForMessageAgeAndSongEnd() {
        let track = SharedTrack(title: "Song", artist: "Artist", durationMs: 100_000)
        var room = ListeningRoom(host: host, name: "Test", queue: [track], currentID: track.id, positionMs: 40_000, playing: true, observedAt: 1000)
        XCTAssertEqual(RoomState.expectedPosition(room, now: 4000), 43_000)
        XCTAssertEqual(RoomState.expectedPosition(room, now: 100_000), 100_000)
        room.playing = false
        XCTAssertEqual(RoomState.expectedPosition(room, now: 4000), 40_000)
    }
    func testSharedMixBalancesContributionsAndDeduplicatesSongs() {
        let one = SharedTrack(title: "One", artist: "Artist")
        let two = SharedTrack(title: "Two", artist: "Artist")
        let three = SharedTrack(title: "Three", artist: "Artist")
        let result = SocialRules.mix([[one, two], [three, one]])
        XCTAssertEqual(result.map(\.title), ["One", "Three", "Two"])
        XCTAssertEqual(Set(result.map(\.id)).count, 3)
    }
}
