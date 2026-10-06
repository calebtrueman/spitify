import XCTest
import AVFoundation
@testable import Spitify

final class AacConverterTests: XCTestCase {
    func testHiResFlacBecomesAac256At48kHz() async throws {
        let source = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "hires-96k-24", withExtension: "flac"))
        let out = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".m4a")
        defer { try? FileManager.default.removeItem(at: out) }
        try await AacConverter.convert(source, to: out)
        let asset = AVURLAsset(url: out)
        let tracks = try await asset.loadTracks(withMediaType: .audio)
        let track = try XCTUnwrap(tracks.first)
        let formats = try await track.load(.formatDescriptions)
        let format = try XCTUnwrap(formats.first)
        let basic = try XCTUnwrap(CMAudioFormatDescriptionGetStreamBasicDescription(format)?.pointee)
        XCTAssertEqual(basic.mFormatID, kAudioFormatMPEG4AAC)
        XCTAssertEqual(basic.mSampleRate, 48_000)
        let duration = try await asset.load(.duration).seconds
        XCTAssertEqual(duration, 2, accuracy: 0.1)
    }
}
