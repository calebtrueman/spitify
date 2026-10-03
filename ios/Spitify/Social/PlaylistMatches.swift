import Foundation
import Observation

@MainActor @Observable final class PlaylistMatches {
    static let shared = PlaylistMatches()
    var failed = Store.load(Set<String>.self, "failedPlaylistMatches") ?? []
    var matched: [String: Song] = [:]
    private var choices = Store.load([String: String].self, "playlistMatchChoices") ?? [:]
    static func key(_ track: SharedTrack) -> String { track.recordingKey + "|" + String(track.durationMs) }
    func manual(_ track: SharedTrack, app: AppModel) -> Song? {
        choices[Self.key(track)].flatMap { id in app.library.library.songs.first { $0.id == id } }
    }
    func choose(_ song: Song, for track: SharedTrack) {
        let key = Self.key(track); choices[key] = song.id; matched[key] = song; failed.remove(key)
        Store.save(choices, "playlistMatchChoices"); Store.save(failed, "failedPlaylistMatches")
    }
    func found(_ song: Song, for track: SharedTrack) { let key = Self.key(track); matched[key] = song; failed.remove(key); Store.save(failed, "failedPlaylistMatches") }
    func retry(_ tracks: [SharedTrack]) { failed.subtract(tracks.map(Self.key)); Store.save(failed, "failedPlaylistMatches") }
    func missing(_ track: SharedTrack) { let key = Self.key(track); matched.removeValue(forKey: key); failed.insert(key); Store.save(failed, "failedPlaylistMatches") }
}
