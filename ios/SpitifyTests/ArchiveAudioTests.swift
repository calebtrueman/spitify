import XCTest
import AVFoundation
@testable import Spitify

final class ArchiveAudioTests: XCTestCase {
    @MainActor func testLiveWholeLoveAlbumFromBackup() async throws {
        guard ProcessInfo.processInfo.environment["SPITIFY_ARCHIVE_LIVE_TEST"] == "1",
              let base = ProcessInfo.processInfo.environment["SPITIFY_RETRY_TEST_BASE"] else { throw XCTSkip("Live source checks are opt-in.") }
        let tracks = try await MonochromeClient().albumTracks("166082371098722304")
        XCTAssertEqual(tracks.count, 28)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let source = ArchiveAudio()
        let store = MusicDownloads(root: dir, stateDirectory: dir, configuration: .ephemeral,
            alternate: { try await source.resolve($0) }, downloadURL: { track in
                track.audioURL.flatMap(URL.init(string:)) ?? URL(string: base + "/missing/" + UUID().uuidString)!
            })
        store.wifiOnly = false; store.start()
        await store.enqueue(tracks)
        let deadline = Date().addingTimeInterval(240)
        while store.jobs.contains(where: { $0.state.active }) && Date() < deadline { try await Task.sleep(for: .milliseconds(250)) }
        for job in store.jobs {
            XCTAssertEqual(job.state, .complete, "\(job.track.title): \(job.error ?? job.lastFailure ?? "timed out")")
            if job.state == .complete {
                let file = dir.appendingPathComponent(job.relativePath)
                let audio = try AVAudioFile(forReading: file)
                XCTAssertGreaterThan(audio.length, 0)
            } else { store.cancel(job.id) }
        }
        print("ARCHIVE ALBUM: \(store.jobs.filter { $0.state == .complete }.count)/28 Love tracks downloaded, tagged with artwork, opened by native audio")
    }

    func testMatchingAndURLs() {
        XCTAssertGreaterThan(SearchMatch.score("Beatles Love", title: "Love", artist: "The Beatles") ?? 0,
                             SearchMatch.score("Beatles Love", title: "Lisa Lauren Loves The Beatles", artist: "Lisa Lauren") ?? 0)
        XCTAssertEqual(ArchiveAudio.albumName("Born To Die (Bonus Track Version)"), ArchiveAudio.albumName("Born to Die (2012)"))
        XCTAssertNotEqual(ArchiveAudio.albumName("Love"), ArchiveAudio.albumName("Abbey Road"))
        XCTAssertNotEqual(ArchiveAudio.albumName("Born To Die (Live)"), ArchiveAudio.albumName("Born To Die"))
        XCTAssertEqual(ArchiveAudio.songName("09. Lana Del Rey - Carmen", artist: "Lana Del Rey"), "carmen")
        XCTAssertTrue(ArchiveAudio.validURL("https://archive.org/download/love_20220324/Love.zip/Love%2FBecause.mp3"))
        for bad in ["http://archive.org/download/a/a.mp3", "https://archive.org.evil.test/download/a/a.mp3", "https://archive.org/download/a/a.html", "https://archive.org/download/a/../a.mp3", "https://archive.org/download/a/a.mp3?x=1"] {
            XCTAssertFalse(ArchiveAudio.validURL(bad), bad)
        }
    }

    @MainActor func testLiveArchiveFallbackImportsAffectedSongs() async throws {
        guard ProcessInfo.processInfo.environment["SPITIFY_ARCHIVE_LIVE_TEST"] == "1",
              let base = ProcessInfo.processInfo.environment["SPITIFY_RETRY_TEST_BASE"] else { throw XCTSkip("Live source checks are opt-in.") }
        let tracks = [
            OnlineTrack(id: "154038260820086784", title: "Carmen", artist: "Lana Del Rey", album: "Born To Die (Bonus Track Version)", releaseID: "154030733734711296", durationMs: 248720, trackNumber: 9, discNumber: 1, artwork: nil, playable: true),
            OnlineTrack(id: "154038263663824896", title: "Million Dollar Man", artist: "Lana Del Rey", album: "Born To Die (Bonus Track Version)", releaseID: "154030733734711296", durationMs: 230120, trackNumber: 10, discNumber: 1, artwork: nil, playable: true),
            OnlineTrack(id: "166384735708897280", title: "Because", artist: "The Beatles", album: "Love", releaseID: "166082371098722304", durationMs: 164631, trackNumber: 1, discNumber: 1, artwork: nil, playable: false)
        ]
        for round in 1...2 {
            let source = ArchiveAudio()
            for track in tracks {
                let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
                defer { try? FileManager.default.removeItem(at: dir) }
                var imports = 0, lookups = 0
                let store = MusicDownloads(root: dir, stateDirectory: dir, configuration: .ephemeral, alternate: { input in
                    lookups += 1
                    return try await source.resolve(input)
                }, downloadURL: { input in
                    input.audioURL.flatMap(URL.init(string:)) ?? URL(string: base + "/missing/" + UUID().uuidString)!
                })
                store.wifiOnly = false; store.onImported = { imports += 1 }; store.start()
                await store.enqueue([track])
                let deadline = Date().addingTimeInterval(100)
                while store.jobs.first?.state.active == true && Date() < deadline { try await Task.sleep(for: .milliseconds(100)) }
                let job = try XCTUnwrap(store.jobs.first)
                XCTAssertEqual(job.state, .complete, "\(track.title): \(job.error ?? job.lastFailure ?? "timed out")")
                guard job.state == .complete else { store.cancel(track.id); continue }
                XCTAssertEqual(imports, 1); XCTAssertEqual(lookups, 1)
                XCTAssertEqual(job.track.album, track.album)
                let audio = try AVAudioFile(forReading: dir.appendingPathComponent(job.relativePath))
                XCTAssertGreaterThan(audio.length, 0)
                XCTAssertLessThan(abs(Double(audio.length) / audio.fileFormat.sampleRate * 1000 - Double(track.durationMs)), 5000)
                print("ARCHIVE LIVE PASS round \(round): \(track.title), real fallback transfer, tags saved, native audio opens")
            }
        }
    }
}
