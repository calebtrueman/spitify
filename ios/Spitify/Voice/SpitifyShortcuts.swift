import AppIntents
import Foundation

struct SpitifyMedia: AppEntity {
    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "Spitify music or show")
    static var defaultQuery = SpitifyMediaQuery()
    var id: String
    var title: String
    var subtitle: String
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(title)", subtitle: "\(subtitle)") }
}

struct SpitifyMediaQuery: EntityStringQuery {
    @MainActor func entities(for identifiers: [String]) async throws -> [SpitifyMedia] {
        await VoiceLibrary.ready()
        let indexed = Dictionary(VoiceLibrary.items().map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        return identifiers.compactMap { indexed[$0] }
    }
    @MainActor func entities(matching string: String) async throws -> [SpitifyMedia] {
        await VoiceLibrary.ready()
        return VoiceLibrary.match(string, in: VoiceLibrary.items())
    }
    @MainActor func suggestedEntities() async throws -> [SpitifyMedia] {
        await VoiceLibrary.ready()
        return Array(VoiceLibrary.items().prefix(20))
    }
}

@MainActor enum VoiceLibrary {
    static func ready() async {
        guard AppModel.shared.profile.onboarded else { return }
        await AppModel.shared.start()
        while AppModel.shared.library.scanning { try? await Task.sleep(for: .milliseconds(50)) }
    }

    static func items() -> [SpitifyMedia] {
        let app = AppModel.shared
        var out: [SpitifyMedia] = []
        if !app.library.liked.isEmpty { out.append(SpitifyMedia(id: "liked", title: "Liked songs", subtitle: "Playlist")) }
        out += app.library.playlists.map { SpitifyMedia(id: "playlist:" + $0.id, title: $0.name, subtitle: "Playlist") }
        out += app.library.library.albums.map { SpitifyMedia(id: "album:" + $0.id, title: $0.title, subtitle: "Album by " + $0.artist) }
        out += app.library.library.artists.map { SpitifyMedia(id: "artist:" + $0.name, title: $0.name, subtitle: "Artist") }
        out += app.library.library.songs.filter(\.playable).map { SpitifyMedia(id: "song:" + $0.id, title: $0.title, subtitle: "Song by " + $0.artist) }
        out += app.shows.shows.filter { $0.following || $0.kind == .audiobook }.map {
            SpitifyMedia(id: "show:" + $0.id, title: $0.title, subtitle: ($0.kind == .audiobook ? "Book by " : "Podcast by ") + $0.author)
        }
        out += app.shows.shows.filter(\.following).flatMap { show in
            app.shows.songs(show).map { SpitifyMedia(id: "episode:" + $0.id, title: $0.title, subtitle: show.title) }
        }
        out += Dictionary(grouping: app.library.books, by: \.albumKey).map { key, chapters in
            SpitifyMedia(id: "book:" + key, title: chapters[0].album, subtitle: "Book by " + chapters[0].artist)
        }
        return out
    }

    static func match(_ query: String, in items: [SpitifyMedia]) -> [SpitifyMedia] {
        let wanted = foldForSearch(query).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !wanted.isEmpty else { return Array(items.prefix(20)) }
        let words = wanted.split(separator: " ")
        return Array(items.filter { item in
            let text = foldForSearch(item.title + " " + item.subtitle)
            return words.allSatisfy { text.contains($0) }
        }.sorted { a, b in
            let aExact = foldForSearch(a.title) == wanted
            let bExact = foldForSearch(b.title) == wanted
            if aExact != bExact { return aExact }
            return a.title.localizedStandardCompare(b.title) == .orderedAscending
        }.prefix(20))
    }

    static func play(_ id: String) -> Bool {
        let app = AppModel.shared
        let parts = id.split(separator: ":", maxSplits: 1).map(String.init)
        let key = parts.count == 2 ? parts[1] : ""
        var songs: [Song] = []
        var title = "Spitify"
        switch parts.first {
        case "liked": songs = app.library.likedSongs; title = "Liked songs"
        case "song", "episode": if let song = app.lookup(key) { songs = [song]; title = song.album }
        case "album": if let album = app.library.library.albumById[key] { songs = album.songs; title = album.title }
        case "artist": songs = app.library.library.artistByName[key]?.songs ?? []; title = key
        case "playlist": if let playlist = app.library.playlists.first(where: { $0.id == key }) { songs = app.library.songs(of: playlist); title = playlist.name }
        case "show": if let show = app.shows.shows.first(where: { $0.id == key }) { songs = app.shows.songs(show); title = show.title }
        case "book": songs = app.library.books.filter { $0.albumKey == key }.sorted { ($0.disc, $0.track, $0.fileName) < ($1.disc, $1.track, $1.fileName) }; title = songs.first?.album ?? title
        default: return false
        }
        songs = songs.filter(\.playable)
        guard let first = songs.first else { return false }
        if first.isAudiobook { app.player.playBook(songs, from: 0, title: title) }
        else if first.isPodcast { app.player.playEpisode(first) }
        else { app.player.play(songs, shuffle: false, source: title) }
        return true
    }
}

struct PlaySpitifyMedia: AudioPlaybackIntent {
    static var title: LocalizedStringResource = "Play in Spitify"
    static var description = IntentDescription("Play a song, artist, album, playlist, podcast, or audiobook in your Spitify library.")
    @Parameter(title: "Music or show") var media: SpitifyMedia
    static var parameterSummary: some ParameterSummary { Summary("Play \(\.$media) in Spitify") }

    @MainActor func perform() async throws -> some IntentResult & ProvidesDialog {
        guard AppModel.shared.profile.onboarded else { return .result(dialog: "Open Spitify on your iPhone to finish setup first.") }
        await VoiceLibrary.ready()
        guard VoiceLibrary.play(media.id) else { return .result(dialog: "I couldn't find that in your Spitify library.") }
        return .result(dialog: "Playing \(media.title) in Spitify.")
    }
}

struct ResumeSpitify: AudioPlaybackIntent {
    static var title: LocalizedStringResource = "Resume Spitify"
    @MainActor func perform() async throws -> some IntentResult & ProvidesDialog {
        guard AppModel.shared.profile.onboarded else { return .result(dialog: "Open Spitify on your iPhone to finish setup first.") }
        await VoiceLibrary.ready()
        let app = AppModel.shared
        if app.player.hasMedia { app.player.resume() }
        else if !app.library.library.songs.isEmpty { app.player.play(app.library.library.songs, shuffle: true, source: "All songs") }
        else if let show = app.shows.podcasts.first, let song = app.shows.songs(show).first { app.player.playEpisode(song) }
        else { return .result(dialog: "Add music or follow a show in Spitify first.") }
        return .result(dialog: "Playing Spitify.")
    }
}

struct PauseSpitify: AudioPlaybackIntent {
    static var title: LocalizedStringResource = "Pause Spitify"
    @MainActor func perform() async throws -> some IntentResult { if AppModel.shared.player.hasMedia { AppModel.shared.player.pause() }; return .result() }
}

struct SpitifyShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: PlaySpitifyMedia(), phrases: [
            "Play \(\.$media) in \(.applicationName)",
            "Play \(\.$media) on \(.applicationName)",
            "Listen to \(\.$media) in \(.applicationName)",
        ], shortTitle: "Play music or a show", systemImageName: "play.fill")
        AppShortcut(intent: ResumeSpitify(), phrases: [
            "Play music in \(.applicationName)", "Resume \(.applicationName)", "Play \(.applicationName)",
        ], shortTitle: "Resume", systemImageName: "play.circle")
        AppShortcut(intent: PauseSpitify(), phrases: ["Pause \(.applicationName)"], shortTitle: "Pause", systemImageName: "pause.fill")
    }
}
