import XCTest
import SwiftUI
import ImageIO
import UniformTypeIdentifiers
@testable import Spitify

final class ImageTests: XCTestCase {
    @MainActor func testArtworkStaysBrightWhileOtherCoversRefresh() async throws {
        let key = "steady-art-" + UUID().uuidString
        let image = UIGraphicsImageRenderer(size: CGSize(width: 80, height: 80)).image { context in UIColor.white.setFill(); context.fill(CGRect(x: 0, y: 0, width: 80, height: 80)) }
        ArtCache.shared.storeEmbedded(try XCTUnwrap(image.pngData()), key: key)
        let app = AppModel()
        let host = UIHostingController(rootView: ArtworkView(key: key, remote: nil).frame(width: 200, height: 200).environment(app))
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let oldKey = scene.keyWindow
        let window = UIWindow(windowScene: scene); window.rootViewController = host; window.makeKeyAndVisible()
        defer { window.isHidden = true; window.rootViewController = nil; oldKey?.makeKeyAndVisible(); try? FileManager.default.removeItem(at: ArtCache.shared.embeddedURL(key)); ArtCache.shared.invalidate(key) }
        try await Task.sleep(for: .milliseconds(400))
        for _ in 0..<8 {
            ArtCache.shared.invalidate(key)
            app.library.artVersion += 1
            try await Task.sleep(for: .milliseconds(70))
            let shot = UIGraphicsImageRenderer(bounds: host.view.bounds).image { _ in host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true) }
            let cg = try XCTUnwrap(shot.cgImage)
            let sample = pixel(cg, cg.width / 2, cg.height / 2)
            XCTAssertGreaterThan(sample.r, 240, "Refreshing covers must not dim an existing image")
            XCTAssertGreaterThan(sample.b, 240)
        }
    }

    /// A camera-style JPEG: pixels stored landscape (left red, right blue) with EXIF orientation 6
    /// ("rotate 90° clockwise to display"), so it should display as a portrait image, red on top.
    private func rotatedJPEG() -> Data {
        let w = 400, h = 200
        let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
        ctx.setFillColor(red: 1, green: 0, blue: 0, alpha: 1); ctx.fill(CGRect(x: 0, y: 0, width: w / 2, height: h))
        ctx.setFillColor(red: 0, green: 0, blue: 1, alpha: 1); ctx.fill(CGRect(x: w / 2, y: 0, width: w / 2, height: h))
        let out = NSMutableData()
        let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil)!
        CGImageDestinationAddImage(dest, ctx.makeImage()!, [kCGImagePropertyOrientation: 6] as CFDictionary)
        CGImageDestinationFinalize(dest)
        return out as Data
    }

    private func pixel(_ img: CGImage, _ x: Int, _ y: Int) -> (r: UInt8, b: UInt8) {
        var px = [UInt8](repeating: 0, count: 4)
        let ctx = CGContext(data: &px, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
        // CGContext's origin is bottom-left; shift so (x, y) from the top-left lands on our 1×1 canvas.
        ctx.draw(img, in: CGRect(x: -x, y: -(img.height - 1 - y), width: img.width, height: img.height))
        return (px[0], px[2])
    }

    func testProfilePhotoIsUprightAndSquare() throws {
        let jpeg = try XCTUnwrap(ArtCache.squareJPEG(rotatedJPEG(), side: 128))
        let src = try XCTUnwrap(CGImageSourceCreateWithData(jpeg as CFData, nil))
        let img = try XCTUnwrap(CGImageSourceCreateImageAtIndex(src, 0, nil))
        XCTAssertEqual(img.width, img.height)
        XCTAssertLessThanOrEqual(img.width, 200)
        let top = pixel(img, img.width / 2, img.height / 8), bottom = pixel(img, img.width / 2, img.height * 7 / 8)
        XCTAssertGreaterThan(top.r, 200); XCTAssertLessThan(top.b, 60)       // red on top: rotation applied
        XCTAssertGreaterThan(bottom.b, 200); XCTAssertLessThan(bottom.r, 60)
    }
}
