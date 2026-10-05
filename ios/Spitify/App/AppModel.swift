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
    var artThemeID: String?
    var hideThemeArt: Bool?
    var backdrop: UInt32?
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
    static let shared = AppModel()
    let library = LibraryStore()
    let shows = ShowsStore()
    let lyrics = LyricsService()
    let player = Player()
    let musicDownloads = MusicDownloads.shared
    let musicStreams = MusicStreams.shared
    let social = SocialStore()
    let artistFollows = ArtistFollows()
    let rooms = ListeningRooms()

    var theme: ThemeSettings = Store.load(ThemeSettings.self, "theme") ?? ThemeSettings() { didSet { Store.save(theme, "theme") } }
    var profile: Profile = Store.load(Profile.self, "profile") ?? Profile() { didSet { Store.save(profile, "profile"); scheduleMixes(); Task { await social.syncProfile() } } }
    var showRecommendations = UserDefaults.standard.object(forKey: "showRecommendations") as? Bool ?? true { didSet { UserDefaults.standard.set(showRecommendations, forKey: "showRecommendations") } }
    var autoFix = UserDefaults.standard.object(forKey: "autoFix") as? Bool ?? true { didSet { UserDefaults.standard.set(autoFix, forKey: "autoFix"); if autoFix { Task { await backgroundFixes() } } } }
    var onlineArt = UserDefaults.standard.object(forKey: "onlineArt") as? Bool ?? true { didSet { UserDefaults.standard.set(onlineArt, forKey: "onlineArt"); if onlineArt { Task { await backgroundFixes() } } } }

    private var generatedMixes: [Mix] = []
    /// Generated playlists the user deleted; they stay gone even though the taste engine keeps making them.
    private(set) var deletedMixIDs = Set(UserDefaults.standard.stringArray(forKey: "deletedMixes") ?? []) {
        didSet { UserDefaults.standard.set(Array(deletedMixIDs), forKey: "deletedMixes") }
    }
    var mixes: [Mix] { generatedMixes.filter { !deletedMixIDs.contains($0.id) } }
    func deleteMix(_ id: String) { deletedMixIDs.insert(id) }
    func restoreDeletedMixes() { deletedMixIDs = [] }
    private(set) var model: TasteModel?
    private(set) var fixing = false
    private var mixTask: Task<Void, Never>?
    private var widgetTask: Task<Void, Never>?
    private var started = false
    private var fixesRequested = false

    static var photoURL: URL { Store.directory.appendingPathComponent("profile.jpg") }

    init() {
        musicStreams.onChanged = { [weak self] in self?.library.rebuild() }
        player.onPlaybackChanged = { [weak self] in self?.scheduleWidgets() }
        player.radio = { [weak self] in self?.songRadio($0) ?? [] }
        library.onWidgetDataChanged = { [weak self] in self?.scheduleWidgets() }
        player.library = library
        player.shows = shows
        library.onScanCompleted = { [weak self] in Task { await self?.backgroundFixes() } }
        library.onTasteInputChanged = { [weak self] in self?.scheduleMixes() }
        musicDownloads.onImported = { [weak self] in await self?.importDownloadedMusic() }
    }

    func start() async {
        guard !started else { return }
        started = true
        rooms.start(app: self)
        do { try social.prepare(); Task { await social.syncProfile() } } catch { social.message = error.localizedDescription }
        await library.scan()
        musicDownloads.start()
        await importDownloadedMusic(rescan: false)
        player.restore { [weak self] id in self?.lookup(id) }
        scheduleWidgets()
        Task { await shows.refreshAll() }
        Task { await backgroundFixes() }
        // Time-based playlists (daylist) move on even if nothing else changes.
        Task { while true { try? await Task.sleep(for: .seconds(1800)); scheduleMixes() } }
    }

    private func importDownloadedMusic(rescan: Bool = true) async {
        if rescan {
            while library.scanning { try? await Task.sleep(for: .milliseconds(100)) }
            await library.scan()
        }
        for job in musicDownloads.jobs where job.state == .complete {
            guard let song = library.rawSongs.first(where: { $0.location == job.relativePath }), library.overrides[song.id] == nil else { continue }
            // The source may reuse a recording tagged with a different release. Keep the chosen album.
            let track = job.track
            library.saveOverride(MetadataOverride(title: track.title, artist: track.artist,
                album: track.album.isEmpty ? nil : track.album, albumArtist: track.albumArtist ?? track.primaryArtist, track: track.trackNumber > 0 ? track.trackNumber : nil,
                disc: track.discNumber, source: "online"), for: [song])
        }
    }

    func scheduleWidgets() {
        widgetTask?.cancel()
        widgetTask = Task { [weak self] in
            do { try await Task.sleep(for: .milliseconds(180)) } catch { return }
            guard let self else { return }
            WidgetPublisher.refresh(self)
        }
    }

    func lookup(_ id: String) -> Song? {
        library.library.songById[id] ?? library.books.first { $0.id == id } ?? shows.allEpisodeSongs[id] ?? musicStreams.lookup(id)
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
            self.generatedMixes = mixes
        }
    }

    func songRadio(_ s: Song) -> [Song] { model.map { PlaylistGenerator.songRadio($0, seed: s) } ?? [s] }
    func artistRadio(_ a: String) -> [Song] { model.map { PlaylistGenerator.artistRadio($0, a) } ?? [] }

    // MARK: Online fixes (missing covers + untagged files)

    func backgroundFixes() async {
        if fixing { fixesRequested = true; return }
        guard autoFix || onlineArt else { return }
        fixing = true
        defer { fixing = false }
        repeat {
            fixesRequested = false
            // Use a snapshot so a new import can request another pass without disrupting this one.
            let songs = library.rawSongs.filter { $0.playable }
            for raw in songs {
                let song = library.library.songById[raw.id] ?? library.books.first { $0.id == raw.id } ?? raw
                await fillMissing(song)
            }
        } while fixesRequested
    }

    private func fillMissing(_ song: Song) async {
        let url = song.kind == .file ? Store.documents.appendingPathComponent(song.location) : nil
        let tags: Tags
        if let url { tags = await TagReader.read(url) }
        else {
            tags = Tags(title: song.title, artist: song.artist, album: song.album, albumArtist: song.albumArtist,
                        genre: song.genre, year: song.year > 0 ? song.year : nil, track: song.track > 0 ? song.track : nil,
                        disc: song.disc > 0 ? song.disc : nil)
        }
        let saved = library.overrides[song.id] ?? MetadataOverride(source: "online")
        var suggested = MetadataOverride(source: "online")
        var coverURL: String?
        let defaults = UserDefaults.standard
        var attempts = defaults.dictionary(forKey: "missingAttemptsV2") as? [String: Double] ?? [:]
        let key = "tag:\(song.id):\(song.sizeBytes)"
        let week: Double = 7 * 24 * 60 * 60
        if autoFix && MissingMetadata.incomplete(tags.edit, saved: saved) && Date().timeIntervalSince1970 - (attempts[key] ?? 0) >= week {
            if song.isAudiobook {
                let guess = song.album.replacingOccurrences(of: "_", with: " ")
                if let hit = await OpenLibrary.search(guess).first(where: { foldForSearch($0.title) == foldForSearch(guess) }) {
                    suggested = MetadataOverride(artist: hit.author, album: hit.title, albumArtist: hit.author, genre: "Audiobook", year: hit.year, source: "online")
                    coverURL = hit.coverURL
                }
            } else if let match = MusicCatalog.confident(song, await MusicCatalog.search(MusicCatalog.query(for: song), durationMs: song.durationMs)) {
                suggested = MetadataOverride(title: match.title, artist: match.artist, album: match.album, albumArtist: match.artist,
                    genre: match.genre, year: match.year, track: match.track, disc: match.disc, source: "online")
                coverURL = match.artURL
            }
            attempts[key] = Date().timeIntervalSince1970
            defaults.set(attempts, forKey: "missingAttemptsV2")
            try? await Task.sleep(for: .milliseconds(1100))
        }
        let edit = autoFix ? MissingMetadata.fill(tags.edit, saved: saved, suggested: suggested) : MetadataOverride(source: "online")
        if edit != MetadataOverride(source: "online") { library.saveMissingOverride(edit, for: song) }
        let updated = library.library.songById[song.id] ?? library.books.first { $0.id == song.id } ?? song
        var cover: Data?
        if onlineArt && tags.artwork == nil {
            // Existing app covers also get embedded automatically in files that have no cover.
            let cache = ArtCache.shared
            cover = cache.image(for: updated.albumKey)?.jpegData(compressionQuality: 0.92)
                ?? cache.image(for: song.albumKey)?.jpegData(compressionQuality: 0.92)
            let artKey = "art:" + updated.albumKey
            if cover == nil && Date().timeIntervalSince1970 - (attempts[artKey] ?? 0) >= week {
                if coverURL == nil { coverURL = await MusicCatalog.albumArt(artist: updated.albumArtist, album: updated.album) }
                if let coverURL { cover = await HTTP.get(coverURL) }
                attempts[artKey] = Date().timeIntervalSince1970
                defaults.set(attempts, forKey: "missingAttemptsV2")
                try? await Task.sleep(for: .milliseconds(800))
            }
            if let cover, !cache.hasArt(updated.albumKey) {
                cache.storeEmbedded(cover, key: updated.albumKey)
                library.artVersion += 1
            }
        }
        if let url, edit != MetadataOverride(source: "online") || cover != nil {
            try? await FileTags.shared.write(url, edit: edit, artwork: cover, onlyMissing: true)
        }
    }
}
