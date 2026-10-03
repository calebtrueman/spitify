import Foundation

/// Keeps the next few songs fresh without putting suggestions ahead of the listener's picks.
enum PlaybackContinuation {
    static func songs(seed: Song, candidates: [Song], history: [Song], upcoming: [Song],
                      hiddenSongs: Set<String> = [], hiddenArtists: Set<String> = [],
                      failed: Set<String> = [], count: Int = 5) -> [Song] {
        let queued = Set(upcoming.map(\.id))
        var seen = Set<String>()
        let usable = candidates.filter { song in
            song.playable && !song.isSpoken && !hiddenSongs.contains(song.id)
                && !hiddenArtists.contains(song.artist) && !song.creditedArtists.contains(where: hiddenArtists.contains)
                && !failed.contains(song.id) && !queued.contains(song.id) && seen.insert(song.id).inserted
        }
        let recent = Set(history.suffix(40).map(\.id))
        let fresh = usable.filter { !recent.contains($0.id) && $0.id != seed.id }
        let older = usable.filter { recent.contains($0.id) && $0.id != seed.id }
        // A small library still keeps playing; never fill a queue with duplicate copies of one song.
        return Array((fresh + older + usable.filter { $0.id == seed.id }).prefix(max(0, count)))
    }
}
