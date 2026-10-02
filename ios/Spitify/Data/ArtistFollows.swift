import Foundation
import Observation
import UserNotifications

struct ArtistReleaseNotice: Codable, Identifiable {
    var id: String { artist.id + ":" + album.id }
    var artist: OnlineArtist
    var album: OnlineAlbum
    var foundAt: Date
}

@MainActor @Observable
final class ArtistFollows {
    private struct Saved: Codable {
        var artists: [OnlineArtist] = []
        var known: [String: Set<String>] = [:]
        var releases: [ArtistReleaseNotice] = []
    }
    private var saved = Store.load(Saved.self, "artistFollows") ?? Saved()
    private(set) var refreshing = false
    var notifications = UserDefaults.standard.bool(forKey: "artistNotifications")
    var message: String?
    var artists: [OnlineArtist] { saved.artists }
    var releases: [ArtistReleaseNotice] { saved.releases.sorted { ($0.album.releaseDate ?? "", $0.foundAt) > ($1.album.releaseDate ?? "", $1.foundAt) } }
    func contains(_ id: String) -> Bool { saved.artists.contains { $0.id == id } }
    func follow(_ artist: OnlineArtist, releases: [OnlineAlbum]) {
        guard !contains(artist.id), saved.artists.count < 128 else { return }
        saved.artists.append(artist)
        saved.known[artist.id] = Set(releases.map(\.id))
        for album in releases.prefix(10) { saved.releases.append(.init(artist: artist, album: album, foundAt: Date())) }
        persist()
    }
    func unfollow(_ id: String) { saved.artists.removeAll { $0.id == id }; saved.known.removeValue(forKey: id); saved.releases.removeAll { $0.artist.id == id }; persist() }
    func setNotifications(_ enabled: Bool) async {
        if enabled {
            do { notifications = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) }
            catch { notifications = false; message = "Notifications couldn't be enabled." }
            if !notifications { message = "Allow notifications for Spitify in Settings to receive release alerts." }
        } else { notifications = false }
        UserDefaults.standard.set(notifications, forKey: "artistNotifications")
    }
    func refresh() async {
        guard !refreshing, !artists.isEmpty else { return }
        message = nil
        refreshing = true; defer { refreshing = false }
        for artist in artists {
            guard !Task.isCancelled else { return }
            do {
                let page = try await MonochromeClient().artistPage(artist.id)
                guard contains(artist.id) else { continue }
                let known = saved.known[artist.id] ?? []
                let fresh = page.albums.filter { !known.contains($0.id) }
                saved.known[artist.id, default: []].formUnion(page.albums.map(\.id))
                for album in fresh {
                    saved.releases.append(.init(artist: artist, album: album, foundAt: Date()))
                    if notifications, let date = album.releaseDate.flatMap({ ISO8601DateFormatter().date(from: $0.replacingOccurrences(of: ".000Z", with: "Z")) }), date >= Date().addingTimeInterval(-14 * 86400), date <= Date() {
                        let content = UNMutableNotificationContent(); content.title = "New from \(artist.name)"; content.body = album.title
                        content.userInfo = ["artistID": artist.id, "releaseID": album.id]
                        try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "release:" + artist.id + ":" + album.id, content: content, trigger: nil))
                    }
                }
                persist()
            } catch { message = "Some artists couldn't refresh. Pull down to try again." }
        }
    }
    private func persist() { if saved.releases.count > 500 { saved.releases = Array(saved.releases.suffix(500)) }; Store.save(saved, "artistFollows") }
}
