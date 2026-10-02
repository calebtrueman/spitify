import Foundation

/// Online suggestions fill holes; tags already in a file and saved choices take priority.
enum MissingMetadata {
    static func fill(_ stored: MetadataOverride, saved: MetadataOverride, suggested: MetadataOverride) -> MetadataOverride {
        func text(_ present: String?, _ saved: String?, _ suggested: String?) -> String? {
            if let present, !present.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return nil }
            return [saved, suggested].compactMap { $0 }.first { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        }
        func number(_ present: Int?, _ saved: Int?, _ suggested: Int?) -> Int? {
            if let present, present > 0 { return nil }
            return [saved, suggested].compactMap { $0 }.first { $0 > 0 }
        }
        return MetadataOverride(title: text(stored.title, saved.title, suggested.title),
            artist: text(stored.artist, saved.artist, suggested.artist), album: text(stored.album, saved.album, suggested.album),
            albumArtist: text(stored.albumArtist, saved.albumArtist, suggested.albumArtist), genre: text(stored.genre, saved.genre, suggested.genre),
            year: number(stored.year, saved.year, suggested.year), track: number(stored.track, saved.track, suggested.track),
            disc: number(stored.disc, saved.disc, suggested.disc), source: "online")
    }

    static func incomplete(_ stored: MetadataOverride, saved: MetadataOverride) -> Bool {
        let values = [stored.title ?? saved.title, stored.artist ?? saved.artist, stored.album ?? saved.album,
                      stored.albumArtist ?? saved.albumArtist, stored.genre ?? saved.genre]
        let numbers = [stored.year ?? saved.year, stored.track ?? saved.track, stored.disc ?? saved.disc]
        return values.contains { ($0 ?? "").isEmpty } || numbers.contains { ($0 ?? 0) <= 0 }
    }
}

extension Tags {
    var edit: MetadataOverride {
        MetadataOverride(title: title, artist: artist, album: album, albumArtist: albumArtist,
                         genre: genre, year: year, track: track, disc: disc, source: "online")
    }
}
