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
        let changed: Set<String> = ["live", "cover", "remix", "mix", "slowed", "sped", "nightcore", "instrumental", "karaoke", "432hz", "528hz", "clean"]
        guard changed.intersection(words).subtracting(wantedWords).isEmpty else { return false }
        return !artist.isEmpty && (clean(author).contains(artist) || found.contains(artist)) && wantedWords.isSubset(of: words)
    }
    static func validAudioURL(_ value: String) -> Bool {
        guard let url = URL(string: value) else { return false }
        guard url.scheme == "https", url.user == nil, url.password == nil else { return false }
        if url.host == MonochromeClient.baseURL.host {
            let parts = url.path.split(separator: "/")
            return parts.count == 2 && parts[0] == "track" && MonochromeClient.id(String(parts[1])) != nil && url.query == nil
        }
        if ArchiveAudio.validURL(value) { return true }
        return url.host?.hasSuffix(".googlevideo.com") == true
    }
    static func resolve(_ track: OnlineTrack) async throws -> OnlineTrack? {
        if let copy = try? await monochromeCopy(track) { return copy }
        try Task.checkCancellation()
        if let copy = try? await ArchiveAudio.shared.resolve(track) { return copy }
        try Task.checkCancellation()
        return try await youtubeCopy(track)
    }

    static func sameRelease(_ a: String, _ b: String) -> Bool {
        func name(_ text: String) -> String {
            SearchMatch.fold(text.replacingOccurrences(of: "(?i)\\s*\\((bonus track version|deluxe( edition)?|special version)\\)", with: "", options: .regularExpression))
        }
        return !name(a).isEmpty && name(a) == name(b)
    }

    static func monochromeCopy(_ track: OnlineTrack,
        search: (String) async throws -> [OnlineTrack] = { try await MonochromeClient().search($0) },
        album: (String) async throws -> [OnlineTrack] = { try await MonochromeClient().albumTracks($0) }) async throws -> OnlineTrack? {
        let tried = Set(((track.attemptedSources ?? []) + [track.id]).filter { MonochromeClient.id($0) != nil })
        guard tried.count < 4 else { return nil }
        let choices = try await search("\(track.artist) \(track.title)")
        for choice in choices.filter({ !tried.contains($0.id) && $0.playable &&
            SearchMatch.fold($0.title) == SearchMatch.fold(track.title) &&
            SearchMatch.fold($0.artist) == SearchMatch.fold(track.artist) &&
            track.durationMs > 0 && abs($0.durationMs - track.durationMs) <= 3000 }).prefix(5) {
            try Task.checkCancellation()
            guard let release = try? await album(choice.releaseID),
                let match = release.first(where: { $0.id == choice.id }), sameRelease(track.album, match.album) else { continue }
            var result = track
            result.audioURL = try MonochromeClient.audioURL(match.id).absoluteString
            result.audioExtension = "flac"; result.audioByteCount = nil; result.fallbackTried = false
            result.attemptedSources = Array(Set((track.attemptedSources ?? []) + [track.id, match.id])).sorted()
            return result
        }
        return nil
    }

    private static func youtubeCopy(_ track: OnlineTrack) async throws -> OnlineTrack? {
        let search = try await request("search", ["query": "\(track.artist) \(track.title) \(track.album) official audio"])
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
        result.playable = true; result.audioURL = url; result.audioExtension = "m4a"; result.audioByteCount = Int64(audio["contentLength"] as? String ?? ""); result.fallbackTried = true
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
