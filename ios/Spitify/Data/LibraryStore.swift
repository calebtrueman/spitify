import Foundation
import MediaPlayer
import Observation
import AppIntents

/// Scans the Spitify Documents folder (+ the Music app library), and owns everything personal:
/// likes, playlists, listening history, metadata edits and hidden recommendations.
@MainActor @Observable
final class LibraryStore {
    private(set) var rawSongs: [Song] = []
    private(set) var library = Library() { didSet { libraryRevision &+= 1 } }
    /// Bumps whenever [library] is rebuilt; cheap to watch instead of hashing every song.
    private(set) var libraryRevision = 0
    private(set) var books: [Song] = []
    private(set) var scanning = false
    private(set) var lastScanFound = 0
    /// True once the first scan this launch finished, so library sync never reads a half-loaded library.
    private(set) var loaded = false
    /// A finished or skipped listen on this device (not one synced from another device).
    @ObservationIgnored var onRecorded: ((Listen) -> Void)?
    /// Play counts from other linked devices: device → song key (`LibrarySync.trackKey`) → plays.
    var remotePlays: [String: [String: Int]] = [:] { didSet { Store.save(remotePlays, "remotePlays") } }
    /// Song id → its library-sync key, kept by library sync so counts from other devices find local songs.
    var syncKeys: [String: String] = [:]

    /// Fired when anything the taste engine learns from changes.
    var onWidgetDataChanged: (() -> Void)?
    var onScanCompleted: (() -> Void)?
    var onTasteInputChanged: (() -> Void)?

    var liked: [String: Date] = [:] { didSet { Store.save(liked, "liked"); onTasteInputChanged?(); onWidgetDataChanged?() } }
    var playlists: [Playlist] = [] { didSet { Store.save(playlists, "playlists"); onWidgetDataChanged?() } }
    var listens: [Listen] = [] { didSet { Store.save(listens, "listens"); onTasteInputChanged?(); onWidgetDataChanged?() } }
    var overrides: [String: MetadataOverride] = [:] { didSet { Store.save(overrides, "overrides"); rebuild() } }
    var hiddenSongs: Set<String> = [] { didSet { Store.save(hiddenSongs, "hiddenSongs"); onTasteInputChanged?(); onWidgetDataChanged?() } }
    var hiddenArtists: Set<String> = [] { didSet { Store.save(hiddenArtists, "hiddenArtists"); onTasteInputChanged?(); onWidgetDataChanged?() } }
    var includeMusicLibrary: Bool = UserDefaults.standard.bool(forKey: "includeMusicLibrary") {
        didSet { UserDefaults.standard.set(includeMusicLibrary, forKey: "includeMusicLibrary") }
    }
    var artVersion = 0 { didSet { onWidgetDataChanged?() } }

    private struct CacheEntry: Codable { var modified: Date; var size: Int64; var song: Song; var hasArt: Bool }
    private var cache: [String: CacheEntry] = [:]

    init() {
        liked = Store.load([String: Date].self, "liked") ?? [:]
        playlists = Store.load([Playlist].self, "playlists") ?? []
        listens = Store.load([Listen].self, "listens") ?? []
        overrides = Store.load([String: MetadataOverride].self, "overrides") ?? [:]
        hiddenSongs = Store.load(Set<String>.self, "hiddenSongs") ?? []
        hiddenArtists = Store.load(Set<String>.self, "hiddenArtists") ?? []
        remotePlays = Store.load([String: [String: Int]].self, "remotePlays") ?? [:]
        cache = Store.load([String: CacheEntry].self, "scanCache") ?? [:]
        rawSongs = cache.values.map(\.song)
        ensureFolders()
        rebuild()
    }

    // MARK: - Folders

    static var audiobooksFolder: URL { Store.documents.appendingPathComponent("Audiobooks", isDirectory: true) }

    private func ensureFolders() {
        let fm = FileManager.default
        for dir in [Store.documents.appendingPathComponent("Music", isDirectory: true), Self.audiobooksFolder] where !fm.fileExists(atPath: dir.path) {
            try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        // Tell people how to add music (visible in Files › On My iPhone › Spitify).
        let readme = Store.documents.appendingPathComponent("Put your music here.txt")
        if !fm.fileExists(atPath: readme.path) {
            try? "Drop music into the Music folder and audiobooks into Audiobooks — from the Files app, AirDrop, or Finder (iPhone › Files) on a Mac. Spitify picks them up automatically.\n".write(to: readme, atomically: true, encoding: .utf8)
        }
    }

    // MARK: - Scanning

    nonisolated static let audioExtensions: Set<String> = ["mp3", "m4a", "m4b", "aac", "alac", "flac", "wav", "aif", "aiff", "aifc", "caf", "mp4", "ac3", "eac3", "amr", "3gp", "ogg", "oga", "opus", "wma", "ape", "wv", "dsf"]

    func scan() async {
        if scanning { return }
        scanning = true
        defer { scanning = false }
        let docs = Store.documents
        var found: [String: CacheEntry] = [:]
        let keys: [URLResourceKey] = [.contentModificationDateKey, .fileSizeKey, .isRegularFileKey, .creationDateKey]
        let urls: [URL] = await Task.detached {
            guard let e = FileManager.default.enumerator(at: docs, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles]) else { return [] }
            return e.allObjects.compactMap { $0 as? URL }.filter { LibraryStore.audioExtensions.contains($0.pathExtension.lowercased()) }
        }.value
        for url in urls {
            let rel = String(url.standardizedFileURL.path.dropFirst(docs.standardizedFileURL.path.count + 1))
            let values = try? url.resourceValues(forKeys: Set(keys))
            let modified = values?.contentModificationDate ?? .distantPast
            let size = Int64(values?.fileSize ?? 0)
            let fileSuffix = url.pathExtension.lowercased()
            let ext = MusicResourceLoader.audioExtension(at: url) ?? fileSuffix
            if var c = cache[rel], c.modified == modified, c.size == size {
                c.song.fileExtension = ext; found[rel] = c; continue
            }
            let tags = await TagReader.read(url)
            let parentFolder = (rel as NSString).deletingLastPathComponent
            let folderName = (parentFolder as NSString).lastPathComponent
            let isBook = rel.lowercased().hasPrefix("audiobooks/") || fileSuffix == "m4b"
            let base = url.deletingPathExtension().lastPathComponent
            let artist = tags.artist ?? "Unknown artist"
            var song = Song(
                id: stableId("file:" + rel),
                title: tags.title ?? base,
                artist: artist,
                album: tags.album ?? (folderName.isEmpty || folderName == "Music" ? "Unknown album" : folderName),
                albumArtist: tags.albumArtist ?? artist,
                durationMs: tags.durationMs,
                track: tags.track ?? Int(base.prefix(while: \.isNumber)) ?? 0,
                disc: tags.disc ?? 1,
                year: tags.year ?? 0,
                genre: tags.genre,
                location: rel, kind: .file,
                dateAdded: values?.creationDate ?? modified,
                sizeBytes: size, fileExtension: ext)
            song.isAudiobook = isBook
            song.isPodcast = isBook
            if let art = tags.artwork { ArtCache.shared.storeEmbedded(art, key: song.albumKey) }
            found[rel] = CacheEntry(modified: modified, size: size, song: song, hasArt: tags.artwork != nil)
        }
        cache = found
        Store.save(cache, "scanCache")
        var songs = found.values.map(\.song)
        if includeMusicLibrary { songs += await musicLibrarySongs() }
        rawSongs = songs
        lastScanFound = songs.count
        rebuild()
        SpitifyShortcuts.updateAppShortcutParameters()
        loaded = true
        onScanCompleted?()
    }

    /// Downloaded, DRM-free songs from the Music app. (Streaming-only / protected tracks can't be played by other apps.)
    private func musicLibrarySongs() async -> [Song] {
        if MPMediaLibrary.authorizationStatus() == .notDetermined {
            _ = await withCheckedContinuation { c in MPMediaLibrary.requestAuthorization { c.resume(returning: $0) } }
        }
        guard MPMediaLibrary.authorizationStatus() == .authorized else { return [] }
        let items = MPMediaQuery.songs().items ?? []
        return items.compactMap { item in
            guard item.assetURL != nil, !item.hasProtectedAsset, !item.isCloudItem else { return nil }
            let artist = item.artist ?? "Unknown artist"
            var s = Song(id: "am\(item.persistentID)", title: item.title ?? "Unknown", artist: artist,
                         album: item.albumTitle ?? "Unknown album", albumArtist: item.albumArtist ?? artist,
                         durationMs: Int64(item.playbackDuration * 1000), track: item.albumTrackNumber, disc: max(1, item.discNumber),
                         year: item.releaseDate.map { Calendar.current.component(.year, from: $0) } ?? 0,
                         genre: item.genre, location: String(item.persistentID), kind: .musicLibrary,
                         dateAdded: item.dateAdded, sizeBytes: 0, fileExtension: "m4a")
            s.isAudiobook = item.mediaType == .audioBook
            s.isPodcast = item.mediaType == .podcast || s.isAudiobook
            if !ArtCache.shared.hasArt(s.albumKey), let img = item.artwork?.image(at: CGSize(width: 600, height: 600)), let data = img.jpegData(compressionQuality: 0.85) {
                ArtCache.shared.storeEmbedded(data, key: s.albumKey)
            }
            return s
        }
    }

    func rebuild() {
        let applied = rawSongs.map { apply($0) }
        books = applied.filter(\.isAudiobook)
        let saved = MusicStreams.shared.savedSongs
        let savedBySong = Dictionary(grouping: saved, by: Library.songMatchKey)
        let local = applied.filter { !$0.isSpoken }.map { Library.completeAlbumDetails($0, from: savedBySong[Library.songMatchKey($0)] ?? []) }
        let localBySong = Dictionary(grouping: local, by: Library.songMatchKey)
        let remote = saved.filter { stream in !(localBySong[Library.songMatchKey(stream)] ?? []).contains { Library.isDownloadedCopy($0, of: stream) } }
        library = Library.build(local + remote)
        onTasteInputChanged?(); onWidgetDataChanged?()
    }

    private func apply(_ s: Song) -> Song {
        guard let o = overrides[s.id] else { return s }
        var x = s
        x.title = o.title ?? x.title; x.artist = o.artist ?? x.artist; x.album = o.album ?? x.album
        x.albumArtist = o.albumArtist ?? (o.album != nil ? (o.artist ?? x.albumArtist) : x.albumArtist)
        x.genre = o.genre ?? x.genre; x.year = o.year ?? x.year; x.track = o.track ?? x.track; x.disc = o.disc ?? x.disc
        return x
    }

    func fileURL(_ s: Song) -> URL? {
        switch s.kind {
        case .file: return Store.documents.appendingPathComponent(s.location)
        case .musicLibrary:
            guard let pid = UInt64(s.location) else { return nil }
            let q = MPMediaQuery.songs()
            q.addFilterPredicate(MPMediaPropertyPredicate(value: NSNumber(value: pid), forProperty: MPMediaItemPropertyPersistentID))
            return q.items?.first?.assetURL
        case .remote: return URL(string: s.location)
        }
    }

    // MARK: - Import

    /// Copies picked files/folders (from Files, iCloud Drive, USB drives…) into the Music or Audiobooks folder.
    func importItems(_ urls: [URL], asAudiobooks: Bool) async -> Int {
        let destRoot = asAudiobooks ? Self.audiobooksFolder : Store.documents.appendingPathComponent("Music", isDirectory: true)
        var copied = 0
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            var isDir: ObjCBool = false
            FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir)
            let dest = destRoot.appendingPathComponent(url.lastPathComponent)
            if FileManager.default.fileExists(atPath: dest.path) { continue }
            do { try FileManager.default.copyItem(at: url, to: dest); copied += 1 } catch { print("Import failed: \(error)") }
        }
        await scan()
        return copied
    }

    // MARK: - Personal data

    func isLiked(_ id: String) -> Bool { liked[id] != nil }
    func toggleLike(_ id: String) { if liked[id] != nil { liked[id] = nil } else { liked[id] = Date() } }
    var likedSongs: [Song] { liked.sorted { $0.value > $1.value }.compactMap { library.songById[$0.key] } }

    func record(_ l: Listen) {
        listens.append(l)
        onRecorded?(l)
        let cutoff = Date().addingTimeInterval(-400 * 86_400)
        if listens.count > 20_000 { listens.removeAll { $0.at < cutoff } }
    }

    func playCount(_ id: String) -> Int { playCounts[id] ?? 0 }
    /// This device's own plays plus every other linked device's counts.
    var playCounts: [String: Int] {
        var counts = Dictionary(grouping: listens.filter { !$0.skipped && $0.device == nil }, by: \.songId).mapValues(\.count)
        guard !remotePlays.isEmpty else { return counts }
        var totals: [String: Int] = [:]
        for plays in remotePlays.values { for (key, n) in plays { totals[key, default: 0] += n } }
        for (id, key) in syncKeys { if let n = totals[key], n > 0 { counts[id, default: 0] += n } }
        return counts
    }
    /// Listens synced from other devices, kept in time order.
    func insertListens(_ added: [Listen]) {
        guard !added.isEmpty else { return }
        listens = (listens + added).sorted { $0.at < $1.at }
    }
    func lastPlayed(_ id: String) -> Date? { listens.last { $0.songId == id && !$0.skipped }?.at }

    var recentlyPlayed: [Song] {
        var seen = Set<String>(); var out: [Song] = []
        for l in listens.reversed() where !l.skipped && seen.insert(l.songId).inserted {
            if let s = library.songById[l.songId] { out.append(s) }
            if out.count >= 50 { break }
        }
        return out
    }
    var recentlyAdded: [Song] { Array(library.songs.sorted { $0.dateAdded > $1.dateAdded }.prefix(100)) }

    func createPlaylist(_ name: String, songs: [Song] = []) -> Playlist {
        let p = Playlist(id: UUID().uuidString, name: name.isEmpty ? "My playlist #\(playlists.count + 1)" : name, songIds: songs.map(\.id), createdAt: Date(), updatedAt: Date())
        playlists.insert(p, at: 0)
        return p
    }
    func add(_ songs: [Song], to id: String) {
        guard let i = playlists.firstIndex(where: { $0.id == id }) else { return }
        playlists[i].songIds += songs.map(\.id); playlists[i].updatedAt = Date()
    }
    func remove(at index: Int, from id: String) {
        guard let i = playlists.firstIndex(where: { $0.id == id }), playlists[i].songIds.indices.contains(index) else { return }
        playlists[i].songIds.remove(at: index); playlists[i].updatedAt = Date()
    }
    func rename(_ id: String, to name: String) { if let i = playlists.firstIndex(where: { $0.id == id }) { playlists[i].name = name } }
    func playlistArtworkKey(_ id: String) -> String? {
        let _ = artVersion
        let key = "playlist:" + id
        return FileManager.default.fileExists(atPath: ArtCache.shared.customURL(key).path) ? key : nil
    }
    func deletePlaylist(_ id: String) { playlists.removeAll { $0.id == id }; ArtCache.shared.removeCustom("playlist:" + id) }
    func songs(of p: Playlist) -> [Song] { entries(of: p).map(\.song) }
    /// Playable entries with their position in the saved list; missing songs are skipped, so the
    /// displayed index isn't the saved index and removals must use [raw].
    func entries(of p: Playlist) -> [(raw: Int, song: Song)] {
        p.songIds.enumerated().compactMap { i, id in (library.songById[id] ?? MusicStreams.shared.lookup(id)).map { (i, $0) } }
    }

    func saveFiles(_ edit: MetadataOverride, for songs: [Song], artwork: Data? = nil) async throws {
        guard songs.allSatisfy({ $0.kind == .file }) else {
            throw NSError(domain: "Spitify.FileTags", code: 2, userInfo: [NSLocalizedDescriptionKey: "Copy this song into Spitify's Music folder first. Apple Music files cannot be changed here."])
        }
        for song in songs {
            try await FileTags.shared.write(Store.documents.appendingPathComponent(song.location), edit: edit, artwork: artwork)
            cache[song.location] = nil
            saveOverride(edit, for: [song])
        }
        await scan()
    }

    func saveMissingOverride(_ edit: MetadataOverride, for song: Song) {
        var saved = overrides[song.id] ?? MetadataOverride(source: "online")
        saved.title = saved.title ?? edit.title; saved.artist = saved.artist ?? edit.artist
        saved.album = saved.album ?? edit.album; saved.albumArtist = saved.albumArtist ?? edit.albumArtist
        saved.genre = saved.genre ?? edit.genre; saved.year = saved.year ?? edit.year
        saved.track = saved.track ?? edit.track; saved.disc = saved.disc ?? edit.disc
        overrides[song.id] = saved
    }

    func saveOverride(_ o: MetadataOverride, for songs: [Song]) {
        var all = overrides
        for s in songs {
            if o.source == "online", all[s.id]?.source == "user" { continue }
            var merged = all[s.id] ?? MetadataOverride(source: o.source)
            merged.title = o.title ?? merged.title; merged.artist = o.artist ?? merged.artist; merged.album = o.album ?? merged.album
            merged.albumArtist = o.albumArtist ?? merged.albumArtist; merged.genre = o.genre ?? merged.genre; merged.year = o.year ?? merged.year
            merged.track = o.track ?? merged.track; merged.disc = o.disc ?? merged.disc; merged.source = o.source
            all[s.id] = merged
        }
        overrides = all
    }
    func resetOverrides(_ songs: [Song]) { var all = overrides; songs.forEach { all[$0.id] = nil }; overrides = all }
    func rawSong(_ id: String) -> Song? { rawSongs.first { $0.id == id } }
}
