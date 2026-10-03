import Foundation

struct WidgetMusicItem: Codable, Equatable, Identifiable {
    var id: String
    var title: String
    var subtitle: String
    var artwork: Data?
}

struct WidgetMusicSnapshot: Codable, Equatable {
    var current: WidgetMusicItem?
    var isPlaying = false
    var playlists: [WidgetMusicItem] = []
    var albums: [WidgetMusicItem] = []
    var mostPlayed: [WidgetMusicItem] = []
    var recentlyPlayed: [WidgetMusicItem] = []
    var recentlyAdded: [WidgetMusicItem] = []
    var liked: [WidgetMusicItem] = []

    func items(for shelf: String) -> [WidgetMusicItem] {
        switch shelf {
        case "playlists": return playlists
        case "albums": return albums
        case "most-played": return mostPlayed
        case "recently-played": return recentlyPlayed
        case "recently-added": return recentlyAdded
        case "liked": return liked
        default: return []
        }
    }
}

enum WidgetMusicStore {
    static let originalGroup = "group.com.calebtrueman.spitify"
    static let fileName = "widget-music.json"

    // SideStore changes the group name when it signs an app for a different team.
    // Read that signed profile instead of guessing the user's team identifier.
    static func groupNames(profile: Data?) -> [String] {
        guard let profile, profile.count < 4_000_000,
              let start = profile.range(of: Data("<plist".utf8)),
              let end = profile.range(of: Data("</plist>".utf8), in: start.lowerBound..<profile.endIndex),
              let root = try? PropertyListSerialization.propertyList(from: profile.subdata(in: start.lowerBound..<end.upperBound), options: 0, format: nil) as? [String: Any],
              let entitlements = root["Entitlements"] as? [String: Any],
              let groups = entitlements["com.apple.security.application-groups"] as? [String] else { return [] }
        return groups.filter { $0 == originalGroup || $0.hasPrefix(originalGroup + ".") }
    }

    static var directory: URL? {
        let profile = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision").flatMap { try? Data(contentsOf: $0) }
        for group in groupNames(profile: profile) + [originalGroup] {
            if let directory = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) { return directory }
        }
        return nil
    }

    static func read(from directory: URL? = directory) -> WidgetMusicSnapshot {
        guard let directory,
              let data = try? Data(contentsOf: directory.appendingPathComponent(fileName)), data.count <= 6_000_000,
              let snapshot = try? JSONDecoder().decode(WidgetMusicSnapshot.self, from: data) else { return WidgetMusicSnapshot() }
        return snapshot
    }

    @discardableResult static func write(_ snapshot: WidgetMusicSnapshot, to directory: URL? = directory) throws -> Bool {
        guard let directory else { return false }
        let encoder = JSONEncoder(); encoder.outputFormatting = .sortedKeys
        let data = try encoder.encode(snapshot)
        guard data.count <= 6_000_000 else { return false }
        let url = directory.appendingPathComponent(fileName)
        if (try? Data(contentsOf: url)) == data { return false }
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        return true
    }
}
