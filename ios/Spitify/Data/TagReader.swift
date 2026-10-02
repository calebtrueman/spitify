import AVFoundation
import Foundation

struct Tags {
    var title: String?
    var artist: String?
    var album: String?
    var albumArtist: String?
    var genre: String?
    var year: Int?
    var track: Int?
    var disc: Int?
    var durationMs: Int64 = 0
    var artwork: Data?
    var lyrics: String?
}

/// Reads tags with AVFoundation, plus a native FLAC Vorbis-comment/picture parser and an ID3
/// lyrics reader (USLT / SYLT / TXXX:LYRICS) for what AVFoundation doesn't surface.
enum TagReader {
    static func read(_ url: URL) async -> Tags {
        var t = Tags()
        let asset = AVURLAsset(url: url)
        if let d = try? await asset.load(.duration), d.isNumeric { t.durationMs = Int64(d.seconds * 1000) }
        var items: [AVMetadataItem] = []
        if let common = try? await asset.load(.commonMetadata) { items += common }
        if let all = try? await asset.load(.metadata) { items += all }
        for item in items {
            let id = item.identifier
            let s = try? await item.load(.stringValue)
            switch id {
            case .commonIdentifierTitle?, .id3MetadataTitleDescription?, .iTunesMetadataSongName?, .quickTimeMetadataTitle?: t.title = t.title ?? clean(s)
            case .commonIdentifierArtist?, .id3MetadataLeadPerformer?, .iTunesMetadataArtist?, .quickTimeMetadataArtist?: t.artist = t.artist ?? clean(s)
            case .commonIdentifierAlbumName?, .id3MetadataAlbumTitle?, .iTunesMetadataAlbum?, .quickTimeMetadataAlbum?: t.album = t.album ?? clean(s)
            case .id3MetadataBand?, .iTunesMetadataAlbumArtist?: t.albumArtist = t.albumArtist ?? clean(s)
            case .id3MetadataContentType?, .iTunesMetadataUserGenre?, .quickTimeMetadataGenre?: t.genre = t.genre ?? cleanGenre(s)
            case .id3MetadataYear?, .id3MetadataRecordingTime?, .iTunesMetadataReleaseDate?, .commonIdentifierCreationDate?, .quickTimeMetadataYear?:
                t.year = t.year ?? s.flatMap { Int($0.prefix(4)) }
            case .id3MetadataTrackNumber?: t.track = t.track ?? s.flatMap { Int($0.split(separator: "/").first ?? "") }
            case .id3MetadataPartOfASet?: t.disc = t.disc ?? s.flatMap { Int($0.split(separator: "/").first ?? "") }
            case .iTunesMetadataTrackNumber?, .iTunesMetadataDiscNumber?:
                if let d = try? await item.load(.dataValue), d.count >= 4 {
                    let n = Int(d[2]) << 8 | Int(d[3])
                    if id == .iTunesMetadataTrackNumber { t.track = t.track ?? n } else { t.disc = t.disc ?? n }
                }
            case .commonIdentifierArtwork?, .id3MetadataAttachedPicture?, .iTunesMetadataCoverArt?:
                if t.artwork == nil { t.artwork = try? await item.load(.dataValue) }
            case .iTunesMetadataLyrics?: t.lyrics = t.lyrics ?? s
            default: break
            }
        }
        // FLAC: AVFoundation exposes little; parse Vorbis comments + picture ourselves.
        if url.pathExtension.lowercased() == "flac", let f = readFlac(url) {
            t.title = t.title ?? f["TITLE"]; t.artist = t.artist ?? f["ARTIST"]; t.album = t.album ?? f["ALBUM"]
            t.albumArtist = t.albumArtist ?? f["ALBUMARTIST"] ?? f["ALBUM ARTIST"]; t.genre = t.genre ?? f["GENRE"]
            t.year = t.year ?? f["DATE"].flatMap { Int($0.prefix(4)) }
            t.track = t.track ?? f["TRACKNUMBER"].flatMap { Int($0.split(separator: "/").first ?? "") }
            t.disc = t.disc ?? f["DISCNUMBER"].flatMap { Int($0.split(separator: "/").first ?? "") }
            t.lyrics = t.lyrics ?? f["SYNCEDLYRICS"] ?? f["LYRICS"] ?? f["UNSYNCEDLYRICS"]
            if t.artwork == nil { t.artwork = flacPicture(url) }
        }
        return t
    }

    /// Lyrics only (called lazily by the lyrics view).
    static func lyrics(_ url: URL) async -> String? {
        switch url.pathExtension.lowercased() {
        case "mp3": return id3Lyrics(url)
        case "flac": let f = readFlac(url); return f?["SYNCEDLYRICS"] ?? f?["LYRICS"] ?? f?["UNSYNCEDLYRICS"]
        default: return await read(url).lyrics
        }
    }

    private static func clean(_ s: String?) -> String? {
        guard let v = s?.trimmingCharacters(in: .whitespacesAndNewlines), !v.isEmpty, v.lowercased() != "<unknown>" else { return nil }
        return v
    }

    private static let id3Genres = ["Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal", "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial", "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House"]

    private static func cleanGenre(_ s: String?) -> String? {
        guard var g = clean(s) else { return nil }
        if g.hasPrefix("("), let close = g.firstIndex(of: ")"), let n = Int(g[g.index(after: g.startIndex)..<close]) {
            g = n < id3Genres.count ? id3Genres[n] : String(g[g.index(after: close)...])
        } else if let n = Int(g), n < id3Genres.count { g = id3Genres[n] }
        return g.isEmpty ? nil : g
    }

    // MARK: - FLAC

    private static func readFlac(_ url: URL) -> [String: String]? {
        guard let h = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? h.close() }
        guard let magic = try? h.read(upToCount: 4), magic == Data("fLaC".utf8) else { return nil }
        while let head = try? h.read(upToCount: 4), head.count == 4 {
            let last = head[0] & 0x80 != 0, type = head[0] & 0x7F
            let len = Int(head[1]) << 16 | Int(head[2]) << 8 | Int(head[3])
            if type == 4, let block = try? h.read(upToCount: len) { return vorbis(block) }
            if last { return nil }
            try? h.seek(toOffset: h.offsetInFile + UInt64(len))
        }
        return nil
    }

    private static func flacPicture(_ url: URL) -> Data? {
        guard let h = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? h.close() }
        _ = try? h.read(upToCount: 4)
        while let head = try? h.read(upToCount: 4), head.count == 4 {
            let last = head[0] & 0x80 != 0, type = head[0] & 0x7F
            let len = Int(head[1]) << 16 | Int(head[2]) << 8 | Int(head[3])
            if type == 6, let b = try? h.read(upToCount: len) {
                var p = 4
                func u32() -> Int { defer { p += 4 }; return p + 4 <= b.count ? Int(b[p]) << 24 | Int(b[p + 1]) << 16 | Int(b[p + 2]) << 8 | Int(b[p + 3]) : 0 }
                p += u32(); p += u32(); p += 16
                let n = u32()
                guard p + n <= b.count else { return nil }
                return b.subdata(in: p..<(p + n))
            }
            if last { return nil }
            try? h.seek(toOffset: h.offsetInFile + UInt64(len))
        }
        return nil
    }

    private static func vorbis(_ b: Data) -> [String: String] {
        var p = 0, out: [String: String] = [:]
        func u32() -> Int { defer { p += 4 }; return p + 4 <= b.count ? Int(b[p]) | Int(b[p + 1]) << 8 | Int(b[p + 2]) << 16 | Int(b[p + 3]) << 24 : 0 }
        p += u32()
        let count = u32()
        for _ in 0..<count {
            let len = u32()
            guard p + len <= b.count else { break }
            let entry = String(decoding: b.subdata(in: p..<(p + len)), as: UTF8.self)
            p += len
            if let eq = entry.firstIndex(of: "=") {
                let k = entry[..<eq].uppercased()
                if out[k] == nil { out[k] = String(entry[entry.index(after: eq)...]) }
            }
        }
        return out
    }

    // MARK: - ID3 lyrics

    private static func id3Lyrics(_ url: URL) -> String? {
        guard let h = try? FileHandle(forReadingFrom: url), let head = try? h.read(upToCount: 10), head.count == 10,
              head.starts(with: Data("ID3".utf8)), (3...4).contains(head[3]) else { return nil }
        defer { try? h.close() }
        let major = head[3]
        func synch(_ d: Data, _ o: Int) -> Int { Int(d[o] & 0x7F) << 21 | Int(d[o + 1] & 0x7F) << 14 | Int(d[o + 2] & 0x7F) << 7 | Int(d[o + 3] & 0x7F) }
        func be(_ d: Data, _ o: Int) -> Int { Int(d[o]) << 24 | Int(d[o + 1]) << 16 | Int(d[o + 2]) << 8 | Int(d[o + 3]) }
        let size = synch(head, 6)
        guard let tag = try? h.read(upToCount: size), tag.count == size else { return nil }
        var p = 0, plain: String?
        while p + 10 <= tag.count {
            if tag[p] == 0 { break }
            let id = String(decoding: tag.subdata(in: p..<(p + 4)), as: UTF8.self)
            let len = major == 4 ? synch(tag, p + 4) : be(tag, p + 4)
            guard len > 0, p + 10 + len <= tag.count else { break }
            let body = tag.subdata(in: (p + 10)..<(p + 10 + len))
            switch id {
            case "SYLT": if let s = sylt(body) { return s }
            case "USLT": if plain == nil { plain = textAfterTerminators(body, skipLanguage: true, descriptors: 1) }
            case "TXXX":
                if plain == nil, let all = textFields(body), all.count >= 2, ["LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS", "USLT"].contains(all[0].uppercased()) { plain = all[1] }
            default: break
            }
            p += 10 + len
        }
        return plain
    }

    private static func encoding(_ e: UInt8) -> String.Encoding {
        switch e { case 0: .isoLatin1; case 1: .utf16; case 2: .utf16BigEndian; default: .utf8 }
    }

    private static func split(_ d: Data, enc: UInt8) -> [Data] {
        var parts: [Data] = [], start = 0, i = 0
        let wide = enc == 1 || enc == 2
        while i < d.count {
            if wide { if i + 1 < d.count, d[i] == 0, d[i + 1] == 0 { parts.append(d.subdata(in: start..<i)); i += 2; start = i; continue }; i += 2 }
            else { if d[i] == 0 { parts.append(d.subdata(in: start..<i)); start = i + 1 }; i += 1 }
        }
        if start < d.count { parts.append(d.subdata(in: start..<d.count)) }
        return parts
    }

    private static func textFields(_ body: Data) -> [String]? {
        guard let enc = body.first else { return nil }
        return split(body.dropFirst().asData, enc: enc).map { String(data: $0, encoding: encoding(enc)) ?? "" }
    }

    private static func textAfterTerminators(_ body: Data, skipLanguage: Bool, descriptors: Int) -> String? {
        guard body.count > 4 else { return nil }
        let enc = body[0]
        let rest = body.subdata(in: (skipLanguage ? 4 : 1)..<body.count)
        let parts = split(rest, enc: enc)
        guard parts.count > descriptors else { return nil }
        return parts[descriptors...].compactMap { String(data: $0, encoding: encoding(enc)) }.joined(separator: "\n")
    }

    /// Binary SYLT (ms timestamps) → LRC text.
    private static func sylt(_ b: Data) -> String? {
        guard b.count > 7, b[4] == 2 else { return nil }
        let enc = b[0]
        var body = b.subdata(in: 6..<b.count)
        let wide = enc == 1 || enc == 2
        func takeString() -> String? {
            var i = 0
            while i < body.count {
                if wide { if i + 1 < body.count, body[i] == 0, body[i + 1] == 0 { break }; i += 2 } else { if body[i] == 0 { break }; i += 1 }
            }
            let s = String(data: body.prefix(i), encoding: encoding(enc))
            body = body.count > i + (wide ? 2 : 1) ? body.subdata(in: (i + (wide ? 2 : 1))..<body.count) : Data()
            return s
        }
        _ = takeString()
        var out = ""
        while !body.isEmpty {
            let text = takeString() ?? ""
            guard body.count >= 4 else { break }
            let ms = Int(body[0]) << 24 | Int(body[1]) << 16 | Int(body[2]) << 8 | Int(body[3])
            body = body.count > 4 ? body.subdata(in: 4..<body.count) : Data()
            out += String(format: "[%02d:%02d.%02d]", ms / 60000, (ms / 1000) % 60, (ms % 1000) / 10) + text.trimmingCharacters(in: .newlines) + "\n"
        }
        return out.isEmpty ? nil : out
    }
}

private extension Data.SubSequence { var asData: Data { Data(self) } }
