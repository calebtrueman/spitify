import Foundation
import Observation

/// Settings › Convert FLAC to AAC: re-encodes every FLAC in Spitify's folder as AAC 256 kbit/s
/// (.m4a) to save space, then deletes the FLAC. Likes, playlists, listens, edits, hidden songs and
/// resume points move to the new file (song ids come from the file path).
@MainActor @Observable
final class FlacConversion {
    enum State: Equatable {
        case idle
        case converting(done: Int, total: Int, current: String)
        case finished(converted: Int, skipped: Int, savedBytes: Int64)
    }

    private(set) var state: State = .idle
    private var task: Task<Void, Never>?

    func candidates(_ app: AppModel) -> [Song] {
        app.library.rawSongs.filter { $0.kind == .file && ($0.fileExtension.lowercased() == "flac" || $0.location.lowercased().hasSuffix(".flac")) }
    }

    /// Rough AAC size: 256 kbit/s ≈ 32 kB per second.
    func estimatedSaving(_ songs: [Song]) -> Int64 { songs.reduce(0) { $0 + max(0, $1.sizeBytes - $1.durationMs * 32) } }

    func start(_ app: AppModel) {
        guard task == nil else { return }
        let playing = app.player.current?.id
        // The song that's playing keeps its file this time; it converts on the next run.
        let songs = candidates(app).filter { $0.id != playing }
        task = Task {
            var converted = 0, skipped = 0, saved: Int64 = 0
            var replacements: [String: Song] = [:]
            for (i, song) in songs.enumerated() {
                if Task.isCancelled { break }
                state = .converting(done: i, total: songs.count, current: song.title)
                guard let source = app.library.fileURL(song) else { skipped += 1; continue }
                var newRel = (song.location as NSString).deletingPathExtension + ".m4a"
                if FileManager.default.fileExists(atPath: Store.documents.appendingPathComponent(newRel).path) {
                    newRel = (song.location as NSString).deletingPathExtension + " (AAC).m4a"
                }
                let destination = Store.documents.appendingPathComponent(newRel)
                do {
                    try await AacConverter.convert(source, to: destination)
                    let tags = await TagReader.read(source)
                    try FileTags.shared.write(destination, edit: MetadataOverride(title: tags.title, artist: tags.artist, album: tags.album,
                        albumArtist: tags.albumArtist, genre: tags.genre, year: tags.year, track: tags.track, disc: tags.disc, source: "file"), artwork: tags.artwork)
                    let size = Int64((try? FileManager.default.attributesOfItem(atPath: destination.path)[.size] as? Int) ?? 0)
                    try FileManager.default.removeItem(at: source)
                    saved += max(0, song.sizeBytes - size)
                    converted += 1
                    var replacement = song
                    replacement.id = stableId("file:" + newRel); replacement.location = newRel; replacement.fileExtension = "m4a"; replacement.sizeBytes = size
                    replacements[song.id] = replacement
                    app.musicDownloads.remapFile(from: song.location, to: newRel)
                } catch {
                    try? FileManager.default.removeItem(at: destination)
                    skipped += 1
                }
            }
            remap(replacements, app: app)
            await app.library.scan()
            state = .finished(converted: converted, skipped: skipped, savedBytes: saved)
            task = nil
        }
    }

    func cancel() { task?.cancel() }

    /// Everything that refers to a song by id follows it to the converted file.
    private func remap(_ replacements: [String: Song], app: AppModel) {
        guard !replacements.isEmpty else { return }
        let ids = replacements.mapValues(\.id)
        let library = app.library
        library.liked = Dictionary(library.liked.map { (ids[$0.key] ?? $0.key, $0.value) }, uniquingKeysWith: { a, _ in a })
        library.playlists = library.playlists.map { p in var p = p; p.songIds = p.songIds.map { ids[$0] ?? $0 }; return p }
        library.listens = library.listens.map { l in var l = l; l.songId = ids[l.songId] ?? l.songId; return l }
        library.overrides = Dictionary(library.overrides.map { (ids[$0.key] ?? $0.key, $0.value) }, uniquingKeysWith: { a, _ in a })
        library.hiddenSongs = Set(library.hiddenSongs.map { ids[$0] ?? $0 })
        app.shows.resume = Dictionary(app.shows.resume.map { (ids[$0.key] ?? $0.key, $0.value) }, uniquingKeysWith: { a, _ in a })
        app.player.remapSongs(replacements)
    }
}
