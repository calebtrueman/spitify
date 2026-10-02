import Foundation
import CryptoKit

struct SharedTrack: Codable, Hashable, Identifiable {
    var id: String = UUID().uuidString.lowercased()
    var title: String
    var artist: String
    var album: String = ""
    var durationMs: Int64 = 0
    var sourceID: String? = nil
    var releaseID: String? = nil
    var spotifyID: String? = nil
    var isrc: String? = nil
    var artwork: String? = nil
    var recordingKey: String { (isrc?.isEmpty == false ? isrc! : "\(title)|\(artist)").folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_US_POSIX")) }
    func valid() -> Bool {
        !id.isEmpty && id.count <= 160 && !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && title.count <= 500 && !artist.isEmpty && artist.count <= 500 && album.count <= 500 && (0...86_400_000).contains(durationMs)
        && (sourceID == nil || (!sourceID!.isEmpty && sourceID!.count <= 30 && sourceID!.allSatisfy { $0 >= "0" && $0 <= "9" })) && (artwork == nil || SocialRules.publicURL(artwork!))
    }
    @MainActor static func from(_ song: Song) -> SharedTrack {
        let remote = MusicStreams.shared.track(song)
        return SharedTrack(title: song.title, artist: song.artist, album: song.album, durationMs: song.durationMs,
                           sourceID: remote?.id, releaseID: remote?.releaseID, artwork: song.artURL.flatMap { SocialRules.publicURL($0) ? $0 : nil })
    }
}

struct SharedPlaylist: Codable, Hashable, Identifiable {
    var id: String = UUID().uuidString.lowercased()
    var owner: String
    var name: String
    var description: String = ""
    var image: String? = nil
    var sourceURL: String? = nil
    var sourceName: String? = nil
    var sourceCount: Int? = nil
    var partial: Bool = false
    var kind: String = "playlist"
    var editors: [String] = []
    var tracks: [SharedTrack] = []
    var revision: Int64 = 1
    var updatedAt: Int64 = SocialRules.now
    var isPublic: Bool = false
    var key: String { owner + ":" + id }
    func valid() -> Bool {
        SocialRules.key(owner) && !id.isEmpty && id.count <= 100 && !name.isEmpty && name.count <= 200 && description.count <= 4_000
        && tracks.count <= 2_000 && Set(tracks.map(\.id)).count == tracks.count && tracks.allSatisfy { $0.valid() }
        && editors.count <= 32 && editors.allSatisfy(SocialRules.key) && revision > 0 && revision < 9_000_000_000_000_000
        && ["playlist", "album", "song", "mix"].contains(kind)
        && (image == nil || SocialRules.publicURL(image!)) && (sourceURL == nil || SocialRules.publicURL(sourceURL!))
    }
}

struct FriendProfile: Codable, Hashable, Identifiable {
    var id: String
    var name: String
    var about: String = ""
    var image: String? = nil
    var updatedAt: Int64 = SocialRules.now
    func valid() -> Bool { SocialRules.key(id) && !name.isEmpty && name.count <= 80 && about.count <= 500 && (image == nil || SocialRules.publicURL(image!)) }
}

struct SharedEdit: Codable, Identifiable {
    var id = UUID().uuidString.lowercased()
    var playlistID: String
    var owner: String
    var action: String
    var tracks: [SharedTrack] = []
    var trackIDs: [String] = []
    var name: String? = nil
    var description: String? = nil
    var createdAt = SocialRules.now
    func valid() -> Bool { id.count <= 100 && SocialRules.key(owner) && playlistID.count <= 100 && tracks.count <= 100 && tracks.allSatisfy { $0.valid() } && trackIDs.count <= 2_000 && (name?.count ?? 0) <= 200 && (description?.count ?? 0) <= 4_000 && ["add", "remove", "reorder", "rename", "mix"].contains(action) }
}

struct ListeningRoom: Codable, Hashable, Identifiable {
    var id = UUID().uuidString.lowercased()
    var host: String
    var name: String
    var members: [String] = []
    var queue: [SharedTrack] = []
    var currentID: String? = nil
    var positionMs: Int64 = 0
    var playing = false
    var speed: Float? = nil
    var observedAt = SocialRules.now
    var expiresAt = SocialRules.now + 12 * 60 * 60 * 1000
    var revision: Int64 = 1
    var allowControls = false
    var ended = false
    var key: String { host + ":" + id }
    func valid() -> Bool { SocialRules.key(host) && id.count <= 100 && name.count <= 200 && members.count <= 32 && members.allSatisfy(SocialRules.key) && queue.count <= 200 && queue.allSatisfy { $0.valid() } && Set(queue.map(\.id)).count == queue.count && positionMs >= 0 && positionMs <= 86_400_000 && revision > 0 && revision < 9_000_000_000_000_000 && observedAt <= SocialRules.now + 300_000 && (speed == nil || speed!.isFinite && (0.25...3).contains(speed!)) }
    var live: Bool { !ended && expiresAt > SocialRules.now }
}

struct RoomRequest: Codable, Identifiable {
    var id = UUID().uuidString.lowercased()
    var roomID: String
    var host: String
    var action: String
    var name: String = ""
    var tracks: [SharedTrack] = []
    var trackID: String? = nil
    var positionMs: Int64? = nil
    var playing: Bool? = nil
    var createdAt = SocialRules.now
    func valid() -> Bool { id.count <= 100 && roomID.count <= 100 && SocialRules.key(host) && name.count <= 80 && tracks.count <= 100 && tracks.allSatisfy { $0.valid() } && (positionMs == nil || (0...86_400_000).contains(positionMs!)) && ["join", "leave", "add", "remove", "control", "next"].contains(action) }
}

struct IncomingRoomRequest: Identifiable { var sender: String; var request: RoomRequest; var id: String { request.id } }

struct SocialPacket: Codable {
    var v = 1
    var type: String
    var body: Data
    static func make<T: Encodable>(_ type: String, _ value: T) throws -> SocialPacket { SocialPacket(type: type, body: try JSONEncoder().encode(value)) }
    func decode<T: Decodable>(_ type: T.Type) throws -> T { try JSONDecoder().decode(type, from: body) }
}

struct SocialPart: Codable {
    var transfer: String
    var index: Int
    var total: Int
    var digest: String
    var content: Data
}

struct SocialLink {
    var type: String
    var owner: String
    var id: String?
    var url: URL { var c = URLComponents(); c.scheme = "spitify"; c.host = type; c.path = "/" + owner + (id.map { "/" + $0 } ?? ""); return c.url! }
    static func parse(_ value: String) -> SocialLink? {
        let text = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if SocialRules.key(text) { return SocialLink(type: "person", owner: text) }
        guard let c = URLComponents(string: text), c.scheme == "spitify", let kind = c.host, ["person", "playlist", "room"].contains(kind) else { return nil }
        let parts = c.path.split(separator: "/").map(String.init)
        guard let key = parts.first, SocialRules.key(key), parts.count == (kind == "person" ? 1 : 2), parts.allSatisfy({ $0.count <= 100 }) else { return nil }
        return SocialLink(type: kind, owner: key, id: parts.count == 2 ? parts[1] : nil)
    }
}

enum SocialRules {
    static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
    static func key(_ value: String) -> Bool { value.count == 64 && value.allSatisfy { "0123456789abcdef".contains($0) } }
    static func hash(_ value: Data) -> String { SHA256.hash(data: value).map { String(format: "%02x", $0) }.joined() }
    static func publicURL(_ value: String) -> Bool {
        guard value.count <= 2_000, let c = URLComponents(string: value), c.scheme == "https", c.user == nil, c.password == nil, c.port == nil || c.port == 443, let host = c.host?.lowercased(), host.contains("."), !host.hasSuffix(".local"), !host.hasSuffix(".localhost"), !host.contains(":"), host != "localhost" else { return false }
        let numbers = host.split(separator: ".").compactMap { Int($0) }
        if numbers.count == 4 { return !(numbers[0] == 10 || numbers[0] == 127 || numbers[0] == 0 || numbers[0] == 169 || numbers[0] == 192 && numbers[1] == 168 || numbers[0] == 172 && (16...31).contains(numbers[1])) }
        return true
    }
    static func mix(_ contributions: [[SharedTrack]]) -> [SharedTrack] {
        var seen = Set<String>(); var result: [SharedTrack] = []
        for index in 0..<(contributions.map(\.count).max() ?? 0) {
            for list in contributions where index < list.count {
                var track = list[index]
                if seen.insert(track.recordingKey).inserted { track.id = UUID().uuidString.lowercased(); result.append(track) }
                if result.count >= 200 { return result }
            }
        }
        return result
    }
}
