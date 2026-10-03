import XCTest
@testable import Spitify

final class SearchAndAlbumTests: XCTestCase {
    func testBlankSavedAlbumDoesNotDuplicateDownloadedSong() {
        let local = Song(id: "file", title: "Style", artist: "Taylor Swift", album: "1989 (Deluxe Edition)", albumArtist: "Taylor Swift", durationMs: 231000, track: 3, disc: 1, year: 2014, location: "Style.flac", kind: .file, dateAdded: Date(), sizeBytes: 100, fileExtension: "flac")
        var blankLocal = local; blankLocal.album = ""
        XCTAssertEqual(Library.completeAlbumDetails(blankLocal, from: [local]).album, local.album)
        var saved = local; saved.id = "stream"; saved.kind = .remote; saved.album = ""
        XCTAssertTrue(Library.isDownloadedCopy(local, of: saved))
        saved.title = "Blank Space"
        XCTAssertFalse(Library.isDownloadedCopy(local, of: saved))
        XCTAssertEqual(Library.build([saved]).albums.first?.title, "Unknown album")
        saved.title = local.title; saved.album = "1989 (Taylor's Version)"
        XCTAssertFalse(Library.isDownloadedCopy(local, of: saved))
    }

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
    @MainActor func testFeaturedArtistsHaveTheirOwnPagesAndRetainCredits() throws {
        let track = try XCTUnwrap(MonochromeClient.track([
            "trackId": "123", "title": "Tomorrow Never Came", "albumTitle": "Lust for Life",
            "artistNames": ["Lana Del Rey", "Sean Ono Lennon"], "duration": 300000
        ]))
        XCTAssertEqual(track.artist, "Lana Del Rey, Sean Ono Lennon")
        XCTAssertEqual(track.primaryArtist, "Lana Del Rey")
        let data = try JSONEncoder().encode(track)
        let restored = try JSONDecoder().decode(OnlineTrack.self, from: data)
        var oldEntry = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        oldEntry.removeValue(forKey: "artistNames")
        let legacy = try JSONDecoder().decode(OnlineTrack.self, from: JSONSerialization.data(withJSONObject: oldEntry))
        XCTAssertNil(legacy.artistNames)
        XCTAssertEqual(legacy.artist, track.artist)
        let song = MusicStreams.song(restored)
        let library = Library.build([song])
        XCTAssertEqual(Set(library.artists.map(\.name)), ["Lana Del Rey"])
        XCTAssertNil(library.artistByName["Sean Ono Lennon"]?.ownCover)
        XCTAssertTrue(library.artistByName["Sean Ono Lennon"]?.albums.isEmpty == true)
        XCTAssertEqual(library.artistByName["Sean Ono Lennon"]?.songs.map(\.id), [song.id])
        XCTAssertEqual(library.albums.first?.artist, "Lana Del Rey")
        XCTAssertEqual(library.songById[song.id]?.artist, track.artist)
        var downloaded = song; downloaded.id = "download"; downloaded.kind = .file; downloaded.artistNames = nil
        XCTAssertEqual(Library.completeAlbumDetails(downloaded, from: [song]).creditedArtists, track.artistNames)
    }

    func testOldFilesUseKnownArtistBoundariesWithoutBreakingBandNames() {
        func song(_ id: String, _ artist: String, _ albumArtist: String) -> Song {
            Song(id: id, title: id, artist: artist, album: "Album " + id, albumArtist: albumArtist,
                 durationMs: 180000, track: 1, disc: 1, year: 2020, location: id + ".flac", kind: .file,
                 dateAdded: Date(), sizeBytes: 100, fileExtension: "flac")
        }
        let library = Library.build([
            song("solo", "Lana Del Rey", "Lana Del Rey"),
            song("guest", "Lana Del Rey, Sean Ono Lennon", "Lana Del Rey, Sean Ono Lennon"),
            song("band", "Earth, Wind & Fire", "Earth, Wind & Fire"),
            song("tyler", "Tyler, The Creator", "Tyler, The Creator"),
            song("feature", "Lana Del Rey (feat. Father John Misty)", "Lana Del Rey")
        ])
        XCTAssertEqual(library.artistByName["Lana Del Rey"]?.songs.count, 3)
        XCTAssertNotNil(library.artistByName["Sean Ono Lennon"])
        XCTAssertNotNil(library.artistByName["Father John Misty"])
        XCTAssertNotNil(library.artistByName["Earth, Wind & Fire"])
        XCTAssertNotNil(library.artistByName["Tyler, The Creator"])
        XCTAssertNil(library.artistByName["Lana Del Rey, Sean Ono Lennon"])
        XCTAssertEqual(ArtistCredits.names("Broadcast (UK)"), ["Broadcast (UK)"])
        XCTAssertEqual(ArtistCredits.names("Simon & Garfunkel"), ["Simon & Garfunkel"])
        XCTAssertEqual(library.songById["guest"]?.artist, "Lana Del Rey, Sean Ono Lennon")
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
