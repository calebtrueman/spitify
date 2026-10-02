import XCTest
import AVFoundation
import UIKit
@testable import Spitify

final class LockScreenArtworkTests: XCTestCase {
    @available(iOS 26.0, *)
    func testPortraitVideoIsLocalDecodableAndReusable() async throws {
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        let poster = UIGraphicsImageRenderer(size: CGSize(width: 720, height: 960), format: format).image { context in
            UIColor.red.setFill(); context.fill(CGRect(x: 0, y: 0, width: 720, height: 960))
        }
        let key = "test-" + UUID().uuidString
        let url = try XCTUnwrap(LockScreenArtwork.makeVideo(poster, key: key))
        defer { try? FileManager.default.removeItem(at: url) }
        XCTAssertTrue(url.isFileURL)
        let asset = AVURLAsset(url: url)
        let duration = try await asset.load(.duration).seconds
        XCTAssertEqual(duration, 2, accuracy: 0.1)
        let tracks = try await asset.loadTracks(withMediaType: .video)
        let track = try XCTUnwrap(tracks.first)
        let size = try await track.load(.naturalSize)
        XCTAssertEqual(size, CGSize(width: 720, height: 960))
        XCTAssertEqual(LockScreenArtwork.makeVideo(poster, key: key), url)
        XCTAssertNotNil(LockScreenArtwork.artwork(image: poster))
    }
}
