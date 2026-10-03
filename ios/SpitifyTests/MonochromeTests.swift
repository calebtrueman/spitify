import XCTest
import AVFoundation
@testable import Spitify

final class MonochromeTests: XCTestCase {
    func testLargeIDsAndMillisecondDurationsSurviveSearchParsing() throws {
        let data = Data(#"{"trackId":"156361611655778304","title":"Carefree","artistNames":["Kevin MacLeod"],"duration":205139,"releaseId":"156361580135583744","playable":false}"#.utf8)
        let item = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let track = try XCTUnwrap(MonochromeClient.track(item))
        XCTAssertEqual(track.id, "156361611655778304")
        XCTAssertEqual(track.durationMs, 205139)
        XCTAssertFalse(track.playable)
        XCTAssertEqual(try MonochromeClient.audioURL(track.id).absoluteString, "https://tracks.monochrome.st/track/156361611655778304")
        XCTAssertThrowsError(try MonochromeClient.audioURL("../another/path"))
    }

    func testAlbumKeepsReleaseTitleAndOrder() throws {
        let track = try XCTUnwrap(MonochromeClient.track(["trackId": "123", "title": "Song", "duration": 500,
            "artists": [["name": "Artist"]], "trackNumber": 4, "discNumber": 2],
            album: ["releaseId": "456", "title": "Chosen release"]))
        XCTAssertEqual(track.album, "Chosen release")
        XCTAssertEqual(track.artist, "Artist")
        XCTAssertEqual(track.durationMs, 500) // Short tracks are milliseconds too.
        XCTAssertEqual(track.trackNumber, 4)
        XCTAssertEqual(track.discNumber, 2)
    }

    func testUnrelatedSourceRowCannotTakeTheAlbumArtistsTrackNumber() throws {
        // The public Punisher response on 2026-10-03 included both rows at track 1.
        let album: [String: Any] = ["releaseId": "155408274068344832", "title": "Punisher", "releaseType": "ALBUM",
            "artists": [["name": "Phoebe Bridgers"]], "tracks": [
                ["trackId": "155408325708615680", "title": "The Race - Remix", "trackNumber": 1, "discNumber": 1,
                 "releaseId": "155408274068344832", "artists": [["name": "Tay-K"], ["name": "21 Savage"], ["name": "Young Nudy"]]],
                ["trackId": "155408297682276352", "title": "DVD Menu", "trackNumber": 1, "discNumber": 1,
                 "releaseId": "155408274068344832", "artists": [["name": "Phoebe Bridgers"]]]
            ]]
        let tracks = try MonochromeClient.albumTracks(from: album, requestedID: "155408274068344832")
        XCTAssertEqual(tracks.map(\.title), ["DVD Menu"])
        XCTAssertEqual(tracks.first?.album, "Punisher")
    }

    func testAlbumChecksReleaseIdentityWithoutRemovingGuests() throws {
        func row(_ id: String, _ number: Int, _ names: [String], release: String = "100", disc: Int = 1) -> [String: Any] {
            ["trackId": id, "title": id, "trackNumber": number, "discNumber": disc, "artistNames": names, "releaseId": release]
        }
        var album: [String: Any] = ["releaseId": "100", "title": "Album", "artistNames": ["Main artist"], "tracks": [
            row("1", 1, ["Main artist"]), row("2", 2, ["Main artist", "Guest"]), row("3", 3, ["Guest"]),
            row("4", 1, ["Guest"], disc: 2), row("5", 4, ["Main artist"], release: "999")
        ]]
        XCTAssertEqual(try MonochromeClient.albumTracks(from: album, requestedID: "100").map(\.id), ["1", "2", "3", "4"])
        XCTAssertThrowsError(try MonochromeClient.albumTracks(from: album, requestedID: "999"))
        album["tracks"] = [row("1", 1, ["Main artist"]), row("2", 1, ["Other artist"])]
        album["releaseType"] = "COMPILATION"
        XCTAssertEqual(try MonochromeClient.albumTracks(from: album, requestedID: "100").count, 2)
        album["releaseType"] = "ALBUM"
        album["artistNames"] = [String]()
        XCTAssertEqual(try MonochromeClient.albumTracks(from: album, requestedID: "100").count, 2)
    }

    func testRejectsHTMLTruncationAndWrongRecording() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let file = dir.appendingPathComponent("test.flac")
        try Data("<html>Access denied</html>".utf8).write(to: file)
        XCTAssertThrowsError(try FLACInfo.read(file))
        var data = Self.flacHeader()
        try data.write(to: file)
        let info = try FLACInfo.read(file, expectedDurationMs: 10_000)
        XCTAssertEqual(info.bits, 16)
        XCTAssertEqual(info.sampleRate, 44100)
        XCTAssertEqual(info.durationMs, 10000)
        XCTAssertThrowsError(try FLACInfo.read(file, expectedDurationMs: 60_000))
        data.removeLast(3)
        try data.write(to: file)
        XCTAssertThrowsError(try FLACInfo.read(file))
    }

    func testAccessAndRateLimitsAreErrorsRatherThanEmptyResults() throws {
        for status in [401, 403, 404, 429, 500] {
            let response = try XCTUnwrap(HTTPURLResponse(url: MonochromeClient.baseURL, statusCode: status, httpVersion: nil, headerFields: nil))
            XCTAssertThrowsError(try MonochromeClient.check(response))
        }
    }

    @MainActor func testQueuePersistsDeduplicatesAndCancelsWithoutStartingNetwork() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = MusicDownloads(root: dir, stateDirectory: dir, configuration: .ephemeral)
        let track = OnlineTrack(id: "123", title: "Song", artist: "Artist", album: "Album", releaseID: "456",
            durationMs: 10000, trackNumber: 1, discNumber: 1, artwork: nil, playable: true)
        await store.enqueue([track, track])
        XCTAssertEqual(store.jobs.count, 1)
        XCTAssertEqual(store.jobs.first?.state, .queued)
        let restored = MusicDownloads(root: dir, stateDirectory: dir, configuration: .ephemeral)
        XCTAssertEqual(restored.jobs.first?.track, track)
        store.cancel(track.id)
        XCTAssertEqual(store.jobs.first?.state, .cancelled)
        await store.enqueue([track])
        XCTAssertEqual(store.jobs.count, 1)
        XCTAssertEqual(store.jobs.first?.state, .queued)
    }

    @MainActor func testCancelledAttemptCannotCompleteItsReplacement() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = MusicDownloads(root: dir, stateDirectory: dir, configuration: .ephemeral)
        let track = OnlineTrack(id: "123", title: "Song", artist: "Artist", album: "Album", releaseID: "456",
            durationMs: 10000, trackNumber: 1, discNumber: 1, artwork: nil, playable: true)
        await store.enqueue([track])
        let old = try XCTUnwrap(store.jobs.first)
        store.cancel(track.id)
        await store.enqueue([track])
        let staleFile = dir.appendingPathComponent("old.flac")
        try Self.flacHeader().write(to: staleFile)
        await store.received(attempt: old.attempt, file: staleFile)
        XCTAssertEqual(store.jobs.first?.state, .queued)
        XCTAssertNotEqual(store.jobs.first?.attempt, old.attempt)
        XCTAssertFalse(FileManager.default.fileExists(atPath: dir.appendingPathComponent(old.relativePath).path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: staleFile.path))
    }

    /// A metadata fixture, not a playable recording. Live tests below check native decoding.
    static func flacHeader() -> Data {
        var data = Data("fLaC".utf8)
        data.append(contentsOf: [0x80, 0, 0, 34])
        var stream = [UInt8](repeating: 0, count: 34)
        let packed = UInt64(44100) << 44 | UInt64(1) << 41 | UInt64(15) << 36 | UInt64(441000)
        for i in 0..<8 { stream[10 + i] = UInt8(truncatingIfNeeded: packed >> (56 - i * 8)) }
        data.append(contentsOf: stream)
        data.append(contentsOf: [0xff, 0xf8])
        return data
    }
}

final class MonochromeLiveTests: XCTestCase {
    @MainActor func testSearchDownloadImportAndNativePlayback() async throws {
        guard ProcessInfo.processInfo.environment["MONOCHROME_LIVE"] == "1" else { throw XCTSkip("Set MONOCHROME_LIVE=1 to run the live download test.") }
        let client = MonochromeClient()
        let search = try await client.search("Kevin MacLeod Carefree")
        let found = try XCTUnwrap(search.first { $0.id == "156361611655778304" })
        let tracks = try await client.albumTracks(found.releaseID)
        let track = try XCTUnwrap(tracks.first { $0.id == found.id })
        let stateDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let destination = Store.documents.appendingPathComponent("Music/Monochrome/\(track.releaseID)/\(track.id).flac")
        // Only run on a disposable simulator. Never replace a file supplied by someone else.
        guard !FileManager.default.fileExists(atPath: destination.path) else { throw XCTSkip("Test recording is already present.") }
        defer { try? FileManager.default.removeItem(at: stateDir); try? FileManager.default.removeItem(at: destination) }
        let configuration = URLSessionConfiguration.background(withIdentifier: "com.calebtrueman.spitify.test.\(UUID().uuidString)")
        let downloads = MusicDownloads(stateDirectory: stateDir, configuration: configuration)
        downloads.wifiOnly = false
        downloads.start()
        await downloads.enqueue([track, track])
        let deadline = Date().addingTimeInterval(120)
        while downloads.jobs.first?.state.active == true && Date() < deadline { try await Task.sleep(for: .milliseconds(200)) }
        XCTAssertEqual(downloads.jobs.count, 1)
        XCTAssertEqual(downloads.jobs.first?.state, .complete, downloads.jobs.first?.error ?? "Timed out")
        let info = try FLACInfo.read(destination, expectedDurationMs: track.durationMs)
        XCTAssertEqual(info.bits, 16)
        let library = LibraryStore()
        await library.scan()
        let song = try XCTUnwrap(library.library.songs.first { $0.location.hasSuffix("\(track.id).flac") })
        XCTAssertEqual(song.title, "Carefree")
        // Decode the entire file using Apple's player decoder, not just a header check.
        let audio = try AVAudioFile(forReading: destination)
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: audio.processingFormat, frameCapacity: 8192))
        var frames: AVAudioFramePosition = 0
        while audio.framePosition < audio.length {
            try audio.read(into: buffer)
            guard buffer.frameLength > 0 else { break }
            frames += AVAudioFramePosition(buffer.frameLength)
        }
        XCTAssertEqual(frames, audio.length)
        XCTAssertGreaterThan(frames, 0)
        XCTAssertEqual(song.durationMs, info.durationMs, accuracy: 100)
        print("MONOCHROME LIVE PASS: searched, downloaded, imported and decoded \(frames) frames; \(info.label)")
    }
}
