import Foundation

enum HTTP {
    static func get(_ url: String) async -> Data? {
        guard let u = URL(string: url) else { return nil }
        var req = URLRequest(url: u, timeoutInterval: 12)
        req.setValue("Spitify/1.0 (iOS music player)", forHTTPHeaderField: "User-Agent")
        guard let (data, resp) = try? await URLSession.shared.data(for: req), (resp as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return data
    }
    static func json(_ url: String) async -> Any? { await get(url).flatMap { try? JSONSerialization.jsonObject(with: $0) } }
    static func q(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? s }
}

private func norm(_ s: String) -> String {
    foldForSearch(s).replacingOccurrences(of: "\\(.*?\\)|\\[.*?\\]", with: " ", options: .regularExpression)
        .replacingOccurrences(of: "[^\\p{L}\\p{N}]+", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
}

struct MetadataCandidate: Identifiable, Hashable {
    var id = UUID()
    var title: String, artist: String, album: String
    var year: Int?, genre: String?, track: Int?, disc: Int?
    var durationMs: Int64
    var artURL: String?
    var source: String
}

/// Deezer + iTunes Search (no keys): missing covers, untagged files, and the editor's suggestions.
enum MusicCatalog {
    static func albumArt(artist: String, album: String) async -> String? {
        let a = norm(artist), b = norm(album)
        guard !a.hasPrefix("unknown"), !b.hasPrefix("unknown"), !b.isEmpty else { return nil }
        func matches(_ x: String, _ y: String) -> Bool { let x = norm(x); return !x.isEmpty && (x == y || x.hasPrefix(y) || y.hasPrefix(x)) }
        if let root = await HTTP.json("https://api.deezer.com/search/album?limit=8&q=\(HTTP.q("\(artist) \(album)"))") as? [String: Any],
           let data = root["data"] as? [[String: Any]] {
            for o in data where matches(o["title"] as? String ?? "", b) && matches((o["artist"] as? [String: Any])?["name"] as? String ?? "", a) {
                if let url = o["cover_xl"] as? String, url.hasPrefix("http") { return url }
            }
        }
        if let root = await HTTP.json("https://itunes.apple.com/search?entity=album&limit=10&term=\(HTTP.q("\(artist) \(album)"))") as? [String: Any],
           let res = root["results"] as? [[String: Any]] {
            for o in res where matches(o["collectionName"] as? String ?? "", b) && matches(o["artistName"] as? String ?? "", a) {
                if let url = o["artworkUrl100"] as? String { return url.replacingOccurrences(of: "100x100bb", with: "1000x1000bb") }
            }
        }
        return nil
    }

    static func search(_ query: String, durationMs: Int64 = 0) async -> [MetadataCandidate] {
        var out: [MetadataCandidate] = []
        if let root = await HTTP.json("https://api.deezer.com/search/track?limit=15&q=\(HTTP.q(query))") as? [String: Any], let data = root["data"] as? [[String: Any]] {
            for o in data {
                out.append(MetadataCandidate(title: o["title"] as? String ?? "", artist: (o["artist"] as? [String: Any])?["name"] as? String ?? "",
                                             album: (o["album"] as? [String: Any])?["title"] as? String ?? "", durationMs: Int64((o["duration"] as? Int ?? 0) * 1000),
                                             artURL: (o["album"] as? [String: Any])?["cover_xl"] as? String, source: "Deezer"))
            }
        }
        if let root = await HTTP.json("https://itunes.apple.com/search?entity=song&limit=15&term=\(HTTP.q(query))") as? [String: Any], let res = root["results"] as? [[String: Any]] {
            for o in res {
                out.append(MetadataCandidate(title: o["trackName"] as? String ?? "", artist: o["artistName"] as? String ?? "", album: o["collectionName"] as? String ?? "",
                                             year: (o["releaseDate"] as? String).flatMap { Int($0.prefix(4)) }, genre: o["primaryGenreName"] as? String,
                                             track: o["trackNumber"] as? Int, disc: o["discNumber"] as? Int, durationMs: Int64(o["trackTimeMillis"] as? Int ?? 0),
                                             artURL: (o["artworkUrl100"] as? String)?.replacingOccurrences(of: "100x100bb", with: "1000x1000bb"), source: "iTunes"))
            }
        }
        guard durationMs > 0 else { return out }
        return out.sorted { abs(($0.durationMs > 0 ? $0.durationMs : .max / 2) - durationMs) < abs(($1.durationMs > 0 ? $1.durationMs : .max / 2) - durationMs) }
    }

    /// Only accept a match when length and title agree - auto-fix never guesses.
    static func confident(_ song: Song, _ candidates: [MetadataCandidate]) -> MetadataCandidate? {
        let words = Set(norm(query(for: song)).split(separator: " ").filter { $0.count > 1 })
        return candidates.first { c in
            let lengthOK = song.durationMs > 0 && c.durationMs > 0 && abs(c.durationMs - song.durationMs) <= 3_000
            let tw = norm(c.title).split(separator: " ").filter { $0.count > 1 }
            return lengthOK && !tw.isEmpty && tw.filter { words.contains($0) }.count >= (tw.count + 1) / 2
        }
    }

    static func needsFix(_ s: Song) -> Bool {
        let base = (s.fileName as NSString).deletingPathExtension
        let fileTitle = s.title == base || s.title.range(of: "^\\d{1,3}[ ._-]", options: .regularExpression) != nil || s.title.contains("_")
        return s.artist.lowercased().hasPrefix("unknown") || (s.album.lowercased().hasPrefix("unknown") && fileTitle)
    }

    static func query(for s: Song) -> String {
        let base = (s.fileName as NSString).deletingPathExtension.replacingOccurrences(of: "^\\d{1,3}[ ._-]+", with: "", options: .regularExpression).replacingOccurrences(of: "_", with: " ")
        let artist = s.artist.lowercased().hasPrefix("unknown") ? "" : s.artist
        return "\(artist) \(s.title == (s.fileName as NSString).deletingPathExtension ? base : s.title)".trimmingCharacters(in: .whitespaces)
    }
}

/// Open Library: free book info and covers for your own audiobook files.
enum OpenLibrary {
    struct Book: Hashable { var title: String, author: String, year: Int?, coverURL: String? }
    static func search(_ q: String) async -> [Book] {
        guard let root = await HTTP.json("https://openlibrary.org/search.json?q=\(HTTP.q(q))&limit=8&fields=title,author_name,first_publish_year,cover_i") as? [String: Any],
              let docs = root["docs"] as? [[String: Any]] else { return [] }
        return docs.map { d in
            Book(title: d["title"] as? String ?? "", author: (d["author_name"] as? [String])?.first ?? "", year: d["first_publish_year"] as? Int,
                 coverURL: (d["cover_i"] as? Int).map { "https://covers.openlibrary.org/b/id/\($0)-L.jpg" })
        }
    }
}
