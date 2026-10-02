import XCTest
@testable import Spitify

final class AutomaticMetadataTests: XCTestCase {
    func testFillsEachGapWithoutReplacingStoredOrSavedValues() {
        let stored = MetadataOverride(title: "Original", artist: "Artist", album: "Album", track: 4, source: "online")
        let saved = MetadataOverride(genre: "My genre", year: 2020, source: "user")
        let online = MetadataOverride(title: "Wrong title", artist: "Wrong artist", album: "Wrong album", albumArtist: "Band", genre: "Online genre", year: 2026, track: 7, disc: 2, source: "online")
        XCTAssertEqual(MissingMetadata.fill(stored, saved: saved, suggested: online),
                       MetadataOverride(albumArtist: "Band", genre: "My genre", year: 2020, disc: 2, source: "online"))
        XCTAssertTrue(MissingMetadata.incomplete(stored, saved: saved))
        XCTAssertEqual(MissingMetadata.fill(online, saved: saved, suggested: stored), MetadataOverride(source: "online"))
    }
}
