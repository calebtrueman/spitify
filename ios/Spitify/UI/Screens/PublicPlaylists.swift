import SwiftUI

struct PublicPlaylistSearch: View {
    var query: String
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    @State private var results: [SpotifyPlaylistResult] = []
    @State private var loading = false
    @State private var message: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Public Spotify playlists").text(.title)
            Text("Search by name or paste a public playlist link. Songs play from your library or the online music catalogue.").text(.bodyS).foregroundStyle(p.secondary)
            if loading { ProgressView("Finding playlists…") }
            if let message { Text(message).text(.bodyS).foregroundStyle(p.secondary) }
            ForEach(results) { result in
                NavigationLink { SpotifyPlaylistPreview(input: result.id) } label: {
                    HStack(spacing: 12) {
                        PlaylistCover(url: result.image).frame(width: 64, height: 64)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(result.name).text(.body).foregroundStyle(p.text)
                            Text(result.owner).text(.caption).foregroundStyle(p.secondary)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(p.secondary)
                    }.contentShape(Rectangle())
                }.buttonStyle(.plain)
            }
        }.padding(16)
        .task(id: query) {
            results = []; message = nil
            let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { return }
            loading = true
            do {
                try await Task.sleep(for: .milliseconds(350))
                if let id = SpotifyPlaylists.playlistID(text) {
                    results = [.init(id: id, name: "Open Spotify playlist", description: "", image: nil, owner: "Spotify")]
                } else {
                    let found = try await SpotifyPlaylists().search(text)
                    try Task.checkCancellation()
                    results = found
                    if results.isEmpty { message = "No public playlists found. Try another name or paste a Spotify playlist link." }
                }
                loading = false
            } catch {
                guard !Task.isCancelled else { return }
                message = error.localizedDescription; loading = false
            }
        }
    }
}

struct PlaylistCover: View {
    var url: String?
    var body: some View {
        AsyncImage(url: url.flatMap(URL.init(string:))) { image in image.resizable().scaledToFill() }
        placeholder: { Color.gray.opacity(0.2).overlay(Image(systemName: "music.note.list")) }
            .clipped().clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

struct SpotifyPlaylistPreview: View {
    var input: String
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    @State private var playlist: SharedPlaylist?
    @State private var message: String?
    var body: some View {
        Group {
            if let playlist { SharedPlaylistView(initial: playlist) }
            else if let message {
                ContentUnavailableView("Couldn't open playlist", systemImage: "music.note.list", description: Text(message))
            } else { ProgressView("Reading playlist…") }
        }.frame(maxWidth: .infinity, maxHeight: .infinity).background(p.background)
        .task(id: input) {
            do { try app.social.prepare(); playlist = try await SpotifyPlaylists().load(input, owner: app.social.publicKey) }
            catch { if !Task.isCancelled { message = error.localizedDescription } }
        }
    }
}

struct SharedPlaylistView: View {
    var initial: SharedPlaylist
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    @State private var message: String?
    @State private var ownCopy: SharedPlaylist?
    @State private var playback: Task<Void, Never>?
    private var playlist: SharedPlaylist { app.social.state.playlists[initial.key] ?? initial }
    private var saved: Bool { app.social.state.playlists[initial.key] != nil }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                PlaylistCover(url: playlist.image).frame(width: 220, height: 220).frame(maxWidth: .infinity)
                Text(playlist.name).text(.title)
                if !playlist.description.isEmpty { Text(playlist.description).text(.bodyS).foregroundStyle(p.secondary) }
                Text("\(playlist.tracks.count) songs" + (playlist.sourceName.map { " · From \($0)" } ?? "")).text(.caption).foregroundStyle(p.secondary)
                if playlist.partial {
                    Label("Only part of this playlist was available. Saved songs will keep their original titles and order.", systemImage: "exclamationmark.triangle").text(.bodyS)
                }
                if let source = playlist.sourceURL.flatMap(URL.init(string:)) { Link("Open original playlist", destination: source) }
                HStack {
                    Button { play(from: 0) } label: { Label("Play", systemImage: "play.fill") }.disabled(playlist.tracks.isEmpty)
                    Spacer()
                    Button(saved ? "Saved" : "Save playlist") {
                        do { try app.social.save(playlist); SharedSongMatch.prepare(playlist, app: app); message = "Saved in Your Library." } catch { message = error.localizedDescription }
                    }.disabled(saved)
                }.buttonStyle(.bordered)
                if playlist.owner != app.social.publicKey {
                    Button("Make your own copy") { do { ownCopy = try app.social.copy(playlist) } catch { message = error.localizedDescription } }
                }
                if saved, playlist.owner == app.social.publicKey {
                    NavigationLink("Share with friends") { PlaylistSharingView(playlist: playlist) }
                }
                if saved, playlist.owner == app.social.publicKey || playlist.editors.contains(app.social.publicKey) {
                    NavigationLink(playlist.kind == "mix" ? "Contribute songs" : "Edit playlist") { SharedPlaylistEditor(initial: playlist) }
                }
                if let message { Text(message).text(.bodyS).foregroundStyle(p.secondary) }
                ForEach(Array(playlist.tracks.enumerated()), id: \.element.id) { index, track in
                    Button { play(from: index) } label: {
                        HStack(spacing: 12) {
                            Text("\(index + 1)").monospacedDigit().foregroundStyle(p.secondary).frame(width: 30)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(track.title).text(.body).foregroundStyle(p.text)
                                Text(track.artist).text(.bodyS).foregroundStyle(p.secondary)
                            }
                            Spacer()
                        }.padding(.vertical, 5).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }
            }.padding(16)
        }.background(p.background).navigationTitle(playlist.kind == "mix" ? "Shared Mix" : playlist.kind.capitalized).navigationBarTitleDisplayMode(.inline)
        .task(id: playlist.key + ":" + String(playlist.revision)) { SharedSongMatch.prepare(playlist, app: app) }
        .navigationDestination(item: $ownCopy) { SharedPlaylistView(initial: $0) }

    }

    private func play(from index: Int) {
        playback?.cancel(); message = nil
        let selected = playlist
        playback = Task { @MainActor in
            var firstID: String?; var missing: [String] = []
            var expectedQueue = app.player.queueVersion
            for track in selected.tracks.dropFirst(index) {
                guard !Task.isCancelled else { return }
                do {
                    let song = try await SharedSongMatch.resolve(track, app: app)
                    try Task.checkCancellation()
                    guard app.player.queueVersion == expectedQueue else { return }
                    if firstID != nil {
                        app.player.appendFromSource([song])
                    } else {
                        firstID = song.id
                        app.player.play([song], shuffle: false, source: selected.name)
                        expectedQueue = app.player.queueVersion
                    }
                }
                catch { missing.append(track.title) }
            }
            if !missing.isEmpty { message = "Couldn't match \(missing.count) songs: " + missing.prefix(4).joined(separator: ", ") + (missing.count > 4 ? "…" : "") }
        }
    }
}
