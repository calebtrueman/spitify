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
            Text("Public Spotify playlists").text(.title).foregroundStyle(p.text)
            Text("Search by name or paste a public playlist link. Songs play from your library or the online music catalogue.").text(.bodyS).foregroundStyle(p.secondary)
            if loading { ProgressView("Finding playlists…") }
            if let message { Text(message).text(.bodyS).foregroundStyle(p.secondary) }
            ForEach(results) { result in
                NavigationLink { SpotifyPlaylistPreview(input: result.id) } label: {
                    HStack(spacing: 12) {
                        PlaylistCover(url: result.image).frame(width: MediaLayout.rowArt, height: MediaLayout.rowArt)
                        MediaRowText(title: result.name, subtitle: "Playlist · " + result.owner)
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(p.secondary)
                    }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
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
                if SpotifyPlaylists.accepts(text) {
                    results = [.init(id: text, name: "Open Spotify playlist", description: "", image: nil, owner: "Spotify")]
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
        ArtworkView(key: url ?? "playlist", remote: url, cornerRadius: 8)
    }
}

struct SpotifyPlaylistPreview: View {
    var input: String
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    @State private var playlist: SharedPlaylist?
    @State private var message: String?
    @State private var attempt = 0
    var body: some View {
        Group {
            if let playlist { SharedPlaylistView(initial: playlist) }
            else if let message {
                VStack(spacing: 16) {
                    ContentUnavailableView("Couldn't open playlist", systemImage: "music.note.list", description: Text(message))
                    Button("Try again") { attempt += 1 }.buttonStyle(.borderedProminent).padding(.bottom, 24)
                }
            } else { ProgressView("Reading playlist…") }
        }.frame(maxWidth: .infinity, maxHeight: .infinity).background(p.background)
        .task(id: input + ":" + String(attempt)) {
            message = nil
            do { try app.social.prepare(); let loaded = try await SpotifyPlaylists().load(input, owner: app.social.publicKey); try Task.checkCancellation(); playlist = loaded }
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
        CollectionLayout(title: playlist.name,
                         subtitle: playlist.sourceName.map { "From \($0)" } ?? (playlist.kind == "mix" ? "Shared mix" : "Shared playlist"),
                         metadata: "\(playlist.kind == "mix" ? "Mix" : "Playlist") • \(songCount(playlist.tracks.count))",
                         artKey: playlist.image ?? "playlist:" + playlist.key, remoteArt: playlist.image) {
            PlaylistCover(url: playlist.image)
        } actions: {
            CollectionActionBar(playing: app.player.source == playlist.name && app.player.isPlaying,
                                enabled: !playlist.tracks.isEmpty, shuffle: { play(from: 0, shuffle: true) }, play: {
                if app.player.source == playlist.name && app.player.hasMedia { app.player.toggle() }
                else { play(from: 0) }
            }) {
                saveButton
                if saved, playlist.owner == app.social.publicKey {
                    NavigationLink { PlaylistSharingView(playlist: playlist) } label: { IconControlLabel(symbol: "square.and.arrow.up") }
                        .accessibilityLabel("Share with friends")
                }
            }
        } content: {
            if !playlist.description.isEmpty {
                Text(playlist.description).text(.bodyS).foregroundStyle(p.secondary)
                    .padding(.horizontal, MediaLayout.inset).padding(.bottom, 12)
            }
            if playlist.partial {
                Label("Only part of this playlist was available. Saved songs will keep their original titles and order.", systemImage: "exclamationmark.triangle")
                    .text(.bodyS).foregroundStyle(p.secondary).padding(.horizontal, MediaLayout.inset).padding(.bottom, 12)
            }
            if let message { Text(message).text(.bodyS).foregroundStyle(p.secondary).padding(.horizontal, MediaLayout.inset).padding(.bottom, 12) }
            LazyVStack(spacing: 0) {
                ForEach(Array(playlist.tracks.enumerated()), id: \.element.id) { index, track in
                    SharedPlaylistTrackRow(track: track, onPlay: { play(from: index) }, onError: { message = $0 })
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if saved || playlist.owner != app.social.publicKey { Menu {
                    if playlist.owner != app.social.publicKey {
                        Button("Make your own copy", systemImage: "square.on.square") { do { ownCopy = try app.social.copy(playlist) } catch { message = error.localizedDescription } }
                    }
                    if saved, playlist.owner == app.social.publicKey {
                        NavigationLink { PlaylistSharingView(playlist: playlist) } label: { Label("Share with friends", systemImage: "square.and.arrow.up") }
                    }
                    if saved, playlist.owner == app.social.publicKey || playlist.editors.contains(app.social.publicKey) {
                        NavigationLink { SharedPlaylistEditor(initial: playlist) } label: { Label(playlist.kind == "mix" ? "Contribute songs" : "Edit playlist", systemImage: "pencil") }
                    }
                } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44).contentShape(Rectangle()) }
                    .accessibilityLabel("Playlist options") }
            }
        }
        .task(id: playlist.key + ":" + String(playlist.revision)) { SharedSongMatch.prepare(playlist, app: app) }
        .navigationDestination(item: $ownCopy) { SharedPlaylistView(initial: $0) }

    }

    private var saveButton: some View {
        Button {
            do { try app.social.save(playlist); SharedSongMatch.prepare(playlist, app: app); message = "Saved in Your Library." } catch { message = error.localizedDescription }
        } label: { IconControlLabel(symbol: saved ? "checkmark.circle.fill" : "plus.circle", selected: saved) }
            .disabled(saved).accessibilityLabel(saved ? "Playlist saved" : "Save playlist")
    }

    private func play(from index: Int, shuffle: Bool = false) {
        playback?.cancel(); message = nil
        let selected = playlist
        let tracks = shuffle ? selected.tracks : Array(selected.tracks.dropFirst(index))
        playback = Task { @MainActor in
            var firstID: String?; var missing: [String] = []; var shuffledSongs: [Song] = []
            if shuffle { message = "Preparing shuffle…" }
            var expectedQueue = app.player.queueVersion
            for track in tracks {
                guard !Task.isCancelled else { return }
                do {
                    let song = try await SharedSongMatch.resolve(track, app: app)
                    try Task.checkCancellation()
                    guard app.player.queueVersion == expectedQueue else { return }
                    if shuffle { shuffledSongs.append(song); continue }
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
            if shuffle {
                if !shuffledSongs.isEmpty { app.player.play(shuffledSongs, shuffle: true, source: selected.name) }
                message = nil
            }
            if !missing.isEmpty { message = "Couldn't match \(missing.count) songs: " + missing.prefix(4).joined(separator: ", ") + (missing.count > 4 ? "…" : "") }
        }
    }
}

/// Keep the imported credit on screen while using the matched song's own artwork and actions.
struct SharedPlaylistTrackRow: View {
    var track: SharedTrack
    var onPlay: () -> Void
    var onError: (String) -> Void
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    @State private var matched: Song?
    var body: some View {
        HStack(spacing: MediaLayout.rowSpacing) {
            Button(action: onPlay) {
                HStack(spacing: 12) {
                    SharedTrackCover(track: track, matched: matched).frame(width: MediaLayout.rowArt, height: MediaLayout.rowArt)
                    MediaRowText(title: track.title, subtitle: track.artist, highlighted: matched?.id == app.player.current?.id && matched != nil)
                }.frame(maxWidth: .infinity, minHeight: MediaLayout.rowArt, alignment: .leading).contentShape(Rectangle())
            }.buttonStyle(.plain)
            if let matched {
                SongMenu(song: matched) { IconControlLabel(symbol: "ellipsis") }
                    .accessibilityLabel("More options for \(track.title)")
            } else {
                Menu {
                    Button("Play", systemImage: "play.fill", action: onPlay)
                    Button("Play next", systemImage: "text.line.first.and.arrowtriangle.forward") { resolve { app.player.playNext([$0]) } }
                    Button("Add to queue", systemImage: "text.line.last.and.arrowtriangle.forward") { resolve { app.player.addToQueue([$0]) } }
                } label: { IconControlLabel(symbol: "ellipsis") }
                    .accessibilityLabel("More options for \(track.title)")
            }
        }.padding(.horizontal, MediaLayout.inset).padding(.vertical, MediaLayout.rowPadding)
        .task(id: track) { matched = try? await SharedSongMatch.resolve(track, app: app) }
    }
    private func resolve(_ action: @escaping (Song) -> Void) {
        Task { do { let song = try await SharedSongMatch.resolve(track, app: app); matched = song; action(song) } catch { onError(error.localizedDescription) } }
    }
}

struct SharedTrackCover: View {
    var track: SharedTrack
    var matched: Song? = nil
    var body: some View {
        if let artwork = track.artwork { ArtworkView(key: "shared:" + track.id + ":" + artwork, remote: artwork) }
        else if let matched { ArtworkView(matched) }
        else { ArtworkView(key: "shared:" + track.id, remote: nil) }
    }
}


struct SpotifyImportView: View {
    @Environment(\.palette) private var p
    @State private var input = ""
    @State private var submitted: String?
    @FocusState private var focused: Bool
    private var valid: Bool { SpotifyPlaylists.accepts(input) }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Image(systemName: "link.circle.fill").font(.system(size: 52)).foregroundStyle(p.accent)
                    .accessibilityHidden(true)
                Text("Your playlists, here.").text(.headlineL).foregroundStyle(p.text)
                Text("In Spotify, open a public playlist and choose Share → Copy link. Paste it below to see its cover and songs before saving it.")
                    .text(.body).foregroundStyle(p.secondary)
                VStack(alignment: .leading, spacing: 8) {
                    Text("Playlist link").text(.label).foregroundStyle(p.text)
                    TextField("https://open.spotify.com/playlist/…", text: $input)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .submitLabel(.go).focused($focused).onSubmit { open() }
                        .padding(14).background(p.surface, in: RoundedRectangle(cornerRadius: 12))
                        .accessibilityIdentifier("spotify-playlist-link")
                    if !input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !valid {
                        Text("Use a Spotify playlist link, rather than a song or album link.")
                            .text(.bodyS).foregroundStyle(p.secondary)
                    }
                    PasteButton(payloadType: String.self) { values in if let value = values.first { input = value } }
                        .tint(p.accent).accessibilityLabel("Paste playlist link")
                }
                Button(action: open) {
                    Text("Preview playlist").text(.label).frame(maxWidth: .infinity).padding(.vertical, 8)
                }.buttonStyle(.borderedProminent).tint(p.accent).disabled(!valid)
                NavigationLink { SpotifyCodeScanView() } label: { Label("Scan a Spotify code", systemImage: "barcode.viewfinder") }.text(.label)
                Text("Only public playlists are available. Some songs may be missing. Nothing is saved until you choose Save playlist on the preview.")
                    .text(.bodyS).foregroundStyle(p.secondary)
            }.padding(20).frame(maxWidth: 560, alignment: .leading).frame(maxWidth: .infinity)
        }.background(p.background).navigationTitle("Add from Spotify").navigationBarTitleDisplayMode(.inline)
        .navigationDestination(isPresented: Binding(get: { submitted != nil }, set: { if !$0 { submitted = nil } })) {
            if let submitted { SpotifyPlaylistPreview(input: submitted) }
        }
    }
    private func open() {
        guard valid else { return }
        focused = false; submitted = input.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
