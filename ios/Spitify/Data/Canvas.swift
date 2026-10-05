import AVFoundation
import CryptoKit
import Foundation

/// Canvas: a short, silent loop from the song's official music video behind the player.
/// Clips come from Apple's public iTunes Search API (music-video previews, 1080p for most modern
/// videos), so they're tied to the exact song. The looped seconds are cut once (no re-encode) and
/// kept on disk, then played natively; no web view.
enum CanvasLookup {
    /// Part of the 30 s preview used as the loop (previews already start inside the video).
    static let loopStart = 6.0, loopEnd = 16.0
    private static let hitTTL: TimeInterval = 30 * 86_400, missTTL: TimeInterval = 86_400
    /// Words that mark a different recording or a non-video upload.
    private static let variants: Set<String> = ["live", "remix", "acoustic", "lyric", "lyrics", "karaoke", "instrumental", "cover", "sped", "slowed", "visualizer", "session", "sessions", "version", "demo"]

    static func clean(_ text: String) -> String {
        var t = text
        for pattern in [#"(?i)\((feat|ft|with)\.?[^)]*\)|\[(feat|ft|with)\.?[^\]]*\]"#, #"(?i)\b(feat|ft|featuring)\b.*$"#,
                        #"(?i)\((official|music|video|hd|4k|remaster(ed)?|\d{4} remaster(ed)?)[^)]*\)"#] {
            t = t.replacingOccurrences(of: pattern, with: " ", options: .regularExpression)
        }
        return SearchMatch.fold(t).split(separator: " ").joined(separator: " ")
    }

    /// Same song by the same artist, and not a live/remix/lyric variant the song itself isn't.
    static func matches(title: String, artist: String, foundTitle: String, foundArtist: String) -> Bool {
        let wanted = clean(title), found = clean(foundTitle)
        guard !wanted.isEmpty, !found.isEmpty else { return false }
        let wantedWords = Set(wanted.split(separator: " ").map(String.init)), foundWords = Set(found.split(separator: " ").map(String.init))
        guard foundWords.intersection(variants).subtracting(wantedWords).isEmpty else { return false }
        // "One More Time (Radio Edit)" / "Song - 2009 Remaster" still match the video "One More Time".
        func core(_ s: String) -> String {
            clean(String(s.components(separatedBy: " - ")[0].split(separator: "(", maxSplits: 1, omittingEmptySubsequences: false)[0].split(separator: "[", maxSplits: 1, omittingEmptySubsequences: false)[0]))
        }
        guard found == wanted || core(foundTitle) == core(title) else { return false }
        let primary = clean(artist.components(separatedBy: CharacterSet(charactersIn: ";,&")).first ?? artist)
        let credited = clean(foundArtist)
        guard !primary.isEmpty else { return false }
        return credited == primary || Set(primary.split(separator: " ")).isSubset(of: Set(credited.split(separator: " ")))
    }

    private static func key(_ title: String, _ artist: String) -> String {
        clean(title) + "|" + clean(artist.components(separatedBy: CharacterSet(charactersIn: ";,&")).first ?? artist)
    }

    @MainActor private static var memory: [String: String] = [:]

    /// The preview URL for this song's music video, or nil when it has none. Never throws.
    @MainActor static func find(title: String, artist: String) async -> URL? {
        let key = key(title, artist)
        if let hit = memory[key] { return URL(string: hit) }
        let defaults = UserDefaults.standard
        if let saved = defaults.dictionary(forKey: "canvas:" + key), let at = saved["at"] as? Double, let url = saved["url"] as? String,
           Date().timeIntervalSince1970 - at < (url.isEmpty ? missTTL : hitTTL) {
            memory[key] = url; return URL(string: url)
        }
        let found: URL?
        do { found = try await search(title: title, artist: artist) } catch { return nil } // offline: retry later, don't remember a miss
        let url = found?.absoluteString ?? ""
        memory[key] = url
        defaults.set(["at": Date().timeIntervalSince1970, "url": url], forKey: "canvas:" + key)
        return found
    }

    private static func search(title: String, artist: String) async throws -> URL? {
        var c = URLComponents(string: "https://itunes.apple.com/search")!
        c.queryItems = [URLQueryItem(name: "term", value: "\(artist.components(separatedBy: CharacterSet(charactersIn: ";,")).first ?? artist) \(clean(title))"),
                        URLQueryItem(name: "entity", value: "musicVideo"), URLQueryItem(name: "limit", value: "15"),
                        URLQueryItem(name: "country", value: Locale.current.region?.identifier ?? "US")]
        var request = URLRequest(url: c.url!, timeoutInterval: 10)
        request.setValue("Spitify/1.0 (iOS music player)", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
        let results = (try JSONSerialization.jsonObject(with: data) as? [String: Any])?["results"] as? [[String: Any]] ?? []
        let hits = results.filter {
            ($0["previewUrl"] as? String)?.hasPrefix("https://") == true &&
                matches(title: title, artist: artist, foundTitle: $0["trackName"] as? String ?? "", foundArtist: $0["artistName"] as? String ?? "")
        }
        // Prefer the HD encodes ("…1920w…") over older 640×480 ones.
        let best = hits.first { ($0["previewUrl"] as? String)?.contains("1920w") == true } ?? hits.first
        return (best?["previewUrl"] as? String).flatMap(URL.init(string:))
    }
}

/// The looped seconds of each canvas, cut once from the preview and kept on disk.
enum CanvasClips {
    static let directory: URL = {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("canvas", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()
    private static let limit: Int64 = 300 * 1_048_576

    static func file(for source: URL) -> URL {
        directory.appendingPathComponent(SHA256.hash(data: Data(source.absoluteString.utf8)).prefix(12).map { String(format: "%02x", $0) }.joined() + ".mp4")
    }

    /// A local file holding just the loop (video only, original quality), or nil if it can't be made.
    static func clip(from source: URL) async -> URL? {
        let out = file(for: source)
        if FileManager.default.fileExists(atPath: out.path) {
            try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: out.path)
            return out
        }
        let asset = AVURLAsset(url: source)
        guard let track = try? await asset.loadTracks(withMediaType: .video).first else { return nil }
        let composition = AVMutableComposition()
        let range = CMTimeRange(start: CMTime(seconds: CanvasLookup.loopStart, preferredTimescale: 600),
                                end: CMTime(seconds: CanvasLookup.loopEnd, preferredTimescale: 600))
        guard let video = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid),
              (try? video.insertTimeRange(range, of: track, at: .zero)) != nil else { return nil }
        if let transform = try? await track.load(.preferredTransform) { video.preferredTransform = transform }
        guard let export = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetPassthrough) else { return nil }
        let partial = out.deletingPathExtension().appendingPathExtension("part.mp4")
        try? FileManager.default.removeItem(at: partial)
        export.outputURL = partial; export.outputFileType = .mp4
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in export.exportAsynchronously { done.resume() } }
        guard export.status == .completed else { try? FileManager.default.removeItem(at: partial); return nil }
        try? FileManager.default.moveItem(at: partial, to: out)
        trim()
        return FileManager.default.fileExists(atPath: out.path) ? out : nil
    }

    private static func trim() {
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
        guard let files = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: keys) else { return }
        var entries = files.compactMap { url -> (URL, Int64, Date)? in
            guard let v = try? url.resourceValues(forKeys: Set(keys)) else { return nil }
            return (url, Int64(v.fileSize ?? 0), v.contentModificationDate ?? .distantPast)
        }.sorted { $0.2 < $1.2 }
        var total = entries.reduce(0) { $0 + $1.1 }
        while total > limit, !entries.isEmpty { let e = entries.removeFirst(); try? FileManager.default.removeItem(at: e.0); total -= e.1 }
    }
}
