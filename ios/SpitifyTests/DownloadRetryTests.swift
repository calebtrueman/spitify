import XCTest
import AVFoundation
@testable import Spitify

final class DownloadRetryTests: XCTestCase {
    private func track(_ id: String = "1", playable: Bool = true) -> OnlineTrack {
        OnlineTrack(id: id, title: "Carmen", artist: "Lana Del Rey", album: "Born To Die (Bonus Track Version)", releaseID: "10",
                    durationMs: 248000, trackNumber: 9, discNumber: 1, artwork: nil, playable: playable)
    }

    func testTemporaryServerAndBrokenResponseErrorsRetryButStorageAndAccessErrorsDoNot() {
        for status in [408, 429, 500, 502, 503, 521] { XCTAssertTrue(DownloadRetry.isTemporary(MusicSourceError.http(status))) }
        for status in [401, 403, 404] { XCTAssertFalse(DownloadRetry.isTemporary(MusicSourceError.http(status))) }
        XCTAssertTrue(DownloadRetry.isTemporary(URLError(.cannotParseResponse)))
        XCTAssertTrue(DownloadRetry.isTemporary(URLError(.networkConnectionLost)))
        XCTAssertFalse(DownloadRetry.isTemporary(URLError(.cancelled)))
        XCTAssertFalse(DownloadRetry.isTemporary(CocoaError(.fileWriteOutOfSpace)))
        XCTAssertEqual([1, 2, 3, 4, 5, 30].map(DownloadRetry.delay), [2, 5, 15, 60, 300, 300])
    }

    @MainActor func testUnavailableAlbumSongsAreQueuedWithoutDiscardingAnyAndRepeatTapDeduplicates() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        var searches = 0
        let store = MusicDownloads(root: dir, stateDirectory: dir, configuration: .ephemeral, alternate: { _ in searches += 1; return nil })
        let tracks = (1...28).map { track(String($0), playable: false) }
        let message = await store.enqueue(tracks)
        XCTAssertTrue(message.hasPrefix("28 songs queued"))
        XCTAssertEqual(store.jobs.count, 28)
        XCTAssertTrue(store.jobs.allSatisfy { $0.state == .queued })
        XCTAssertEqual(searches, 0)
        let second = await store.enqueue(tracks)
        XCTAssertEqual(second, "Already in your download queue.")
        XCTAssertEqual(store.jobs.count, 28)
    }

    @MainActor func testTransientFailurePersistsRetryAndCancellationStopsOldCallbacks() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let original = MusicDownload(track: track(), attempt: "first", state: .downloading, relativePath: "one.flac")
        try JSONEncoder().encode([original]).write(to: dir.appendingPathComponent("music-downloads.json"))
        let store = MusicDownloads(root: dir, stateDirectory: dir, configuration: .ephemeral)
        store.failed(attempt: "first", error: MusicSourceError.http(521))
        let pending = try XCTUnwrap(store.jobs.first)
        XCTAssertEqual(pending.state, .waiting)
        XCTAssertEqual(pending.retryCount, 1)
        XCTAssertNotNil(pending.retryAt)
        XCTAssertEqual(pending.track.id, original.track.id)
        XCTAssertTrue(pending.lastFailure?.contains("521") == true)
        let restored = MusicDownloads(root: dir, stateDirectory: dir, configuration: .ephemeral)
        XCTAssertEqual(restored.jobs.first?.state, .waiting)
        XCTAssertEqual(restored.jobs.first?.retryAt, pending.retryAt)
        store.cancel(pending.id)
        store.failed(attempt: "first", error: URLError(.cannotParseResponse))
        XCTAssertEqual(store.jobs.first?.state, .cancelled)
        await store.enqueue([track()])
        XCTAssertEqual(store.jobs.first?.state, .queued)
        XCTAssertNil(store.jobs.first?.retryCount)
    }

    @MainActor func testSourceHandoffKeepsSongAndAlbumAndExhaustedBackupsKeepTemporaryFailureQueued() async throws {
        for foundCopy in [true, false] {
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: dir) }
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            var original = MusicDownload(track: track(), attempt: "first", state: .downloading, relativePath: "one.flac")
            original.retryCount = 3
            try JSONEncoder().encode([original]).write(to: dir.appendingPathComponent("music-downloads.json"))
            var calls = 0
            let store = MusicDownloads(root: dir, stateDirectory: dir, configuration: .ephemeral, alternate: { input in
                calls += 1
                guard foundCopy else { return nil }
                var copy = input; copy.audioURL = "https://tracks.monochrome.st/track/2"; copy.audioExtension = "flac"; copy.attemptedSources = ["1", "2"]
                return copy
            })
            store.failed(attempt: "first", error: MusicSourceError.http(502))
            // A second callback from the same failed URLSession task must not erase the lookup.
            store.failed(attempt: "first", error: URLError(.cannotParseResponse))
            for _ in 0..<30 where store.jobs.first?.state == .finding { await Task.yield() }
            XCTAssertEqual(calls, 1)
            XCTAssertEqual(store.jobs.first?.state, foundCopy ? .queued : .waiting)
            XCTAssertEqual(store.jobs.first?.track.album, original.track.album)
            XCTAssertEqual(store.jobs.first?.track.id, original.track.id)
            if foundCopy { XCTAssertEqual(store.jobs.first?.track.audioURL, "https://tracks.monochrome.st/track/2") }
            else { XCTAssertEqual(store.jobs.first?.retryCount, 4) }
        }
    }

    func testMatchingCopyChecksAlbumAndSkipsPreviouslyTriedSource() async throws {
        let requested = track()
        var candidate = track("2"); candidate.album = "Born To Die"; candidate.releaseID = "20"
        let choice = candidate
        let replacement = try await AudioFallback.monochromeCopy(requested, search: { _ in [choice] }, album: { _ in [choice] })
        XCTAssertEqual(replacement?.audioURL, "https://tracks.monochrome.st/track/2")
        XCTAssertEqual(replacement?.album, requested.album)
        XCTAssertEqual(replacement?.trackNumber, 9)
        let retried = try XCTUnwrap(replacement)
        let none = try await AudioFallback.monochromeCopy(retried, search: { _ in [choice] }, album: { _ in [choice] })
        XCTAssertNil(none)
        XCTAssertFalse(AudioFallback.sameRelease("Love", "Abbey Road"))
        XCTAssertFalse(AudioFallback.validAudioURL("https://tracks.monochrome.st/track/../../secret"))
        XCTAssertFalse(AudioFallback.validAudioURL("https://tracks.monochrome.st.attacker.test/track/2"))
    }
}

final class NativeDownloadRetryTests: XCTestCase {
    @MainActor func testRealTransferRetriesAndHandoffBothImportPlayableAudio() async throws {
        guard let base = ProcessInfo.processInfo.environment["SPITIFY_RETRY_TEST_BASE"] else {
            throw XCTSkip("Set SPITIFY_RETRY_TEST_BASE to the local retry fixture server.")
        }
        let fixture = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "sample", withExtension: "flac"))
        let duration = try FLACInfo.read(fixture).durationMs
        for scenario in ["retry", "missing"] {
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            defer { try? FileManager.default.removeItem(at: dir) }
            let run = UUID().uuidString
            var lookups = 0, imports = 0
            let store = MusicDownloads(root: dir, stateDirectory: dir, configuration: .ephemeral, alternate: { track in
                lookups += 1
                var copy = track; copy.audioURL = "https://tracks.monochrome.st/track/2"; copy.audioExtension = "flac"
                return copy
            }, downloadURL: { track in
                URL(string: base + "/" + (track.audioURL == nil ? scenario : "copy") + "/" + run)!
            })
            store.wifiOnly = false
            store.onImported = { imports += 1 }
            store.start()
            let track = OnlineTrack(id: "100", title: "Retry test", artist: "Spitify Test", album: "Test album", releaseID: "200",
                                    durationMs: duration, trackNumber: 1, discNumber: 1, artwork: nil, playable: false)
            await store.enqueue([track])
            let deadline = Date().addingTimeInterval(20)
            while store.jobs.first?.state.active == true && Date() < deadline { try await Task.sleep(for: .milliseconds(50)) }
            let job = try XCTUnwrap(store.jobs.first)
            XCTAssertEqual(job.state, .complete, "\(scenario): \(job.error ?? "timed out")")
            XCTAssertEqual(lookups, scenario == "retry" ? 0 : 1)
            XCTAssertEqual(imports, 1)
            let file = dir.appendingPathComponent(job.relativePath)
            XCTAssertEqual(try FLACInfo.read(file).durationMs, duration)
            let audio = try AVAudioFile(forReading: file)
            XCTAssertGreaterThan(audio.length, 0)
            XCTAssertEqual(job.track.album, track.album)
            print("NATIVE RETRY PASS: \(scenario), one queued request, complete playable tagged file")
        }
    }
}
