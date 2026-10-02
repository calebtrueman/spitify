import Foundation

struct MusicVideo: Equatable {
    var id: String
    var title: String
    var duration: Double
}

enum MusicVideoLookup {
    static func matches(title: String, artist: String, durationMs: Int64, videoTitle: String, channel: String, videoDurationMs: Int64) -> Bool {
        let wanted = SearchMatch.fold(title.replacingOccurrences(of: #"(?i)\s*[\(\[].*?(remaster|version|edition|feat\.|ft\.).*?[\)\]]"#, with: "", options: .regularExpression)), found = SearchMatch.fold(videoTitle)
        let creator = SearchMatch.fold(artist.components(separatedBy: CharacterSet(charactersIn: ";,")).first ?? artist)
        guard !wanted.isEmpty, !creator.isEmpty, videoDurationMs > 0, durationMs <= 0 || (videoDurationMs >= durationMs / 2 && videoDurationMs <= max(durationMs * 3, durationMs + 600_000)) else { return false }
        let words = Set(found.split(separator: " ").map(String.init))
        let wantedWords = Set(wanted.split(separator: " ").map(String.init))
        let otherVersions: Set<String> = ["live", "cover", "remix", "karaoke", "instrumental", "slowed", "sped", "reaction", "lyrics", "lyric", "audio", "visualizer"]
        guard otherVersions.intersection(words).subtracting(wantedWords).isEmpty else { return false }
        let channelName = SearchMatch.fold(channel).replacingOccurrences(of: " ", with: "")
        let artistName = creator.replacingOccurrences(of: " ", with: "")
        let artistChannel = [artistName, artistName + "vevo", artistName + "official"].contains(channelName)
        let artistInTitle = Set(creator.split(separator: " ").map(String.init)).isSubset(of: words)
        return wantedWords.isSubset(of: words) && (artistChannel || artistInTitle)
    }

    @MainActor static func find(title: String, artist: String, durationMs: Int64) async throws -> MusicVideo? {
        try await findAll(title: title, artist: artist, durationMs: durationMs).first
    }

    @MainActor private static var cache: [String: (Date, [MusicVideo])] = [:]
    @MainActor private static var pending: [String: Task<[MusicVideo], Error>] = [:]
    @MainActor static func prepare(_ song: Song) {
        guard !song.isSpoken else { return }
        Task { if let videos = try? await findAll(title: song.title, artist: song.artist, durationMs: song.durationMs), let first = videos.first { VideoWebCache.prepare(first.id) } }
    }
    @MainActor static func findAll(title: String, artist: String, durationMs: Int64) async throws -> [MusicVideo] {
        let key = "\(SearchMatch.fold(title))|\(SearchMatch.fold(artist))"
        if let entry = cache[key], Date().timeIntervalSince(entry.0) < (entry.1.isEmpty ? 600 : 86400) { return entry.1 }
        if let task = pending[key] { return try await task.value }
        let task = Task { try await search(title: title, artist: artist, durationMs: durationMs) }
        pending[key] = task
        defer { pending[key] = nil }
        let result = try await task.value
        if cache.count >= 100, let oldest = cache.min(by: { $0.value.0 < $1.value.0 })?.key { cache[oldest] = nil }
        cache[key] = (Date(), result)
        return result
    }

    private static func search(title: String, artist: String, durationMs: Int64) async throws -> [MusicVideo] {
        let search = try await AudioFallback.request("search", ["query": "\(artist) \(title) music video"])
        var candidates: [[String: Any]] = []
        func visit(_ value: Any) {
            if let object = value as? [String: Any] {
                if let video = object["videoRenderer"] as? [String: Any] { candidates.append(video) }
                object.values.forEach(visit)
            } else if let array = value as? [Any] { array.forEach(visit) }
        }
        visit(search)
        func text(_ value: Any?) -> String {
            guard let object = value as? [String: Any] else { return "" }
            return object["simpleText"] as? String ?? (object["runs"] as? [[String: Any]])?.compactMap { $0["text"] as? String }.joined() ?? ""
        }
        let matches = candidates.filter { item in
            let seconds = text(item["lengthText"]).split(separator: ":").reduce(Int64(0)) { $0 * 60 + (Int64($1) ?? 0) }
            return Self.matches(title: title, artist: artist, durationMs: durationMs, videoTitle: text(item["title"]), channel: text(item["ownerText"]), videoDurationMs: seconds * 1000)
        }
        var seen = Set<String>()
        return matches.compactMap { match in
            guard let id = match["videoId"] as? String,
                  id.range(of: "^[A-Za-z0-9_-]{11}$", options: .regularExpression) != nil,
                  seen.insert(id).inserted else { return nil }
            let duration = text(match["lengthText"]).split(separator: ":").reduce(Double(0)) { $0 * 60 + (Double($1) ?? 0) }
            return MusicVideo(id: id, title: text(match["title"]), duration: duration)
        }
    }
}
