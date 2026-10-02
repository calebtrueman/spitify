import Foundation

/// Monochrome's public Tracks API. Keep its wire format out of the library/player.
struct OnlineTrack: Codable, Identifiable, Equatable {
    let id: String
    var title: String
    var artist: String
    var album: String
    var releaseID: String
    var durationMs: Int64
    var trackNumber: Int
    var discNumber: Int
    var artwork: String?
    var playable: Bool
}

struct OnlineAlbum: Identifiable {
    let id: String
    let title: String
    let artist: String
    let artwork: String?
}

enum MusicSourceError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let text) = self { return text }; return nil }
}

struct MonochromeClient {
    static let baseURL = URL(string: "https://tracks.monochrome.st")!
    var session: URLSession = .shared

    static func audioURL(_ id: String) throws -> URL {
        guard !id.isEmpty, id.allSatisfy({ $0.isASCII && $0.isNumber }) else {
            throw MusicSourceError.message("This song has an invalid source ID.")
        }
        return baseURL.appendingPathComponent("track").appendingPathComponent(id)
    }

    private func json(_ path: String, query: String? = nil) async throws -> [String: Any] {
        var parts = URLComponents(url: Self.baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if let query { parts.queryItems = [URLQueryItem(name: "q", value: query), URLQueryItem(name: "limit", value: "30")] }
        var request = URLRequest(url: parts.url!, timeoutInterval: 25)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        try Self.check(response)
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw MusicSourceError.message("Monochrome returned an unexpected response.")
        }
        return object
    }

    static func check(_ response: URLResponse) throws {
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 else {
            let text: String
            switch code {
            case 401, 403, 428: text = "Monochrome is asking for access approval. Try its website."
            case 404: text = "This song is no longer available."
            case 429: text = "Monochrome is busy. Wait a little before retrying."
            default: text = "Monochrome could not complete the request (\(code))."
            }
            throw MusicSourceError.message(text)
        }
    }

    func search(_ query: String) async throws -> [OnlineTrack] {
        let data = try await json("search/tracks", query: query)
        guard let tracks = data["tracks"] as? [[String: Any]] else { throw MusicSourceError.message("Monochrome's search response has changed.") }
        return tracks.compactMap { Self.track($0) }
    }

    func searchAlbums(_ query: String) async throws -> [OnlineAlbum] {
        let data = try await json("search/releases", query: query)
        guard let albums = data["releases"] as? [[String: Any]] else { throw MusicSourceError.message("Monochrome's album response has changed.") }
        return albums.compactMap { item in
            guard let id = Self.id(item["releaseId"] ?? item["id"]) else { return nil }
            return OnlineAlbum(id: id, title: item["title"] as? String ?? "Unknown album", artist: Self.artist(item), artwork: item["artwork"] as? String)
        }
    }

    func albumTracks(_ id: String) async throws -> [OnlineTrack] {
        _ = try Self.audioURL(id) // IDs are digits, never paths or arbitrary URLs.
        let album = try await json("releases/\(id)")
        guard let tracks = album["tracks"] as? [[String: Any]] else { throw MusicSourceError.message("Monochrome did not return this album's songs.") }
        return tracks.compactMap { Self.track($0, album: album) }.sorted { ($0.discNumber, $0.trackNumber) < ($1.discNumber, $1.trackNumber) }
    }

    static func id(_ value: Any?) -> String? {
        let string = (value as? String) ?? (value as? NSNumber)?.stringValue
        guard let string, !string.isEmpty, string.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
        return string
    }

    static func artist(_ item: [String: Any]) -> String {
        let names = item["artistNames"] as? [String] ?? (item["artists"] as? [[String: Any]])?.compactMap { ($0["name"] ?? $0["displayName"]) as? String } ?? []
        return names.isEmpty ? "Unknown artist" : names.joined(separator: ", ")
    }

    static func track(_ item: [String: Any], album: [String: Any]? = nil) -> OnlineTrack? {
        guard let id = id(item["trackId"] ?? item["id"]), let title = item["title"] as? String else { return nil }
        let name = artist(item)
        return OnlineTrack(id: id, title: title, artist: name == "Unknown artist" ? artist(album ?? [:]) : name,
            album: album?["title"] as? String ?? item["albumTitle"] as? String ?? "",
            releaseID: Self.id(item["releaseId"] ?? album?["releaseId"]) ?? "",
            durationMs: (item["duration"] as? NSNumber)?.int64Value ?? 0,
            trackNumber: item["trackNumber"] as? Int ?? 0, discNumber: item["discNumber"] as? Int ?? 1,
            artwork: (item["artwork"] ?? album?["artwork"]) as? String, playable: item["playable"] as? Bool ?? true)
    }
}

struct FLACInfo: Equatable {
    let sampleRate: Int
    let bits: Int
    let durationMs: Int64
    var label: String { "FLAC · \(bits)-bit · \(Double(sampleRate) / 1000) kHz" }

    /// Checks the actual file, including metadata boundaries, rather than trusting its suffix or MIME type.
    static func read(_ url: URL, expectedDurationMs: Int64 = 0) throws -> FLACInfo {
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        let size = try file.seekToEnd()
        try file.seek(toOffset: 0)
        guard try file.read(upToCount: 4) == Data("fLaC".utf8) else { throw MusicSourceError.message("The download is not a FLAC audio file.") }
        var info: FLACInfo?
        var last = false
        while !last {
            guard let header = try file.read(upToCount: 4), header.count == 4 else { throw MusicSourceError.message("The audio download is incomplete.") }
            last = header[0] & 0x80 != 0
            let type = header[0] & 0x7f
            let length = UInt64(header[1]) << 16 | UInt64(header[2]) << 8 | UInt64(header[3])
            let offset = try file.offset()
            guard length <= size, offset <= size - length else { throw MusicSourceError.message("The audio download is incomplete.") }
            if info == nil {
                guard type == 0, length == 34, let data = try file.read(upToCount: 34), data.count == 34 else { throw MusicSourceError.message("The audio header is invalid.") }
                let packed = data[10..<18].reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
                let rate = Int(packed >> 44), bits = Int((packed >> 36) & 31) + 1
                let samples = packed & 0xFFFFFFFFF
                guard rate > 0, samples > 0 else { throw MusicSourceError.message("The audio file has no duration.") }
                info = FLACInfo(sampleRate: rate, bits: bits, durationMs: Int64(samples * 1000 / UInt64(rate)))
            } else {
                try file.seek(toOffset: offset + length)
            }
        }
        guard let info, try file.offset() < size else { throw MusicSourceError.message("The download contains no audio.") }
        if expectedDurationMs > 0, abs(info.durationMs - expectedDurationMs) > 5_000 {
            throw MusicSourceError.message("The downloaded song's length does not match. It was not added.")
        }
        return info
    }
}
