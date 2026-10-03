import Foundation
import Observation

struct Show: Identifiable, Codable, Hashable {
    enum Kind: String, Codable { case podcast, audiobook }
    var id: String
    var feedURL: String
    var title: String
    var author: String
    var summary: String
    var artworkURL: String?
    var kind: Kind
    var subscribedAt: Date
    var episodes: [Episode]
    var following: Bool { subscribedAt.timeIntervalSince1970 > 0 }
}

struct Episode: Identifiable, Codable, Hashable {
    var id: String
    var title: String
    var summary: String
    var audioURL: String
    var published: Date?
    var durationMs: Int64
    var artworkURL: String?
    var position: Int
    var localFile: String?
    var explicit: Bool? = nil
}

struct Resume: Codable, Hashable { var positionMs: Int64; var durationMs: Int64; var played: Bool; var updated: Date }

struct ShowSearchResult: Identifiable, Hashable { var id: String { feedURL }; var title, author, feedURL: String; var artworkURL: String?; var genre: String?; var explicit: Bool? = nil }
struct BookSearchResult: Identifiable, Hashable { var id: String; var title, author, summary: String; var seconds: Int64; var coverURL: String?; var language: String }

/// RSS 2.0 + iTunes namespace.
final class FeedParser: NSObject, XMLParserDelegate {
    struct Feed { var title = "", author = "", summary = ""; var art: String?; var episodes: [Episode] = [] }
    private var feed = Feed()
    private var text = ""
    private var inItem = false, inImage = false
    private var rootIsFeed = false, sawRoot = false
    private var cur: [String: String] = [:]

    static func parse(_ data: Data) -> Feed? {
        let p = XMLParser(data: data); let d = FeedParser(); p.delegate = d
        let parsed = p.parse()
        return d.rootIsFeed && (parsed || !d.feed.episodes.isEmpty) ? d.feed : nil
    }

    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName q: String?, attributes a: [String: String] = [:]) {
        if !sawRoot { sawRoot = true; rootIsFeed = ["rss", "feed", "rdf:RDF"].contains(name) }
        text = ""
        switch name {
        case "item", "entry": inItem = true; cur = [:]
        case "image": inImage = true
        case "itunes:image": if let h = a["href"] { if inItem { cur["art"] = h } else if feed.art == nil { feed.art = h } }
        case "enclosure": if inItem { cur["url"] = a["url"]; cur["type"] = a["type"] }
        default: break
        }
    }
    func parser(_ parser: XMLParser, foundCharacters s: String) { text += s }
    func parser(_ parser: XMLParser, foundCDATA d: Data) { text += String(decoding: d, as: UTF8.self) }
    func parser(_ parser: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName q: String?) {
        let v = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if inItem {
            switch name {
            case "title": if cur["title"] == nil { cur["title"] = v }
            case "guid": cur["guid"] = v
            case "pubDate": cur["date"] = v
            case "itunes:duration": cur["dur"] = v
            case "itunes:explicit": cur["explicit"] = v.lowercased()
            case "description", "itunes:summary": if cur["desc"] == nil { cur["desc"] = v }
            case "content:encoded": if v.count > (cur["desc"]?.count ?? 0) { cur["desc"] = v }
            case "item", "entry":
                inItem = false
                if let url = cur["url"], feed.episodes.count < 300 {
                    feed.episodes.append(Episode(id: stableId(cur["guid"] ?? url), title: cur["title"] ?? "Untitled episode", summary: FeedParser.clean(cur["desc"] ?? ""),
                                                 audioURL: url, published: FeedParser.date(cur["date"]), durationMs: FeedParser.duration(cur["dur"] ?? ""),
                                                 artworkURL: cur["art"], position: feed.episodes.count, explicit: cur["explicit"].map { ["yes", "true", "explicit"].contains($0) }))
                }
            default: break
            }
        } else {
            switch name {
            case "title": if !inImage && feed.title.isEmpty { feed.title = v }
            case "itunes:author": if feed.author.isEmpty { feed.author = v }
            case "description", "itunes:summary": if feed.summary.isEmpty { feed.summary = FeedParser.clean(v) }
            case "url": if inImage && feed.art == nil { feed.art = v }
            case "image": inImage = false
            default: break
            }
        }
    }

    static func clean(_ html: String) -> String {
        html.replacingOccurrences(of: "<br\\s*/?>|</p>", with: "\n", options: .regularExpression)
            .replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
            .replacingOccurrences(of: "&amp;", with: "&").replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&nbsp;", with: " ").replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "\n{3,}", with: "\n\n", options: .regularExpression).trimmingCharacters(in: .whitespacesAndNewlines)
    }
    static func duration(_ s: String) -> Int64 {
        let parts = s.split(separator: ":").map { Double($0.trimmingCharacters(in: .whitespaces)) }
        guard !parts.contains(where: { $0 == nil }) else { return 0 }
        return Int64(parts.reduce(0.0) { $0 * 60 + $1! } * 1000)
    }
    static func date(_ s: String?) -> Date? {
        guard let s else { return nil }
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX")
        for fmt in ["EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, d MMM yyyy HH:mm:ss Z", "yyyy-MM-dd'T'HH:mm:ssXXXXX"] {
            f.dateFormat = fmt; if let d = f.date(from: s) { return d }
        }
        return nil
    }
}

/// Podcasts (Apple directory + any RSS) and LibriVox audiobooks (via the Internet Archive).
@MainActor @Observable
final class ShowsStore {
    private let persist: Bool
    var shows: [Show] = [] { didSet { if persist { Store.save(shows, "shows") } } }
    var resume: [String: Resume] = [:] { didSet { if persist { Store.save(resume, "resume") } } }

    init(persist: Bool = true) {
        self.persist = persist
        if persist {
            shows = Store.load([Show].self, "shows") ?? []
            resume = Store.load([String: Resume].self, "resume") ?? [:]
        }
    }
    private(set) var downloads: [String: Double] = [:]
    private(set) var refreshing = false
    static let downloadDir: URL = { let d = Store.directory.appendingPathComponent("Downloads"); try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true); return d }()

    var podcasts: [Show] { shows.filter { $0.kind == .podcast && $0.following } }
    var books: [Show] { shows.filter { $0.kind == .audiobook } }

    func song(_ e: Episode, in show: Show) -> Song {
        var s = Song(id: "ep\(show.id)\(e.id)", title: e.title, artist: show.title, album: show.title, albumArtist: show.author,
                     durationMs: e.durationMs, track: e.position + 1, disc: 1, year: 0, genre: show.kind == .podcast ? "Podcast" : "Audiobook",
                     location: e.localFile.map { Self.downloadDir.appendingPathComponent($0).absoluteString } ?? e.audioURL,
                     kind: .remote, dateAdded: e.published ?? show.subscribedAt, sizeBytes: 0, fileExtension: "mp3")
        s.artURL = e.artworkURL ?? show.artworkURL
        s.isPodcast = true
        s.isAudiobook = show.kind == .audiobook
        s.episodeId = "\(show.id)/\(e.id)"
        s.explicit = e.explicit
        return s
    }
    func songs(_ show: Show) -> [Song] { show.episodes.sorted { show.kind == .audiobook ? $0.position < $1.position : ($0.published ?? .distantPast) > ($1.published ?? .distantPast) }.map { song($0, in: show) } }
    var allEpisodeSongs: [String: Song] { Dictionary(shows.flatMap(songs).map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a }) }

    // MARK: Discovery

    func searchPodcasts(_ term: String, fetch: (String) async throws -> Any? = HTTP.fetchJSON) async throws -> [ShowSearchResult] {
        guard let root = try await fetch("https://itunes.apple.com/search?media=podcast&entity=podcast&limit=30&term=\(HTTP.q(term))") as? [String: Any],
              let res = root["results"] as? [[String: Any]] else { throw URLError(.badServerResponse) }
        return res.compactMap { o in
            guard let feed = o["feedUrl"] as? String else { return nil }
            return ShowSearchResult(title: o["collectionName"] as? String ?? "", author: o["artistName"] as? String ?? "", feedURL: feed,
                                    artworkURL: (o["artworkUrl600"] as? String) ?? (o["artworkUrl100"] as? String), genre: o["primaryGenreName"] as? String, explicit: (o["collectionExplicitness"] as? String).map { $0 == "explicit" })
        }
    }

    func searchBooks(_ term: String, fetch: (String) async throws -> Any? = HTTP.fetchJSON) async throws -> [BookSearchResult] {
        let q = HTTP.q("collection:librivoxaudio AND (\(term))")
        let fields = ["identifier", "title", "creator", "description", "runtime", "language"].map { "&fl%5B%5D=\($0)" }.joined()
        guard let root = try await fetch("https://archive.org/advancedsearch.php?q=\(q)\(fields)&sort%5B%5D=downloads+desc&rows=30&output=json") as? [String: Any],
              let docs = (root["response"] as? [String: Any])?["docs"] as? [[String: Any]] else { throw URLError(.badServerResponse) }
        func str(_ v: Any?) -> String { (v as? String) ?? (v as? [String])?.joined(separator: ", ") ?? "" }
        return docs.map { d in
            let id = str(d["identifier"])
            return BookSearchResult(id: id, title: str(d["title"]).replacingOccurrences(of: " (LibriVox)", with: ""), author: str(d["creator"]).isEmpty ? "Various" : str(d["creator"]),
                                    summary: FeedParser.clean(str(d["description"])), seconds: FeedParser.duration(str(d["runtime"])) / 1000,
                                    coverURL: "https://archive.org/services/img/\(id)", language: str(d["language"]) == "eng" ? "English" : str(d["language"]))
        }
    }

    // MARK: Subscribe

    @discardableResult
    func subscribe(feedURL: String, art: String? = nil, follow: Bool = true) async -> Show? {
        if let i = shows.firstIndex(where: { $0.feedURL == feedURL }) {
            if follow && !shows[i].following { shows[i].subscribedAt = Date() }
            return shows[i]
        }
        guard let data = await HTTP.get(feedURL), let feed = FeedParser.parse(data) else { return nil }
        // Another tap can finish loading this feed while the request is in flight.
        if let i = shows.firstIndex(where: { $0.feedURL == feedURL }) {
            if follow && !shows[i].following { shows[i].subscribedAt = Date() }
            return shows[i]
        }
        let show = Show(id: stableId(feedURL), feedURL: feedURL, title: feed.title, author: feed.author, summary: feed.summary,
                        artworkURL: feed.art ?? art, kind: .podcast, subscribedAt: follow ? Date() : Date(timeIntervalSince1970: 0), episodes: feed.episodes)
        shows.insert(show, at: 0)
        return show
    }

    @discardableResult
    func addBook(_ r: BookSearchResult) async -> Show? {
        let feedURL = "archive:\(r.id)"
        if let s = shows.first(where: { $0.feedURL == feedURL }) { return s }
        guard let root = await HTTP.json("https://archive.org/metadata/\(r.id)") as? [String: Any], let files = root["files"] as? [[String: Any]] else { return nil }
        var mp3s = files.filter { ($0["name"] as? String ?? "").hasSuffix("_64kb.mp3") }
        if mp3s.isEmpty { mp3s = files.filter { ($0["name"] as? String ?? "").lowercased().hasSuffix(".mp3") && ($0["source"] as? String) == "original" } }
        mp3s.sort { (Int(($0["track"] as? String ?? "").split(separator: "/").first ?? "") ?? .max, $0["name"] as? String ?? "") < (Int(($1["track"] as? String ?? "").split(separator: "/").first ?? "") ?? .max, $1["name"] as? String ?? "") }
        let episodes = mp3s.enumerated().map { i, f -> Episode in
            let name = f["name"] as? String ?? ""
            let enc = name.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? name
            return Episode(id: stableId(name), title: (f["title"] as? String) ?? (name as NSString).deletingPathExtension.replacingOccurrences(of: "_", with: " "),
                           summary: "", audioURL: "https://archive.org/download/\(r.id)/\(enc)", published: nil,
                           durationMs: FeedParser.duration(f["length"] as? String ?? ""), artworkURL: nil, position: i)
        }
        guard !episodes.isEmpty else { return nil }
        let show = Show(id: stableId(feedURL), feedURL: feedURL, title: r.title, author: r.author, summary: r.summary, artworkURL: r.coverURL, kind: .audiobook, subscribedAt: Date(), episodes: episodes)
        shows.insert(show, at: 0)
        return show
    }

    func setFollowing(_ show: Show, _ follow: Bool) {
        guard let i = shows.firstIndex(where: { $0.id == show.id }) else { return }
        shows[i].subscribedAt = follow ? Date() : Date(timeIntervalSince1970: 0)
    }

    func remove(_ show: Show) {
        for e in show.episodes { if let f = e.localFile { try? FileManager.default.removeItem(at: Self.downloadDir.appendingPathComponent(f)) } }
        shows.removeAll { $0.id == show.id }
    }

    func refreshAll(fetch: (String) async -> Data? = HTTP.get) async {
        guard !refreshing else { return }
        refreshing = true
        defer { refreshing = false }
        for show in shows where show.kind == .podcast && show.following {
            guard let data = await fetch(show.feedURL), let feed = FeedParser.parse(data),
                  let i = shows.firstIndex(where: { $0.id == show.id && $0.following }) else { continue }
            let known = Set(shows[i].episodes.map(\.id))
            let fresh = feed.episodes.filter { !known.contains($0.id) }
            if !fresh.isEmpty { shows[i].episodes = fresh + shows[i].episodes }
        }
    }

    // MARK: Downloads

    func download(_ e: Episode, in show: Show) {
        guard let url = URL(string: e.audioURL), downloads[e.id] == nil else { return }
        downloads[e.id] = 0
        Task {
            let name = "\(show.id)_\(e.id).\(url.pathExtension.isEmpty ? "mp3" : url.pathExtension)"
            do {
                let (tmp, _) = try await URLSession.shared.download(from: url)
                let dest = Self.downloadDir.appendingPathComponent(name)
                try? FileManager.default.removeItem(at: dest)
                try FileManager.default.moveItem(at: tmp, to: dest)
                if let si = shows.firstIndex(where: { $0.id == show.id }), let ei = shows[si].episodes.firstIndex(where: { $0.id == e.id }) { shows[si].episodes[ei].localFile = name }
            } catch { print("Download failed: \(error)") }
            downloads[e.id] = nil
        }
    }

    func deleteDownload(_ e: Episode, in show: Show) {
        guard let si = shows.firstIndex(where: { $0.id == show.id }), let ei = shows[si].episodes.firstIndex(where: { $0.id == e.id }), let f = shows[si].episodes[ei].localFile else { return }
        try? FileManager.default.removeItem(at: Self.downloadDir.appendingPathComponent(f))
        shows[si].episodes[ei].localFile = nil
    }

    // MARK: Resume

    func saveProgress(_ key: String, _ pos: Int64, _ dur: Int64) {
        guard dur > 0 else { return }
        let played = pos >= dur - 30_000
        resume[key] = Resume(positionMs: played ? 0 : pos, durationMs: dur, played: played, updated: Date())
    }
    func setPlayed(_ key: String, _ played: Bool, _ dur: Int64) { resume[key] = Resume(positionMs: 0, durationMs: dur, played: played, updated: Date()) }
    func resumePosition(_ key: String) -> Int64 { resume[key].flatMap { $0.played ? nil : $0.positionMs } ?? 0 }
}
