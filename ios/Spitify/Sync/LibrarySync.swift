import Foundation
import CryptoKit

/// A JSON value with org.json's shapes, so documents round-trip with the Kotlin side unchanged.
enum SyncJSON: Hashable, Sendable, Codable {
    case null
    case bool(Bool)
    case int(Int64)
    case double(Double)
    case string(String)
    case array([SyncJSON])
    case object([String: SyncJSON])

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Int64.self) { self = .int(v) }
        else if let v = try? c.decode(Double.self) { self = v.rounded() == v && abs(v) < 9e15 ? .int(Int64(v)) : .double(v) }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode([SyncJSON].self) { self = .array(v) }
        else { self = .object(try c.decode([String: SyncJSON].self)) }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let v): try c.encode(v)
        case .int(let v): try c.encode(v)
        case .double(let v): try c.encode(v)
        case .string(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        case .object(let v): try c.encode(v)
        }
    }

    var object: [String: SyncJSON]? { if case .object(let o) = self { return o }; return nil }
    var array: [SyncJSON]? { if case .array(let a) = self { return a }; return nil }
    var string: String? { if case .string(let s) = self { return s }; return nil }
    var isNull: Bool { self == .null }
    /// org.json `getLong`: numbers (truncated) or numeric text.
    var long: Int64? {
        switch self {
        case .int(let v): return v
        case .double(let v): return v.isFinite ? Int64(max(-9.2e18, min(9.2e18, v))) : nil
        case .string(let s): return Int64(s) ?? Double(s).flatMap { $0.isFinite ? Int64(max(-9.2e18, min(9.2e18, $0))) : nil }
        default: return nil
        }
    }
    var double: Double? {
        switch self {
        case .int(let v): return Double(v)
        case .double(let v): return v
        case .string(let s): return Double(s)
        default: return nil
        }
    }
    /// org.json `getBoolean`: booleans or "true"/"false".
    var bool: Bool? {
        switch self {
        case .bool(let v): return v
        case .string(let s): return s.lowercased() == "true" ? true : s.lowercased() == "false" ? false : nil
        default: return nil
        }
    }

    static func parse(_ data: Data) -> SyncJSON? { try? JSONDecoder().decode(SyncJSON.self, from: data) }
    var data: Data { (try? JSONEncoder().encode(self)) ?? Data("null".utf8) }
}

typealias SyncObject = [String: SyncJSON]

extension Dictionary where Key == String, Value == SyncJSON {
    /// org.json `optString` that treats a missing value, "" and "null" alike, like Kotlin's `text`.
    func text(_ key: String) -> String? { self[key]?.string.flatMap { $0.isEmpty || $0 == "null" ? nil : $0 } }
}

/// Kotlin `String.compareTo`: UTF-16 code units, so every platform sorts the same way.
@inline(__always) func utf16Less(_ a: String, _ b: String) -> Bool { a.utf16.lexicographicallyPrecedes(b.utf16) }

struct Stamp: Hashable, Comparable, Sendable {
    var at: Int64
    var device: String
    static func < (l: Stamp, r: Stamp) -> Bool { l.at != r.at ? l.at < r.at : utf16Less(l.device, r.device) }
}

struct SyncItem: Hashable, Sendable {
    var key: String
    var present: Bool
    var value: SyncObject?
    var stamp: Stamp
    /// What counts as a change: everything but the song details, which differ between a file's tags and the catalogue.
    var meaning: String? { present ? LibrarySync.meaning(value) : nil }
    func json() -> SyncJSON {
        var o: SyncObject = ["k": .string(key), "p": .bool(present), "t": .int(stamp.at), "d": .string(stamp.device)]
        if present, let value { o["v"] = .object(value) }
        return .object(o)
    }
    static func parse(_ json: SyncJSON) -> SyncItem? {
        guard let o = json.object, let key = o["k"]?.string, let present = o["p"]?.bool, let at = o["t"]?.long, let device = o["d"]?.string else { return nil }
        return SyncItem(key: key, present: present, value: o["v"]?.object, stamp: Stamp(at: at, device: device))
    }
}

/// A remote change the platform should make locally. `value` is nil for removals.
struct SyncChange: Hashable, Sendable {
    var collection: String
    var key: String
    var present: Bool
    var value: SyncObject?
}

/// Keeps the library identical on all linked devices, like Spotify. The Swift port of
/// core/.../data/sync/LibrarySync.kt with identical behaviour and JSON; see docs/library-sync.md.
///
/// Every collection is a set of items, each with a `Stamp`; the newest stamp wins, removals are kept
/// as tombstones so they can't come back, and merging is order-independent. Platforms don't hook every
/// button: they `report` what a collection holds now and call `applied` for what they did with remote changes.
/// Not thread-safe: `LibrarySyncStore` uses it from one serial lane.
final class LibrarySync: @unchecked Sendable {
    let me: String
    private let clock: () -> Int64
    /// collection → key → winning item.
    private var items: [String: [String: SyncItem]] = [:]
    /// collection → key → value text the platform has locally, as last reported or applied.
    private var local: [String: [String: String]] = [:]
    private var lastStamp: Int64 = 0

    init(me: String, clock: @escaping () -> Int64 = { SocialRules.now }) { self.me = me; self.clock = clock }

    private func stamp() -> Stamp { lastStamp = max(clock(), lastStamp + 1); return Stamp(at: lastStamp, device: me) }

    /// What `collection` holds on this device now (key → value). New or changed items are stamped as
    /// ours; items that were here last time and are gone now become removals. Returns the names of the
    /// documents that changed (to send). A sudden loss of most of a big collection is treated as the
    /// collection not being loaded yet and ignored, unless `allowMassRemoval`.
    @discardableResult
    func report(_ collection: String, _ current: [String: SyncObject], allowMassRemoval: Bool = false) -> Set<String> {
        precondition(!Self.isGrowOnly(collection), "Use add() for \(collection)")
        var before = local[collection] ?? [:]
        let gone = Set(before.keys).subtracting(current.keys)
        if !allowMassRemoval && gone.count >= Self.massRemovalMin && gone.count * 2 > before.count { return [] }
        var coll = items[collection] ?? [:]
        var changed = Set<String>()
        for (key, value) in current {
            let text = Self.meaning(value)
            if before[key] == text { continue }
            let winner = coll[key]
            if winner == nil || !winner!.present || winner!.meaning != text {
                coll[key] = SyncItem(key: key, present: true, value: value, stamp: stamp()); changed.insert(Self.docName(collection, key))
            }
            before[key] = text
        }
        for key in gone {
            before.removeValue(forKey: key)
            if let winner = coll[key], winner.present { coll[key] = SyncItem(key: key, present: false, value: nil, stamp: stamp()); changed.insert(Self.docName(collection, key)) }
        }
        items[collection] = coll; local[collection] = before
        return changed
    }

    /// Adds to a grow-only collection (history): never removed except by age.
    @discardableResult
    func add(_ collection: String, _ key: String, _ value: SyncObject) -> Set<String> {
        precondition(Self.isGrowOnly(collection))
        if items[collection]?[key] != nil { return [] }
        items[collection, default: [:]][key] = SyncItem(key: key, present: true, value: value, stamp: stamp())
        local[collection, default: [:]][key] = Self.meaning(value)
        return [Self.docName(collection, key)]
    }

    /// Merges a document from another device. Returns the changes to make locally: items whose winner
    /// changed and differs from what this device has. Call `applied` for each one actually made.
    func receive(_ doc: SyncJSON) -> [SyncChange] {
        guard let o = doc.object, let collection = o["collection"]?.string, Self.validCollection(collection) else { return [] }
        var coll = items[collection] ?? [:]
        let have = local[collection] ?? [:]
        var out: [SyncChange] = []
        for raw in o["items"]?.array ?? [] {
            guard let incoming = SyncItem.parse(raw) else { continue }
            if incoming.key.utf16.count > 400 || Self.isGrowOnly(collection) && !incoming.present { continue }
            if let current = coll[incoming.key], current.stamp >= incoming.stamp { continue }
            coll[incoming.key] = incoming
            let localText = have[incoming.key]
            if incoming.present && localText != incoming.meaning { out.append(SyncChange(collection: collection, key: incoming.key, present: true, value: incoming.value)) }
            else if !incoming.present && localText != nil { out.append(SyncChange(collection: collection, key: incoming.key, present: false, value: nil)) }
        }
        items[collection] = coll
        return out
    }

    /// The platform made `change` locally (or found it already true).
    func applied(_ change: SyncChange) {
        if change.present { local[change.collection, default: [:]][change.key] = Self.meaning(change.value) }
        else { local[change.collection]?.removeValue(forKey: change.key) }
    }

    /// Remote items this device doesn't have yet (e.g. a song that couldn't be matched); retry later.
    func pending(_ collection: String) -> [SyncChange] {
        let have = local[collection] ?? [:]
        return (items[collection] ?? [:]).values.filter { $0.present && have[$0.key] != $0.meaning }
            .map { SyncChange(collection: collection, key: $0.key, present: true, value: $0.value) }
    }

    func present(_ collection: String) -> [String: SyncObject] {
        var out: [String: SyncObject] = [:]
        for item in (items[collection] ?? [:]).values where item.present { if let value = item.value { out[item.key] = value } }
        return out
    }

    /// The winning item for `key`, if any (Swift-only helper for platform code).
    func item(_ collection: String, _ key: String) -> SyncItem? { items[collection]?[key] }

    func collections() -> Set<String> { Set(items.keys) }

    /// Drops a whole collection (a deleted playlist's entries, a removed device's counts).
    func forget(_ collection: String) { items.removeValue(forKey: collection); local.removeValue(forKey: collection) }

    /// Grow-only collections older than `historyDays` are trimmed by age.
    func trim(now: Int64? = nil) {
        let cutoff = (now ?? clock()) - Int64(Self.historyDays) * 24 * 60 * 60_000
        for name in items.keys where Self.isGrowOnly(name) {
            let old = items[name]!.values.filter { ($0.value?["playedAt"]?.long ?? $0.stamp.at) < cutoff }.map(\.key)
            for key in old { items[name]?.removeValue(forKey: key); local[name]?.removeValue(forKey: key) }
        }
    }

    // MARK: Documents: what travels. One collection is split into a few shards so a change resends little.

    func docNames() -> Set<String> {
        var out = Set<String>()
        for (c, m) in items { for key in m.keys { out.insert(Self.docName(c, key)) } }
        return out
    }

    func doc(_ name: String) -> SyncJSON {
        let (collection, shard) = Self.splitDoc(name)
        let list = (items[collection] ?? [:]).values.filter { Self.shardOf(collection, $0.key) == shard }.sorted { utf16Less($0.key, $1.key) }
        return .object(["collection": .string(collection), "shard": .int(Int64(shard)), "items": .array(list.map { $0.json() })])
    }

    /// A short fingerprint per document, so devices only send what differs. Built from keys and stamps
    /// only (a stamp marks each version), so it's the same text on every platform.
    func digest() -> [String: String] {
        var lines: [String: [String]] = [:]
        for (c, m) in items {
            for item in m.values { lines[Self.docName(c, item.key), default: []].append("\(item.key)|\(item.present)|\(item.stamp.at)|\(item.stamp.device)") }
        }
        return lines.mapValues { String(SocialRules.hash(Data($0.sorted(by: utf16Less).joined(separator: "\n").utf8)).prefix(16)) }
    }

    // MARK: Saving

    func json() -> SyncJSON {
        var all: SyncObject = [:]
        for (c, m) in items { all[c] = .array(m.values.sorted { utf16Less($0.key, $1.key) }.map { $0.json() }) }
        var have: SyncObject = [:]
        for (c, m) in local { have[c] = .object(m.mapValues { .string($0) }) }
        return .object(["me": .string(me), "lastStamp": .int(lastStamp), "items": .object(all), "local": .object(have)])
    }

    func load(_ json: SyncJSON) {
        guard let o = json.object, (o["me"]?.string ?? "") == me else { return }
        items = [:]; local = [:]; lastStamp = o["lastStamp"]?.long ?? 0
        for (c, arr) in o["items"]?.object ?? [:] {
            var map: [String: SyncItem] = [:]
            for raw in arr.array ?? [] { if let item = SyncItem.parse(raw) { map[item.key] = item } }
            items[c] = map
        }
        for (c, m) in o["local"]?.object ?? [:] {
            var map: [String: String] = [:]
            for (k, v) in m.object ?? [:] { if let s = v.string { map[k] = s } }
            local[c] = map
        }
    }

    // MARK: Shared rules

    static let liked = "liked"
    static let savedTracks = "savedTracks"
    static let savedAlbums = "savedAlbums"
    static let followedArtists = "followedArtists"
    static let hiddenSongs = "hiddenSongs"
    static let hiddenArtists = "hiddenArtists"
    static let hiddenMixes = "hiddenMixes"
    static let podcasts = "podcasts"
    static let progress = "progress"
    static let playlists = "playlists"
    static let history = "history"
    static let profile = "profile"
    static let friends = "friends"
    static let savedShared = "savedShared"
    static let settings = "settings"
    /// One per playlist: "playlist:<global id>".
    static func playlist(_ id: String) -> String { "playlist:" + id }
    /// One per device, written only by that device: "stats:<device key>".
    static func stats(_ device: String) -> String { "stats:" + device }

    static let massRemovalMin = 20
    static let historyDays = 180

    private static let fixed: Set<String> = [liked, savedTracks, savedAlbums, followedArtists, hiddenSongs, hiddenArtists, hiddenMixes, podcasts, progress, playlists, history, profile, friends, savedShared, settings]

    /// Settings that follow you between devices. Everything else (EQ, folders, device name, window) stays per device.
    static let syncedSettings: Set<String> = [
        "themeMode", "accent", "accentFromArt", "font", "textScale", "artShape", "playerStyle", "artworkTint", "blurBackdrop", "reduceMotion",
        "showRecommendations", "hideShortTracks", "autoplay", "crossfadeMs", "crossfadeKeepAlbums", "normalizeAudio", "skipSilence",
        "speedMusic", "speedPodcast", "onlineLyrics", "onlineArt", "autoFixMetadata", "releaseNotifications",
    ]
    static func validCollection(_ name: String) -> Bool {
        fixed.contains(name) || name.hasPrefix("playlist:") && (10...80).contains(name.utf16.count) ||
            name.hasPrefix("stats:") && SocialRules.key(String(name.dropFirst(6)))
    }
    static func isGrowOnly(_ name: String) -> Bool { name == history }

    private static func shards(_ collection: String) -> Int {
        if collection == history { return 16 }
        if collection == liked || collection == progress || collection.hasPrefix("stats:") { return 8 }
        if collection == savedTracks || collection.hasPrefix("playlist:") { return 4 }
        if collection == playlists { return 8 } // covers ride along: up to 24 KB each
        if collection == savedAlbums || collection == podcasts { return 2 }
        return 1
    }
    static func shardOf(_ collection: String, _ key: String) -> Int {
        let n = shards(collection); if n == 1 { return 0 }
        return Int(SocialRules.hash(Data(key.utf8)).prefix(4), radix: 16)! % n
    }
    static func docName(_ collection: String, _ key: String) -> String { "\(collection)#\(shardOf(collection, key))" }
    /// Kotlin `substringBeforeLast('#')` / `substringAfterLast('#')`.
    static func splitDoc(_ name: String) -> (String, Int) {
        guard let hash = name.lastIndex(of: "#") else { return (name, Int(name) ?? 0) }
        return (String(name[..<hash]), Int(name[name.index(after: hash)...]) ?? 0)
    }

    private static let featuring = try! NSRegularExpression(pattern: "\\s*[(\\[](feat\\.?|ft\\.?|featuring|with)\\s[^)\\]]*[)\\]]", options: [.caseInsensitive])
    private static let credits = try! NSRegularExpression(pattern: "\\s*(,|&|;|/|\\bfeat\\.?|\\bft\\.?|\\bfeaturing\\b|\\bwith\\b|\\bx\\b|\\band\\b|\\bvs\\.?)\\s*", options: [.caseInsensitive])
    private static let nonWord = try! NSRegularExpression(pattern: "[^\\p{L}\\p{N}]+")

    /// Kotlin `SearchMatch.fold`: NFD, drop combining marks, lowercase, anything but letters and digits → one space, trim.
    static func fold(_ text: String) -> String {
        var scalars = String.UnicodeScalarView()
        for u in text.decomposedStringWithCanonicalMapping.unicodeScalars where u.properties.generalCategory != .nonspacingMark { scalars.append(u) }
        let lower = String(scalars).lowercased()
        let spaced = nonWord.stringByReplacingMatches(in: lower, range: NSRange(lower.startIndex..., in: lower), withTemplate: " ")
        return spaced.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// What a song *is*, the same on every device whether it's a local file, a download or a stream:
    /// folded title without "(feat. …)", and the folded main artist. Title tags like "- Remastered"
    /// stay, so different versions stay different.
    static func trackKey(_ title: String, _ artist: String) -> String {
        let base = featuring.stringByReplacingMatches(in: title, range: NSRange(title.startIndex..., in: title), withTemplate: "")
        // The first credited name, cut the same way everywhere: tags say "Band, Guest" or "Band & Guest"
        // where the catalogue says "Band". Bands with "&" or "," in their name are cut alike on every device.
        let ns = artist as NSString
        var pieces: [String] = []; var start = 0
        for match in credits.matches(in: artist, range: NSRange(location: 0, length: ns.length)) {
            pieces.append(ns.substring(with: NSRange(location: start, length: match.range.location - start)))
            start = match.range.location + match.range.length
        }
        pieces.append(ns.substring(from: start))
        let first = pieces.first { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } ?? artist
        return fold(base) + "|" + fold(first)
    }
    static func trackKey(_ track: SharedTrack) -> String { trackKey(track.title, track.artist) }

    /// Progress keys, the same on every platform: "e:<feedUrl>#<guid>" for episodes, "t:" + trackKey for
    /// local audiobook/podcast files and long tracks. Keys over 380 characters keep their first 300, then
    /// "~" and the first 32 hex characters of the whole key's SHA-256.
    static func episodeProgressKey(feed: String, guid: String) -> String { shortKey("e:" + feed + "#" + guid) }
    static func trackProgressKey(_ title: String, _ artist: String) -> String { shortKey("t:" + trackKey(title, artist)) }
    static func shortKey(_ key: String) -> String {
        guard key.utf16.count > 380 else { return key }
        return String(decoding: Array(key.utf16.prefix(300)), as: UTF16.self) + "~" + String(SocialRules.hash(Data(key.utf8)).prefix(32))
    }

    static func trackJSON(_ track: SharedTrack) -> SyncJSON {
        var o: SyncObject = ["id": .string(track.id), "title": .string(track.title), "artist": .string(track.artist), "album": .string(track.album), "durationMs": .int(track.durationMs)]
        if let v = track.sourceID { o["sourceID"] = .string(v) }
        if let v = track.releaseID { o["releaseID"] = .string(v) }
        if let v = track.spotifyID { o["spotifyID"] = .string(v) }
        if let v = track.isrc { o["isrc"] = .string(v) }
        if let v = track.artwork { o["artwork"] = .string(v) }
        return .object(o)
    }
    /// The standard value for a song item: {"track": SharedTrack}.
    static func trackValue(_ track: SharedTrack) -> SyncObject {
        var keyed = track; keyed.id = trackKey(track)
        return ["track": trackJSON(keyed)]
    }
    static func track(_ value: SyncObject?) -> SharedTrack? {
        guard let o = value?["track"]?.object, let id = o["id"]?.string, let title = o["title"]?.string, let artist = o["artist"]?.string else { return nil }
        let track = SharedTrack(id: id, title: title, artist: artist, album: o["album"]?.string ?? "", durationMs: o["durationMs"]?.long ?? 0,
                                sourceID: o.text("sourceID"), releaseID: o.text("releaseID"), spotifyID: o.text("spotifyID"), isrc: o.text("isrc"), artwork: o.text("artwork"))
        return track.valid() ? track : nil
    }

    /// Entry keys inside a playlist: the song's key plus which occurrence it is ("…#2" for the second
    /// time the same song appears), so devices agree without sharing database ids. Value: {"track", "pos"}.
    static func entryKeys(_ tracks: [SharedTrack]) -> [String] {
        var seen: [String: Int] = [:]
        return tracks.map { t in let k = trackKey(t); let n = (seen[k] ?? 0) + 1; seen[k] = n; return "\(k)#\(n)" }
    }
    static func entryValue(_ track: SharedTrack, pos: Int) -> SyncObject { var v = trackValue(track); v["pos"] = .int(Int64(pos)); return v }

    /// Canonical text of a value without "track" and "_…" (informational) fields: sorted keys, so it's
    /// identical on every platform and every run. Null-valued keys are dropped.
    static func meaning(_ value: SyncObject?) -> String {
        guard let value else { return "{}" }
        return canon(.object(value.filter { $0.key != "track" && !$0.key.hasPrefix("_") }))
    }

    private static func canon(_ v: SyncJSON) -> String {
        switch v {
        case .null: return "null"
        case .object(let o): return "{" + o.filter { !$0.value.isNull }.keys.sorted(by: utf16Less).map { quote($0) + ":" + canon(o[$0]!) }.joined(separator: ",") + "}"
        case .array(let a): return "[" + a.map(canon).joined(separator: ",") + "]"
        case .string(let s): return quote(s)
        case .int(let i): return String(i)
        case .double(let d):
            if d.isFinite && d == d.rounded(.down) { return String(Int64(max(-9.2e18, min(9.2e18, d)))) }
            return javaDouble(d)
        case .bool(let b): return b ? "true" : "false"
        }
    }

    /// org.json `JSONObject.quote` as shipped on Android: `"`, `\` and `/` are escaped, control characters as \b \t \n \f \r or \u00XX.
    static func quote(_ s: String) -> String {
        var out = "\""
        for u in s.unicodeScalars {
            switch u {
            case "\"", "\\", "/": out += "\\"; out.unicodeScalars.append(u)
            case "\t": out += "\\t"
            case "\u{8}": out += "\\b"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\u{C}": out += "\\f"
            default:
                if u.value <= 0x1F { out += String(format: "\\u%04x", u.value) } else { out.unicodeScalars.append(u) }
            }
        }
        return out + "\""
    }

    /// Java `Double.toString` for a non-whole number: plain between 10⁻³ and 10⁷, otherwise "1.5E-5".
    private static func javaDouble(_ d: Double) -> String {
        if d.isNaN { return "NaN" }
        if d.isInfinite { return d > 0 ? "Infinity" : "-Infinity" }
        let magnitude = abs(d)
        if magnitude >= 1e-3 && magnitude < 1e7 { return "\(d)" }
        // Shortest round-trip digits from Swift, re-laid out as d.dddE±n.
        let text = "\(magnitude)"
        var digits: String, exponent: Int
        if let e = text.firstIndex(where: { $0 == "e" || $0 == "E" }) {
            let mantissa = String(text[..<e]); exponent = Int(text[text.index(after: e)...].replacingOccurrences(of: "+", with: "")) ?? 0
            let dot = mantissa.firstIndex(of: ".").map { mantissa.distance(from: mantissa.startIndex, to: $0) } ?? mantissa.count
            digits = mantissa.replacingOccurrences(of: ".", with: ""); exponent += dot - 1
        } else {
            let dot = text.firstIndex(of: ".").map { text.distance(from: text.startIndex, to: $0) } ?? text.count
            digits = text.replacingOccurrences(of: ".", with: ""); exponent = dot - 1
        }
        while digits.count > 1 && digits.hasPrefix("0") { digits.removeFirst(); exponent -= 1 }
        while digits.count > 1 && digits.hasSuffix("0") { digits.removeLast() }
        let rest = digits.count > 1 ? String(digits.dropFirst()) : "0"
        return (d < 0 ? "-" : "") + String(digits.prefix(1)) + "." + rest + "E" + String(exponent)
    }
}
