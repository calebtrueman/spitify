import Foundation

/// Where a track's audio lives.
enum SourceKind: String, Codable, Hashable {
    /// A file inside Spitify's Documents folder (Files app › On My iPhone › Spitify).
    case file
    /// A downloaded song in the Music app library (non-DRM, played through AVPlayer).
    case musicLibrary
    /// A podcast episode / audiobook chapter streamed or downloaded by Spitify.
    case remote
}

struct Song: Identifiable, Hashable, Codable {
    var id: String
    var title: String
    var artist: String
    var album: String
    var albumArtist: String
    var durationMs: Int64
    var track: Int
    var disc: Int
    var year: Int
    var genre: String?
    /// Relative path under Documents (for `.file`), persistent-id string (`.musicLibrary`) or URL (`.remote`).
    var location: String
    var kind: SourceKind
    var dateAdded: Date
    var sizeBytes: Int64
    var fileExtension: String
    var artURL: String? = nil
    var isPodcast: Bool = false
    var isAudiobook: Bool = false
    var episodeId: String? = nil
    var artVersion: Int = 0
    var explicit: Bool? = nil
    /// Separate people/bands when the source provides them; `artist` keeps the full credit.
    var artistNames: [String]? = nil

    var creditedArtists: [String] { ArtistCredits.names(artist, explicit: artistNames, albumArtist: albumArtist) }
    var primaryArtist: String { ArtistCredits.primary(artist, explicit: artistNames, albumArtist: albumArtist) }

    var albumKey: String { Song.albumKey(album: album, artist: albumArtist) }
    var isSpoken: Bool { isPodcast || isAudiobook }
    var folder: String { (location as NSString).deletingLastPathComponent }
    var fileName: String { (location as NSString).lastPathComponent }

    static func albumKey(album: String, artist: String) -> String {
        "\(album.trimmingCharacters(in: .whitespaces).lowercased())\u{1}\(albumArtist(artist).lowercased())"
    }

    static func albumArtist(_ credit: String) -> String {
        credit.components(separatedBy: ";").first!.replacingOccurrences(of: "(?i)\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.*$", with: "", options: .regularExpression).trimmingCharacters(in: .whitespaces)
    }

    /// AVFoundation on iOS can't decode these.
    static let unsupportedExtensions: Set<String> = ["wma", "ape", "wv", "dsf", "dff", "mpc", "tta", "mka", "ra", "rm", "mid", "midi"]
    var playable: Bool { !Song.unsupportedExtensions.contains(fileExtension.lowercased()) || kind == .remote }

    var resumeKey: String { episodeId.map { "ep:\($0)" } ?? id }
}

struct Album: Identifiable, Hashable {
    var id: String
    var title: String
    var artist: String
    var year: Int
    var songs: [Song]
    var cover: Song { songs[0] }
    var durationMs: Int64 { songs.reduce(0) { $0 + $1.durationMs } }
}

struct Artist: Identifiable, Hashable {
    var id: String { name }
    var name: String
    var songs: [Song]
    var albums: [Album]
    var cover: Song { ownCover ?? songs[0] }
    var ownCover: Song? { songs.first { $0.primaryArtist.caseInsensitiveCompare(name) == .orderedSame || $0.albumArtist.caseInsensitiveCompare(name) == .orderedSame } }
}

struct Genre: Identifiable, Hashable {
    var id: String { name }
    var name: String
    var songs: [Song]
}

struct Folder: Identifiable, Hashable {
    var id: String { path }
    var path: String
    var songs: [Song]
    var name: String { path.isEmpty ? "Spitify" : (path as NSString).lastPathComponent }
}

struct Playlist: Identifiable, Hashable, Codable {
    var id: String
    var name: String
    var songIds: [String]
    var createdAt: Date
    var updatedAt: Date
}

/// Immutable grouped snapshot of the library.
struct Library {
    var songs: [Song] = []
    var albums: [Album] = []
    var artists: [Artist] = []
    var genres: [Genre] = []
    var folders: [Folder] = []
    var songById: [String: Song] = [:]
    var albumById: [String: Album] = [:]
    var artistByName: [String: Artist] = [:]

    var isEmpty: Bool { songs.isEmpty }

    static func songMatchKey(_ song: Song) -> String { SearchMatch.fold(song.title) + "|" + SearchMatch.fold(song.artist) }
    static func completeAlbumDetails(_ local: Song, from saved: [Song]) -> Song {
        var local = local
        let creditMatches = saved.filter { isDownloadedCopy(local, of: $0) }.compactMap(\.artistNames)
        if local.artistNames == nil, Set(creditMatches).count == 1 { local.artistNames = creditMatches.first }
        guard local.album.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || local.album == "Unknown album" else { return local }
        let matches = saved.filter { songMatchKey($0) == songMatchKey(local) && !$0.album.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.album != "Unknown album" && abs($0.durationMs - local.durationMs) <= 5000 }
        guard Set(matches.map(\.albumKey)).count == 1, let match = matches.first else { return local }
        var result = local; result.album = match.album; result.albumArtist = match.albumArtist; result.artistNames = local.artistNames ?? match.artistNames; result.artURL = local.artURL ?? match.artURL
        return result
    }
    static func isDownloadedCopy(_ local: Song, of saved: Song) -> Bool {
        let missingAlbum = saved.album.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        return SearchMatch.fold(local.title) == SearchMatch.fold(saved.title)
            && SearchMatch.fold(local.artist) == SearchMatch.fold(saved.artist)
            && (saved.durationMs <= 0 || local.durationMs <= 0 || abs(local.durationMs - saved.durationMs) <= 5000)
            && (missingAlbum || AudioFallback.sameRelease(local.album, saved.album))
    }
    static func build(_ songs: [Song]) -> Library {
        let songs = songs.map { song -> Song in
            var song = song
            if song.album.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { song.album = "Unknown album" }
            return song
        }
        let credits = Dictionary(grouping: songs, by: { $0.album.trimmingCharacters(in: .whitespaces).lowercased() })
            .mapValues { tracks in Set(tracks.map { Song.albumArtist($0.albumArtist) }).sorted { $0.count > $1.count } }
        let knownArtists = songs.flatMap { song in
            song.artistNames ?? [Song.albumArtist(song.albumArtist), Song.albumArtist(song.artist)]
        }.filter { !$0.contains(",") }
        let input = songs.map { song -> Song in
            var song = song
            let credit = Song.albumArtist(song.albumArtist)
            let candidates = credits[song.album.trimmingCharacters(in: .whitespaces).lowercased()] ?? []
            song.albumArtist = candidates.first { credit.lowercased().hasPrefix($0.lowercased() + ", ") } ?? credit
            song.artistNames = ArtistCredits.names(song.artist, explicit: song.artistNames, albumArtist: song.albumArtist, known: knownArtists)
            if song.albumArtist.isEmpty || song.albumArtist.caseInsensitiveCompare(song.artist) == .orderedSame {
                song.albumArtist = song.primaryArtist
            }
            return song
        }
        let byTitle: (Song, Song) -> Bool = { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        let albums = Dictionary(grouping: input, by: \.albumKey).map { key, tracks -> Album in
            let sorted = tracks.sorted { ($0.disc, $0.track, $0.title.lowercased()) < ($1.disc, $1.track, $1.title.lowercased()) }
            return Album(id: key, title: sorted[0].album, artist: Song.albumArtist(sorted[0].albumArtist), year: sorted.map(\.year).max() ?? 0, songs: sorted)
        }.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        let appearances = input.flatMap { song in song.creditedArtists.map { (name: $0, song: song) } }
        let artists = Dictionary(grouping: appearances, by: { $0.name.lowercased() }).map { key, entries -> Artist in
            let name = entries[0].name
            let tracks = entries.map(\.song).sorted(by: byTitle)
            let own = albums.filter { $0.artist.lowercased() == key || $0.songs.contains { $0.primaryArtist.lowercased() == key } }
            return Artist(name: name, songs: tracks, albums: own.sorted { $0.year > $1.year })
        }.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        let genres = Dictionary(grouping: input.filter { !($0.genre ?? "").trimmingCharacters(in: .whitespaces).isEmpty }, by: { $0.genre!.trimmingCharacters(in: .whitespaces) })
            .map { Genre(name: $0.key, songs: $0.value.sorted(by: byTitle)) }
            .sorted { $0.songs.count > $1.songs.count }
        let folders = Dictionary(grouping: input.filter { $0.kind == .file }, by: \.folder)
            .map { Folder(path: $0.key, songs: $0.value.sorted(by: byTitle)) }
            .sorted { $0.path < $1.path }
        var lib = Library(songs: input.sorted(by: byTitle), albums: albums, artists: artists.filter { $0.ownCover != nil }, genres: genres, folders: folders)
        lib.songById = Dictionary(input.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        lib.albumById = Dictionary(albums.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        lib.artistByName = Dictionary(artists.map { ($0.name, $0) }, uniquingKeysWith: { a, _ in a })
        for artist in artists {
            for name in artist.songs.flatMap(\.creditedArtists) where name.caseInsensitiveCompare(artist.name) == .orderedSame { lib.artistByName[name] = artist }
        }
        return lib
    }
}

/// Never split commas or ampersands without a known artist boundary: they can be part of a band name.
enum ArtistCredits {
    static func primary(_ credit: String, explicit: [String]? = nil, albumArtist: String? = nil) -> String {
        let album = (albumArtist ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !album.isEmpty, !["various artists", "unknown artist", "unknown", "various"].contains(album.lowercased()),
           album.caseInsensitiveCompare(credit) != .orderedSame,
           [" feat", " ft.", " featuring ", " (feat", " & ", " and ", ", ", ";", " with "].contains(where: { credit.lowercased().hasPrefix(album.lowercased() + $0) }) { return album }
        return names(credit, explicit: explicit, albumArtist: albumArtist).first ?? credit
    }

    static func names(_ credit: String, explicit: [String]? = nil, albumArtist: String? = nil, known: [String] = []) -> [String] {
        if let explicit, explicit.count > 1 { return unique(explicit) }
        let separators = [" & ", " and ", " AND ", ", ", " x ", " with "]
        let anchors = unique([albumArtist ?? ""] + known).filter { !$0.isEmpty && $0.lowercased() != "various artists" }.sorted { $0.count > $1.count }
        for anchor in anchors {
            for separator in separators where credit.lowercased().hasPrefix((anchor + separator).lowercased()) {
                let remainder = String(credit.dropFirst(anchor.count + separator.count)).trimmingCharacters(in: .whitespacesAndNewlines)
                if albumArtist?.caseInsensitiveCompare(anchor) == .orderedSame || anchors.contains(where: { $0.caseInsensitiveCompare(remainder) == .orderedSame }) {
                    return unique([anchor] + names(remainder, known: anchors.filter { $0 != anchor }))
                }
            }
        }
        let lower = credit.lowercased()
        if !credit.contains(";"), !credit.contains(", "), !lower.contains("feat"), !lower.contains("ft."), !lower.contains("ft ") { return unique([credit]) }
        let hasFeatureParenthesis = credit.range(of: "(?i)\\(\\s*(?:feat\\.?|ft\\.?|featuring)\\s+", options: .regularExpression) != nil
        let separated = credit.replacingOccurrences(of: "(?i)\\s*\\(?\\b(?:feat\\.?|ft\\.?|featuring)\\s+", with: ";", options: .regularExpression)
        let candidates = unique(([albumArtist ?? ""] + known).filter { !$0.isEmpty && $0.caseInsensitiveCompare("Various Artists") != .orderedSame }).sorted { $0.count > $1.count }
        let names = separated.components(separatedBy: ";").flatMap { part -> [String] in
            let clean = part.trimmingCharacters(in: .whitespacesAndNewlines)
            let part = hasFeatureParenthesis ? clean.trimmingCharacters(in: CharacterSet(charactersIn: ")")).trimmingCharacters(in: .whitespacesAndNewlines) : clean
            if let main = candidates.first(where: { part.lowercased().hasPrefix($0.lowercased() + ", ") }) {
                let remaining = String(part.dropFirst(main.count + 2))
                return [main] + Self.names(remaining, known: candidates.filter { $0.caseInsensitiveCompare(main) != .orderedSame })
            }
            return [part]
        }
        return unique(names.isEmpty ? [credit] : names)
    }
    private static func unique(_ input: [String]) -> [String] {
        var seen = Set<String>()
        return input.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty && seen.insert($0.lowercased()).inserted }
    }
}

/// One listen - the signal the recommendation engine learns from.
struct Listen: Codable, Hashable {
    var songId: String
    var at: Date
    var listenedMs: Int64
    var durationMs: Int64
    var completed: Bool
    var skipped: Bool
    /// Set for a listen on another linked device (the start of its key); it doesn't count toward this device's own plays.
    var device: String? = nil
}

struct MetadataOverride: Codable, Hashable {
    var title: String?
    var artist: String?
    var album: String?
    var albumArtist: String?
    var genre: String?
    var year: Int?
    var track: Int?
    var disc: Int?
    /// "user" edits always beat "online" auto-fixes.
    var source: String
}

extension Int64 {
    var formattedDuration: String {
        let total = Swift.max(0, self / 1000)
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
    var formattedLong: String {
        let minutes = self / 60_000
        return minutes >= 60 ? "\(minutes / 60) hr \(minutes % 60) min" : "\(Swift.max(1, minutes)) min"
    }
}

func songCount(_ n: Int) -> String { n == 1 ? "1 song" : "\(n) songs" }
