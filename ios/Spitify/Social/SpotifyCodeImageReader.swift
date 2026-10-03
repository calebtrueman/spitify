import UIKit

/// Examines pixels on the device. Pictures never need to be uploaded to read the bars.
enum SpotifyCodeImageReader {
    static func read(_ image: UIImage) -> UInt64? {
        let longest = max(image.size.width, image.size.height)
        guard longest > 0 else { return nil }
        let scale = min(1, 1200 / longest)
        let size = CGSize(width: max(1, image.size.width * scale), height: max(1, image.size.height * scale))
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        let normalized = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        guard let cg = normalized.cgImage else { return nil }
        let width = cg.width, height = cg.height
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        guard let context = CGContext(data: &pixels, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        context.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
        var gray = [Int](repeating: 0, count: width * height)
        for i in gray.indices {
            let red = Int(pixels[i * 4]) * 299
            let green = Int(pixels[i * 4 + 1]) * 587
            let blue = Int(pixels[i * 4 + 2]) * 114
            gray[i] = (red + green + blue) / 1000
        }
        for rotated in [false, true] {
            let w = rotated ? height : width, h = rotated ? width : height
            for threshold in [64, 128, 192] {
                for inverted in [false, true] {
                    func ink(_ x: Int, _ y: Int) -> Bool {
                        let value = gray[rotated ? x * width + y : y * width + x]
                        return inverted ? value > threshold : value < threshold
                    }
                    var tried = Set<[Int]>()
                    for y in stride(from: 0, to: h, by: 2) {
                        var runs: [(Int, Int)] = []; var start: Int?
                        for x in 0...w {
                            if x < w && ink(x, y) { if start == nil { start = x } }
                            else if let a = start { if x - a >= 2 { runs.append((a, x - 1)) }; start = nil }
                        }
                        guard runs.count >= 23 else { continue }
                        for offset in 0...(runs.count - 23) {
                            let bars = Array(runs[offset..<(offset + 23)]), centers = bars.map { ($0.0 + $0.1) / 2 }
                            let spacing = Double(centers[22] - centers[0]) / 22
                            guard spacing >= 4,
                                  zip(centers.dropFirst(), centers).allSatisfy({ abs(Double($0.0 - $0.1) - spacing) < spacing * 0.18 }),
                                  bars.allSatisfy({ Double($0.1 - $0.0 + 1) < spacing * 0.8 }) else { continue }
                            let lengths = centers.map { x -> Int in
                                var a = y, b = y
                                while a > 0 && ink(x, a - 1) { a -= 1 }
                                while b < h - 1 && ink(x, b + 1) { b += 1 }
                                return b - a + 1
                            }
                            let small = Double(lengths[0] + lengths[22]) / 2, large = Double(lengths[11])
                            guard large > small * 2, abs(Double(lengths[0] - lengths[22])) < spacing * 0.4 else { continue }
                            let levels = lengths.map { Int(((Double($0) - small) / (large - small) * 7).rounded()) }
                            guard tried.insert(levels).inserted else { continue }
                            if let value = SpotifyCodeDecoder.decode(levels) ?? SpotifyCodeDecoder.decode(Array(levels.reversed())) { return value }
                        }
                    }
                }
            }
        }
        return nil
    }
}
