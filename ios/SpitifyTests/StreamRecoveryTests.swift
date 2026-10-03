import XCTest
import AVFoundation
@testable import Spitify

final class StreamRecoveryTests: XCTestCase {
    @MainActor private func fixture() throws -> URL {
        guard let base = ProcessInfo.processInfo.environment["SPITIFY_STREAM_TEST_BASE"] else {
            throw XCTSkip("Needs the local slow audio fixture server")
        }
        return try XCTUnwrap(URL(string: base))
    }

    @MainActor private func track() -> OnlineTrack {
        OnlineTrack(id: UUID().uuidString, title: "Opus stream check", artist: "Spitify", album: "Playback checks",
                    releaseID: "1", durationMs: 30_000, trackNumber: 1, discNumber: 1, artwork: nil, playable: true)
    }

    @MainActor private func wait(_ condition: () -> Bool, seconds: Double = 15) async throws {
        let deadline = Date().addingTimeInterval(seconds)
        while !condition(), Date() < deadline { try await Task.sleep(for: .milliseconds(50)) }
        XCTAssertTrue(condition(), "Playback did not reach the expected state")
    }

    @MainActor func testFirstFailureRetriesTheSameStreamBeforeReportingAnError() async throws {
        let base = try fixture(), item = track()
        var attempts = 0, errors = 0
        let backend = StreamBackend { track in
            attempts += 1
            let url = base.appendingPathComponent(attempts == 1 ? "missing" : "stream.ogg")
            return MusicResourceLoader(track: track, sourceURL: { _ in url }, alternate: { _ in nil })
        }
        defer { backend.stop(); ListeningCache.queue.async { try? FileManager.default.removeItem(at: ListeningCache.file(ListeningCache.key(item))) } }
        backend.onError = { errors += 1 }; backend.setVolume(0)
        let song = MusicStreams.shared.register(item)
        backend.load(try XCTUnwrap(URL(string: song.location)), at: 0, play: true)
        try await wait({ backend.currentTime > 0.2 })
        XCTAssertEqual(attempts, 2)
        XCTAssertEqual(errors, 0, "A temporary first failure must not make Player skip the song")
    }

    @MainActor func testRapidOpusSeeksFinishAtTheLastPositionAndKeepPlaying() async throws {
        let base = try fixture(), item = track()
        let url = base.appendingPathComponent("stream.ogg").appending(queryItems: [URLQueryItem(name: "ranges", value: "1")])
        let probe = SamplesProbe()
        let backend = StreamBackend(makeLevelingTap: { StreamLeveling.makeTap(observeSamples: probe.record) }) { track in
            MusicResourceLoader(track: track, sourceURL: { _ in url }, alternate: { _ in nil })
        }
        defer { backend.stop(); ListeningCache.queue.async { try? FileManager.default.removeItem(at: ListeningCache.file(ListeningCache.key(item))) } }
        let song = MusicStreams.shared.register(item)
        backend.setVolume(0); backend.load(try XCTUnwrap(URL(string: song.location)), at: 0, play: true)
        try await wait({ backend.currentTime > 0.2 })
        try await wait({ probe.samples > 0 })
        var audibleMoves = 0
        for index in 0..<48 {
            let samples = probe.samples
            backend.seek(index % 2 == 0 ? 17 : 3)
            try await Task.sleep(for: .milliseconds(60))
            if index > 1, probe.samples > samples { audibleMoves += 1 }
        }
        print("OPUS_DRAG_AUDIO: non-silent samples during \(audibleMoves) of 46 measured moves")
        XCTAssertGreaterThan(audibleMoves, 4, "Audio must reach the output tap during the drag, not only after release")
        backend.seek(12)
        try await wait({ (12.1..<14).contains(backend.player.currentTime().seconds) })
        let before = backend.player.currentTime().seconds
        try await Task.sleep(for: .milliseconds(500))
        XCTAssertGreaterThan(backend.player.currentTime().seconds, before + 0.2)
        XCTAssertFalse(backend.failed)
        XCTAssertTrue(backend.isPlaying)
        backend.pause(); backend.seek(6)
        try await wait({ abs(backend.player.currentTime().seconds - 6) < 0.15 })
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertFalse(backend.isPlaying, "A seek finishing after Pause must not restart audio")
        XCTAssertEqual(backend.player.currentTime().seconds, 6, accuracy: 0.15)
        backend.setRate(0.75)
        XCTAssertEqual(backend.player.currentItem?.audioTimePitchAlgorithm, .spectral, "Changed playback speeds keep pitch correction")
        backend.setRate(1)
        XCTAssertEqual(backend.player.currentItem?.audioTimePitchAlgorithm, .varispeed)
    }

    @MainActor func testChangingSongsCancelsAnOldRetry() async throws {
        let base = try fixture(), first = track(), second = track()
        var loads: [String] = []
        let backend = StreamBackend { track in
            loads.append(track.id)
            let url = base.appendingPathComponent(track.id == first.id ? "missing" : "stream.ogg")
            return MusicResourceLoader(track: track, sourceURL: { _ in url }, alternate: { _ in nil })
        }
        defer { backend.stop(); ListeningCache.queue.async { try? FileManager.default.removeItem(at: ListeningCache.file(ListeningCache.key(second))) } }
        backend.setVolume(0)
        backend.load(try XCTUnwrap(URL(string: MusicStreams.shared.register(first).location)), at: 0, play: true)
        try await wait({ backend.failed })
        backend.load(try XCTUnwrap(URL(string: MusicStreams.shared.register(second).location)), at: 0, play: true)
        try await wait({ backend.currentTime > 0.3 })
        XCTAssertEqual(loads, [first.id, second.id], "A delayed retry must not restore a song the listener left")
    }

    @MainActor func testPermanentFailureOnlyRetriesOnceAndPauseCancelsRetry() async throws {
        let base = try fixture(), item = track()
        var attempts = 0, errors = 0
        let backend = StreamBackend { track in
            attempts += 1
            return MusicResourceLoader(track: track, sourceURL: { _ in base.appendingPathComponent("missing") }, alternate: { _ in nil })
        }
        defer { backend.stop() }
        backend.onError = { errors += 1 }; backend.setVolume(0)
        let url = try XCTUnwrap(URL(string: MusicStreams.shared.register(item).location))
        backend.load(url, at: 0, play: true)
        try await wait({ errors == 1 })
        XCTAssertEqual(attempts, 2)
        backend.load(url, at: 0, play: true)
        try await wait({ backend.failed })
        backend.pause()
        try await Task.sleep(for: .milliseconds(600))
        XCTAssertEqual(attempts, 3)
        XCTAssertEqual(errors, 1)
        XCTAssertFalse(backend.isPlaying)
    }

    @MainActor func testLiveBugglesStartsColdAndKeepsPlayingAfterRapidSeeks() async throws {
        guard ProcessInfo.processInfo.environment["SPITIFY_AUDIO_LIVE_CHECK"] == "1" else { throw XCTSkip("Opt-in live recording check") }
        let item = OnlineTrack(id: "live-check-" + UUID().uuidString, title: "Video Killed The Radio Star", artist: "The Buggles",
                               album: "The Age Of Plastic", releaseID: "165831031918305280", durationMs: 253_800,
                               trackNumber: 1, discNumber: 1, artwork: nil, playable: true)
        let source = try MonochromeClient.audioURL("165831996106633216")
        let probe = SamplesProbe()
        let backend = StreamBackend(makeLevelingTap: { StreamLeveling.makeTap(observeSamples: probe.record) }) { track in
            MusicResourceLoader(track: track, sourceURL: { _ in source }, alternate: { _ in nil })
        }
        var errors = 0; backend.onError = { errors += 1 }
        defer { backend.stop(); ListeningCache.queue.async { try? FileManager.default.removeItem(at: ListeningCache.file(ListeningCache.key(item))) } }
        backend.setVolume(0.001)
        backend.load(try XCTUnwrap(URL(string: MusicStreams.shared.register(item).location)), at: 0, play: true)
        try await wait({ backend.currentTime > 0.2 }, seconds: 45)
        try await wait({ probe.samples > 0 }, seconds: 8)
        print("LIVE_BUGGLES_INITIAL_AUDIO: \(probe.samples) non-silent samples")
        try await Task.sleep(for: .milliseconds(600))
        print("LIVE_BUGGLES_BEFORE_SEEK: \(probe.samples) non-silent samples; time \(backend.currentTime)")
        let cached = ListeningCache.file(ListeningCache.key(item))
        if let file = try? AVAudioFile(forReading: cached) {
            print("LIVE_BUGGLES_CACHE: format \(file.fileFormat), frames \(file.length)")
        }
        let beforeFirstSeek = probe.samples
        backend.seek(32)
        try await wait({ (32..<34).contains(backend.currentTime) }, seconds: 5)
        let landedSamples = probe.samples
        try await wait({ probe.samples > landedSamples }, seconds: 5)
        print("LIVE_BUGGLES_FIRST_SEEK: \(probe.samples - beforeFirstSeek) non-silent samples; time \(backend.currentTime); \(probe.formats)")
        var audibleMoves = 0
        for index in 0..<32 {
            let samples = probe.samples
            backend.seek(index % 2 == 0 ? 35 + Double(index) / 8 : 32)
            try await Task.sleep(for: .milliseconds(60))
            if index > 1, probe.samples > samples { audibleMoves += 1 }
        }
        print("LIVE_BUGGLES_DRAG_AUDIO: non-silent samples during \(audibleMoves) of 30 measured moves")
        XCTAssertGreaterThan(audibleMoves, 4, "The real recording must deliver sound during vinyl movement")
        let released = Date(), samplesAtRelease = probe.samples
        backend.seek(40)
        try await wait({ (40.1..<42).contains(backend.player.currentTime().seconds) }, seconds: 10)
        let landingDelay = Date().timeIntervalSince(released)
        XCTAssertLessThan(landingDelay, 1.5, "The final finger position must land promptly")
        let before = backend.player.currentTime().seconds
        try await Task.sleep(for: .milliseconds(500))
        XCTAssertGreaterThan(backend.player.currentTime().seconds, before + 0.2)
        XCTAssertGreaterThan(probe.samples, samplesAtRelease, "Decoded sound must continue after release")
        XCTAssertEqual(errors, 0)
        XCTAssertTrue(backend.isPlaying)
        print("LIVE_BUGGLES_AUDIO_RESULT: errors=\(errors), audibleMoves=\(audibleMoves), finalSeekSeconds=\(landingDelay), playing=\(backend.isPlaying)")
    }
}

private final class SamplesProbe: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    private var seenFormats: Set<String> = []
    var samples: Int { lock.lock(); defer { lock.unlock() }; return count }
    var formats: Set<String> { lock.lock(); defer { lock.unlock() }; return seenFormats }
    func record(_ list: UnsafeMutablePointer<AudioBufferList>, _ format: AudioStreamBasicDescription) {
        var added = 0
        for buffer in UnsafeMutableAudioBufferListPointer(list) {
            guard let data = buffer.mData, format.mFormatID == kAudioFormatLinearPCM else { continue }
            if format.mFormatFlags & kAudioFormatFlagIsFloat != 0, format.mBitsPerChannel == 32 {
                let values = data.assumingMemoryBound(to: Float.self)
                for index in 0..<(Int(buffer.mDataByteSize) / MemoryLayout<Float>.size) {
                    if values[index].isFinite, abs(values[index]) > 0.0001 { added += 1 }
                }
            } else if format.mBitsPerChannel == 16 {
                let values = data.assumingMemoryBound(to: Int16.self)
                for index in 0..<(Int(buffer.mDataByteSize) / MemoryLayout<Int16>.size) { if abs(Int(values[index])) > 3 { added += 1 } }
            }
        }
        lock.lock(); count += added
        seenFormats.insert("\(format.mSampleRate)Hz \(format.mBitsPerChannel)bit flags=\(format.mFormatFlags)")
        lock.unlock()
    }
}
