import Foundation
import Observation

/// Saving a song keeps its details. Listening does not add it to the library or create an offline download.
@MainActor @Observable
final class MusicStreams {
    static let shared = MusicStreams()
    private(set) var tracks: [String: OnlineTrack]
    private(set) var savedIDs: Set<String>
    private var savedAt: [String: Date]
    var onChanged: (() -> Void)?
    private let storageName: String
    /// Built Songs, so views and the player reuse one value instead of rebuilding it per lookup.
    @ObservationIgnored private var songCache: [String: Song] = [:]
    /// Every track ever seen is one JSON file; a search page registers dozens at once, so saves are coalesced.
    @ObservationIgnored private var saveTask: Task<Void, Never>?
    init(storageName: String = "stream") {
        self.storageName = storageName
        tracks = Store.load([String: OnlineTrack].self, storageName + "Tracks") ?? [:]
        savedIDs = Store.load(Set<String>.self, storageName + "Library") ?? []
        savedAt = Store.load([String: Date].self, storageName + "SavedAt") ?? [:]
    }
    @discardableResult func register(_ incoming: OnlineTrack) -> Song {
        var track = incoming
        if let previous = tracks[track.id] {
            if track.album.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { track.album = previous.album }
            if track.releaseID.isEmpty { track.releaseID = previous.releaseID }
            if track.albumArtist == nil { track.albumArtist = previous.albumArtist }
            if track.artistNames == nil { track.artistNames = previous.artistNames }
            if track.artwork == nil { track.artwork = previous.artwork }
        }
        if tracks[track.id] != track { tracks[track.id] = track; songCache[track.id] = nil; scheduleTrackSave() }
        return cachedSong(track)
    }
    private func scheduleTrackSave() {
        guard saveTask == nil else { return }
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(500))
            guard let self else { return }
            self.saveTask = nil
            let snapshot = self.tracks, name = self.storageName + "Tracks"
            let file = Store.directory.appendingPathComponent("\(name).json")
            Task.detached(priority: .utility) {
                let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .millisecondsSince1970
                if let data = try? encoder.encode(snapshot) { try? data.write(to: file, options: .atomic) }
            }
        }
    }
    private func cachedSong(_ track: OnlineTrack) -> Song {
        if let song = songCache[track.id] { return song }
        let song = Self.song(track); songCache[track.id] = song; return song
    }
    func save(_ items: [OnlineTrack]) { for track in items { register(track); if savedIDs.insert(track.id).inserted { savedAt[track.id] = Date() } }; changed() }
    func remove(_ items: [OnlineTrack]) { for track in items { savedIDs.remove(track.id); savedAt.removeValue(forKey: track.id) }; changed() }
    private func changed() { Store.save(savedIDs, storageName + "Library"); Store.save(savedAt, storageName + "SavedAt"); onChanged?() }
    var savedSongs: [Song] { savedIDs.sorted().compactMap { tracks[$0] }.map { track in
        var song = Self.song(track); song.dateAdded = savedAt[track.id] ?? .distantPast; return song
    } }
    func lookup(_ id: String) -> Song? { guard id.hasPrefix("stream:") else { return nil }; return tracks[String(id.dropFirst(7))].map(cachedSong) }
    func track(_ song: Song) -> OnlineTrack? { guard song.id.hasPrefix("stream:") else { return nil }; return tracks[String(song.id.dropFirst(7))] }
    static func song(_ track: OnlineTrack) -> Song {
        Song(id: "stream:" + track.id, title: track.title, artist: track.artist, album: track.album,
             albumArtist: track.albumArtist ?? track.primaryArtist, durationMs: track.durationMs,
             track: track.trackNumber, disc: track.discNumber, year: 0, genre: nil,
             location: "spitify://music/" + track.id, kind: .remote, dateAdded: Date(timeIntervalSince1970: 0),
             sizeBytes: 0, fileExtension: track.audioExtension ?? "flac", artURL: track.artwork, explicit: track.explicit, artistNames: track.artistNames)
    }
}
