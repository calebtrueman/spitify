import Foundation

struct SpotifyPlaylistResult: Identifiable, Hashable {
    var id: String
    var name: String
    var description: String
    var image: String?
    var owner: String
}

struct SpotifyPlaylists {
    var session: URLSession = .shared
    static let service = "https://spotify.xwolf.space/api"
    static func playlistID(_ input: String) -> String? {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let candidate: String
        if text.hasPrefix("spotify:playlist:") { candidate = String(text.dropFirst(17)) }
        else if let url = URLComponents(string: text), url.scheme == "https", url.host == "open.spotify.com", url.user == nil, url.password == nil {
            let parts = url.path.split(separator: "/")
            guard let index = parts.firstIndex(of: "playlist"), parts.count == index + 2 else { return nil }
            candidate = String(parts[index + 1])
        } else { candidate = text }
        return candidate.count == 22 && candidate.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber) } ? candidate : nil
    }
    static func plain(_ value: String) -> String {
        value.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
            .replacingOccurrences(of: "&amp;", with: "&").replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#39;", with: "'").replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
    }
    private func data(_ url: URL) async throws -> Data {
        var request = URLRequest(url: url, timeoutInterval: 25)
        request.setValue("Spitify/1.0", forHTTPHeaderField: "User-Agent")
        let (bytes, response) = try await session.bytes(for: request)
        try MonochromeClient.check(response)
        var data = Data()
        for try await byte in bytes {
            data.append(byte)
            guard data.count <= 8_000_000 else { throw MusicSourceError.message("This playlist response is too large.") }
        }
        return data
    }
    func search(_ query: String) async throws -> [SpotifyPlaylistResult] {
        var url = URLComponents(string: Self.service + "/search")!
        url.queryItems = [.init(name: "q", value: String(query.prefix(200))), .init(name: "type", value: "playlist"), .init(name: "limit", value: "20")]
        let object = try JSONSerialization.jsonObject(with: await data(url.url!)) as? [String: Any]
        guard object?["success"] as? Bool == true, let rows = object?["results"] as? [[String: Any]] else { throw MusicSourceError.message("Playlist search is unavailable. You can still paste a Spotify playlist link.") }
        return rows.prefix(50).compactMap { row in
            guard let id = row["id"] as? String, Self.playlistID(id) != nil, let name = row["name"] as? String else { return nil }
            let image = row["thumbnail"] as? String
            return SpotifyPlaylistResult(id: id, name: String(name.prefix(200)), description: Self.plain(row["description"] as? String ?? ""), image: image.flatMap { SocialRules.publicURL($0) ? $0 : nil }, owner: row["owner"] as? String ?? "Spotify")
        }
    }
    func load(_ input: String, owner: String) async throws -> SharedPlaylist {
        guard let id = Self.playlistID(input) else { throw MusicSourceError.message("Paste a public Spotify playlist link.") }
        do {
            let bytes = try await data(URL(string: Self.service + "/playlist/" + id)!)
            let object = try JSONSerialization.jsonObject(with: bytes) as? [String: Any]
            guard object?["success"] as? Bool == true, let playlist = object?["playlist"] as? [String: Any] else { throw MusicSourceError.message("Couldn't read that playlist.") }
            return try Self.parse(playlist, id: id, owner: owner)
        } catch {
            try Task.checkCancellation()
            let bytes = try await data(URL(string: "https://open.spotify.com/embed/playlist/" + id)!)
            return try Self.parseEmbed(bytes, id: id, owner: owner)
        }
    }
    static func parse(_ object: [String: Any], id: String, owner: String) throws -> SharedPlaylist {
        guard let name = object["name"] as? String, let rows = object["tracks"] as? [[String: Any]] else { throw MusicSourceError.message("The playlist source has changed. Try another public Spotify playlist link.") }
        let tracks = rows.prefix(2000).enumerated().compactMap { index, row -> SharedTrack? in
            guard let title = row["title"] as? String, let artist = row["artist"] as? String else { return nil }
            let track = SharedTrack(id: "spotify:\(id):\(index)", title: title, artist: artist, album: row["album"] as? String ?? "", durationMs: (row["duration_ms"] as? NSNumber)?.int64Value ?? 0, spotifyID: row["id"] as? String)
            return track.valid() ? track : nil
        }
        let count = (object["total_tracks"] as? NSNumber)?.intValue
        let image = object["thumbnail"] as? String
        let playlist = SharedPlaylist(id: "spotify-" + id, owner: owner, name: String(name.prefix(200)), description: String(plain(object["description"] as? String ?? "").prefix(4000)), image: image.flatMap { SocialRules.publicURL($0) ? $0 : nil }, sourceURL: "https://open.spotify.com/playlist/" + id, sourceName: "Spotify", sourceCount: count, partial: count == nil || count != tracks.count || rows.count != tracks.count, tracks: tracks)
        guard playlist.valid() else { throw MusicSourceError.message("That playlist contains invalid details.") }
        return playlist
    }
    static func parseEmbed(_ data: Data, id: String, owner: String) throws -> SharedPlaylist {
        guard let html = String(data: data, encoding: .utf8), let regex = try? NSRegularExpression(pattern: "<script[^>]*id=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>", options: .dotMatchesLineSeparators),
              let match = regex.firstMatch(in: html, range: NSRange(html.startIndex..., in: html)), let range = Range(match.range(at: 1), in: html),
              let json = String(html[range]).data(using: .utf8), let root = try JSONSerialization.jsonObject(with: json) as? [String: Any],
              let props = root["props"] as? [String: Any], let page = props["pageProps"] as? [String: Any], let state = page["state"] as? [String: Any],
              let value = state["data"] as? [String: Any], let entity = value["entity"] as? [String: Any], entity["type"] as? String == "playlist",
              entity["id"] as? String == id, let rows = entity["trackList"] as? [[String: Any]] else { throw MusicSourceError.message("This playlist is private, unavailable, or its page has changed.") }
        let attributes = entity["attributes"] as? [[String: Any]] ?? []
        let description = attributes.first { $0["key"] as? String == "episode_description" }?["value"] as? String ?? ""
        let cover = entity["coverArt"] as? [String: Any]
        let image = (cover?["sources"] as? [[String: Any]])?.first?["url"] as? String
        let mapped: [[String: Any]] = rows.map { ["title": $0["title"] ?? "", "artist": $0["subtitle"] ?? "", "duration_ms": $0["duration"] ?? 0, "id": ($0["uri"] as? String)?.split(separator: ":").last.map(String.init) ?? ""] }
        var object: [String: Any] = ["name": entity["name"] ?? entity["title"] ?? "Spotify playlist", "description": description, "tracks": mapped]
        object["thumbnail"] = image
        // The embed does not prove the total. Keep the partial warning even for a short list.
        return try parse(object, id: id, owner: owner)
    }
}
