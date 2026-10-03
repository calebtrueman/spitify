import Foundation

/// A missing byte count is unknown, not a new transfer at zero percent.
enum DownloadProgress {
    static func measured(previous: Double?, received: Int64, total: Int64) -> Double? {
        guard total > 0 else { return previous }
        return max(previous ?? 0, min(1, max(0, Double(received) / Double(total))))
    }
    static func fraction(state: MusicDownloadState?, measured: Double?) -> Double? {
        switch state {
        case .checking, .complete: return 1
        case .downloading: return measured.map { min(1, max(0, $0)) }
        default: return nil
        }
    }
    static func album(_ fractions: [Double?]) -> Double? {
        guard !fractions.isEmpty, fractions.contains(where: { $0 != nil }) else { return nil }
        return fractions.reduce(0) { $0 + ($1 ?? 0) } / Double(fractions.count)
    }
}
