import XCTest
@testable import Spitify

final class SearchAndAlbumTests: XCTestCase {
    func testExactTitleWinsAndAllQueryWordsMustMatch() {
        XCTAssertGreaterThan(SearchMatch.score("hello", title: "Hello", artist: "Adele")!, SearchMatch.score("hello", title: "Hello Again", artist: "Other")!)
        XCTAssertNotNil(SearchMatch.score("joji high hopes", title: "High Hopes", artist: "Joji, Omar Apollo"))
        XCTAssertNil(SearchMatch.score("joji nectar", title: "High Hopes", artist: "Other"))
        XCTAssertEqual(SearchMatch.score("beyonce", title: "Beyoncé", artist: "Artist"), 1000)
    }
    func testGuestCreditsShareAnAlbumWithoutLosingSongCredits() {
        let credits = ["Joji", "Joji; Omar Apollo", "Joji feat. Diplo", "Joji, Lil Yachty", "Other Artist"]
        let songs = credits.enumerated().map { i, credit in
            Song(id: "\(i)", title: "Track \(i)", artist: credit, album: "Nectar", albumArtist: credit, durationMs: 180000,
                 track: i + 1, disc: 1, year: 2020, location: "Music/Nectar/\(i).flac", kind: .file,
                 dateAdded: Date(), sizeBytes: 100, fileExtension: "flac")
        }
        let library = Library.build(songs)
        XCTAssertEqual(library.albums.count, 2)
        XCTAssertEqual(library.albums.first { $0.artist == "Joji" }?.songs.count, 4)
        for song in songs { XCTAssertEqual(library.songById[song.id]?.artist, song.artist) }
        XCTAssertEqual(Song.albumArtist("Tyler, The Creator"), "Tyler, The Creator")
    }
    func testAlternateAudioRejectsWrongVersionsAndUntrustedURLs() {
        let track = OnlineTrack(id: "1", title: "High Hopes", artist: "Joji", album: "Nectar", releaseID: "2", durationMs: 183000, trackNumber: 1, discNumber: 1, artwork: nil, playable: false)
        XCTAssertTrue(AudioFallback.matches(track, title: "Joji - High Hopes (Official Audio)", author: "Joji", durationMs: 183000))
        XCTAssertFalse(AudioFallback.matches(track, title: "High Hopes live", author: "Joji", durationMs: 183000))
        XCTAssertFalse(AudioFallback.matches(track, title: "High Hopes", author: "Joji", durationMs: 240000))
        XCTAssertFalse(AudioFallback.matches(track, title: "High Hopes", author: "Other", durationMs: 183000))
        XCTAssertTrue(AudioFallback.validAudioURL("https://rr1.googlevideo.com/videoplayback?id=test"))
        XCTAssertFalse(AudioFallback.validAudioURL("https://rr1.googlevideo.com.attacker.test/audio"))
        XCTAssertFalse(AudioFallback.validAudioURL("https://user@rr1.googlevideo.com/audio"))
        XCTAssertFalse(AudioFallback.validAudioURL("http://rr1.googlevideo.com/audio"))
    }
}
