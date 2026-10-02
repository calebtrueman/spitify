import UIKit
import AVFoundation
import CryptoKit

import XCTest
import TagLibSwift
@testable import Spitify

final class FileTagsTests: XCTestCase {
    func testSavesTagsAndCoverAndKeepsOtherTags() async throws {
        let image = await MainActor.run {
            UIGraphicsImageRenderer(size: CGSize(width: 4, height: 4)).image { context in
                UIColor.red.setFill(); context.fill(CGRect(x: 0, y: 0, width: 4, height: 4))
            }.jpegData(compressionQuality: 1)!
        }
        for ext in ["flac", "mp3", "m4a"] {
            let source = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "sample", withExtension: ext))
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "." + ext)
            try FileManager.default.copyItem(at: source, to: url)
            defer { try? FileManager.default.removeItem(at: url) }
            let audioBefore = try audioHash(url)
            try await FileTags.shared.write(url, edit: MetadataOverride(title: "Café", artist: "An artist", album: "Album", albumArtist: "Album artist", year: 2026, track: 4, disc: 2, source: "user"), artwork: image)
            try await FileTags.shared.write(url, edit: MetadataOverride(title: "Changed", source: "user"))
            let file = try XCTUnwrap(AudioFile(path: url.path))
            XCTAssertEqual(file.properties["TITLE"], ["Changed"])
            XCTAssertNotNil(try Data(contentsOf: url).range(of: Data("Keep this note".utf8)))
            XCTAssertFalse(file.pictures.isEmpty)
            XCTAssertEqual(try audioHash(url), audioBefore)
        }
    }

    func testAutomaticFillKeepsExistingTagsAndCover() async throws {
        let images = await MainActor.run { [UIColor.red, UIColor.blue].map { color in
            UIGraphicsImageRenderer(size: CGSize(width: 4, height: 4)).image { context in
                color.setFill(); context.fill(CGRect(x: 0, y: 0, width: 4, height: 4))
            }.jpegData(compressionQuality: 1)!
        } }
        for ext in ["flac", "mp3", "m4a"] {
            let source = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "sample", withExtension: ext))
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "." + ext)
            try FileManager.default.copyItem(at: source, to: url)
            defer { try? FileManager.default.removeItem(at: url) }
            try await FileTags.shared.write(url, edit: MetadataOverride(title: "Keep title", artist: "Keep artist", track: 4, source: "user"), artwork: images[0])
            let before = try XCTUnwrap(AudioFile(path: url.path))
            let cover = before.pictures.first?.data
            XCTAssertNil(before.properties["GENRE"])
            let audioBefore = try audioHash(url)
            try await FileTags.shared.write(url, edit: MetadataOverride(title: "Replace title", artist: "Replace artist", genre: "Filled genre", track: 9, source: "online"), artwork: images[1], onlyMissing: true)
            let result = try XCTUnwrap(AudioFile(path: url.path))
            XCTAssertEqual(result.properties["TITLE"], ["Keep title"])
            XCTAssertEqual(result.properties["ARTIST"], ["Keep artist"])
            XCTAssertEqual(result.properties["TRACKNUMBER"], ["4"])
            XCTAssertEqual(result.properties["GENRE"], ["Filled genre"])
            XCTAssertEqual(result.pictures.first?.data, cover)
            XCTAssertEqual(try audioHash(url), audioBefore)
        }
    }

    private func audioHash(_ url: URL) throws -> SHA256.Digest {
        let file = try AVAudioFile(forReading: url)
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: 4096))
        var hash = SHA256()
        while file.framePosition < file.length {
            try file.read(into: buffer)
            if buffer.frameLength == 0 { break }
            for audio in UnsafeMutableAudioBufferListPointer(buffer.mutableAudioBufferList) {
                if let data = audio.mData { hash.update(data: Data(bytes: data, count: Int(audio.mDataByteSize))) }
            }
        }
        return hash.finalize()
    }

    func testBadArtworkLeavesOriginalUntouched() async throws {
        let source = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "sample", withExtension: "flac"))
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".flac")
        try FileManager.default.copyItem(at: source, to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let before = try Data(contentsOf: url)
        do {
            try await FileTags.shared.write(url, edit: MetadataOverride(title: "Changed", source: "user"), artwork: Data("not an image".utf8))
            XCTFail("Expected invalid art to fail")
        } catch { XCTAssertEqual(try Data(contentsOf: url), before) }
    }
}
