import XCTest
import UIKit
import AVFoundation
@testable import Spitify

@MainActor final class WidgetTests: XCTestCase {
    func testSnapshotSavesArtworkAndAllShelvesAndIgnoresBrokenData() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let cover = UIGraphicsImageRenderer(size: CGSize(width: 64, height: 64)).image { context in
            UIColor.systemPurple.setFill(); context.fill(CGRect(x: 0, y: 0, width: 64, height: 64))
        }.jpegData(compressionQuality: 0.8)
        let item = WidgetMusicItem(id: "song:one", title: "One", subtitle: "Artist", artwork: cover)
        let snapshot = WidgetMusicSnapshot(current: item, isPlaying: true, playlists: [item], albums: [item], mostPlayed: [item], recentlyPlayed: [item], recentlyAdded: [item], liked: [item])
        XCTAssertTrue(try WidgetMusicStore.write(snapshot, to: directory))
        XCTAssertEqual(WidgetMusicStore.read(from: directory), snapshot)
        XCTAssertFalse(try WidgetMusicStore.write(snapshot, to: directory))
        for shelf in ["playlists", "albums", "most-played", "recently-played", "recently-added", "liked"] { XCTAssertEqual(WidgetMusicStore.read(from: directory).items(for: shelf).first?.title, "One") }
        XCTAssertNotNil(UIImage(data: try XCTUnwrap(WidgetMusicStore.read(from: directory).current?.artwork)))
        try Data("incomplete".utf8).write(to: directory.appendingPathComponent(WidgetMusicStore.fileName))
        XCTAssertNil(WidgetMusicStore.read(from: directory).current)
    }

    func testFindsSharedGroupAfterSideStoreRenamesIt() throws {
        let group = WidgetMusicStore.originalGroup + ".TESTTEAM"
        let plist = try PropertyListSerialization.data(fromPropertyList: ["Entitlements": ["com.apple.security.application-groups": [group, "group.unrelated"]]], format: .xml, options: 0)
        let cms = Data([0, 1, 2]) + plist + Data([3, 4, 5])
        XCTAssertEqual(WidgetMusicStore.groupNames(profile: cms), [group])
        XCTAssertEqual(WidgetMusicStore.groupNames(profile: Data("not a profile".utf8)), [])
    }

    func testShelvesUseSavedPlaylistsCoversAndListeningOrder() async throws {
        let app = AppModel()
        let previousLists = app.library.playlists, previousListens = app.library.listens, previousLikes = app.library.liked
        let previousOverrides = app.library.overrides
        let prefix = "Widget check " + UUID().uuidString
        var imported: [Song] = []
        defer {
            app.library.playlists = previousLists; app.library.listens = previousListens; app.library.liked = previousLikes; app.library.overrides = previousOverrides
            for song in imported { if let url = app.library.fileURL(song) { try? FileManager.default.removeItem(at: url) }; ArtCache.shared.removeCustom(song.albumKey) }
        }
        for index in 0..<2 {
            let source = FileManager.default.temporaryDirectory.appendingPathComponent(prefix + "-\(index).wav")
            let format = AVAudioFormat(standardFormatWithSampleRate: 44100, channels: 1)!
            let file = try AVAudioFile(forWriting: source, settings: format.settings)
            let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 4410)!; buffer.frameLength = 4410; try file.write(from: buffer)
            _ = await app.library.importItems([source], asAudiobooks: false)
            try? FileManager.default.removeItem(at: source)
            let original = try XCTUnwrap(app.library.library.songs.first { $0.fileName == source.lastPathComponent })
            app.library.saveOverride(MetadataOverride(title: "Track \(index)", artist: "Widget Artist", album: prefix + " \(index)", source: "user"), for: [original])
            let song = try XCTUnwrap(app.library.library.songById[original.id]); imported.append(song)
            let image = UIGraphicsImageRenderer(size: CGSize(width: 500, height: 500)).image { context in
                (index == 0 ? UIColor.red : UIColor.blue).setFill(); context.fill(CGRect(x: 0, y: 0, width: 500, height: 500))
            }
            ArtCache.shared.storeCustom(try XCTUnwrap(image.pngData()), key: song.albumKey)
        }
        app.library.playlists = [Playlist(id: prefix, name: "Widget playlist", songIds: imported.map(\.id), createdAt: .now, updatedAt: .now)]
        app.library.listens = [0, 0, 1].enumerated().map { offset, index in Listen(songId: imported[index].id, at: Date(timeIntervalSince1970: Double(offset + 1)), listenedMs: 1000, durationMs: 1000, completed: true, skipped: false) }
        app.library.liked = [imported[1].id: .now]
        let snapshot = WidgetPublisher.makeSnapshot(app)
        XCTAssertEqual(snapshot.playlists.first?.title, "Widget playlist")
        let artwork = try XCTUnwrap(snapshot.playlists.first?.artwork)
        let pixels = try XCTUnwrap(UIImage(data: artwork)?.cgImage)
        XCTAssertLessThanOrEqual(max(pixels.width, pixels.height), 400)
        XCTAssertEqual(snapshot.mostPlayed.first?.id, "song:" + imported[0].id)
        XCTAssertEqual(snapshot.recentlyPlayed.first?.id, "song:" + imported[1].id)
        XCTAssertEqual(snapshot.liked.first?.id, "song:" + imported[1].id)
        XCTAssertTrue(snapshot.albums.contains { $0.title == imported[0].album && $0.artwork != nil })
        XCTAssertLessThanOrEqual(snapshot.mostPlayed.count, 8)
    }
}
