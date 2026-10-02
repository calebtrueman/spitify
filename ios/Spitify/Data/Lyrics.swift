import Network
import Foundation
import Observation

struct LyricLine: Hashable { var time: TimeInterval; var text: String }

struct Lyrics: Hashable {
    var lines: [LyricLine]
    var synced: Bool
    var source: String
    var offset: TimeInterval = 0
}

enum LRC {
    private static let stamp = try! NSRegularExpression(pattern: "\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")
    private static let word = try! NSRegularExpression(pattern: "<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")
    private static let offsetTag = try! NSRegularExpression(pattern: "\\[offset:\\s*([+-]?\\d+)\\s*\\]", options: .caseInsensitive)

    static func isSynced(_ t: String) -> Bool { stamp.firstMatch(in: t, range: NSRange(t.startIndex..., in: t)) != nil }

    static func parse(_ text: String) -> [LyricLine] {
        var offset = 0.0
        if let m = offsetTag.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)), let r = Range(m.range(at: 1), in: text) { offset = (Double(text[r]) ?? 0) / 1000 }
        var out: [LyricLine] = []
        for raw in text.components(separatedBy: .newlines) {
            let ns = raw as NSString
            let matches = stamp.matches(in: raw, range: NSRange(location: 0, length: ns.length))
            guard let last = matches.last else { continue }
            var body = ns.substring(from: last.range.location + last.range.length)
            body = word.stringByReplacingMatches(in: body, range: NSRange(location: 0, length: (body as NSString).length), withTemplate: "").trimmingCharacters(in: .whitespaces)
            for m in matches {
                let min = Double(ns.substring(with: m.range(at: 1))) ?? 0
                let sec = Double(ns.substring(with: m.range(at: 2))) ?? 0
                var frac = 0.0
                if m.range(at: 3).location != NSNotFound { let f = ns.substring(with: m.range(at: 3)); frac = (Double(f) ?? 0) / pow(10, Double(f.count)) }
                out.append(LyricLine(time: max(0, min * 60 + sec + frac - offset), text: body))
            }
        }
        return out.sorted { $0.time < $1.time }
    }

    static func plain(_ text: String) -> [LyricLine] {
        var lines = text.replacingOccurrences(of: "\r", with: "").components(separatedBy: "\n").map { LyricLine(time: -1, text: $0.trimmingCharacters(in: .whitespaces)) }
        while lines.first?.text.isEmpty == true { lines.removeFirst() }
        while lines.last?.text.isEmpty == true { lines.removeLast() }
        return lines
    }

    static func activeIndex(_ lines: [LyricLine], _ t: TimeInterval) -> Int {
        var lo = 0, hi = lines.count - 1, ans = -1
        while lo <= hi { let mid = (lo + hi) / 2; if lines[mid].time <= t { ans = mid; lo = mid + 1 } else { hi = mid - 1 } }
        return ans
    }
}

/// Embedded tags → matching .lrc next to the file (or in a "Lyrics" folder) → LRCLIB (on by default).
/// Misses are cached, except ones caused by being offline: those are retried when the connection returns.
@MainActor @Observable
final class LyricsService {
    enum State: Equatable { case loading, missing(searchedOnline: Bool), found(Lyrics) }
    private(set) var states: [String: State] = [:]
    var onlineEnabled = UserDefaults.standard.object(forKey: "lyricsOnline") as? Bool ?? true { didSet { UserDefaults.standard.set(onlineEnabled, forKey: "lyricsOnline") } }
    private var cache: [String: String] = Store.load([String: String].self, "lyricsCache") ?? [:]
    private var offsets: [String: Double] = Store.load([String: Double].self, "lyricsOffsets") ?? [:]
    private var offlineMisses: Set<String> = []
    private let monitor = NWPathMonitor()

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            Task { @MainActor in
                guard let self, !self.offlineMisses.isEmpty else { return }
                for id in self.offlineMisses { self.states[id] = nil }
                self.offlineMisses.removeAll()
            }
        }
        monitor.start(queue: .global(qos: .utility))
    }

    func request(_ song: Song, fileURL: URL?, force: Bool = false) {
        if !force, states[song.id] != nil { return }
        states[song.id] = .loading
        Task {
            if !force, let c = cache[song.id] {
                states[song.id] = c.isEmpty ? .missing(searchedOnline: false) : .found(make(c, source: "Saved", song))
                if !c.isEmpty || !onlineEnabled { return }
            }
            if let url = fileURL, song.kind == .file {
                if let t = await TagReader.lyrics(url), !t.isEmpty { return save(song, t, "Embedded in file") }
                if let t = sidecar(url), !t.isEmpty { return save(song, t, ".lrc file") }
            }
            if force || onlineEnabled {
                do {
                    if let t = try await lrclib(song) { return save(song, t, "LRCLIB") }
                } catch {
                    // No connection: don't remember a miss, look again once we're back online.
                    offlineMisses.insert(song.id)
                    states[song.id] = .missing(searchedOnline: false)
                    return
                }
            }
            cache[song.id] = ""
            Store.save(cache, "lyricsCache")
            states[song.id] = .missing(searchedOnline: force || onlineEnabled)
        }
    }

    func setOffset(_ song: Song, _ offset: Double) {
        offsets[song.id] = offset
        Store.save(offsets, "lyricsOffsets")
        if case .found(var l) = states[song.id] { l.offset = offset; states[song.id] = .found(l) }
    }

    private func save(_ s: Song, _ text: String, _ source: String) {
        cache[s.id] = text
        Store.save(cache, "lyricsCache")
        states[s.id] = .found(make(text, source: source, s))
    }

    private func make(_ text: String, source: String, _ s: Song) -> Lyrics {
        let synced = LRC.isSynced(text)
        return Lyrics(lines: synced ? LRC.parse(text) : LRC.plain(text), synced: synced, source: source, offset: offsets[s.id] ?? 0)
    }

    private func sidecar(_ url: URL) -> String? {
        let base = url.deletingPathExtension().lastPathComponent
        let candidates = [url.deletingPathExtension().appendingPathExtension("lrc"),
                          Store.documents.appendingPathComponent("Lyrics").appendingPathComponent("\(base).lrc")]
        for c in candidates { if let t = try? String(contentsOf: c, encoding: .utf8) { return t } }
        return nil
    }

    private func lrclib(_ s: Song) async throws -> String? {
        let secs = s.durationMs / 1000
        func pick(_ o: [String: Any]) -> String? {
            if o["instrumental"] as? Bool == true { return "[00:00.00]♪ Instrumental" }
            if let t = o["syncedLyrics"] as? String, !t.isEmpty { return t }
            if let t = o["plainLyrics"] as? String, !t.isEmpty { return t }
            return nil
        }
        if let o = try await HTTP.fetchJSON("https://lrclib.net/api/get?artist_name=\(HTTP.q(s.artist))&track_name=\(HTTP.q(s.title))&album_name=\(HTTP.q(s.album))&duration=\(secs)") as? [String: Any], let t = pick(o) { return t }
        guard let arr = try await HTTP.fetchJSON("https://lrclib.net/api/search?track_name=\(HTTP.q(s.title))&artist_name=\(HTTP.q(s.artist))") as? [[String: Any]] else { return nil }
        return arr.filter { abs(($0["duration"] as? Double ?? 0) - Double(secs)) <= 5 }
            .sorted { abs(($0["duration"] as? Double ?? 0) - Double(secs)) < abs(($1["duration"] as? Double ?? 0) - Double(secs)) }
            .lazy.compactMap(pick).first
    }
}
