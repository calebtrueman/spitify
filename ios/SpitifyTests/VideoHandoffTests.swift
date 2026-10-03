import XCTest
@testable import Spitify

final class VideoHandoffTests: XCTestCase {
    @MainActor func testReturningOldSurfaceKeepsNextVideoReady() {
        // Invalid video IDs keep this check entirely off the network.
        let old = VideoWebCache.take("old-video-test")
        VideoWebCache.prepare("next-video-test")
        let next = VideoWebCache.take("next-video-test")
        VideoWebCache.store(next, id: "next-video-test")

        VideoWebCache.store(old, id: "old-video-test")

        XCTAssertTrue(VideoWebCache.take("next-video-test") === next,
                      "The old screen must not replace the next song's ready clip")
    }

    @MainActor func testTakingDifferentVideoRemovesStaleWarmClip() {
        let old = VideoWebCache.take("stale-video-test")
        VideoWebCache.store(old, id: "stale-video-test")

        let next = VideoWebCache.take("current-video-test")

        XCTAssertFalse(VideoWebCache.take("stale-video-test") === old,
                       "Changing videos must remove the older cached clip")
        VideoWebCache.store(next, id: "current-video-test")
    }
}
