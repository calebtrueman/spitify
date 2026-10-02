import XCTest
@testable import Spitify

final class SocialTests: XCTestCase {
    func testMusicVideosAllowLabelUploadsAndLongerEdits() {
        XCTAssertTrue(MusicVideoLookup.matches(title: "Thriller (2009 Remastered Version)", artist: "Michael Jackson", durationMs: 357000, videoTitle: "Michael Jackson - Thriller (Official Video)", channel: "Sony Music", videoDurationMs: 840000))
        XCTAssertTrue(MusicVideoLookup.matches(title: "Thriller", artist: "Michael Jackson", durationMs: 0, videoTitle: "Michael Jackson - Thriller", channel: "Epic Records", videoDurationMs: 357000))
        XCTAssertFalse(MusicVideoLookup.matches(title: "Thriller", artist: "Michael Jackson", durationMs: 357000, videoTitle: "Michael Jackson - Thriller cover", channel: "Someone Else", videoDurationMs: 357000))
        XCTAssertFalse(MusicVideoLookup.matches(title: "Thriller", artist: "Michael Jackson", durationMs: 357000, videoTitle: "Another Artist - Thriller", channel: "Epic Records", videoDurationMs: 357000))
    }
    func testMusicVideoRejectsDifferentVersionsAndWrongChannels() {
        XCTAssertTrue(MusicVideoLookup.matches(title: "Summertime Sadness", artist: "Lana Del Rey", durationMs: 265000, videoTitle: "Lana Del Rey - Summertime Sadness (Official Music Video)", channel: "Lana Del Rey", videoDurationMs: 266000))
        XCTAssertFalse(MusicVideoLookup.matches(title: "Summertime Sadness", artist: "Lana Del Rey", durationMs: 265000, videoTitle: "Summertime Sadness Live (Official Video)", channel: "Lana Del Rey", videoDurationMs: 266000))
        XCTAssertFalse(MusicVideoLookup.matches(title: "Summertime Sadness", artist: "Lana Del Rey", durationMs: 265000, videoTitle: "Summertime Sadness (Official Video)", channel: "Cover Singer", videoDurationMs: 266000))
    }
    func testMusicVideosFromArtistChannelDoNotNeedOfficialInTitle() {
        for title in ["Video Games", "Born To Die"] {
            XCTAssertTrue(MusicVideoLookup.matches(title: title, artist: "Lana Del Rey", durationMs: 282000, videoTitle: "Lana Del Rey - " + title, channel: "Lana Del Rey", videoDurationMs: 287000))
            XCTAssertTrue(MusicVideoLookup.matches(title: title, artist: "Lana Del Rey", durationMs: 282000, videoTitle: "Lana Del Rey - " + title, channel: "LanaDelReyVEVO", videoDurationMs: 287000))
            XCTAssertTrue(MusicVideoLookup.matches(title: title, artist: "Lana Del Rey", durationMs: 282000, videoTitle: "Lana Del Rey - " + title, channel: "Lana Del Rey Fan Videos", videoDurationMs: 287000))
        }
    }
    @MainActor func testPlaylistReusesPreviouslyMatchedSongWithoutSearch() async throws {
        let app = AppModel()
        let title = "Saved match " + UUID().uuidString
        let track = OnlineTrack(id: "998811221", title: title, artist: "Test Artist", album: "Test Album", releaseID: "1", durationMs: 120000, trackNumber: 1, discNumber: 1, artwork: nil, playable: true)
        let expected = app.musicStreams.register(track)
        let shared = SharedTrack(title: title, artist: "Test Artist", durationMs: 120000)
        let result = try await SharedSongMatch.resolve(shared, app: app)
        XCTAssertEqual(result.id, expected.id)
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

    func testPlaylistLinksRejectOtherHostsAndExtraPath() {
        let id = "37i9dQZF1DXcBWIGoYBM5M"
        XCTAssertEqual(SpotifyPlaylists.playlistID("https://open.spotify.com/playlist/" + id + "?si=example"), id)
        XCTAssertNil(SpotifyPlaylists.playlistID("https://open.spotify.com.attacker.test/playlist/" + id))
        XCTAssertNil(SpotifyPlaylists.playlistID("https://open.spotify.com/playlist/" + id + "/other"))
    }
}
