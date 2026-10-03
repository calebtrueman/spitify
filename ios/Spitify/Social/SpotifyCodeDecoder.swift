import Foundation

/// Reads Spotify's 23 bar heights locally. The returned number is a lookup key, not a song ID.
/// Format reference: https://boonepeter.github.io/posts/spotify-codes-part-2/
enum SpotifyCodeDecoder {
    static func decode(_ bars: [Int]) -> UInt64? {
        guard bars.count == 23, bars.allSatisfy({ (0...7).contains($0) }), bars[0] == 0, bars[11] == 7, bars[22] == 0 else { return nil }
        var output: [Int] = []
        for (i, height) in bars.enumerated() where i != 0 && i != 11 && i != 22 {
            let gray = height ^ (height >> 1)
            output += [gray >> 2, (gray >> 1) & 1, gray & 1]
        }
        var rows = matrix.enumerated().map { $0.element | (UInt64(output[$0.offset]) << 45) }
        for col in 0..<45 {
            guard let at = (col..<60).first(where: { (rows[$0] >> col) & 1 != 0 }) else { return nil }
            rows.swapAt(col, at)
            for row in 0..<60 where row != col && (rows[row] >> col) & 1 != 0 { rows[row] ^= rows[col] }
        }
        guard rows[45...].allSatisfy({ $0 == 0 }) else { return nil }
        let bits = rows.prefix(45).map { $0 >> 45 }
        let value = (0..<37).reduce(UInt64(0)) { $0 | (bits[$1] << $1) }
        var crc = 0
        for byte in 0..<5 {
            crc ^= Int((value >> (byte * 8)) & 255)
            for _ in 0..<8 { crc = ((crc << 1) ^ (crc & 128 != 0 ? 7 : 0)) & 255 }
        }
        let check = (0..<8).reduce(UInt64(0)) { $0 | (bits[$1 + 37] << $1) }
        return check == UInt64(crc ^ 255) ? value : nil
    }

    private static let matrix: [UInt64] = {
        var rows = [UInt64](repeating: 0, count: 60)
        for column in 0..<45 {
            let bits = (0..<45).map { $0 == column ? 1 : 0 }
            let full = Array(bits.suffix(6)) + bits
            var stream: [Int] = []
            for i in 0..<45 {
                for mask in [0b1011011, 0b1111001] {
                    stream.append((0..<7).reduce(0) { $0 ^ (full[i + $1] * ((mask >> $1) & 1)) })
                }
            }
            let short = stream.enumerated().filter { $0.offset % 3 != 2 }.map(\.element)
            for row in 0..<60 { rows[row] |= UInt64(short[(row * 7) % 60]) << column }
        }
        return rows
    }()
}
