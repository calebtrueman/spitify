import XCTest
@testable import Spitify

final class PodcastFollowingTests: XCTestCase {
    @MainActor func testSearchDistinguishesEmptyResultsFromFailedRequests() async throws {
        let store = ShowsStore(persist: false)
        let podcasts = try await store.searchPodcasts("unknown") { _ in ["results": [[String: Any]]()] }
        let books = try await store.searchBooks("unknown") { _ in ["response": ["docs": [[String: Any]]()]] }
        XCTAssertTrue(podcasts.isEmpty)
        XCTAssertTrue(books.isEmpty)
        do {
            _ = try await store.searchPodcasts("news") { _ in throw URLError(.notConnectedToInternet) }
            XCTFail("An offline request must show a retry, not an empty search")
        } catch { XCTAssertEqual((error as? URLError)?.code, .notConnectedToInternet) }
        do {
            _ = try await store.searchBooks("stories") { _ in nil }
            XCTFail("A failed response must show a retry, not an empty search")
        } catch { XCTAssertEqual((error as? URLError)?.code, .badServerResponse) }
    }

    func testWebErrorPageIsNotAcceptedAsAPodcastFeed() {
        XCTAssertNil(FeedParser.parse(Data("<html><head><title>Server error</title></head><body>Try again</body></html>".utf8)))
        XCTAssertEqual(FeedParser.parse(Data("<rss><channel><title>New show</title></channel></rss>".utf8))?.title, "New show")
    }

    @MainActor func testRefreshKeepsEpisodesWithTheirShowIfLibraryChangesWhileLoading() async {
        let store = ShowsStore(persist: false)
        let original = Show(id: "original", feedURL: "https://example.invalid/original", title: "Original", author: "Host", summary: "", artworkURL: nil, kind: .podcast, subscribedAt: Date(), episodes: [])
        var added = original; added.id = "added"; added.feedURL = "https://example.invalid/added"; added.title = "Added"
        store.shows = [original]
        await store.refreshAll { _ in
            store.shows.insert(added, at: 0)
            return Data("<rss><channel><title>Original</title><item><title>New episode</title><enclosure url=\"https://example.invalid/episode.mp3\"/></item></channel></rss>".utf8)
        }
        XCTAssertTrue(store.shows.first { $0.id == "added" }!.episodes.isEmpty)
        XCTAssertEqual(store.shows.first { $0.id == "original" }?.episodes.first?.title, "New episode")
    }

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
