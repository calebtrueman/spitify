import Foundation

enum SearchMatch {
    static func fold(_ text: String) -> String {
        text.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_US_POSIX"))
            .replacingOccurrences(of: "[^\\p{L}\\p{N}]+", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
    }
    static func score(_ query: String, title: String, artist: String, album: String = "") -> Int? {
        let q = fold(query), t = fold(title), a = fold(artist), b = fold(album)
        guard !q.isEmpty else { return nil }
        let words = q.split(separator: " ").map(String.init)
        guard words.allSatisfy({ "\(t) \(a) \(b)".contains($0) }) else { return nil }
        if t == q { return 1000 }
        let named = (t + " " + a).split(separator: " ").filter { $0 != "the" }.sorted()
        if named == q.split(separator: " ").filter({ $0 != "the" }).sorted() { return 950 }
        if "\(t) \(a)" == q || "\(a) \(t)" == q { return 950 }
        if t.hasPrefix(q) { return 850 }
        if t.contains(q) { return 750 }
        if a == q { return 700 }
        if words.allSatisfy({ t.contains($0) }) { return 650 }
        if a.contains(q) { return 550 }
        return 400 + words.filter { t.contains($0) }.count * 10
    }
    static func sameSong(_ song: Song, _ track: OnlineTrack) -> Bool {
        fold(song.title) == fold(track.title) && fold(song.artist) == fold(track.artist) &&
            song.durationMs > 0 && track.durationMs > 0 && abs(song.durationMs - track.durationMs) <= 3_000
    }
}
