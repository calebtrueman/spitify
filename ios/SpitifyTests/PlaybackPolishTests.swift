import XCTest
import AVFoundation
import UniformTypeIdentifiers
import Intents
@testable import Spitify

final class PlaybackPolishTests: XCTestCase {
    private func song(_ id: String, artist: String = "Test", spoken: Bool = false) -> Song {
        Song(id: id, title: id, artist: artist, album: "Test", albumArtist: artist, durationMs: 1000,
             track: 1, disc: 1, year: 0, genre: spoken ? "Audiobook" : nil,
             location: "unused.flac", kind: .file, dateAdded: .distantPast, sizeBytes: 0, fileExtension: "flac")
    }

    func testContinuationAvoidsHiddenBrokenQueuedAndRecentlyPlayedSongs() {
        let seed = song("A")
        let candidates = [song("B"), song("C"), song("D"), song("E", artist: "Hidden"), song("F"), song("G"), seed]
        let result = PlaybackContinuation.songs(seed: seed, candidates: candidates,
            history: [song("B"), seed], upcoming: [song("C")], hiddenSongs: ["D"], hiddenArtists: ["Hidden"], failed: ["F"], count: 2)
        XCTAssertEqual(result.map(\.id), ["G", "B"])
    }

    func testContinuationKeepsSmallLibrariesPlayingWithoutDuplicateSuggestions() {
        let a = song("A"), b = song("B")
        XCTAssertEqual(PlaybackContinuation.songs(seed: a, candidates: [a, a], history: [a], upcoming: []).map(\.id), ["A"])
        XCTAssertEqual(PlaybackContinuation.songs(seed: a, candidates: [a, b, b], history: [a], upcoming: [b]).map(\.id), ["A"])
    }

    @MainActor func testEndOfMusicContinuesButSleepAndRepeatStillWin() throws {
        let source = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "sample", withExtension: "mp3"))
        var item = song("autoplay-fixture"); item.kind = .remote; item.location = source.absoluteString
        let library = LibraryStore(), player = Player()
        player.library = library; player.autoplay = true; player.setRepeat(.off)
        defer { player.stop() }
        player.play([item], shuffle: false)
        XCTAssertFalse(player.upNext.isEmpty)
        player.trackEnded()
        XCTAssertTrue(player.isPlaying)
        player.setRepeat(.one)
        let repeatedIndex = player.index
        player.trackEnded()
        XCTAssertEqual(player.index, repeatedIndex)
        player.sleepAtEndOfTrack(); player.trackEnded()
        XCTAssertFalse(player.isPlaying)
    }

    @MainActor func testClearingUpNextDoesNotSecretlyRestoreAutoplay() throws {
        let source = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "sample", withExtension: "mp3"))
        var item = song("clear-fixture"); item.kind = .remote; item.location = source.absoluteString
        let library = LibraryStore(), player = Player()
        player.library = library; player.autoplay = true; player.setRepeat(.off)
        defer { player.stop() }
        player.play([item], shuffle: false)
        player.clearUpNext(); player.refreshAutoplay()
        XCTAssertTrue(player.upNext.isEmpty)
        player.trackEnded(); XCTAssertFalse(player.isPlaying)
    }

    @MainActor func testRestoreKeepsSelectedSongWhenOlderFilesAreMissing() {
        let defaults = UserDefaults.standard
        let keys = ["queue", "queueIndex", "queuePosition", "queueSource", "queueManualIndices", "queueAutomaticIndices", "queueUnshuffled"]
        let saved = keys.map { ($0, defaults.object(forKey: $0)) }
        defer { for (key, value) in saved { if let value { defaults.set(value, forKey: key) } else { defaults.removeObject(forKey: key) } } }
        defaults.set(["missing", "B", "C"], forKey: "queue"); defaults.set(1, forKey: "queueIndex")
        let player = Player(); player.restore { $0 == "missing" ? nil : self.song($0) }
        XCTAssertEqual(player.current?.id, "B")
    }

    @MainActor func testShuffleAndRepeatKeepManualPicksSeparateFromAutoplay() throws {
        let source = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "sample", withExtension: "mp3"))
        let songs = ["A", "B", "C", "X", "Y"].map { id -> Song in
            var item = song(id); item.kind = .remote; item.location = source.absoluteString; return item
        }
        let library = LibraryStore(), player = Player()
        player.library = library; player.autoplay = true; player.setRepeat(.off)
        defer { player.stop() }
        player.play(Array(songs.prefix(3)), shuffle: false)
        player.addToQueue(Array(songs.suffix(2))); player.next()
        player.toggleShuffle(); player.toggleShuffle()
        XCTAssertEqual(player.manuallyQueued.map { $0.1.id }, ["Y"])
        XCTAssertTrue(player.nextFromSource.contains { $0.1.id == "B" })
        XCTAssertTrue(player.nextFromSource.contains { $0.1.id == "C" })
        player.setRepeat(.all)
        XCTAssertTrue(player.automaticallyQueued.isEmpty)
        XCTAssertEqual(player.manuallyQueued.map { $0.1.id }, ["Y"])
        XCTAssertTrue(player.nextFromSource.contains { $0.1.id == "B" })
        XCTAssertTrue(player.nextFromSource.contains { $0.1.id == "C" })
    }

    @MainActor func testWholeOutputLabelUsesNativeRoutePicker() {
        let control = AudioOutputPicker.Control(frame: CGRect(x: 0, y: 0, width: 240, height: 44))
        control.layoutIfNeeded(); control.picker.layoutIfNeeded()
        for point in [CGPoint(x: 20, y: 22), CGPoint(x: 180, y: 22)] {
            let target = control.hitTest(point, with: nil)
            XCTAssertNotNil(target)
            XCTAssertTrue(target === control.picker || target?.isDescendant(of: control.picker) == true)
        }
    }

    func testLevelingOnlyReducesLoudAudioAndRejectsInvalidValues() {
        XCTAssertEqual(AudioLeveling.gain(rms: 0.5, peak: 0.9), 0.252, accuracy: 0.001)
        XCTAssertEqual(AudioLeveling.gain(rms: 0.02, peak: 0.08), 1)
        XCTAssertEqual(AudioLeveling.gain(rms: 0, peak: 0), 1)
        XCTAssertEqual(AudioLeveling.gain(rms: .nan, peak: .infinity), 1)
        XCTAssertLessThanOrEqual(AudioLeveling.gain(rms: 0.01, peak: 2), 0.49)
    }

    @MainActor func testLocalLevelingMeasuresDecodedSamplesAndRouteChangeReschedules() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".caf")
        defer { try? FileManager.default.removeItem(at: url) }
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 44100, channels: 1))
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 44100 * 4))
        buffer.frameLength = buffer.frameCapacity
        buffer.floatChannelData?[0].initialize(repeating: 0.5, count: Int(buffer.frameLength))
        let file = try AVAudioFile(forWriting: url, settings: format.settings); try file.write(from: buffer)
        let reader = try AVAudioFile(forReading: url)
        XCTAssertEqual(AudioLeveling.fileGain(reader), 0.252, accuracy: 0.001)
        XCTAssertEqual(reader.framePosition, 0, "Measuring must not disturb playback's file cursor")
        let backend = EngineBackend(); defer { backend.stop() }
        backend.setVolume(0)
        try backend.load(url, at: 1, play: false)
        backend.recoverAfterRouteChange(at: 1, play: true)
        try await Task.sleep(for: .milliseconds(180))
        XCTAssertTrue(backend.isPlaying); XCTAssertGreaterThan(backend.currentTime, 1)
        backend.recoverAfterRouteChange(at: 2, play: false)
        XCTAssertFalse(backend.isPlaying); XCTAssertEqual(backend.currentTime, 2)
    }

    func testAudioHeaderDistinguishesAACFromMP3AndRejectsNoise() {
        XCTAssertEqual(MusicResourceLoader.audioType(Data([0xff, 0xf1, 0x50, 0x80, 0, 0, 0, 0, 0, 0, 0, 0])), UTType(filenameExtension: "aac")?.identifier)
        XCTAssertEqual(MusicResourceLoader.audioType(Data([0xff, 0xfb, 0x90, 0x64, 0, 0, 0, 0, 0, 0, 0, 0])), UTType.mp3.identifier)
        XCTAssertEqual(MusicResourceLoader.audioType(Data("RIFF1234WAVE".utf8)), UTType.wav.identifier)
        XCTAssertNil(MusicResourceLoader.audioType(Data(repeating: 0xff, count: 12)))
        XCTAssertNil(MusicResourceLoader.audioType(Data("<html>Error</html>".utf8)))
    }

    @MainActor func testMislabeledOpusDownloadKeepsRecordingAndSavesRealExtension() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let fixture = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "sample", withExtension: "ogg"))
        let staged = root.appendingPathComponent("download.flac")
        try FileManager.default.copyItem(at: fixture, to: staged)
        let track = OnlineTrack(id: "opus-fixture", title: "A quiet test tone", artist: "Spitify tests", album: "Decoded bytes", releaseID: "fixture", durationMs: 2000, trackNumber: 1, discNumber: 1, artwork: nil, playable: true, audioExtension: "flac")
        let job = MusicDownload(track: track, attempt: "opus-attempt", state: .downloading, relativePath: "tone.flac")
        try JSONEncoder().encode([job]).write(to: root.appendingPathComponent("music-downloads.json"))
        let downloads = MusicDownloads(root: root, stateDirectory: root, configuration: .ephemeral, alternate: { _ in XCTFail("Valid bytes must not trigger a different recording"); return nil })
        await downloads.received(attempt: job.attempt, file: staged)
        let saved = try XCTUnwrap(downloads.jobs.first)
        XCTAssertEqual(saved.state, .complete, saved.error ?? saved.lastFailure ?? "")
        XCTAssertEqual(saved.track.id, track.id)
        XCTAssertEqual(saved.track.audioExtension, "opus")
        XCTAssertEqual(saved.relativePath, "tone.opus")
        XCTAssertTrue(saved.quality?.hasPrefix("Opus") == true)
        let reader = try AVAudioFile(forReading: root.appendingPathComponent(saved.relativePath))
        XCTAssertEqual(reader.fileFormat.streamDescription.pointee.mFormatID, kAudioFormatOpus)
    }

    func testPartialResponsesKeepTheWholeLengthAndRejectBrokenRanges() {
        XCTAssertEqual(MusicResourceLoader.contentRange("bytes 0-65535/900000")?.total, 900000)
        XCTAssertEqual(MusicResourceLoader.contentRange("bytes 65536-90000/900000")?.start, 65536)
        XCTAssertNil(MusicResourceLoader.contentRange("bytes 10-1/900000"))
        XCTAssertNil(MusicResourceLoader.contentRange("bytes 0-100/100"))
        XCTAssertNil(MusicResourceLoader.contentRange("bytes */100"))
    }

    @MainActor func testOpusBytesDecodeWithWrongExtensionAndStreamLevelingKeepsPlaying() async throws {
        let fixture = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "sample", withExtension: "ogg"))
        let bytes = try Data(contentsOf: fixture)
        XCTAssertEqual(MusicResourceLoader.audioType(bytes), "org.xiph.ogg-audio")
        let mislabeled = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".flac")
        try bytes.write(to: mislabeled); defer { try? FileManager.default.removeItem(at: mislabeled) }
        let file = try AVAudioFile(forReading: mislabeled)
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: 4096))
        try file.read(into: buffer)
        XCTAssertGreaterThan(buffer.frameLength, 0)
        XCTAssertEqual(file.fileFormat.streamDescription.pointee.mFormatID, kAudioFormatOpus)
        var peak: Float = 0
        if let channel = buffer.floatChannelData?[0] { for i in 0..<Int(buffer.frameLength) { peak = max(peak, abs(channel[i])) } }
        XCTAssertGreaterThan(peak, 0.01); XCTAssertLessThan(peak, 0.3, "The known quiet tone must not turn into full-scale noise")
        let stream = StreamBackend(); stream.setVolume(0)
        defer { stream.stop() }
        stream.load(mislabeled, at: 0, play: true)
        let deadline = Date().addingTimeInterval(5)
        while stream.currentTime < 0.1 && !stream.failed && Date() < deadline { try await Task.sleep(for: .milliseconds(50)) }
        XCTAssertFalse(stream.failed); XCTAssertGreaterThan(stream.currentTime, 0.1)
        XCTAssertNotNil(stream.player.currentItem?.audioMix?.inputParameters.first?.audioTapProcessor)
        stream.normalizeVolume = false
        let before = stream.currentTime
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertGreaterThan(stream.currentTime, before)
    }

    func testSiriMediaTypesAndSearchNamesAreExposed() {
        XCTAssertEqual(SiriMediaHandler.type("song:stream:123"), .song)
        XCTAssertEqual(SiriMediaHandler.type("album:123"), .album)
        XCTAssertEqual(SiriMediaHandler.type("book:123"), .audioBook)
        let search = INMediaSearch(mediaType: .song, sortOrder: .unknown, mediaName: "Video Killed the Radio Star", artistName: "The Buggles", albumName: nil, genreNames: nil, moodNames: nil, releaseDate: nil, reference: .unknown, mediaIdentifier: nil)
        let intent = INPlayMediaIntent(mediaItems: nil, mediaContainer: nil, playShuffled: nil, playbackRepeatMode: .unknown, resumePlayback: nil, playbackQueueLocation: .now, playbackSpeed: nil, mediaSearch: search)
        XCTAssertEqual(SiriMediaHandler.query(intent), "Video Killed the Radio Star The Buggles")
    }
}
