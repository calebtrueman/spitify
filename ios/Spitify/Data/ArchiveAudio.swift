import Foundation

/// Public Archive files only. Album identity is checked before a song can enter the queue.
actor ArchiveAudio {
    static let shared = ArchiveAudio()
    struct Candidate {
        let url: String
        let title: String
        let durationMs: Int64?
        let ext: String
        var byteCount: Int64? = nil
    }
    private var cached: [String: (Date, [Candidate])] = [:]
    private var loading: [String: Task<[Candidate], Error>] = [:]

    func resolve(_ track: OnlineTrack) async throws -> OnlineTrack? {
        guard track.durationMs > 0, !track.album.isEmpty else { return nil }
        let key = SearchMatch.fold(track.artist) + "|" + Self.albumName(track.album)
        let choices: [Candidate]
        if let entry = cached[key], Date().timeIntervalSince(entry.0) < 900 { choices = entry.1 }
        else {
            let task = loading[key] ?? Task { try await Self.lookup(track) }
            loading[key] = task
            do {
                choices = try await task.value
                cached[key] = (Date(), choices)
                loading[key] = nil
                if cached.count > 40 { cached = [key: (Date(), choices)] }
            } catch { loading[key] = nil; throw error }
        }
        try Task.checkCancellation()
        let tried = Set(track.attemptedSources ?? [])
        guard let choice = choices.first(where: {
            !tried.contains($0.url) && Self.songName($0.title, artist: track.artist) == SearchMatch.fold(track.title) &&
            ($0.durationMs == nil || abs($0.durationMs! - track.durationMs) <= 3000)
        }) else { return nil }
        var result = track
        result.audioURL = choice.url; result.audioExtension = choice.ext; result.audioByteCount = choice.byteCount; result.playable = true
        result.fallbackTried = false
        result.attemptedSources = Array(tried.union([choice.url])).sorted()
        return result
    }

    nonisolated static func archiveEntrySize(_ row: String) -> Int64? {
        guard let expression = try? NSRegularExpression(pattern: "id=\"size\">([0-9]+)"),
              let match = expression.firstMatch(in: row, range: NSRange(row.startIndex..., in: row)),
              let range = Range(match.range(at: 1), in: row), let size = Int64(row[range]), size > 0 else { return nil }
        return size
    }
    nonisolated static func albumName(_ value: String) -> String {
        SearchMatch.fold(value.replacingOccurrences(of: "(?i)\\s*[\\(\\[](?:(?:19|20)\\d{2}|bonus track version|deluxe(?: edition| version)?|special version)[\\)\\]]", with: "", options: .regularExpression))
    }
    nonisolated static func songName(_ value: String, artist: String) -> String {
        let name = value.replacingOccurrences(of: "^\\s*\\d{1,3}[.\\s_-]+", with: "", options: .regularExpression)
        let folded = SearchMatch.fold(name), prefix = SearchMatch.fold(artist) + " "
        return folded.hasPrefix(prefix) ? String(folded.dropFirst(prefix.count)) : folded
    }
    nonisolated static func validURL(_ value: String) -> Bool {
        guard let url = URL(string: value), url.scheme == "https", url.host == "archive.org", url.user == nil,
              url.password == nil, url.query == nil, url.fragment == nil else { return false }
        let parts = url.path.split(separator: "/")
        return parts.count >= 3 && parts[0] == "download" && validID(String(parts[1])) &&
            !parts.contains("..") && ["flac", "m4a", "mp3"].contains(url.pathExtension.lowercased())
    }
    private nonisolated static func validID(_ value: String) -> Bool {
        value.range(of: "^[A-Za-z0-9][A-Za-z0-9_.-]{0,199}$", options: .regularExpression) != nil && value != ".."
    }
    private nonisolated static func strings(_ value: Any?) -> [String] {
        if let text = value as? String { return [text] }
        return value as? [String] ?? []
    }
    private nonisolated static func restricted(_ value: Any?) -> Bool {
        if let boolean = value as? Bool { return boolean }
        return ["true", "1"].contains((value as? String ?? "").lowercased())
    }
    private nonisolated static func get(_ url: URL) async throws -> Data {
        let (data, response) = try await URLSession.shared.data(for: URLRequest(url: url, timeoutInterval: 25))
        guard (response as? HTTPURLResponse)?.statusCode == 200, data.count <= 5_000_000 else {
            throw MusicSourceError.message("The backup catalogue is unavailable right now.")
        }
        return data
    }
    private nonisolated static func json(_ url: URL) async throws -> [String: Any] {
        guard let result = try JSONSerialization.jsonObject(with: await get(url)) as? [String: Any] else {
            throw MusicSourceError.message("The backup catalogue returned an unreadable response.")
        }
        return result
    }
    private nonisolated static func lookup(_ track: OnlineTrack) async throws -> [Candidate] {
        let artist = SearchMatch.fold(track.artist), album = albumName(track.album)
        guard !artist.isEmpty, !album.isEmpty else { return [] }
        var query = URLComponents(string: "https://archive.org/advancedsearch.php")!
        query.queryItems = [URLQueryItem(name: "q", value: "mediatype:audio AND creator:(\(artist)) AND title:(\(album))"),
            URLQueryItem(name: "output", value: "json"), URLQueryItem(name: "rows", value: "12"),
            URLQueryItem(name: "fl[]", value: "identifier,title,creator")]
        let search = try await json(query.url!)
        let docs = (search["response"] as? [String: Any])?["docs"] as? [[String: Any]] ?? []
        var result: [Candidate] = []
        for doc in docs {
            try Task.checkCancellation()
            guard let id = doc["identifier"] as? String, validID(id),
                  strings(doc["creator"]).contains(where: { SearchMatch.fold($0) == artist }) else { continue }
            let title = albumName(doc["title"] as? String ?? "")
            guard title == album || title == artist + " " + album else { continue }
            let item = try await json(URL(string: "https://archive.org/metadata/\(id)")!)
            let metadata = item["metadata"] as? [String: Any] ?? [:]
            guard !restricted(item["is_dark"]), !restricted(item["is_restricted"]), !restricted(metadata["access-restricted-item"]),
                  strings(metadata["creator"]).contains(where: { SearchMatch.fold($0) == artist }) else { continue }
            let files = item["files"] as? [[String: Any]] ?? []
            let base = URL(string: "https://archive.org/download/\(id)/")!
            for file in files where !restricted(file["private"]) {
                guard let name = file["name"] as? String else { continue }
                let ext = (name as NSString).pathExtension.lowercased()
                if ["flac", "m4a", "mp3"].contains(ext) {
                    let url = base.appendingPathComponent(name).absoluteString
                    guard validURL(url) else { continue }
                    let length = Double(file["length"] as? String ?? "").map { Int64($0 * 1000) }
                    result.append(Candidate(url: url, title: file["title"] as? String ?? ((name as NSString).lastPathComponent as NSString).deletingPathExtension,
                        durationMs: length, ext: ext, byteCount: Int64(file["size"] as? String ?? "")))
                }
            }
            // Archive exposes individual public ZIP entries; the app never downloads or unpacks an album ZIP.
            if result.isEmpty, let zip = files.first(where: { !restricted($0["private"]) && ($0["name"] as? String ?? "").lowercased().hasSuffix(".zip") }),
               let name = zip["name"] as? String {
                let zipURL = base.appendingPathComponent(name, isDirectory: true)
                let html = String(data: try await get(zipURL), encoding: .utf8) ?? ""
                let regex = try NSRegularExpression(pattern: "href=\"(//archive\\.org/download/[^\"]+)\"")
                let prefix = base.appendingPathComponent(name).absoluteString + "/"
                for match in regex.matches(in: html, range: NSRange(html.startIndex..., in: html)) {
                    guard let range = Range(match.range(at: 1), in: html) else { continue }
                    let link = "https:" + String(html[range]).replacingOccurrences(of: "&amp;", with: "&")
                    guard link.hasPrefix(prefix), validURL(link), let url = URL(string: link) else { continue }
                    let file = (url.path as NSString).lastPathComponent
                    let afterLink = String(html[range.upperBound...].prefix(2000)).components(separatedBy: "</tr>").first ?? ""
                    let size = archiveEntrySize(afterLink)
                    result.append(Candidate(url: link, title: (file as NSString).deletingPathExtension, durationMs: nil, ext: url.pathExtension.lowercased(), byteCount: size))
                }
            }
            if !result.isEmpty { break }
        }
        return result.sorted { ($0.ext == "flac" ? 0 : 1) < ($1.ext == "flac" ? 0 : 1) }
    }
}
