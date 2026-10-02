import SwiftUI
import Observation

enum ThemeMode: String, CaseIterable, Codable { case dark = "Dark", light = "Light", amoled = "AMOLED black", system = "Follow system" }
enum AccentSource: String, CaseIterable, Codable { case preset = "Pick", artwork = "Album art" }
enum AppFont: String, CaseIterable, Codable { case figtree = "Figtree", system = "SF Pro", rounded = "SF Rounded", serif = "New York" }
enum ArtShape: String, CaseIterable, Codable { case rounded = "Rounded", square = "Square", soft = "Extra round" }
enum PlayerStyle: String, CaseIterable, Codable { case artwork = "Artwork", vinyl = "Spinning vinyl", minimal = "Minimal" }
enum TextScale: String, CaseIterable, Codable { case small = "Small", normal = "Default", large = "Large", huge = "Extra large" }

let accentPresets: [(String, UInt32)] = [("Spitify green", 0x1ED760), ("Ocean", 0x3D8BFF), ("Violet", 0x9B6BFF), ("Hot pink", 0xFF4FA3), ("Coral", 0xFF6B5A),
                                         ("Amber", 0xFFB300), ("Lime", 0xB6F23D), ("Aqua", 0x22D3C5), ("Crimson", 0xE53950), ("Ice", 0xA5C8FF)]

struct ThemeSettings: Codable, Equatable {
    var mode: ThemeMode = .dark
    var accentSource: AccentSource = .preset
    var accent: UInt32 = 0x1ED760
    var font: AppFont = .figtree
    var textScale: TextScale = .normal
    var artShape: ArtShape = .rounded
    var playerStyle: PlayerStyle = .artwork
    var artworkTint = true
    var blur = true
    var reduceMotion = false
    var haptics = true
}

struct Profile: Codable, Equatable {
    var name = ""
    var onboarded = false
    var seedArtists: Set<String> = []
    var photoVersion = 0
}

/// Owns every store and keeps the generated playlists up to date.
@MainActor @Observable
final class AppModel {
    let library = LibraryStore()
    let shows = ShowsStore()
    let lyrics = LyricsService()
    let player = Player()

    var theme: ThemeSettings = Store.load(ThemeSettings.self, "theme") ?? ThemeSettings() { didSet { Store.save(theme, "theme") } }
    var profile: Profile = Store.load(Profile.self, "profile") ?? Profile() { didSet { Store.save(profile, "profile"); scheduleMixes() } }
    var autoFix = UserDefaults.standard.object(forKey: "autoFix") as? Bool ?? true { didSet { UserDefaults.standard.set(autoFix, forKey: "autoFix") } }
    var onlineArt = UserDefaults.standard.object(forKey: "onlineArt") as? Bool ?? true { didSet { UserDefaults.standard.set(onlineArt, forKey: "onlineArt") } }

    private(set) var mixes: [Mix] = []
    private(set) var model: TasteModel?
    private(set) var fixing = false
    private var mixTask: Task<Void, Never>?
    private var started = false

    static var photoURL: URL { Store.directory.appendingPathComponent("profile.jpg") }

    init() {
        player.library = library
        player.shows = shows
        library.onTasteInputChanged = { [weak self] in self?.scheduleMixes() }
    }

    func start() async {
        guard !started else { return }
        started = true
        await library.scan()
        player.restore { [weak self] id in self?.lookup(id) }
        Task { await shows.refreshAll() }
        Task { await backgroundFixes() }
        // Time-based playlists (daylist) move on even if nothing else changes.
        Task { while true { try? await Task.sleep(for: .seconds(1800)); scheduleMixes() } }
    }

    func lookup(_ id: String) -> Song? {
        library.library.songById[id] ?? library.books.first { $0.id == id } ?? shows.allEpisodeSongs[id]
    }

    // MARK: Recommendations

    func scheduleMixes() {
        mixTask?.cancel()
        let input = TasteInput(songs: library.library.songs, listens: library.listens, liked: Set(library.liked.keys),
                               seedArtists: profile.seedArtists, hiddenSongs: library.hiddenSongs, hiddenArtists: library.hiddenArtists, userName: profile.name)
        mixTask = Task {
            try? await Task.sleep(for: .milliseconds(600))
            if Task.isCancelled { return }
            let (m, mixes) = await Task.detached(priority: .utility) { () -> (TasteModel, [Mix]) in
                let m = TasteModel(input); return (m, PlaylistGenerator.generate(m))
            }.value
            if Task.isCancelled { return }
            self.model = m
            self.mixes = mixes
        }
    }

    func songRadio(_ s: Song) -> [Song] { model.map { PlaylistGenerator.songRadio($0, seed: s) } ?? [s] }
    func artistRadio(_ a: String) -> [Song] { model.map { PlaylistGenerator.artistRadio($0, a) } ?? [] }

    // MARK: Online fixes (missing covers + untagged files)

    func backgroundFixes() async {
        guard !fixing else { return }
        fixing = true
        defer { fixing = false }
        var tried = Set(UserDefaults.standard.stringArray(forKey: "fixTried") ?? [])
        if onlineArt {
            for album in library.library.albums where !ArtCache.shared.hasArt(album.id) && !tried.contains("art:" + album.id) {
                tried.insert("art:" + album.id)
                if let url = await MusicCatalog.albumArt(artist: album.artist, album: album.title), let data = await HTTP.get(url) {
                    ArtCache.shared.storeEmbedded(data, key: album.id)
                    library.artVersion += 1
                }
                try? await Task.sleep(for: .milliseconds(800))
            }
        }
        if autoFix {
            for song in library.library.songs where MusicCatalog.needsFix(song) && library.overrides[song.id] == nil && !tried.contains("tag:" + song.id) {
                tried.insert("tag:" + song.id)
                if let c = MusicCatalog.confident(song, await MusicCatalog.search(MusicCatalog.query(for: song), durationMs: song.durationMs)) {
                    library.saveOverride(MetadataOverride(title: c.title, artist: c.artist, album: c.album, albumArtist: c.artist, genre: c.genre, year: c.year, track: c.track, disc: c.disc, source: "online"), for: [song])
                    let key = Song.albumKey(album: c.album, artist: c.artist)
                    if !ArtCache.shared.hasArt(key), let art = c.artURL, let data = await HTTP.get(art) { ArtCache.shared.storeEmbedded(data, key: key); library.artVersion += 1 }
                }
                try? await Task.sleep(for: .milliseconds(1100))
            }
            // Own audiobooks with a folder-name title: ask Open Library.
            for (key, chapters) in Dictionary(grouping: library.books, by: \.albumKey) where !tried.contains("book:" + key) {
                tried.insert("book:" + key)
                let first = chapters[0]
                guard first.artist.lowercased().hasPrefix("unknown") || first.album == (first.folder as NSString).lastPathComponent else { continue }
                let guess = first.album.replacingOccurrences(of: "_", with: " ")
                if let hit = await OpenLibrary.search(guess).first(where: { foldForSearch($0.title).contains(foldForSearch(guess)) || foldForSearch(guess).contains(foldForSearch($0.title)) }) {
                    library.saveOverride(MetadataOverride(artist: hit.author, album: hit.title, albumArtist: hit.author, genre: "Audiobook", year: hit.year, source: "online"), for: chapters)
                    if let c = hit.coverURL, let data = await HTTP.get(c) { ArtCache.shared.storeEmbedded(data, key: Song.albumKey(album: hit.title, artist: hit.author)); library.artVersion += 1 }
                }
            }
        }
        UserDefaults.standard.set(Array(tried), forKey: "fixTried")
    }
}
