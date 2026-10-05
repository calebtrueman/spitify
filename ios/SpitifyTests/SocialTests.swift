import XCTest
import UIKit
@testable import Spitify

final class SocialTests: XCTestCase {
    @MainActor func testHomeAlwaysClearsItsOldPage() {
        let router = Router()
        router.go(.settings)
        router.reselect(.home)
        XCTAssertEqual(router.paths[.home]?.count, 0)
        router.go(.settings)
        router.reselect(.library)
        router.reselect(.home)
        XCTAssertEqual(router.tab, .home)
        XCTAssertEqual(router.paths[.home]?.count, 0)
    }

    @MainActor func testPlaylistReusesPreviouslyMatchedSongWithoutSearch() async throws {
        let app = AppModel()
        let title = "Saved match " + UUID().uuidString
        let track = OnlineTrack(id: "998811221", title: title, artist: "Test Artist", album: "Test Album", releaseID: "1", durationMs: 120000, trackNumber: 1, discNumber: 1, artwork: "https://images.example.com/matched-cover.jpg", playable: true)
        let expected = app.musicStreams.register(track)
        let shared = SharedTrack(title: title, artist: "Test Artist", durationMs: 120000)
        let result = try await SharedSongMatch.resolve(shared, app: app)
        XCTAssertEqual(result.id, expected.id)
        XCTAssertEqual(result.artURL, track.artwork)
        XCTAssertNil(shared.artwork)
    }
    func testExplicitFlagSurvivesSongConversionAndOldSavedTracks() throws {
        let track = try XCTUnwrap(MonochromeClient.track(["id": "123", "title": "Song", "artistNames": ["Artist"], "explicit": true]))
        XCTAssertEqual(track.explicit, true)
        let encoded = try JSONEncoder().encode(track)
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any]); object.removeValue(forKey: "explicit")
        XCTAssertNil(try JSONDecoder().decode(OnlineTrack.self, from: JSONSerialization.data(withJSONObject: object)).explicit)
    }
    let owner = String(repeating: "a", count: 64)
    let editor = String(repeating: "b", count: 64)
    let stranger = String(repeating: "c", count: 64)

    func testOnlyOwnerCanReplaceSharedPlaylist() {
        var state = SocialState(); state.following.insert(owner)
        let playlist = SharedPlaylist(owner: owner, name: "Friends", isPublic: true)
        XCTAssertFalse(state.acceptPlaylist(playlist, author: stranger, me: editor, encrypted: false))
        XCTAssertTrue(state.acceptPlaylist(playlist, author: owner, me: editor, encrypted: false))
        XCTAssertFalse(state.acceptPlaylist(playlist, author: owner, me: editor, encrypted: false))
        var privateCopy = playlist; privateCopy.revision += 1; privateCopy.isPublic = false
        XCTAssertFalse(state.acceptPlaylist(privateCopy, author: owner, me: editor, encrypted: false))
        XCTAssertTrue(state.acceptPlaylist(privateCopy, author: owner, me: editor, encrypted: true))
    }

    func testEditsNeedPermissionAndApplyOnlyOnce() throws {
        var state = SocialState()
        let playlist = SharedPlaylist(owner: owner, name: "Friends", editors: [editor])
        state.playlists[playlist.key] = playlist
        let edit = SharedEdit(playlistID: playlist.id, owner: owner, action: "add", tracks: [.init(title: "One", artist: "Artist")])
        XCTAssertNil(state.apply(edit, author: stranger, me: owner))
        XCTAssertEqual(state.apply(edit, author: editor, me: owner)?.tracks.count, 1)
        XCTAssertNil(state.apply(edit, author: editor, me: owner))
        state.playlists[playlist.key]?.editors = []
        var next = edit; next.id = "new-request"
        XCTAssertNil(state.apply(next, author: editor, me: owner))
    }

    func testSpotifyKeepsSourceMetadataAndLabelsMissingSongs() throws {
        let input: [String: Any] = ["name": "Source name", "description": "A &amp; B", "total_tracks": 3,
            "tracks": [["title": "Song", "artist": "Singer", "duration_ms": 1000], ["title": "Missing artist"]]]
        let playlist = try SpotifyPlaylists.parse(input, id: "37i9dQZF1DXcBWIGoYBM5M", owner: owner)
        XCTAssertEqual(playlist.name, "Source name")
        XCTAssertEqual(playlist.description, "A & B")
        XCTAssertEqual(playlist.tracks.count, 1)
        XCTAssertEqual(playlist.sourceCount, 3)
        XCTAssertTrue(playlist.partial)
        XCTAssertEqual(playlist.tracks[0].title, "Song")
        XCTAssertEqual(playlist.sourceURL, "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
    }

    func testImportedPlaylistPreservesSafeSongArtworkAndSourceOrder() throws {
        let input: [String: Any] = ["name": "Evening", "total_tracks": 3, "tracks": [
            ["id": "first", "title": "One", "artist": "Singer", "artwork": "https://images.example.com/one.jpg"],
            ["id": "second", "title": "Two", "artist": "Singer", "thumbnail": "https://images.example.com/two.jpg"],
            ["id": "third", "title": "Three", "artist": "Singer", "artwork": "file:///private/image.jpg"]
        ]]
        let playlist = try SpotifyPlaylists.parse(input, id: "37i9dQZF1DXcBWIGoYBM5M", owner: owner)
        XCTAssertEqual(playlist.tracks.map(\.title), ["One", "Two", "Three"])
        XCTAssertEqual(playlist.tracks.map(\.spotifyID), ["first", "second", "third"])
        XCTAssertEqual(playlist.tracks[0].artwork, "https://images.example.com/one.jpg")
        XCTAssertEqual(playlist.tracks[1].artwork, "https://images.example.com/two.jpg")
        XCTAssertNil(playlist.tracks[2].artwork)
        XCTAssertFalse(playlist.partial)
        XCTAssertEqual(try JSONDecoder().decode(SharedPlaylist.self, from: JSONEncoder().encode(playlist)), playlist)
    }

    func testSpotifyHandshakeAndTargetParsing() {
        // RFC 6238 SHA-1 example, using Spotify's six-digit output.
        XCTAssertEqual(SpotifyCodeLookup.totp(base32: "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", time: 59), "287082")
        XCTAssertEqual(SpotifyCodeTarget.parse("spotify:user:owner:playlist:37i9dQZF1DXcBWIGoYBM5M")?.kind, "playlist")
        for kind in ["track", "album", "artist", "show", "episode", "audiobook", "user"] { XCTAssertEqual(SpotifyCodeTarget.parse("spotify:\(kind):abc123")?.kind, kind) }
        XCTAssertNil(SpotifyCodeTarget.parse("https://attacker.example"))
        XCTAssertNil(SpotifyCodeTarget.parse("spotify:track:../../bad"))
    }

    func testSpotifyCodeImageReaderHandlesLightDarkAndSideways() {
        let bars = [0,6,6,0,7,6,0,2,2,3,1,7,0,7,6,4,6,1,4,7,4,1,0]
        for light in [false, true] {
            for sideways in [false, true] {
                let format = UIGraphicsImageRendererFormat(); format.scale = 1
                let image = UIGraphicsImageRenderer(size: CGSize(width: 640, height: 640), format: format).image { canvas in
                    (light ? UIColor.black : UIColor.white).setFill(); canvas.fill(CGRect(x: 0, y: 0, width: 640, height: 640))
                    (light ? UIColor.white : UIColor.black).setFill()
                    for (i, level) in bars.enumerated() {
                        let length = CGFloat(12 + level * 8), center = CGFloat(80 + i * 20)
                        let rect = sideways ? CGRect(x: 320 - length / 2, y: center - 5, width: length, height: 10) : CGRect(x: center - 5, y: 320 - length / 2, width: 10, height: length)
                        canvas.fill(rect)
                    }
                }
                XCTAssertEqual(SpotifyCodeImageReader.read(image), 26560102031)
            }
        }
    }

    func testSpotifyBarsDecodeLocallyAndRejectDamage() {
        let photo = [0,6,6,0,7,6,0,2,2,3,1,7,0,7,6,4,6,1,4,7,4,1,0]
        XCTAssertEqual(SpotifyCodeDecoder.decode(photo), 26560102031)
        XCTAssertEqual(SpotifyCodeDecoder.decode([0,2,6,7,1,7,0,0,0,0,4,7,1,7,3,4,2,7,5,6,5,6,0]), 67775490487)
        XCTAssertNil(SpotifyCodeDecoder.decode([]))
        for i in 1..<22 where i != 11 {
            var damaged = photo; damaged[i] = (damaged[i] + 1) % 8
            XCTAssertNil(SpotifyCodeDecoder.decode(damaged))
        }
    }

    func testSpotifyImportAcceptsSharedLinksAndRejectsUnrelatedContent() {
        let id = "37i9dQZF1DXcBWIGoYBM5M"
        XCTAssertTrue(SpotifyPlaylists.accepts("  https://open.spotify.com/intl-en/playlist/" + id + "?si=example  "))
        XCTAssertTrue(SpotifyPlaylists.accepts("spotify:playlist:" + id))
        XCTAssertTrue(SpotifyPlaylists.accepts("https://spotify.link/example"))
        for input in ["", "https://spotify.link.attacker.test/example", "https://someone@spotify.link/example", "http://spotify.link/example", "https://open.spotify.com/track/" + id, "https://open.spotify.com/other/playlist/" + id, "https://open.spotify.com:9999/playlist/" + id] {
            XCTAssertFalse(SpotifyPlaylists.accepts(input), input)
        }
    }

    func testPlaylistLinksRejectOtherHostsAndExtraPath() {
        let id = "37i9dQZF1DXcBWIGoYBM5M"
        XCTAssertEqual(SpotifyPlaylists.playlistID("https://open.spotify.com/playlist/" + id + "?si=example"), id)
        XCTAssertNil(SpotifyPlaylists.playlistID("https://open.spotify.com.attacker.test/playlist/" + id))
        XCTAssertNil(SpotifyPlaylists.playlistID("https://open.spotify.com/playlist/" + id + "/other"))
    }
}
