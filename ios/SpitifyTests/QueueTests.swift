import XCTest
@testable import Spitify

final class QueueTests: XCTestCase {
    private func song(_ id: String) -> Song {
        Song(id: id, title: id, artist: "Test", album: "Test", albumArtist: "Test", durationMs: 1000,
             track: 1, disc: 1, year: 0, location: "unused.flac", kind: .file,
             dateAdded: Date(timeIntervalSince1970: 0), sizeBytes: 0, fileExtension: "flac")
    }

    @MainActor func testManualPicksPlayBeforeLibraryInAddedOrder() {
        let p = Player()
        p.play([song("A"), song("B"), song("C")], shuffle: false)
        p.addToQueue([song("X")])
        p.addToQueue([song("Y"), song("Z")])
        XCTAssertEqual(p.upNext.map { $0.1.id }, ["X", "Y", "Z", "B", "C"])
        p.next()
        p.addToQueue([song("W")])
        XCTAssertEqual(p.upNext.map { $0.1.id }, ["Y", "Z", "W", "B", "C"])
        p.playNext([song("N")])
        XCTAssertEqual(p.upNext.map { $0.1.id }, ["N", "Y", "Z", "W", "B", "C"])
    }

    @MainActor func testShuffleKeepsManualOrderAndDuplicateSeparateFromLibrary() {
        let p = Player()
        p.play([song("A"), song("B"), song("C")], shuffle: false)
        p.addToQueue([song("B"), song("X")])
        p.toggleShuffle()
        XCTAssertEqual(p.upNext.prefix(2).map { $0.1.id }, ["B", "X"])
        p.toggleShuffle()
        XCTAssertEqual(p.upNext.map { $0.1.id }, ["B", "X", "B", "C"])
        p.next()
        p.toggleShuffle()
        p.toggleShuffle()
        XCTAssertEqual(p.manuallyQueued.map { $0.1.id }, ["X"])
        XCTAssertEqual(p.nextFromSource.map { $0.1.id }, ["A", "B", "C"])
    }

    @MainActor func testRemoveMoveClearAndRestoreKeepManualPicks() {
        let p = Player()
        let songs = ["A", "B", "C", "X", "Y", "Z"].map(song)
        p.play(Array(songs.prefix(3)), shuffle: false)
        p.addToQueue(Array(songs.suffix(3)))
        p.remove(at: 2)
        p.move(from: 2, to: 1)
        XCTAssertEqual(p.manuallyQueued.map { $0.1.id }, ["Z", "X"])
        let restored = Player()
        restored.restore { id in songs.first { $0.id == id } }
        XCTAssertEqual(restored.manuallyQueued.map { $0.1.id }, ["Z", "X"])
        restored.addToQueue([song("Y")])
        XCTAssertEqual(restored.upNext.map { $0.1.id }, ["Z", "X", "Y", "B", "C"])
        restored.clearUpNext()
        restored.addToQueue([song("B")])
        XCTAssertEqual(restored.upNext.map { $0.1.id }, ["B"])
    }
}
