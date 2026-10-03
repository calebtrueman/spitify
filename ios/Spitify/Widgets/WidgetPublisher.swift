import UIKit
import WidgetKit

@MainActor enum WidgetPublisher {
    private static var coverCache: [String: (ObjectIdentifier, Data)] = [:]
    private static var artworkTask: Task<Void, Never>?

    static func makeSnapshot(_ app: AppModel) -> WidgetMusicSnapshot {
        let library = app.library
        func item(_ song: Song) -> WidgetMusicItem {
            WidgetMusicItem(id: "song:" + song.id, title: song.title, subtitle: song.artist, artwork: artwork(song))
        }
        var snapshot = WidgetMusicSnapshot(current: app.player.current.map(item), isPlaying: app.player.isPlaying)
        snapshot.playlists = library.playlists.sorted { $0.updatedAt > $1.updatedAt }.prefix(8).map { playlist in
            let songs = library.songs(of: playlist)
            return WidgetMusicItem(id: "playlist:" + playlist.id, title: playlist.name, subtitle: "\(songs.count) songs", artwork: songs.first.flatMap(artwork))
        }
        snapshot.albums = library.library.albums.sorted { ($0.songs.map(\.dateAdded).max() ?? .distantPast) > ($1.songs.map(\.dateAdded).max() ?? .distantPast) }.prefix(8).map {
            WidgetMusicItem(id: "album:" + $0.id, title: $0.title, subtitle: $0.artist, artwork: artwork($0.cover))
        }
        let counts = library.playCounts
        snapshot.mostPlayed = library.library.songs.filter { (counts[$0.id] ?? 0) > 0 }.sorted {
            let a = counts[$0.id] ?? 0, b = counts[$1.id] ?? 0
            return a == b ? $0.title.localizedStandardCompare($1.title) == .orderedAscending : a > b
        }.prefix(8).map(item)
        snapshot.recentlyPlayed = library.recentlyPlayed.prefix(8).map(item)
        snapshot.recentlyAdded = library.recentlyAdded.prefix(8).map(item)
        snapshot.liked = library.likedSongs.prefix(8).map(item)
        return snapshot
    }

    private static func artwork(_ song: Song) -> Data? {
        guard let image = ArtCache.shared.image(for: song.albumKey, remote: song.artURL) else { return nil }
        let id = ObjectIdentifier(image)
        if let cached = coverCache[song.albumKey], cached.0 == id { return cached.1 }
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
        let ratio = min(1, 400 / max(image.size.width, image.size.height))
        let size = CGSize(width: image.size.width * ratio, height: image.size.height * ratio)
        let copy = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        guard let data = copy.jpegData(compressionQuality: 0.78) else { return nil }
        if coverCache.count >= 128 { coverCache.removeAll() }
        coverCache[song.albumKey] = (id, data)
        return data
    }

    static func publish(_ app: AppModel) {
        guard WidgetMusicStore.directory != nil else { return }
        if (try? WidgetMusicStore.write(makeSnapshot(app))) == true { WidgetCenter.shared.reloadAllTimelines() }
    }

    static func refresh(_ app: AppModel) {
        publish(app)
        artworkTask?.cancel()
        let snapshot = makeSnapshot(app)
        let items = snapshot.playlists + snapshot.albums + snapshot.mostPlayed + snapshot.recentlyPlayed + snapshot.recentlyAdded + snapshot.liked
        var songs = items.filter { $0.artwork == nil }.compactMap { row -> Song? in
            if row.id.hasPrefix("song:") { return app.lookup(String(row.id.dropFirst(5))) }
            if row.id.hasPrefix("album:") { return app.library.library.albumById[String(row.id.dropFirst(6))]?.cover }
            if row.id.hasPrefix("playlist:"), let list = app.library.playlists.first(where: { $0.id == String(row.id.dropFirst(9)) }) { return app.library.songs(of: list).first }
            return nil
        }
        if let current = app.player.current { songs.insert(current, at: 0) }
        var seen = Set<String>()
        songs = songs.filter { $0.artURL != nil && seen.insert($0.albumKey).inserted && ArtCache.shared.image(for: $0.albumKey, remote: $0.artURL) == nil }
        artworkTask = Task {
            for song in songs.prefix(24) {
                guard !Task.isCancelled else { return }
                _ = await ArtCache.shared.load(key: song.albumKey, remote: song.artURL)
                guard !Task.isCancelled else { return }
                publish(app)
            }
        }
    }
}
