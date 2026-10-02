import XCTest
import AVFoundation
import MediaPlayer
import UIKit
@testable import Spitify

final class StreamingTests: XCTestCase {
    @MainActor func testPausingLocalAudioStopsEngineAndResumesAtSamePosition() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".caf")
        defer { try? FileManager.default.removeItem(at: url) }
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 44100, channels: 1))
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 44100 * 5))
        buffer.frameLength = buffer.frameCapacity
        if let samples = buffer.floatChannelData?[0] { samples.initialize(repeating: 0, count: Int(buffer.frameLength)) }
        let file = try AVAudioFile(forWriting: url, settings: format.settings)
        try file.write(from: buffer)
        let backend = EngineBackend()
        defer { backend.stop() }
        try backend.load(url, at: 1, play: false)
        XCTAssertFalse(backend.engine.isRunning)
        backend.play()
        XCTAssertTrue(backend.engine.isRunning)
        try await Task.sleep(for: .milliseconds(200))
        backend.pause()
        let position = backend.currentTime
        XCTAssertFalse(backend.engine.isRunning, "Paused audio must release the running output so iOS can show Play")
        try await Task.sleep(for: .milliseconds(150))
        XCTAssertEqual(backend.currentTime, position, accuracy: 0.01)
        backend.play()
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertTrue(backend.engine.isRunning)
        XCTAssertGreaterThan(backend.currentTime, position)
        backend.stop()
        XCTAssertFalse(backend.engine.isRunning)
    }
    func track(_ suffix: String) -> OnlineTrack {
        OnlineTrack(id: suffix, title: "Stream test", artist: "Spitify", album: "Streaming tests", releaseID: "1", durationMs: 30000,
                    trackNumber: 1, discNumber: 1, artwork: nil, playable: true)
    }
    @MainActor func testLibrarySaveDoesNotDownloadAndSurvivesReload() async throws {
        let item = track("99001110001")
        let name = "stream-test-" + UUID().uuidString
        let store = MusicStreams(storageName: name)
        store.remove([item]); defer { store.remove([item]) }
        let song = store.register(item)
        XCTAssertFalse(store.savedIDs.contains(item.id))
        XCTAssertEqual(song.kind, .remote)
        store.save([item])
        await Store.flush()
        XCTAssertTrue(MusicStreams(storageName: name).savedIDs.contains(item.id), "Memory: \(store.savedIDs); Disk: \((try? String(contentsOf: Store.directory.appendingPathComponent(name + "Library.json"), encoding: .utf8)) ?? "missing")")
        XCTAssertFalse(FileManager.default.fileExists(atPath: ListeningCache.file(ListeningCache.key(item)).path))
        store.remove([item]); await Store.flush(); XCTAssertFalse(MusicStreams(storageName: name).savedIDs.contains(item.id))
        XCTAssertNotNil(store.lookup(song.id), "Removing from the library must not break the current queue")
    }
    func testArchiveSizeMakesChunkedAudioStreamable() {
        XCTAssertEqual(ArchiveAudio.archiveEntrySize("<td id=\"size\">7011979</tr>"), 7011979)
        XCTAssertNil(ArchiveAudio.archiveEntrySize("<td id=\"size\">-1</tr>"))
        XCTAssertNil(ArchiveAudio.archiveEntrySize("<td>unknown</td>"))
    }
    func testCollapseNeedsEnoughDownwardMovement() {
        XCTAssertTrue(PlayerDismissGesture.shouldDismiss(distance: 140, predicted: 145))
        XCTAssertTrue(PlayerDismissGesture.shouldDismiss(distance: 60, predicted: 300))
        XCTAssertFalse(PlayerDismissGesture.shouldDismiss(distance: 20, predicted: 300))
        XCTAssertFalse(PlayerDismissGesture.shouldDismiss(distance: 60, predicted: 100))
        XCTAssertFalse(PlayerDismissGesture.shouldDismiss(distance: -140, predicted: -300))
    }
    @MainActor func testLockScreenFollowsRapidSongChangesAndClearsOldArtwork() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "sample", withExtension: "mp3"))
        let player = Player()
        let songs = (0..<3).map { i -> Song in
            var song = MusicStreams.song(track("lock-\(i)")); song.location = url.absoluteString
            song.title = "Song \(i)"; song.album = "Album \(i)"; return song
        }
        let image = UIGraphicsImageRenderer(size: CGSize(width: 20, height: 20)).image { context in
            UIColor.red.setFill(); context.fill(CGRect(x: 0, y: 0, width: 20, height: 20))
        }
        ArtCache.shared.storeEmbedded(try XCTUnwrap(image.pngData()), key: songs[0].albumKey)
        defer { try? FileManager.default.removeItem(at: ArtCache.shared.embeddedURL(songs[0].albumKey)); ArtCache.shared.invalidate(songs[0].albumKey) }
        player.play(songs, shuffle: false); player.pause()
        XCTAssertNotNil(MPNowPlayingInfoCenter.default().nowPlayingInfo?[MPMediaItemPropertyArtwork])
        for i in [1, 2, 0, 2, 1] {
            player.skip(to: i); player.pause()
            let info = MPNowPlayingInfoCenter.default().nowPlayingInfo
            XCTAssertEqual(info?[MPMediaItemPropertyTitle] as? String, songs[i].title)
            XCTAssertEqual(info?[MPNowPlayingInfoPropertyExternalContentIdentifier] as? String, songs[i].id)
            XCTAssertEqual(info?[MPNowPlayingInfoPropertyPlaybackQueueIndex] as? Int, i)
            if i != 0 { XCTAssertNil(info?[MPMediaItemPropertyArtwork], "The previous album cover must not carry over") }
        }
        player.stop()
        XCTAssertNil(MPNowPlayingInfoCenter.default().nowPlayingInfo)
    }
    @MainActor func testRestoringOldQueueDoesNotPublishUntilPlaybackStarts() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "sample", withExtension: "mp3"))
        var oldSong = MusicStreams.song(track("restored-old")); oldSong.location = url.absoluteString; oldSong.title = "Previous session"
        var newSong = oldSong; newSong.id = "stream:restored-new"; newSong.title = "Current song"
        let defaults = UserDefaults.standard
        let keys = ["queue", "queueIndex", "queuePosition", "queueSource", "queueManualIndices", "queueUnshuffled"]
        let saved = keys.map { ($0, defaults.object(forKey: $0)) }
        defer { for (key, value) in saved { if let value { defaults.set(value, forKey: key) } else { defaults.removeObject(forKey: key) } } }
        defaults.set([oldSong.id], forKey: "queue"); defaults.set(0, forKey: "queueIndex"); defaults.set(1.0, forKey: "queuePosition")
        let player = Player()
        player.restore { $0 == oldSong.id ? oldSong : nil }
        XCTAssertEqual(player.current?.title, "Previous session")
        XCTAssertFalse(player.isPlaying)
        XCTAssertNil(MPNowPlayingInfoCenter.default().nowPlayingInfo)
        NotificationCenter.default.post(name: UIApplication.willResignActiveNotification, object: nil)
        XCTAssertNil(MPNowPlayingInfoCenter.default().nowPlayingInfo)
        player.resume()
        XCTAssertEqual(MPNowPlayingInfoCenter.default().nowPlayingInfo?[MPMediaItemPropertyTitle] as? String, oldSong.title)
        player.play([newSong], shuffle: false)
        NotificationCenter.default.post(name: UIApplication.willResignActiveNotification, object: nil)
        XCTAssertEqual(MPNowPlayingInfoCenter.default().nowPlayingInfo?[MPMediaItemPropertyTitle] as? String, newSong.title)
        player.stop()
        NotificationCenter.default.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        XCTAssertNil(MPNowPlayingInfoCenter.default().nowPlayingInfo)
    }

    func testCacheEvictsOldAudioAndKeepsActiveFileAndDownloads() throws {
        try ListeningCache.queue.sync {
            let fm = FileManager.default
            try fm.createDirectory(at: ListeningCache.directory, withIntermediateDirectories: true)
            let names = ["test-old", "test-active", "test-new"]
            let urls = names.map(ListeningCache.file)
            defer { for url in urls { try? fm.removeItem(at: url) }; ListeningCache.active.remove("test-active") }
            for (i, url) in urls.enumerated() {
                fm.createFile(atPath: url.path, contents: nil)
                let file = try FileHandle(forWritingTo: url); try file.truncate(atOffset: UInt64(ListeningCache.limit / 2)); try file.close()
                try fm.setAttributes([.modificationDate: Date(timeIntervalSince1970: Double(i))], ofItemAtPath: url.path)
            }
            ListeningCache.active.insert("test-active"); ListeningCache.trim()
            XCTAssertFalse(fm.fileExists(atPath: urls[0].path))
            XCTAssertTrue(fm.fileExists(atPath: urls[1].path))
            XCTAssertTrue(fm.fileExists(atPath: urls[2].path))
        }
    }
    @MainActor func testSlowStreamStartsBeforeDownloadEndsThenPlaysFromCache() async throws {
        guard let base = ProcessInfo.processInfo.environment["SPITIFY_STREAM_TEST_BASE"] else { throw XCTSkip("Needs the local slow audio fixture server") }
        for (ext, chunked) in [("mp3", false), ("flac", false), ("m4a", false), ("mp3", true)] {
            var item = track(UUID().uuidString); item.audioExtension = ext
            let url = URL(string: base + "/stream." + ext + (chunked ? "?chunked=1" : ""))!
            if chunked {
                var request = URLRequest(url: url); request.httpMethod = "HEAD"
                let (_, response) = try await URLSession.shared.data(for: request)
                item.audioByteCount = response.expectedContentLength
            }
            let cache = ListeningCache.file(ListeningCache.key(item))
            let loader = MusicResourceLoader(track: item, sourceURL: { _ in url }, alternate: { _ in nil })
            let player = AVPlayer(playerItem: AVPlayerItem(asset: loader.asset())); player.volume = 0
            player.playImmediately(atRate: 1)
            let firstDeadline = Date().addingTimeInterval(25)
            while player.currentTime().seconds < 0.15 && player.currentItem?.status != .failed && Date() < firstDeadline { try await Task.sleep(for: .milliseconds(100)) }
            XCTAssertNil(player.currentItem?.error, "\(ext): \(String(describing: player.currentItem?.error))")
            XCTAssertGreaterThan(player.currentTime().seconds, 0.1, ext)
            XCTAssertFalse(FileManager.default.fileExists(atPath: cache.path), "\(ext) must start before the download finishes")
            let end = Date().addingTimeInterval(60)
            while !FileManager.default.fileExists(atPath: cache.path) && Date() < end { try await Task.sleep(for: .milliseconds(100)) }
            XCTAssertTrue(FileManager.default.fileExists(atPath: cache.path), ext)
            player.pause(); player.replaceCurrentItem(with: nil); loader.stop()
            await barrier()
            let offline = MusicResourceLoader(track: item, sourceURL: { _ in URL(string: "http://127.0.0.1:1/unavailable") }, alternate: { _ in nil })
            player.replaceCurrentItem(with: AVPlayerItem(asset: offline.asset())); player.playImmediately(atRate: 1)
            let replay = Date().addingTimeInterval(8)
            while player.currentTime().seconds < 0.15 && Date() < replay { try await Task.sleep(for: .milliseconds(100)) }
            XCTAssertGreaterThan(player.currentTime().seconds, 0.1, "\(ext) cached replay")
            player.pause(); player.replaceCurrentItem(with: nil); offline.stop(); await barrier()
            try? FileManager.default.removeItem(at: cache)
        }
    }
    @MainActor func testFailedSourceSwitchesAndCancelledStreamLeavesNoFinishedCache() async throws {
        guard let base = ProcessInfo.processInfo.environment["SPITIFY_STREAM_TEST_BASE"] else { throw XCTSkip("Needs the local slow audio fixture server") }
        let item = track(UUID().uuidString)
        let key = ListeningCache.key(item)
        let loader = MusicResourceLoader(track: item, sourceURL: { candidate in
            URL(string: base + (candidate.audioURL == nil ? "/missing" : "/stream.mp3"))
        }, alternate: { original in
            var copy = original; copy.audioURL = "fixture"; copy.audioExtension = "mp3"; return copy
        })
        let player = AVPlayer(playerItem: AVPlayerItem(asset: loader.asset())); player.volume = 0; player.playImmediately(atRate: 1)
        let deadline = Date().addingTimeInterval(12)
        while player.currentTime().seconds < 0.15 && Date() < deadline { try await Task.sleep(for: .milliseconds(100)) }
        XCTAssertGreaterThan(player.currentTime().seconds, 0.1)
        XCTAssertFalse(FileManager.default.fileExists(atPath: ListeningCache.file(key).path))
        player.pause(); player.replaceCurrentItem(with: nil); loader.stop(); await barrier()
        XCTAssertFalse(FileManager.default.fileExists(atPath: ListeningCache.file(key).path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: ListeningCache.directory.appendingPathComponent(key + ".partial").path))
    }
    private func barrier() async { await withCheckedContinuation { continuation in ListeningCache.queue.async { continuation.resume() } } }
}
