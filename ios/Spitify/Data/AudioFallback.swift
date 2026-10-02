import Foundation

/// Public audio lookup on this device. A login or access challenge ends the attempt.
enum AudioFallback {
    static func matches(_ track: OnlineTrack, title: String, author: String, durationMs: Int64) -> Bool {
        guard track.durationMs > 0, abs(track.durationMs - durationMs) <= 3_000 else { return false }
        func clean(_ value: String) -> String {
            SearchMatch.fold(value).replacingOccurrences(of: "\\b(feat|ft|featuring)\\b", with: " ", options: .regularExpression)
                .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
        }
        let wanted = clean(track.title), found = clean(title)
        let artist = clean(track.artist.components(separatedBy: CharacterSet(charactersIn: ";,")).first ?? track.artist)
        let words = Set(found.split(separator: " ").map(String.init)), wantedWords = Set(wanted.split(separator: " ").map(String.init))
        let changed: Set<String> = ["live", "cover", "remix", "slowed", "sped", "nightcore", "instrumental", "karaoke", "432hz", "528hz", "clean"]
        guard changed.intersection(words).subtracting(wantedWords).isEmpty else { return false }
        return !artist.isEmpty && (clean(author).contains(artist) || found.contains(artist)) && wantedWords.isSubset(of: words)
    }
    static func validAudioURL(_ value: String) -> Bool {
        guard let url = URL(string: value) else { return false }
        return url.scheme == "https" && url.host?.hasSuffix(".googlevideo.com") == true && url.user == nil
    }
    static func resolve(_ track: OnlineTrack) async throws -> OnlineTrack? {
        let search = try await request("search", ["query": "\(track.artist) \(track.title) official audio"])
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
        guard let match = candidates.first(where: { video in
            let duration = text(video["lengthText"]).split(separator: ":").reduce(Int64(0)) { $0 * 60 + (Int64($1) ?? 0) } * 1000
            return matches(track, title: text(video["title"]), author: text(video["ownerText"]), durationMs: duration)
        }), let id = match["videoId"] as? String, id.range(of: "^[A-Za-z0-9_-]{11}$", options: .regularExpression) != nil else { return nil }
        let player = try await request("player", ["videoId": id])
        guard (player["playabilityStatus"] as? [String: Any])?["status"] as? String == "OK",
              let details = player["videoDetails"] as? [String: Any],
              matches(track, title: details["title"] as? String ?? "", author: details["author"] as? String ?? "", durationMs: (Int64(details["lengthSeconds"] as? String ?? "") ?? 0) * 1000),
              let formats = (player["streamingData"] as? [String: Any])?["adaptiveFormats"] as? [[String: Any]] else { return nil }
        guard let audio = formats.filter({ ($0["mimeType"] as? String)?.hasPrefix("audio/mp4") == true && validAudioURL($0["url"] as? String ?? "") })
            .max(by: { ($0["bitrate"] as? Int ?? 0) < ($1["bitrate"] as? Int ?? 0) }), let url = audio["url"] as? String else { return nil }
        var result = track
        result.playable = true; result.audioURL = url; result.audioExtension = "m4a"; result.fallbackTried = true
        return result
    }
    private static func request(_ path: String, _ payload: [String: Any]) async throws -> [String: Any] {
        var body = payload
        body["context"] = ["client": ["clientName": "WEB", "clientVersion": "2.20260708.00.00"]]
        var req = URLRequest(url: URL(string: "https://www.youtube.com/youtubei/v1/\(path)")!, timeoutInterval: 15)
        req.httpMethod = "POST"; req.httpBody = try JSONSerialization.data(withJSONObject: body)
        req.setValue("application/json", forHTTPHeaderField: "Content-Type"); req.setValue("Mozilla/5.0", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: req)
        guard (response as? HTTPURLResponse)?.statusCode == 200, data.count <= 5_000_000,
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw MusicSourceError.message("Could not check another recording. Please try again later.") }
        return object
    }
}
