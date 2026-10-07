import XCTest
import AVFoundation
@testable import Spitify

@MainActor final class FriendsLibraryTests: XCTestCase {
    func testFriendPictureCodeRoundTripRejectsOtherKinds() throws {
        let link = SocialLink(type: "person", owner: String(repeating: "a", count: 64)).url.absoluteString
        let image = try XCTUnwrap(FriendPictureCode.make(link))
        XCTAssertEqual(FriendPictureCode.read(try XCTUnwrap(image.pngData())), link)
        XCTAssertNil(FriendPictureCode.make("spitify://room/" + String(repeating: "b", count: 64) + "/party"))
    }
    func testKnownAlbumArtistSeparatesGuestWithoutSplittingBandNames() {
        XCTAssertEqual(ArtistCredits.names("Justin Bieber AND Glup Shitto", albumArtist: "Justin Bieber"), ["Justin Bieber", "Glup Shitto"])
        XCTAssertEqual(ArtistCredits.primary("Justin Bieber AND Glup Shitto", albumArtist: "Justin Bieber"), "Justin Bieber")
        for band in ["Florence and the Machine", "Earth, Wind & Fire", "Simon & Garfunkel"] { XCTAssertEqual(ArtistCredits.names(band), [band]) }
        XCTAssertEqual(ArtistCredits.primary("Foobar", albumArtist: "Foo"), "Foobar")
    }
    func testImportedFileRepairsFailedMatchAndManualChoiceKeepsOrder() async throws {
        let app = AppModel()
        let title = "Match check " + UUID().uuidString
        let source = FileManager.default.temporaryDirectory.appendingPathComponent(title + ".wav")
        let format = AVAudioFormat(standardFormatWithSampleRate: 44100, channels: 1)!
        let file = try AVAudioFile(forWriting: source, settings: format.settings)
        let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 4410)!
        buffer.frameLength = 4410
        try file.write(from: buffer)
        let track = SharedTrack(title: title, artist: "Match Artist")
        let manualTrack = SharedTrack(title: "Different title " + title, artist: "Other credit")
        let original = [SharedTrack(title: "Before", artist: "Artist"), track, manualTrack, SharedTrack(title: "After", artist: "Artist")]
        PlaylistMatches.shared.missing(track); PlaylistMatches.shared.missing(manualTrack)
        defer { try? FileManager.default.removeItem(at: source) }
        let count = await app.library.importItems([source], asAudiobooks: false)
        XCTAssertEqual(count, 1)
        let song = try XCTUnwrap(app.library.library.songs.first { $0.fileName == source.lastPathComponent })
        let imported = app.library.fileURL(song)
        // Delete the imported copy and rescan, so later tests (autoplay) never pick a song whose file is gone.
        addTeardownBlock { @MainActor in
            if let imported { try? FileManager.default.removeItem(at: imported) }
            await app.library.scan()
        }
        app.library.saveOverride(MetadataOverride(title: title, artist: "Match Artist", source: "user"), for: [song])
        let matched = try await SharedSongMatch.resolve(track, app: app)
        XCTAssertEqual(matched.id, song.id)
        XCTAssertFalse(PlaylistMatches.shared.failed.contains(PlaylistMatches.key(track)))
        PlaylistMatches.shared.choose(matched, for: manualTrack)
        await Store.flush()
        XCTAssertEqual(PlaylistMatches().manual(manualTrack, app: app)?.id, song.id)
        let picked = try await SharedSongMatch.resolve(manualTrack, app: app)
        XCTAssertEqual(picked.id, song.id)
        XCTAssertEqual(original.filter { !PlaylistMatches.shared.failed.contains(PlaylistMatches.key($0)) }.map(\.id), original.map(\.id))
    }
    func testProfileReadsOldAndNewFormats() throws {
        let key = String(repeating: "b", count: 64)
        let old = "{\"id\":\"\(key)\",\"name\":\"Alex\",\"about\":\"Music\",\"updatedAt\":1}"
        XCTAssertTrue(try JSONDecoder().decode(FriendProfile.self, from: Data(old.utf8)).valid())
        let profile = FriendProfile(photo: Data([1, 2, 3]).base64EncodedString(), isPublic: false, id: key, name: "Alex")
        XCTAssertEqual(try JSONDecoder().decode(FriendProfile.self, from: JSONEncoder().encode(profile)), profile)
    }
}
