import SwiftUI
import UIKit

/// Album artwork on disk (Caches/Spitify/art) + in memory, and the dominant colour of each cover.
/// Order of preference: custom art the user picked → embedded/Music-library art → art fetched online.
final class ArtCache: @unchecked Sendable {
    static let shared = ArtCache()
    private let memory = NSCache<NSString, UIImage>()
    private var colors: [String: Color] = [:]
    private let lock = NSLock()
    let dir: URL = { let d = Store.caches.appendingPathComponent("art"); try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true); return d }()
    let customDir: URL = { let d = Store.directory.appendingPathComponent("custom_art"); try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true); return d }()

    init() { memory.countLimit = 300 }

    func fileKey(_ key: String) -> String { stableId(key) }
    func embeddedURL(_ key: String) -> URL { dir.appendingPathComponent("\(fileKey(key)).jpg") }
    func customURL(_ key: String) -> URL { customDir.appendingPathComponent("\(fileKey(key)).jpg") }
    func remoteURL(_ url: String) -> URL { dir.appendingPathComponent("r_\(stableId(url)).jpg") }

    func hasArt(_ key: String) -> Bool {
        FileManager.default.fileExists(atPath: customURL(key).path) || FileManager.default.fileExists(atPath: embeddedURL(key).path)
    }

    /// Stores embedded artwork once per album, downscaled.
    func storeEmbedded(_ data: Data, key: String) {
        let url = embeddedURL(key)
        if FileManager.default.fileExists(atPath: url.path) { return }
        guard let img = UIImage(data: data) else { return }
        try? Self.downscaled(img, 700).jpegData(compressionQuality: 0.85)?.write(to: url, options: .atomic)
        invalidate(key)
    }

    func storeCustom(_ data: Data, key: String) {
        guard let img = UIImage(data: data) else { return }
        try? Self.downscaled(img, 1000).jpegData(compressionQuality: 0.9)?.write(to: customURL(key), options: .atomic)
        invalidate(key)
    }

    func removeCustom(_ key: String) { try? FileManager.default.removeItem(at: customURL(key)); invalidate(key) }

    func invalidate(_ key: String) {
        memory.removeObject(forKey: key as NSString)
        lock.lock(); colors[key] = nil; lock.unlock()
    }

    /// Synchronous lookup used from background tasks.
    func image(for key: String, remote: String? = nil) -> UIImage? {
        if let m = memory.object(forKey: key as NSString) { return m }
        let candidates = [customURL(key), embeddedURL(key)] + (remote.map { [remoteURL($0)] } ?? [])
        for url in candidates {
            if let data = try? Data(contentsOf: url), let img = UIImage(data: data) {
                memory.setObject(img, forKey: key as NSString)
                return img
            }
        }
        return nil
    }

    func load(key: String, remote: String?) async -> UIImage? {
        if let img = image(for: key, remote: remote) { return img }
        guard let remote, let url = URL(string: remote) else { return nil }
        guard let (data, _) = try? await URLSession.shared.data(from: url), let img = UIImage(data: data) else { return nil }
        let small = Self.downscaled(img, 700)
        try? small.jpegData(compressionQuality: 0.85)?.write(to: remoteURL(remote), options: .atomic)
        memory.setObject(small, forKey: key as NSString)
        return small
    }

    /// A vivid but dark-enough colour from the cover, for gradients behind headers and the player.
    func color(for key: String, image: UIImage) -> Color {
        lock.lock(); if let c = colors[key] { lock.unlock(); return c }; lock.unlock()
        let c = Self.dominant(image)
        lock.lock(); colors[key] = c; lock.unlock()
        return c
    }

    static func downscaled(_ img: UIImage, _ max: CGFloat) -> UIImage {
        let scale = min(1, max / Swift.max(img.size.width, img.size.height))
        if scale >= 1 { return img }
        let size = CGSize(width: img.size.width * scale, height: img.size.height * scale)
        return UIGraphicsImageRenderer(size: size).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }
    }

    static func dominant(_ img: UIImage) -> Color {
        let n = 24
        guard let cg = img.cgImage else { return .gray }
        var px = [UInt8](repeating: 0, count: n * n * 4)
        guard let ctx = CGContext(data: &px, width: n, height: n, bitsPerComponent: 8, bytesPerRow: n * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return .gray }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: n, height: n))
        // Weight each pixel by saturation and mid brightness, so vivid tones win over greys/near-black.
        var r = 0.0, g = 0.0, b = 0.0, wsum = 0.0
        for i in stride(from: 0, to: px.count, by: 4) {
            let pr = Double(px[i]) / 255, pg = Double(px[i + 1]) / 255, pb = Double(px[i + 2]) / 255
            let mx = Swift.max(pr, pg, pb), mn = Swift.min(pr, pg, pb)
            let sat = mx == 0 ? 0 : (mx - mn) / mx
            let w = 0.05 + sat * sat * (1 - abs(mx - 0.6))
            r += pr * w; g += pg * w; b += pb * w; wsum += w
        }
        r /= wsum; g /= wsum; b /= wsum
        let darken = 0.72
        return Color(red: r * darken, green: g * darken, blue: b * darken)
    }
}

/// Deterministic fallback tile colour per album, same palette as Android.
func fallbackColor(_ seed: String) -> Color {
    let palette: [UInt32] = [0x8E44AD, 0x1E88E5, 0xE5533D, 0x00897B, 0xF4A300, 0xD81B60, 0x3949AB, 0x43A047]
    var h: UInt64 = 0
    for b in seed.utf8 { h = h &* 31 &+ UInt64(b) }
    return Color(hex: palette[Int(h % UInt64(palette.count))])
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}
