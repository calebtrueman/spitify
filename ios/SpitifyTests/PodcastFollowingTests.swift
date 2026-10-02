import XCTest
@testable import Spitify

final class PodcastFollowingTests: XCTestCase {
    @MainActor func testPreviewFollowAndUnfollowKeepEpisodesWithoutFollowingOnOpen() async throws {
        let store = ShowsStore(persist: false)
        let preview = Show(id: "preview", feedURL: "https://example.invalid/feed", title: "Preview",
                           author: "Host", summary: "", artworkURL: nil, kind: .podcast,
                           subscribedAt: Date(timeIntervalSince1970: 0), episodes: [])
        store.shows = [preview]
        let opened = await store.subscribe(feedURL: preview.feedURL, follow: false)
        XCTAssertEqual(opened?.id, preview.id)
        XCTAssertTrue(store.podcasts.isEmpty)
        let followed = await store.subscribe(feedURL: preview.feedURL)
        XCTAssertTrue(try XCTUnwrap(followed).following)
        XCTAssertEqual(store.podcasts.count, 1)
        _ = await store.subscribe(feedURL: preview.feedURL, follow: false)
        XCTAssertEqual(store.podcasts.count, 1, "Opening a followed show must preserve following")
        store.setFollowing(preview, false)
        XCTAssertTrue(store.podcasts.isEmpty)
        XCTAssertEqual(store.shows.count, 1, "Unfollowing must preserve cached episode references")
        _ = await store.subscribe(feedURL: preview.feedURL, follow: false)
        XCTAssertTrue(store.podcasts.isEmpty, "Reopening must not follow again")
        let restored = try JSONDecoder().decode([Show].self, from: JSONEncoder().encode(store.shows))
        XCTAssertFalse(try XCTUnwrap(restored.first).following)
    }
}
