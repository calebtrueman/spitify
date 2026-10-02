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
    static let unsupportedExtensions: Set<String> = ["wma", "ape", "wv", "dsf", "dff", "mpc", "tta", "ogg", "oga", "opus", "mka", "ra", "rm", "mid", "midi"]
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
    var cover: Song { songs[0] }
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

    static func build(_ songs: [Song]) -> Library {
        let credits = Dictionary(grouping: songs, by: { $0.album.trimmingCharacters(in: .whitespaces).lowercased() })
            .mapValues { tracks in Set(tracks.map { Song.albumArtist($0.albumArtist) }).sorted { $0.count > $1.count } }
        let input = songs.map { song -> Song in
            var song = song
            let credit = Song.albumArtist(song.albumArtist)
            let candidates = credits[song.album.trimmingCharacters(in: .whitespaces).lowercased()] ?? []
            song.albumArtist = candidates.first { credit.lowercased().hasPrefix($0.lowercased() + ", ") } ?? credit
            return song
        }
        let byTitle: (Song, Song) -> Bool = { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        let albums = Dictionary(grouping: input, by: \.albumKey).map { key, tracks -> Album in
            let sorted = tracks.sorted { ($0.disc, $0.track, $0.title.lowercased()) < ($1.disc, $1.track, $1.title.lowercased()) }
            return Album(id: key, title: sorted[0].album, artist: Song.albumArtist(sorted[0].albumArtist), year: sorted.map(\.year).max() ?? 0, songs: sorted)
        }.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        let albumsByArtist = Dictionary(grouping: albums, by: \.artist)
        let artists = Dictionary(grouping: input, by: \.artist).map { name, tracks -> Artist in
            var own = albumsByArtist[name] ?? []
            for a in albums where a.songs.contains(where: { $0.artist == name }) && !own.contains(where: { $0.id == a.id }) { own.append(a) }
            return Artist(name: name, songs: tracks.sorted(by: byTitle), albums: own.sorted { $0.year > $1.year })
        }.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        let genres = Dictionary(grouping: input.filter { !($0.genre ?? "").trimmingCharacters(in: .whitespaces).isEmpty }, by: { $0.genre!.trimmingCharacters(in: .whitespaces) })
            .map { Genre(name: $0.key, songs: $0.value.sorted(by: byTitle)) }
            .sorted { $0.songs.count > $1.songs.count }
        let folders = Dictionary(grouping: input.filter { $0.kind == .file }, by: \.folder)
            .map { Folder(path: $0.key, songs: $0.value.sorted(by: byTitle)) }
            .sorted { $0.path < $1.path }
        var lib = Library(songs: input.sorted(by: byTitle), albums: albums, artists: artists, genres: genres, folders: folders)
        lib.songById = Dictionary(input.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        lib.albumById = Dictionary(albums.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        lib.artistByName = Dictionary(artists.map { ($0.name, $0) }, uniquingKeysWith: { a, _ in a })
        return lib
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
