import Foundation

struct MusicVideo: Equatable {
    var id: String
    var title: String
    var duration: Double
}

enum MusicVideoLookup {
    static func matches(title: String, artist: String, durationMs: Int64, videoTitle: String, channel: String, videoDurationMs: Int64) -> Bool {
        let wanted = SearchMatch.fold(title), found = SearchMatch.fold(videoTitle)
        let creator = SearchMatch.fold(artist.components(separatedBy: CharacterSet(charactersIn: ";,")).first ?? artist)
        guard !wanted.isEmpty, !creator.isEmpty, durationMs > 0, abs(durationMs - videoDurationMs) <= 30_000 else { return false }
        let words = Set(found.split(separator: " ").map(String.init))
        let wantedWords = Set(wanted.split(separator: " ").map(String.init))
        let otherVersions: Set<String> = ["live", "cover", "remix", "karaoke", "instrumental", "slowed", "sped", "reaction", "lyrics", "lyric", "audio", "visualizer"]
        guard otherVersions.intersection(words).subtracting(wantedWords).isEmpty else { return false }
        let channelName = SearchMatch.fold(channel).replacingOccurrences(of: " ", with: "")
        let artistName = creator.replacingOccurrences(of: " ", with: "")
        let artistChannel = [artistName, artistName + "vevo", artistName + "official"].contains(channelName)
        return wantedWords.isSubset(of: words) && artistChannel
    }

    static func find(title: String, artist: String, durationMs: Int64) async throws -> MusicVideo? {
        let search = try await AudioFallback.request("search", ["query": "\(artist) \(title) official music video"])
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
        guard let match = matches.first, let id = match["videoId"] as? String,
              id.range(of: "^[A-Za-z0-9_-]{11}$", options: .regularExpression) != nil else { return nil }
        let duration = text(match["lengthText"]).split(separator: ":").reduce(Double(0)) { $0 * 60 + (Double($1) ?? 0) }
        return MusicVideo(id: id, title: text(match["title"]), duration: duration)
    }
}
