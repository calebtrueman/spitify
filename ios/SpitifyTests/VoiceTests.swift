import XCTest
@testable import Spitify

final class VoiceTests: XCTestCase {
    @MainActor func testVoiceSearchFindsNamedContentAndRejectsMisses() {
        let choices = [
            SpitifyMedia(id: "artist:1", title: "Café", subtitle: "Artist"),
            SpitifyMedia(id: "song:1", title: "First Light", subtitle: "Song by Café"),
            SpitifyMedia(id: "album:1", title: "Dawn", subtitle: "Album by Café"),
            SpitifyMedia(id: "playlist:1", title: "Road trip", subtitle: "Playlist"),
            SpitifyMedia(id: "show:1", title: "Space news", subtitle: "Podcast"),
        ]
        XCTAssertEqual(VoiceLibrary.match("Cafe", in: choices).first?.id, "artist:1")
        XCTAssertEqual(VoiceLibrary.match("First Light by Cafe", in: choices).map(\.id), ["song:1"])
        XCTAssertEqual(VoiceLibrary.match("Dawn", in: choices).map(\.id), ["album:1"])
        XCTAssertEqual(VoiceLibrary.match("Road trip", in: choices).map(\.id), ["playlist:1"])
        XCTAssertEqual(VoiceLibrary.match("Space news", in: choices).map(\.id), ["show:1"])
        XCTAssertTrue(VoiceLibrary.match("No such song", in: choices).isEmpty)
    }
}
