import Foundation

/// Saved music and permissions. Receiving a message never starts audio by itself.
struct SocialState: Codable {
    var following: Set<String> = []
    var profiles: [String: FriendProfile] = [:]
    var playlists: [String: SharedPlaylist] = [:]
    var recipients: [String: Set<String>] = [:]
    var handledEdits: [String] = []
    var contributions: [String: [String: [SharedTrack]]] = [:]

    mutating func acceptProfile(_ profile: FriendProfile, author: String) -> Bool {
        guard profile.valid(), profile.id == author, profile.updatedAt <= SocialRules.now + 300_000,
              profiles[author] == nil || profiles[author]!.updatedAt < profile.updatedAt,
              profiles.count < 500 || profiles[author] != nil else { return false }
        profiles[author] = profile; return true
    }

    mutating func acceptPlaylist(_ playlist: SharedPlaylist, author: String, me: String, encrypted: Bool) -> Bool {
        guard playlist.valid(), playlist.owner == author, author != me,
              playlist.updatedAt <= SocialRules.now + 300_000,
              encrypted || playlist.isPublic,
              following.contains(author) || playlists[playlist.key] != nil,
              playlists[playlist.key] == nil || playlists[playlist.key]!.revision < playlist.revision,
              playlists.count < 500 || playlists[playlist.key] != nil else { return false }
        playlists[playlist.key] = playlist; return true
    }

    mutating func apply(_ edit: SharedEdit, author: String, me: String) -> SharedPlaylist? {
        let key = me + ":" + edit.playlistID
        let requestKey = author + ":" + edit.id
        guard edit.valid(), edit.owner == me, !edit.id.isEmpty, !edit.playlistID.isEmpty,
              edit.createdAt <= SocialRules.now + 300_000, edit.createdAt >= SocialRules.now - 30 * 24 * 60 * 60 * 1000,
              !handledEdits.contains(requestKey), var playlist = playlists[key], playlist.owner == me,
              author == me || playlist.editors.contains(author) else { return nil }
        switch edit.action {
        case "add":
            guard playlist.tracks.count + edit.tracks.count <= 2_000 else { return nil }
            let existing = Set(playlist.tracks.map(\.id))
            playlist.tracks += edit.tracks.filter { !existing.contains($0.id) }
        case "remove": playlist.tracks.removeAll { edit.trackIDs.contains($0.id) }
        case "reorder":
            guard edit.trackIDs.count == playlist.tracks.count, Set(edit.trackIDs) == Set(playlist.tracks.map(\.id)) else { return nil }
            let lookup = Dictionary(uniqueKeysWithValues: playlist.tracks.map { ($0.id, $0) })
            playlist.tracks = edit.trackIDs.compactMap { lookup[$0] }
        case "rename":
            if let name = edit.name { playlist.name = name }
            if let description = edit.description { playlist.description = description }
        case "mix":
            guard playlist.kind == "mix" else { return nil }
            var people = contributions[key] ?? [:]; people[author] = edit.tracks
            let permitted = Set(playlist.editors + [me])
            people = people.filter { permitted.contains($0.key) }
            playlist.tracks = SocialRules.mix(people.keys.sorted().compactMap { people[$0] })
            contributions[key] = people
        default: return nil
        }
        playlist.revision += 1; playlist.updatedAt = SocialRules.now
        guard playlist.valid() else { return nil }
        playlists[key] = playlist
        handledEdits.append(requestKey)
        if handledEdits.count > 4_000 { handledEdits.removeFirst(handledEdits.count - 4_000) }
        return playlist
    }
}
