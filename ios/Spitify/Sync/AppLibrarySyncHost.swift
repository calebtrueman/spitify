import Foundation
import Observation
import UIKit

/// Library sync for the app: reads every synced collection from the real stores and makes remote
/// changes there (docs/library-sync.md, "Collections"). Matching remote songs runs in the background,
/// at most four at a time.
@MainActor @Observable
final class AppLibrarySyncHost: LibrarySyncHost {
    /// Songs from another device that couldn't be found here, by song key.
    private(set) var unmatched: [String: SharedTrack] = [:]

    @ObservationIgnored private unowned let app: AppModel
    @ObservationIgnored var onPlay: ((String, SyncObject) -> Void)?
    @ObservationIgnored var onDirty: ((Set<String>) -> Void)?
    /// Songs matched in the background: library sync tries what's waiting again.
    @ObservationIgnored var onResolved: (() -> Void)?
    /// Collections whose next report may drop many items (an explicit "clear all").
    @ObservationIgnored private var massRemovals = Set<String>()
    /// Global playlist id → local playlist id.
    @ObservationIgnored private(set) var playlistIDs: [String: String] = [:]
    /// Playlist descriptions from other devices (playlists here have none), so reports keep them.
    @ObservationIgnored private var descriptions: [String: String] = [:]
    /// Local song id → the song as linked devices know it; its `id` is the song key, which then stays stable.
    @ObservationIgnored private var tracks: [String: SharedTrack] = [:]
    /// Song key → local song id found for it.
    @ObservationIgnored private var matched: [String: String] = [:]
    /// Remote items accepted but not expressible here (a saved stream that's a local file here, a setting
    /// iOS can't show). They're reported back as they are, so nothing is undone.
    @ObservationIgnored private var held: [String: [String: SyncObject]] = [:]
    /// For a held setting: this device's own value when it was held; changing it here drops the held one.
    @ObservationIgnored private var heldLocal: [String: SyncJSON] = [:]
    /// Local playlist id → its image as last sent or received, so the same picture isn't re-encoded differently.
    @ObservationIgnored private var images: [String: SyncPlaylistImage] = [:]
    @ObservationIgnored private var resolving = Set<String>()
    @ObservationIgnored private var queue: [(String, SharedTrack)] = []
    @ObservationIgnored private var running = 0
    @ObservationIgnored private var loadingShows = Set<String>()
    @ObservationIgnored private var retryTask: Task<Void, Never>?
    @ObservationIgnored private var index: (revision: Int, keys: [String: [String]])?

    init(app: AppModel) { self.app = app }

    var loaded: Bool { app.library.loaded }
    var isPlaying: Bool { app.player.isPlaying }

    func allowMassRemoval(_ collections: Set<String>) { massRemovals.formUnion(collections) }
    func takeMassRemovals() -> Set<String> { defer { massRemovals = [] }; return massRemovals }

    // MARK: Observing

    func touch(_ group: String) {
        let library = app.library
        switch group {
        case LibrarySync.liked: _ = library.liked
        case LibrarySync.savedTracks: _ = app.musicStreams.savedIDs
        case LibrarySync.followedArtists: _ = app.artistFollows.artists
        case LibrarySync.hiddenSongs: _ = library.hiddenSongs
        case LibrarySync.hiddenArtists: _ = library.hiddenArtists
        case LibrarySync.hiddenMixes: _ = app.deletedMixIDs
        case LibrarySync.podcasts: _ = app.shows.shows
        case LibrarySync.progress: _ = app.shows.resume
        case LibrarySync.playlists: _ = library.playlists; _ = library.artVersion
        case "stats": _ = library.listens
        case LibrarySync.profile: _ = app.profile
        case LibrarySync.friends, LibrarySync.savedShared: _ = app.social.state
        case LibrarySync.settings:
            _ = app.theme; _ = app.showRecommendations; _ = app.autoFix; _ = app.onlineArt; _ = app.lyrics.onlineEnabled
            let player = app.player
            _ = player.autoplay; _ = player.crossfade; _ = player.keepAlbumsGapless; _ = player.normalizeVolume; _ = player.speeds
        case "library": _ = library.libraryRevision
        default: break
        }
    }

    // MARK: Reporting

    /// A song by local id: library songs and catalogue streams (never the slow episode list).
    private func song(_ id: String) -> Song? { app.library.library.songById[id] ?? app.musicStreams.lookup(id) }

    private func downloadedIDs() -> [String: String] {
        Dictionary(app.musicDownloads.jobs.filter { $0.state == .complete }.map { ($0.relativePath, $0.track.id) }, uniquingKeysWith: { a, _ in a })
    }

    func capture(_ groups: Set<String>, context: SyncContext) -> @Sendable () -> SyncCapture {
        let library = app.library, me = context.me, engine = context.engine
        let known = tracks, streams = app.musicStreams.tracks, downloads = downloadedIDs()
        var songs: [String: Song] = [:]
        func need(_ ids: some Sequence<String>) { for id in ids where songs[id] == nil && known[id] == nil { if let s = song(id) { songs[id] = s } } }

        let liked = groups.contains(LibrarySync.liked) ? library.liked : nil
        let hiddenSongs = groups.contains(LibrarySync.hiddenSongs) ? library.hiddenSongs : nil
        let saved = groups.contains(LibrarySync.savedTracks) ? app.musicStreams.savedSongs : nil
        let playlists = groups.contains(LibrarySync.playlists) ? library.playlists : nil
        let listens = groups.contains("stats") ? library.listens.filter { $0.device == nil } : nil
        let resume = groups.contains(LibrarySync.progress) ? app.shows.resume : nil
        if let liked { need(liked.keys) }
        if let hiddenSongs { need(hiddenSongs) }
        if let playlists { for p in playlists { need(p.songIds) } }
        if let listens { need(Set(listens.map(\.songId))) }
        if let resume { need(resume.keys.filter { !$0.hasPrefix("ep:") }) }
        for song in saved ?? [] { songs[song.id] = song }

        var fixed: [String: [String: SyncObject]] = [:]
        if groups.contains(LibrarySync.followedArtists) {
            fixed[LibrarySync.followedArtists] = Dictionary(app.artistFollows.artists.map { a in
                var artist: SyncObject = ["id": .string(a.id), "name": .string(a.name)]
                if let art = a.artwork { artist["artwork"] = .string(art) }
                return (a.id, ["_artist": .object(artist)])
            }, uniquingKeysWith: { a, _ in a })
        }
        if groups.contains(LibrarySync.hiddenArtists) {
            var out: [String: SyncObject] = [:]
            for name in library.hiddenArtists { let key = LibrarySync.fold(name); if !key.isEmpty { out[key] = ["_name": .string(name)] } }
            fixed[LibrarySync.hiddenArtists] = out
        }
        if groups.contains(LibrarySync.hiddenMixes) {
            fixed[LibrarySync.hiddenMixes] = Dictionary(app.deletedMixIDs.map { ($0, SyncObject()) }, uniquingKeysWith: { a, _ in a })
        }
        if groups.contains(LibrarySync.podcasts) {
            var out: [String: SyncObject] = [:]
            for show in app.shows.shows where show.kind == .audiobook || show.following {
                var value: SyncObject = ["feedUrl": .string(show.feedURL), "title": .string(show.title), "author": .string(show.author), "kind": .string(show.kind.rawValue)]
                if let art = show.artworkURL { value["artwork"] = .string(art) }
                out[show.feedURL] = ["_show": .object(value)]
            }
            fixed[LibrarySync.podcasts] = out
        }
        if groups.contains(LibrarySync.profile) {
            let profile = app.profile
            fixed[LibrarySync.profile] = Self.nonDefault([
                ("name", .string(profile.name), profile.name.isEmpty),
                ("seedArtists", .array(profile.seedArtists.sorted(by: utf16Less).map { .string($0) }), profile.seedArtists.isEmpty),
                ("onboarded", .bool(profile.onboarded), !profile.onboarded),
            ], LibrarySync.profile, engine)
        }
        let social = app.social
        if groups.contains(LibrarySync.friends) {
            fixed[LibrarySync.friends] = Dictionary(social.state.following.map { id in
                (id, social.state.profiles[id].map { ["_name": .string($0.name)] } ?? SyncObject())
            }, uniquingKeysWith: { a, _ in a })
        }
        if groups.contains(LibrarySync.savedShared) {
            fixed[LibrarySync.savedShared] = Dictionary(social.state.playlists.values.filter { $0.owner != social.publicKey }.map { ($0.key, ["_name": .string($0.name)]) },
                                                        uniquingKeysWith: { a, _ in a })
        }
        if groups.contains(LibrarySync.settings) {
            var values = settingsValues(engine)
            // A value iOS can't show (another platform's font, say) stands until it's changed here.
            for (name, remote) in held[LibrarySync.settings] ?? [:] {
                if values[name]?["value"] == heldLocal[name] { values[name] = remote }
                else { held[LibrarySync.settings]?.removeValue(forKey: name); heldLocal[name] = nil }
            }
            fixed[LibrarySync.settings] = values
        }

        // Playlists get a global id the first time they're reported.
        var playlistRows: [(gid: String, playlist: Playlist, image: URL?, memo: SyncPlaylistImage?, description: String)] = []
        if let playlists {
            // Lists deleted here: this report turns them into removals, then their ids are forgotten.
            let localIDs = Set(playlists.map(\.id))
            playlistIDs = playlistIDs.filter { localIDs.contains($0.value) }
            var byLocal = Dictionary(playlistIDs.map { ($0.value, $0.key) }, uniquingKeysWith: { a, _ in a })
            for p in playlists {
                let gid = byLocal[p.id] ?? UUID().uuidString.lowercased()
                byLocal[p.id] = gid; playlistIDs[gid] = p.id
                let url = ArtCache.shared.customURL("playlist:" + p.id)
                playlistRows.append((gid, p, FileManager.default.fileExists(atPath: url.path) ? url : nil, images[p.id], descriptions[gid] ?? ""))
            }
        }

        let rows = playlistRows, found = songs, values = fixed, kept = held, episodes = resume == nil ? [:] : episodeKeys().local
        return {
            var out = SyncCapture()
            func track(_ id: String) -> SharedTrack? {
                if let t = known[id] ?? out.learned[id] { return t }
                guard let song = found[id], let t = Self.shared(song, streams: streams, downloads: downloads) else { return nil }
                out.learned[id] = t
                return t
            }
            func songItems(_ ids: some Sequence<String>, extra: (String) -> SyncObject = { _ in [:] }) -> [String: SyncObject] {
                var items: [String: SyncObject] = [:]
                for id in ids { guard let t = track(id) else { continue }; var v: SyncObject = ["track": LibrarySync.trackJSON(t)]; v.merge(extra(id)) { a, _ in a }; items[t.id] = v }
                return items
            }
            out.collections = values
            if let liked { out.collections[LibrarySync.liked] = songItems(liked.keys) { ["_addedAt": .int(Int64(liked[$0]!.timeIntervalSince1970 * 1000))] } }
            if let hiddenSongs { out.collections[LibrarySync.hiddenSongs] = songItems(hiddenSongs) }
            if let saved {
                let added = Dictionary(saved.map { ($0.id, $0.dateAdded) }, uniquingKeysWith: { a, _ in a })
                out.collections[LibrarySync.savedTracks] = songItems(saved.map(\.id)) { ["_addedAt": .int(Int64(max(0, added[$0]!.timeIntervalSince1970) * 1000))] }
            }
            if let listens {
                var stats: [String: (track: SharedTrack, plays: Int64, skips: Int64, last: Int64)] = [:]
                for l in listens {
                    guard let t = track(l.songId) else { continue }
                    var s = stats[t.id] ?? (t, 0, 0, 0)
                    if l.skipped { s.skips += 1 } else { s.plays += 1 }
                    s.last = max(s.last, Int64(l.at.timeIntervalSince1970 * 1000)); stats[t.id] = s
                }
                out.collections[LibrarySync.stats(me)] = stats.mapValues { ["track": LibrarySync.trackJSON($0.track), "plays": .int($0.plays), "skips": .int($0.skips), "lastPlayed": .int($0.last)] }
            }
            if let resume {
                var items: [String: SyncObject] = [:]
                for (key, r) in resume {
                    let syncKey: String
                    if key.hasPrefix("ep:") { guard let k = episodes[key] else { continue }; syncKey = k }
                    else if let t = track(key) { syncKey = LibrarySync.shortKey("t:" + t.id) } else { continue }
                    items[syncKey] = ["positionMs": .int(r.positionMs / 5_000 * 5_000), "durationMs": .int(r.durationMs), "played": .bool(r.played),
                                      "_updatedAt": .int(Int64(r.updated.timeIntervalSince1970 * 1000))]
                }
                out.collections[LibrarySync.progress] = items
            }
            if playlists != nil {
                var lists: [String: SyncObject] = [:]
                for row in rows {
                    var value: SyncObject = ["name": .string(row.playlist.name), "description": .string(row.description),
                                             "_createdAt": .int(Int64(row.playlist.createdAt.timeIntervalSince1970 * 1000))]
                    // A cover from another device that's still downloading is reported as received.
                    if let image = row.image.flatMap({ Self.image($0, memo: row.memo) }) ?? row.memo {
                        value["_image"] = .string(image.url); value["imageHash"] = .string(image.hash)
                        out.images[row.playlist.id] = image
                    }
                    lists[row.gid] = value
                    let entries = row.playlist.songIds.compactMap(track)
                    let known = engine.present(LibrarySync.playlist(row.gid))
                    var items: [String: SyncObject] = [:], previous: Int64 = -1
                    for (key, t) in zip(LibrarySync.entryKeys(entries), entries) {
                        // Keep the positions other devices sent while the order agrees, so a song that's
                        // missing here doesn't shift everyone else's positions.
                        let pos = known[key]?["pos"]?.long.flatMap { $0 > previous ? $0 : nil } ?? previous + 1
                        previous = pos
                        items[key] = ["track": LibrarySync.trackJSON(t), "pos": .int(pos)]
                    }
                    out.collections[LibrarySync.playlist(row.gid)] = items
                }
                out.collections[LibrarySync.playlists] = lists
            }
            // Accepted remote items this device can't express are reported as they are.
            for (collection, items) in kept where out.collections[collection] != nil {
                for (key, value) in items where out.collections[collection]![key] == nil { out.collections[collection]![key] = value }
            }
            return out
        }
    }

    /// Episode resume keys here ("ep:<show>/<episode>") ↔ progress keys everywhere ("e:<feedUrl>#<guid>").
    private func episodeKeys() -> (local: [String: String], synced: [String: String]) {
        var local: [String: String] = [:], synced: [String: String] = [:]
        for show in app.shows.shows {
            for e in show.episodes {
                guard let guid = e.guid else { continue }
                let mine = "ep:\(show.id)/\(e.id)", key = LibrarySync.episodeProgressKey(feed: show.feedURL, guid: guid)
                local[mine] = key; synced[key] = mine
            }
        }
        return (local, synced)
    }

    func learned(_ capture: SyncCapture) {
        for (id, track) in capture.learned where tracks[id] == nil { tracks[id] = track }
        for (id, image) in capture.images { images[id] = image }
    }

    /// Song keys for play counts from other devices.
    private func publishKeys(_ index: [String: [String]]) {
        guard !app.library.remotePlays.isEmpty else { return }
        var keys: [String: String] = [:]
        for (key, ids) in index { for id in ids { keys[id] = key } }
        if app.library.syncKeys != keys { app.library.syncKeys = keys }
    }

    func historySeed(me: String) -> @Sendable () -> [(String, SyncObject)] {
        let cutoff = Date().addingTimeInterval(-Double(LibrarySync.historyDays) * 86_400)
        let listens = Array(app.library.listens.filter { $0.device == nil && $0.at >= cutoff }.suffix(LibrarySyncStore.historySeedLimit))
        var songs: [String: Song] = [:]
        for id in Set(listens.map(\.songId)) where tracks[id] == nil { if let s = song(id) { songs[id] = s } }
        let known = tracks, streams = app.musicStreams.tracks, downloads = downloadedIDs(), found = songs
        return {
            listens.compactMap { l in
                guard let t = known[l.songId] ?? found[l.songId].flatMap({ Self.shared($0, streams: streams, downloads: downloads) }) else { return nil }
                return Self.historyItem(l, track: t, me: me)
            }
        }
    }

    /// Called for each local listen.
    func recorded(_ listen: Listen, me: String) {
        guard listen.device == nil, let t = tracks[listen.songId] ?? song(listen.songId).flatMap({ Self.shared($0, streams: app.musicStreams.tracks, downloads: downloadedIDs()) }) else { return }
        if tracks[listen.songId] == nil { tracks[listen.songId] = t }
        let (key, value) = Self.historyItem(listen, track: t, me: me)
        onPlay?(key, value)
    }

    nonisolated static func historyItem(_ l: Listen, track: SharedTrack, me: String) -> (String, SyncObject) {
        let at = Int64(l.at.timeIntervalSince1970 * 1000)
        return ("\(me.prefix(8)):\(at):\(track.id)", ["track": LibrarySync.trackJSON(track), "playedAt": .int(at), "listenedMs": .int(l.listenedMs),
                                                    "durationMs": .int(l.durationMs), "skipped": .bool(l.skipped)])
    }

    /// `SharedTrack.from` without the main actor; `id` is the song key.
    nonisolated static func shared(_ song: Song, streams: [String: OnlineTrack], downloads: [String: String]) -> SharedTrack? {
        let remote = song.id.hasPrefix("stream:") ? streams[String(song.id.dropFirst(7))] : nil
        let source = remote.flatMap { MonochromeClient.id($0.id) } ?? (song.kind == .file ? downloads[song.location].flatMap { MonochromeClient.id($0) } : nil)
        var title = String(song.title.trimmingCharacters(in: .whitespacesAndNewlines).prefix(500)); if title.isEmpty { title = "Unknown song" }
        var artist = String(song.artist.prefix(500)); if artist.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { artist = "Unknown artist" }
        var track = SharedTrack(title: title, artist: artist, album: String(song.album.prefix(500)), durationMs: max(0, min(song.durationMs, 86_400_000)),
                                sourceID: source, releaseID: remote?.releaseID.isEmpty == false ? remote?.releaseID : nil,
                                artwork: song.artURL.flatMap { SocialRules.publicURL($0) ? $0 : nil })
        track.id = LibrarySync.trackKey(track)
        return track.valid() ? track : nil
    }

    /// A ≤ 24 KB, 300 px JPEG data URL of a playlist cover, reusing the last one while the file is unchanged.
    nonisolated static func image(_ url: URL, memo: SyncPlaylistImage?) -> SyncPlaylistImage? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        let file = String(SocialRules.hash(data).prefix(32))
        if let memo, memo.file == file { return memo }
        guard var jpeg = ArtCache.squareJPEG(data, side: 300) else { return nil }
        var quality: CGFloat = 0.8
        while jpeg.count > 17_500, quality > 0.2, let smaller = UIImage(data: jpeg)?.jpegData(compressionQuality: quality) { jpeg = smaller; quality -= 0.15 }
        let text = "data:image/jpeg;base64," + jpeg.base64EncodedString()
        guard text.utf8.count <= 24_000 else { return nil }
        return SyncPlaylistImage(file: file, url: text, hash: String(SocialRules.hash(Data(text.utf8)).prefix(16)))
    }

    // MARK: Settings

    private static let themeModes: [(ThemeMode, String)] = [(.dark, "Dark"), (.light, "Light"), (.amoled, "Amoled"), (.system, "System")]
    private static let fonts: [(AppFont, String)] = [(.figtree, "Figtree"), (.system, "System"), (.rounded, "Nunito"), (.serif, "Serif")]
    private static let textScales: [(TextScale, Double)] = [(.small, 0.9), (.normal, 1), (.large, 1.12), (.huge, 1.25)]
    private static let artShapes: [(ArtShape, String)] = [(.rounded, "Rounded"), (.square, "Square"), (.soft, "Soft")]
    private static let playerStyles: [(PlayerStyle, String)] = [(.artwork, "Artwork"), (.vinyl, "Vinyl"), (.minimal, "Minimal")]
    private static func name<T: Equatable>(_ value: T, _ table: [(T, String)]) -> String { table.first { $0.0 == value }!.1 }
    private static func value<T>(_ name: String?, _ table: [(T, String)]) -> T? { table.first { $0.1.caseInsensitiveCompare(name ?? "") == .orderedSame }?.0 }
    private static func speed(_ value: Float) -> SyncJSON { .double((Double(value) * 100).rounded() / 100) }

    /// Settings that are off their defaults, or that another device already set: a new device doesn't
    /// overwrite the others with its defaults, and a setting once synced is only ever changed, not removed.
    private func settingsValues(_ engine: LibrarySync) -> [String: SyncObject] {
        let t = app.theme, d = ThemeSettings(), player = app.player
        let all: [(String, SyncJSON, Bool)] = [
            ("themeMode", .string(Self.name(t.mode, Self.themeModes)), t.mode == d.mode),
            ("accent", .int(Int64(0xFF00_0000) | Int64(t.accent & 0xFF_FFFF)), t.accent == d.accent),
            ("accentFromArt", .bool(t.accentSource == .artwork), t.accentSource == d.accentSource),
            ("font", .string(Self.name(t.font, Self.fonts)), t.font == d.font),
            ("textScale", .double(Self.textScales.first { $0.0 == t.textScale }!.1), t.textScale == d.textScale),
            ("artShape", .string(Self.name(t.artShape, Self.artShapes)), t.artShape == d.artShape),
            ("playerStyle", .string(Self.name(t.playerStyle, Self.playerStyles)), t.playerStyle == d.playerStyle),
            ("artworkTint", .bool(t.artworkTint), t.artworkTint == d.artworkTint),
            ("blurBackdrop", .bool(t.blur), t.blur == d.blur),
            ("reduceMotion", .bool(t.reduceMotion), t.reduceMotion == d.reduceMotion),
            ("showRecommendations", .bool(app.showRecommendations), app.showRecommendations),
            ("autoplay", .bool(player.autoplay), player.autoplay),
            ("crossfadeMs", .int(Int64((player.crossfade * 1000).rounded())), player.crossfade == 0),
            ("crossfadeKeepAlbums", .bool(player.keepAlbumsGapless), player.keepAlbumsGapless),
            ("normalizeAudio", .bool(player.normalizeVolume), player.normalizeVolume),
            ("speedMusic", Self.speed(player.speeds.music), player.speeds.music == 1),
            ("speedPodcast", Self.speed(player.speeds.spoken), player.speeds.spoken == 1),
            ("onlineLyrics", .bool(app.lyrics.onlineEnabled), app.lyrics.onlineEnabled),
            ("onlineArt", .bool(app.onlineArt), app.onlineArt),
            ("autoFixMetadata", .bool(app.autoFix), app.autoFix),
        ]
        return Self.nonDefault(all, LibrarySync.settings, engine)
    }

    private static func nonDefault(_ all: [(String, SyncJSON, Bool)], _ collection: String, _ engine: LibrarySync) -> [String: SyncObject] {
        var out: [String: SyncObject] = [:]
        for (name, value, isDefault) in all where !isDefault || engine.item(collection, name)?.present == true { out[name] = ["value": value] }
        return out
    }

    /// Sets one synced setting (nil value: back to the default). False when iOS can't show that value.
    private func setSetting(_ name: String, _ value: SyncJSON?) -> Bool {
        let d = ThemeSettings(), player = app.player
        var theme = app.theme
        defer { if theme != app.theme { app.theme = theme } }
        switch name {
        case "themeMode": guard let v = value == nil ? d.mode : Self.value(value?.string, Self.themeModes) else { return false }; theme.mode = v
        case "accent": guard let v = value == nil ? Int64(d.accent) : value?.long else { return false }; theme.accent = UInt32(v & 0xFF_FFFF)
        case "accentFromArt": guard let v = value == nil ? false : value?.bool else { return false }; theme.accentSource = v ? .artwork : .preset
        case "font": guard let v = value == nil ? d.font : Self.value(value?.string, Self.fonts) else { return false }; theme.font = v
        case "textScale": guard let v = value == nil ? d.textScale : value?.double.flatMap({ s in Self.textScales.min { abs($0.1 - s) < abs($1.1 - s) }?.0 }) else { return false }; theme.textScale = v
        case "artShape": guard let v = value == nil ? d.artShape : Self.value(value?.string, Self.artShapes) else { return false }; theme.artShape = v
        case "playerStyle": guard let v = value == nil ? d.playerStyle : Self.value(value?.string, Self.playerStyles) else { return false }; theme.playerStyle = v
        case "artworkTint": guard let v = value == nil ? d.artworkTint : value?.bool else { return false }; theme.artworkTint = v
        case "blurBackdrop": guard let v = value == nil ? d.blur : value?.bool else { return false }; theme.blur = v
        case "reduceMotion": guard let v = value == nil ? d.reduceMotion : value?.bool else { return false }; theme.reduceMotion = v
        case "showRecommendations": guard let v = value == nil ? true : value?.bool else { return false }; if app.showRecommendations != v { app.showRecommendations = v }
        case "autoplay": guard let v = value == nil ? true : value?.bool else { return false }; if player.autoplay != v { player.autoplay = v }
        case "crossfadeMs": guard let v = value == nil ? 0 : value?.long, (0...12_000).contains(v) else { return false }; if player.crossfade != Double(v) / 1000 { player.crossfade = Double(v) / 1000 }
        case "crossfadeKeepAlbums": guard let v = value == nil ? true : value?.bool else { return false }; if player.keepAlbumsGapless != v { player.keepAlbumsGapless = v }
        case "normalizeAudio": guard let v = value == nil ? true : value?.bool else { return false }; if player.normalizeVolume != v { player.normalizeVolume = v }
        case "speedMusic": guard let v = value == nil ? 1 : value?.double, (0.5...3).contains(v) else { return false }; player.setSpeeds(music: Float(v))
        case "speedPodcast": guard let v = value == nil ? 1 : value?.double, (0.5...3).contains(v) else { return false }; player.setSpeeds(spoken: Float(v))
        case "onlineLyrics": guard let v = value == nil ? true : value?.bool else { return false }; if app.lyrics.onlineEnabled != v { app.lyrics.onlineEnabled = v }
        case "onlineArt": guard let v = value == nil ? true : value?.bool else { return false }; if app.onlineArt != v { app.onlineArt = v }
        case "autoFixMetadata": guard let v = value == nil ? true : value?.bool else { return false }; if app.autoFix != v { app.autoFix = v }
        default: return false
        }
        return true
    }

    // MARK: Matching songs

    /// Song key → local song ids (files, downloads and saved streams), rebuilt off the main thread when the library changes.
    private func localIndex() async -> [String: [String]] {
        let library = app.library
        if let index, index.revision == library.libraryRevision { return index.keys }
        let revision = library.libraryRevision
        let songs = library.library.songs.map { ($0.id, $0.title, $0.artist) }, known = tracks
        let keys = await Task.detached(priority: .utility) { () -> [String: [String]] in
            var out: [String: [String]] = [:]
            for (id, title, artist) in songs { out[known[id]?.id ?? LibrarySync.trackKey(title, artist), default: []].append(id) }
            return out
        }.value
        index = (revision, keys)
        return keys
    }

    /// The local song for a remote song: a local copy with that key (closest length), then the catalogue
    /// stream by `sourceID`, then (with `search`) the shared-track matcher in the background.
    private func resolve(_ key: String, _ track: SharedTrack?, index: [String: [String]], search: Bool) -> Song? {
        if let id = matched[key], let song = song(id) { return song }
        let target = track?.durationMs ?? 0
        if let local = (index[key] ?? []).compactMap(song).min(by: { abs($0.durationMs - target) < abs($1.durationMs - target) }) { return remember(key, track, local) }
        guard let track else { return nil }
        if track.sourceID != nil { return remember(key, track, app.musicStreams.register(Self.online(track))) }
        if search && unmatched[key] == nil && resolving.insert(key).inserted { queue.append((key, track)); pump() }
        return nil
    }

    private static func online(_ track: SharedTrack) -> OnlineTrack {
        OnlineTrack(id: track.sourceID ?? "", title: track.title, artist: track.artist, album: track.album, releaseID: track.releaseID ?? "",
                    durationMs: track.durationMs, trackNumber: 0, discNumber: 1, artwork: track.artwork, playable: true)
    }

    @discardableResult private func remember(_ key: String, _ track: SharedTrack?, _ song: Song) -> Song {
        matched[key] = song.id
        if tracks[song.id] == nil, var known = track { known.id = key; tracks[song.id] = known }
        return song
    }

    private func pump() {
        while running < 4, !queue.isEmpty {
            let (key, track) = queue.removeFirst()
            running += 1
            Task { [weak self] in
                guard let self else { return }
                do {
                    let song = try await SharedSongMatch.resolve(track, app: self.app)
                    self.remember(key, track, song); self.unmatched[key] = nil
                    self.scheduleRetry()
                } catch { self.unmatched[key] = track }
                self.resolving.remove(key); self.running -= 1; self.pump()
            }
        }
    }

    private func scheduleRetry() {
        retryTask?.cancel()
        retryTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled else { return }
            self?.onResolved?()
        }
    }

    /// "Couldn't find on this device" › Retry.
    func retryUnmatched() { forgetFailures(); onResolved?() }

    /// The network came back, or an hour passed: songs that failed (perhaps offline) are searched again.
    func forgetFailures() {
        guard !unmatched.isEmpty else { return }
        PlaylistMatches.shared.retry(Array(unmatched.values))
        unmatched = [:]
    }

    // MARK: Applying

    func apply(_ changes: [SyncChange], context: SyncContext) async -> [SyncChange] {
        let index = await localIndex()
        var done: [SyncChange] = []
        let groups = Dictionary(grouping: changes, by: \.collection)
        // Playlists before their songs, so a new list exists when its songs arrive.
        for collection in groups.keys.sorted(by: { ($0 == LibrarySync.playlists ? 0 : 1, $0) < ($1 == LibrarySync.playlists ? 0 : 1, $1) }) {
            let list = groups[collection]!
            switch collection {
            case LibrarySync.liked: done += applyLiked(list, index)
            case LibrarySync.hiddenSongs: done += applyHiddenSongs(list, index)
            case LibrarySync.savedTracks: done += applySaved(list, index)
            case LibrarySync.followedArtists: done += applyArtists(list)
            case LibrarySync.hiddenArtists: done += applyHiddenArtists(list)
            case LibrarySync.hiddenMixes:
                for change in list { app.setMixDeleted(change.key, change.present) }
                done += list
            case LibrarySync.podcasts: done += applyShows(list)
            case LibrarySync.progress: done += applyProgress(list, index)
            case LibrarySync.playlists: done += applyPlaylists(list, context, index)
            case LibrarySync.history: done += applyHistory(list, index)
            case LibrarySync.profile: done += applyProfile(list)
            case LibrarySync.friends:
                for change in list { if change.present { app.social.followFromSync(change.key) } else { app.social.unfollow(change.key) } }
                done += list
            case LibrarySync.savedShared: done += applySavedShared(list)
            case LibrarySync.settings:
                for change in list {
                    if setSetting(change.key, change.present ? change.value?["value"] : nil) { held[collection]?.removeValue(forKey: change.key); heldLocal[change.key] = nil }
                    else if change.present, let value = change.value {
                        held[collection, default: [:]][change.key] = value
                        heldLocal[change.key] = settingsValues(context.engine)[change.key]?["value"]
                    }
                    done.append(change)
                }
            default:
                if collection.hasPrefix("playlist:") { done += applyEntries(collection, list, context, index) }
                else if collection.hasPrefix("stats:") { done += applyStats(collection, list) }
            }
        }
        publishKeys(index)
        return done
    }

    private func keyOf(_ id: String) -> String? {
        if let t = tracks[id] { return t.id }
        guard let song = song(id) else { return nil }
        return LibrarySync.trackKey(song.title, song.artist)
    }

    private func addedAt(_ value: SyncObject?) -> Date {
        value?["_addedAt"]?.long.map { Date(timeIntervalSince1970: Double($0) / 1000) } ?? Date()
    }

    private func applyLiked(_ list: [SyncChange], _ index: [String: [String]]) -> [SyncChange] {
        var liked = app.library.liked, done: [SyncChange] = []
        for change in list {
            if change.present {
                guard let song = resolve(change.key, LibrarySync.track(change.value), index: index, search: true) else { continue }
                if liked[song.id] == nil { liked[song.id] = addedAt(change.value) }
            } else {
                for id in liked.keys where keyOf(id) == change.key { liked[id] = nil }
            }
            done.append(change)
        }
        if liked != app.library.liked { app.library.liked = liked }
        return done
    }

    private func applyHiddenSongs(_ list: [SyncChange], _ index: [String: [String]]) -> [SyncChange] {
        var hidden = app.library.hiddenSongs, done: [SyncChange] = []
        for change in list {
            if change.present {
                guard let song = resolve(change.key, LibrarySync.track(change.value), index: index, search: true) else { continue }
                hidden.insert(song.id)
            } else {
                hidden = hidden.filter { keyOf($0) != change.key }
            }
            done.append(change)
        }
        if hidden != app.library.hiddenSongs { app.library.hiddenSongs = hidden }
        return done
    }

    private func applySaved(_ list: [SyncChange], _ index: [String: [String]]) -> [SyncChange] {
        let streams = app.musicStreams
        var done: [SyncChange] = [], add: [OnlineTrack] = [], remove: [OnlineTrack] = []
        for change in list {
            if change.present {
                let track = LibrarySync.track(change.value)
                // A saved song is a catalogue stream: by its id when known, else whatever matches.
                let found = track.flatMap { $0.sourceID != nil ? streams.register(Self.online($0)) : nil } ?? resolve(change.key, track, index: index, search: true)
                guard let song = found else { continue }
                if let online = streams.track(song) { add.append(online); held[change.collection]?.removeValue(forKey: change.key) }
                else if let value = change.value { held[change.collection, default: [:]][change.key] = value }
            } else {
                held[change.collection]?.removeValue(forKey: change.key)
                for id in streams.savedIDs where keyOf("stream:" + id) == change.key { if let t = streams.tracks[id] { remove.append(t) } }
            }
            done.append(change)
        }
        if !add.isEmpty { streams.save(add.filter { !streams.savedIDs.contains($0.id) }) }
        if !remove.isEmpty { streams.remove(remove) }
        return done
    }

    private func applyArtists(_ list: [SyncChange]) -> [SyncChange] {
        let follows = app.artistFollows
        var done: [SyncChange] = []
        for change in list {
            if change.present {
                guard let a = change.value?["_artist"]?.object, let name = a["name"]?.string else { continue }
                follows.followFromSync(OnlineArtist(id: a["id"]?.string ?? change.key, name: name, artwork: a.text("artwork")))
                guard follows.contains(change.key) else { continue }
            } else if follows.contains(change.key) { follows.unfollow(change.key) }
            done.append(change)
        }
        return done
    }

    private func applyHiddenArtists(_ list: [SyncChange]) -> [SyncChange] {
        var hidden = app.library.hiddenArtists
        for change in list {
            if change.present {
                if !hidden.contains(where: { LibrarySync.fold($0) == change.key }) { hidden.insert(change.value?.text("_name") ?? change.key) }
            } else { hidden = hidden.filter { LibrarySync.fold($0) != change.key } }
        }
        if hidden != app.library.hiddenArtists { app.library.hiddenArtists = hidden }
        return list
    }

    private func applyShows(_ list: [SyncChange]) -> [SyncChange] {
        let shows = app.shows
        var done: [SyncChange] = []
        for change in list {
            let existing = shows.shows.first { $0.feedURL == change.key }
            if change.present {
                if let existing {
                    if existing.kind == .podcast && !existing.following { shows.setFollowing(existing, true) }
                    done.append(change); continue
                }
                // Loading a feed takes a while; the next report marks it done.
                guard loadingShows.insert(change.key).inserted else { continue }
                let show = change.value?["_show"]?.object
                let key = change.key
                Task { [weak self] in
                    if key.hasPrefix("archive:") {
                        let id = String(key.dropFirst(8))
                        await shows.addBook(BookSearchResult(id: id, title: show?.text("title") ?? id, author: show?.text("author") ?? "", summary: "", seconds: 0,
                                                             coverURL: show?.text("artwork"), language: ""))
                    } else {
                        await shows.subscribe(feedURL: key, art: show?.text("artwork"))
                    }
                    self?.loadingShows.remove(key)
                }
            } else {
                if let existing { if existing.kind == .audiobook { shows.remove(existing) } else if existing.following { shows.setFollowing(existing, false) } }
                done.append(change)
            }
        }
        return done
    }

    private func applyProgress(_ list: [SyncChange], _ index: [String: [String]]) -> [SyncChange] {
        var resume = app.shows.resume, done: [SyncChange] = []
        let episodes = episodeKeys().synced
        for change in list {
            let local: String
            if let mine = episodes[change.key] { local = mine }
            else if change.key.hasPrefix("e:"), let hash = change.key.firstIndex(of: "#"), !change.key.contains("~") {
                // An episode of a show whose guids aren't known here yet: ids are derived the same way.
                let feed = String(change.key[change.key.index(change.key.startIndex, offsetBy: 2)..<hash]), guid = String(change.key[change.key.index(after: hash)...])
                local = "ep:\(stableId(feed))/\(stableId(guid))"
            }
            else if change.key.hasPrefix("t:"), let id = matched[String(change.key.dropFirst(2))] ?? index[String(change.key.dropFirst(2))]?.first { local = id }
            else {
                if let value = change.value { held[change.collection, default: [:]][change.key] = value }
                done.append(change); continue
            }
            if change.present, let v = change.value {
                let updated = v["_updatedAt"]?.long ?? 0
                // Newer wins: progress made here since then is kept and reported over it.
                if let mine = resume[local], Int64(mine.updated.timeIntervalSince1970 * 1000) > updated { done.append(change); onDirty?([LibrarySync.progress]); continue }
                resume[local] = Resume(positionMs: v["positionMs"]?.long ?? 0, durationMs: v["durationMs"]?.long ?? 0, played: v["played"]?.bool ?? false,
                                       updated: Date(timeIntervalSince1970: Double(updated) / 1000))
            } else { resume[local] = nil }
            done.append(change)
        }
        if resume != app.shows.resume { app.shows.resume = resume }
        return done
    }

    private func applyHistory(_ list: [SyncChange], _ index: [String: [String]]) -> [SyncChange] {
        var done: [SyncChange] = [], added: [Listen] = []
        let have = Set(app.library.listens.map { "\($0.songId)|\(Int64($0.at.timeIntervalSince1970 * 1000))" })
        for change in list where change.present {
            guard let v = change.value, let at = v["playedAt"]?.long, let track = LibrarySync.track(v),
                  let song = resolve(track.id, track, index: index, search: false) else { continue }
            if !have.contains("\(song.id)|\(at)") {
                let skipped = v["skipped"]?.bool ?? false, listened = v["listenedMs"]?.long ?? 0, duration = v["durationMs"]?.long ?? 0
                added.append(Listen(songId: song.id, at: Date(timeIntervalSince1970: Double(at) / 1000), listenedMs: listened, durationMs: duration,
                                    completed: !skipped && (duration <= 0 || Double(listened) >= Double(duration) * 0.85), skipped: skipped,
                                    device: String(change.key.prefix { $0 != ":" })))
            }
            done.append(change)
        }
        app.library.insertListens(added)
        return done
    }

    private func applyStats(_ collection: String, _ list: [SyncChange]) -> [SyncChange] {
        let device = String(collection.dropFirst(6))
        var plays = app.library.remotePlays[device] ?? [:]
        for change in list {
            if change.present { plays[change.key] = Int(change.value?["plays"]?.long ?? 0) } else { plays[change.key] = nil }
        }
        if app.library.remotePlays[device] != plays { app.library.remotePlays[device] = plays.isEmpty ? nil : plays }
        return list
    }

    func forgetDevice(_ id: String) {
        if app.library.remotePlays[id] != nil { app.library.remotePlays[id] = nil }
    }

    private func applyProfile(_ list: [SyncChange]) -> [SyncChange] {
        var profile = app.profile
        for change in list {
            let value = change.present ? change.value?["value"] : nil
            switch change.key {
            case "name": profile.name = String((value?.string ?? "").prefix(80))
            case "seedArtists": profile.seedArtists = Set((value?.array ?? []).compactMap(\.string))
            case "onboarded": profile.onboarded = value?.bool ?? false
            default: break
            }
        }
        if profile != app.profile { app.profile = profile }
        return list
    }

    private func applySavedShared(_ list: [SyncChange]) -> [SyncChange] {
        let social = app.social
        var done: [SyncChange] = []
        for change in list {
            let existing = social.state.playlists[change.key]
            if change.present {
                if existing != nil { done.append(change); continue }
                let parts = change.key.split(separator: ":", maxSplits: 1).map(String.init)
                if parts.count == 2 { social.requestFromSync(owner: parts[0], id: parts[1]) }
            } else {
                if let existing { social.remove(existing) }
                done.append(change)
            }
        }
        return done
    }

    // MARK: Playlists

    private func applyPlaylists(_ list: [SyncChange], _ context: SyncContext, _ index: [String: [String]]) -> [SyncChange] {
        let library = app.library
        for change in list {
            let local = playlistIDs[change.key].flatMap { id in library.playlists.first { $0.id == id } }
            guard change.present, let v = change.value else {
                if let local { library.deletePlaylist(local.id) }
                playlistIDs[change.key] = nil; descriptions[change.key] = nil
                context.engine.forget(LibrarySync.playlist(change.key))
                continue
            }
            let name = String((v["name"]?.string ?? "").prefix(200))
            descriptions[change.key] = v["description"]?.string ?? ""
            var id: String
            if let local {
                id = local.id
                if local.name != name, !name.isEmpty { library.rename(id, to: name) }
            } else {
                let created = library.createPlaylist(name)
                id = created.id; playlistIDs[change.key] = id
                if let at = v["_createdAt"]?.long, let i = library.playlists.firstIndex(where: { $0.id == id }) { library.playlists[i].createdAt = Date(timeIntervalSince1970: Double(at) / 1000) }
                rebuild(change.key, context, index)
            }
            applyImage(id, v)
        }
        return list
    }

    private func applyImage(_ id: String, _ value: SyncObject) {
        let key = "playlist:" + id
        guard let text = value.text("_image"), let hash = value.text("imageHash") else {
            if images[id] != nil, value["imageHash"] == nil { ArtCache.shared.removeCustom(key); images[id] = nil; app.library.artVersion += 1 }
            return
        }
        guard images[id]?.hash != hash else { return }
        // Reported back as received until (and after) the picture is stored here.
        images[id] = SyncPlaylistImage(file: "", url: text, hash: hash)
        func store(_ data: Data) {
            guard UIImage(data: data) != nil, images[id]?.hash == hash else { return }
            try? data.write(to: ArtCache.shared.customURL(key), options: .atomic)
            ArtCache.shared.invalidate(key)
            images[id]?.file = String(SocialRules.hash(data).prefix(32))
            app.library.artVersion += 1
        }
        if text.hasPrefix("data:image/"), let comma = text.firstIndex(of: ","), let data = Data(base64Encoded: String(text[text.index(after: comma)...])) { store(data) }
        else if SocialRules.publicURL(text) { Task { if let data = await HTTP.get(text) { store(data) } } }
    }

    private func applyEntries(_ collection: String, _ list: [SyncChange], _ context: SyncContext, _ index: [String: [String]]) -> [SyncChange] {
        let gid = String(collection.dropFirst(9))
        // The list itself hasn't arrived yet: its songs wait.
        guard playlistIDs[gid] != nil, context.engine.item(LibrarySync.playlists, gid)?.present == true else { return [] }
        let resolved = rebuild(gid, context, index)
        return list.filter { !$0.present || resolved.contains($0.key) }
    }

    /// Lays the local playlist out from every device's entries (by position, then key), keeping songs
    /// added here that haven't been reported yet. Returns the entry keys that were found here.
    @discardableResult
    private func rebuild(_ gid: String, _ context: SyncContext, _ index: [String: [String]]) -> Set<String> {
        let library = app.library
        guard let id = playlistIDs[gid], let i = library.playlists.firstIndex(where: { $0.id == id }) else { return [] }
        let collection = LibrarySync.playlist(gid)
        let entries = context.engine.present(collection).sorted { a, b in
            let pa = a.value["pos"]?.long ?? 0, pb = b.value["pos"]?.long ?? 0
            return pa != pb ? pa < pb : utf16Less(a.key, b.key)
        }
        var ids: [String] = [], found = Set<String>()
        for (key, value) in entries {
            let songKey = String(key[..<(key.lastIndex(of: "#") ?? key.endIndex)])
            guard let song = resolve(songKey, LibrarySync.track(value), index: index, search: true) else { continue }
            ids.append(song.id); found.insert(key)
        }
        // Songs added here since the last report (unknown to sync so far) keep their place at the end.
        let streams = app.musicStreams.tracks, downloads = downloadedIDs()
        let local = library.playlists[i].songIds.compactMap { songID in (tracks[songID] ?? song(songID).flatMap { Self.shared($0, streams: streams, downloads: downloads) }).map { (songID, $0) } }
        for ((songID, _), key) in zip(local, LibrarySync.entryKeys(local.map(\.1))) where context.engine.item(collection, key) == nil { ids.append(songID) }
        if library.playlists[i].songIds != ids { library.playlists[i].songIds = ids; library.playlists[i].updatedAt = Date() }
        return found
    }

    // MARK: Saving

    /// Optional fields, so files from older versions still load.
    private struct Saved: Codable {
        var playlistIDs: [String: String]?
        var descriptions: [String: String]?
        var tracks: [String: SharedTrack]?
        var matched: [String: String]?
        var held: [String: [String: SyncJSON]]?
        var heldLocal: [String: SyncJSON]?
        var images: [String: SyncPlaylistImage]?
    }

    func savedState() -> @Sendable () -> SyncJSON {
        // Only songs something still refers to.
        let library = app.library
        var used = Set(library.liked.keys).union(library.hiddenSongs).union(library.playlists.flatMap(\.songIds)).union(library.listens.map(\.songId))
        used.formUnion(app.musicStreams.savedIDs.map { "stream:" + $0 }); used.formUnion(app.shows.resume.keys); used.formUnion(matched.values)
        let saved = Saved(playlistIDs: playlistIDs, descriptions: descriptions, tracks: tracks.filter { used.contains($0.key) }, matched: matched,
                          held: held.mapValues { $0.mapValues { .object($0) } }, heldLocal: heldLocal, images: images)
        return { (try? JSONDecoder().decode(SyncJSON.self, from: JSONEncoder().encode(saved))) ?? .null }
    }

    func loadState(_ json: SyncJSON) {
        guard let saved = try? JSONDecoder().decode(Saved.self, from: json.data) else { return }
        playlistIDs = saved.playlistIDs ?? [:]; descriptions = saved.descriptions ?? [:]; tracks = saved.tracks ?? [:]; matched = saved.matched ?? [:]
        held = (saved.held ?? [:]).mapValues { $0.compactMapValues(\.object) }; heldLocal = saved.heldLocal ?? [:]; images = saved.images ?? [:]
    }
}

/// A playlist cover as it travels: a ≤ 64 KB JPEG data URL, its hash, and which local file it came from.
struct SyncPlaylistImage: Codable, Hashable, Sendable { var file: String; var url: String; var hash: String }
