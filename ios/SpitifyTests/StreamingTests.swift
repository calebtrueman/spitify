import XCTest
import AVFoundation
import MediaPlayer
import UIKit
@testable import Spitify

final class StreamingTests: XCTestCase {
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
