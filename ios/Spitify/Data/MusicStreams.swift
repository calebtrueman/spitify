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
        if tracks[track.id] != track { tracks[track.id] = track; Store.save(tracks, storageName + "Tracks") }
        return Self.song(track)
    }
    func save(_ items: [OnlineTrack]) { for track in items { register(track); if savedIDs.insert(track.id).inserted { savedAt[track.id] = Date() } }; changed() }
    func remove(_ items: [OnlineTrack]) { for track in items { savedIDs.remove(track.id); savedAt.removeValue(forKey: track.id) }; changed() }
    private func changed() { Store.save(savedIDs, storageName + "Library"); Store.save(savedAt, storageName + "SavedAt"); onChanged?() }
    var savedSongs: [Song] { savedIDs.sorted().compactMap { tracks[$0] }.map { track in
        var song = Self.song(track); song.dateAdded = savedAt[track.id] ?? .distantPast; return song
    } }
    func lookup(_ id: String) -> Song? { guard id.hasPrefix("stream:") else { return nil }; return tracks[String(id.dropFirst(7))].map(Self.song) }
    func track(_ song: Song) -> OnlineTrack? { guard song.id.hasPrefix("stream:") else { return nil }; return tracks[String(song.id.dropFirst(7))] }
    static func song(_ track: OnlineTrack) -> Song {
        Song(id: "stream:" + track.id, title: track.title, artist: track.artist, album: track.album,
             albumArtist: track.albumArtist ?? track.primaryArtist, durationMs: track.durationMs,
             track: track.trackNumber, disc: track.discNumber, year: 0, genre: nil,
             location: "spitify://music/" + track.id, kind: .remote, dateAdded: Date(timeIntervalSince1970: 0),
             sizeBytes: 0, fileExtension: track.audioExtension ?? "flac", artURL: track.artwork, explicit: track.explicit, artistNames: track.artistNames)
    }
}
