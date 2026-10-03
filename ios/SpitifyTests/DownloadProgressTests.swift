import XCTest
@testable import Spitify

final class DownloadProgressTests: XCTestCase {
    func testKnownProgressSurvivesMissingTotalsAndDoesNotGoBackwards() {
        let halfway = DownloadProgress.measured(previous: nil, received: 50, total: 100)
        XCTAssertEqual(halfway, 0.5)
        XCTAssertEqual(DownloadProgress.measured(previous: halfway, received: 55, total: -1), 0.5)
        XCTAssertEqual(DownloadProgress.measured(previous: halfway, received: 40, total: 100), 0.5)
        XCTAssertNil(DownloadProgress.measured(previous: nil, received: 20, total: -1))
        XCTAssertEqual(DownloadProgress.measured(previous: nil, received: 0, total: 100), 0)
    }
    func testCheckingAndImportKeepRingUntilVerifiedComplete() {
        XCTAssertEqual(DownloadProgress.fraction(state: .checking, measured: nil), 1)
        XCTAssertEqual(DownloadProgress.fraction(state: .complete, measured: nil), 1)
        XCTAssertNil(DownloadProgress.fraction(state: .queued, measured: 0.9))
        XCTAssertNil(DownloadProgress.fraction(state: .waiting, measured: 0.9))
        XCTAssertEqual(DownloadProgress.album([1, 0.5, nil, nil]), 0.375)
        XCTAssertEqual(DownloadProgress.album([1, 1, nil, nil]), 0.5)
        XCTAssertNil(DownloadProgress.album([nil, nil]))
    }
    @MainActor func testStoreKeepsMeasuredProgressAndResetsNewAttempt() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let track = OnlineTrack(id: "12345", title: "Progress", artist: "Tests", album: "Tests", releaseID: "1", durationMs: 30000, trackNumber: 1, discNumber: 1, artwork: nil, playable: true)
        let job = MusicDownload(track: track, attempt: "attempt1", state: .downloading, relativePath: "progress.flac")
        try JSONEncoder().encode([job]).write(to: root.appendingPathComponent("music-downloads.json"))
        let store = MusicDownloads(root: root, stateDirectory: root, configuration: .ephemeral)
        store.updated(attempt: job.attempt, received: 50, total: 100)
        store.updated(attempt: job.attempt, received: 60, total: -1)
        XCTAssertEqual(store.progress[track.id], 0.5)
        store.cancel(track.id)
        XCTAssertNil(store.progress[track.id])
        _ = await store.enqueue([track])
        XCTAssertNil(store.progress[track.id])
        store.updated(attempt: job.attempt, received: 100, total: 100)
        XCTAssertNil(store.progress[track.id], "A late old callback cannot complete the new attempt")
    }
}
