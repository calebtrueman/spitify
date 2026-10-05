import SwiftUI
import PhotosUI

/// Shared layout for albums, playlists, mixes, smart lists, genres and folders: colour-matched
/// header, big play button, then the tracks. The header art shrinks and fades as you scroll.
struct CollectionView<Extra: View>: View {
    var title: String
    var kind: String
    var subtitle: String
    var art: Song?
    var songs: [Song]
    var trackNumbers = false
    var hero = false
    var mix: Mix? = nil
    var songSubtitle: ((Song) -> String)? = nil
    var removeLabel: String? = nil
    var onRemove: ((Int) -> Void)? = nil
    var toolbarExtra: AnyView? = nil
    var catalogTracks: [OnlineTrack]? = nil
    var remoteArt: String? = nil
    var customArtKey: String? = nil
    @ViewBuilder var extra: () -> Extra
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    @Environment(Router.self) private var router
    var body: some View {
        let isThis = app.player.source == title
        let count = catalogTracks?.count ?? songs.count
        let length = catalogTracks?.reduce(Int64(0)) { $0 + $1.durationMs } ?? songs.reduce(Int64(0)) { $0 + $1.durationMs }
        CollectionLayout(title: title, subtitle: subtitle, metadata: "\(kind) • \(songCount(count)), \(length.formattedLong)",
                         artKey: customArtKey ?? (kind == "Artist" ? ArtistChoices.key(title) : art?.albumKey ?? remoteArt ?? "none"), remoteArt: art?.artURL ?? remoteArt) {
            if let customArtKey { ArtworkView(key: customArtKey, remote: nil, cornerRadius: 8) }
            else if kind == "Artist" { ArtistPicture(name: title, fallback: art, remote: remoteArt) }
            else if let mix { MixCover(mix: mix) }
            else if art == nil, let remoteArt { ArtworkView(key: remoteArt, remote: remoteArt, cornerRadius: 8, circle: hero) }
            else { ArtworkView(art, cornerRadius: 8, circle: hero) }
        } actions: {
            CollectionActionBar(playing: isThis && app.player.isPlaying, enabled: !songs.isEmpty,
                                shuffle: { app.player.play(songs, shuffle: true, source: title) }, play: {
                if isThis && app.player.hasMedia { app.player.toggle() }
                else { app.player.play(songs, shuffle: false, source: title) }
            }) {
                if kind == "Artist" { ArtistOptions(name: title) }
                if let toolbarExtra { toolbarExtra }
                if !songs.isEmpty && songs.allSatisfy({ !$0.isSpoken }) {
                    IconControl(title: "Share with friends", symbol: "square.and.arrow.up") {
                        router.share(name: title, songs: songs, kind: kind == "Album" ? "album" : kind == "Song" ? "song" : "playlist", app: app)
                    }
                }
                IconControl(title: "Add to queue", symbol: "text.badge.plus") { app.player.addToQueue(songs) }.disabled(songs.isEmpty)
            }
        } content: {
            if let mix, mix.why != nil || mix.refresh != nil {
                Text([mix.why, mix.refresh].compactMap { $0 }.joined(separator: " · ")).text(.caption).foregroundStyle(p.secondary)
                    .padding(.horizontal, MediaLayout.inset).padding(.bottom, 8)
            }
            extra()
            if songs.isEmpty && catalogTracks == nil { EmptyState(title: "Nothing here yet", message: "Add songs from any song's ••• menu.") }
            LazyVStack(spacing: 0) {
                if let catalogTracks {
                    ForEach(Array(catalogTracks.enumerated()), id: \.element.id) { i, track in
                        OnlineTrackRow(track: track, trackNumber: trackNumbers ? (track.trackNumber > 0 ? track.trackNumber : i + 1) : nil, onPlay: { song in
                            app.player.play(songs, from: songs.firstIndex(where: { $0.id == song.id }) ?? 0, shuffle: false, source: title)
                        })
                    }
                } else {
                    ForEach(Array(songs.enumerated()), id: \.offset) { i, song in
                        SongRow(song: song, trackNumber: trackNumbers ? (song.track > 0 ? song.track : i + 1) : nil, subtitle: songSubtitle?(song),
                                onTap: { app.player.play(songs, from: i, shuffle: false, source: title) },
                                removeLabel: removeLabel, onRemove: onRemove.map { action in { action(i) } })
                    }
                }
            }
        }
    }
}

extension CollectionView where Extra == EmptyView {
    init(title: String, kind: String, subtitle: String, art: Song?, songs: [Song], trackNumbers: Bool = false, hero: Bool = false, mix: Mix? = nil,
         songSubtitle: ((Song) -> String)? = nil, removeLabel: String? = nil, onRemove: ((Int) -> Void)? = nil, toolbarExtra: AnyView? = nil, catalogTracks: [OnlineTrack]? = nil, remoteArt: String? = nil, customArtKey: String? = nil) {
        self.init(title: title, kind: kind, subtitle: subtitle, art: art, songs: songs, trackNumbers: trackNumbers, hero: hero, mix: mix, songSubtitle: songSubtitle,
                  removeLabel: removeLabel, onRemove: onRemove, toolbarExtra: toolbarExtra, catalogTracks: catalogTracks, remoteArt: remoteArt, customArtKey: customArtKey, extra: { EmptyView() })
    }
}

struct AlbumView: View {
    var id: String
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    var body: some View {
        if let a = app.library.library.albumById[id] {
            let more = (app.library.library.artistByName[a.artist]?.albums ?? []).filter { $0.id != a.id }
            CollectionView(title: a.title, kind: "Album", subtitle: [a.artist, a.year > 0 ? String(a.year) : nil].compactMap { $0 }.joined(separator: " • "), art: a.cover, songs: a.songs,
                           trackNumbers: true, songSubtitle: { $0.artist },
                           toolbarExtra: AnyView(Button { router.editing = (a.songs, true) } label: { IconControlLabel(symbol: "pencil") })) {
                EmptyView()
            }
            .toolbar { ToolbarItem(placement: .topBarTrailing) { if !more.isEmpty { Menu { ForEach(more) { m in Button(m.title) { router.go(.album(m.id)) } } } label: { Image(systemName: "square.stack") } } } }
        } else { EmptyState(title: "Album not found", message: "It may have been removed.") }
    }
}

struct ArtistView: View {
    var name: String
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    var body: some View {
        if let a = app.library.library.artistByName[name] {
            let counts = app.library.playCounts
            let popular = a.songs.sorted { (counts[$0.id] ?? 0) > (counts[$1.id] ?? 0) }
            CollectionView(title: a.name, kind: "Artist", subtitle: "\(a.albums.count) \(a.albums.count == 1 ? "album" : "albums") • \(songCount(a.songs.count))", art: a.ownCover, songs: popular, hero: true,
                           songSubtitle: { s in (counts[s.id] ?? 0) > 0 ? "\(counts[s.id]!) plays • \(s.album)" : s.album },
                           toolbarExtra: AnyView(Button { app.player.play(app.artistRadio(a.name).isEmpty ? a.songs : app.artistRadio(a.name), shuffle: false, source: "\(a.name) Radio") } label: {
                               IconControlLabel(symbol: "dot.radiowaves.left.and.right") })) {
                Button("View online artist") { router.go(.onlineArtistName(a.name)) }.padding(.horizontal, 20)
                if !a.albums.isEmpty {
                    TileShelf(title: "Saved albums", tiles: a.albums.map { al in Tile(id: al.id, title: al.title, subtitle: al.year > 0 ? String(al.year) : "Album", song: al.cover) { router.go(.album(al.id)) } })
                    SectionHeader(title: "Popular in your library")
                }
            }
        } else { EmptyState(title: "Artist not found", message: "") }
    }
}

struct PlaylistView: View {
    @State private var photo: PhotosPickerItem?
    @State private var deleting = false
    var id: String
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @Environment(\.dismiss) private var dismiss
    @State private var renaming = false
    @State private var name = ""
    var body: some View {
        if let pl = app.library.playlists.first(where: { $0.id == id }) {
            let entries = app.library.entries(of: pl)
            let songs = entries.map(\.song)
            CollectionView(title: pl.name, kind: "Playlist", subtitle: "Your playlist", art: songs.first, songs: songs, removeLabel: "Remove from this playlist",
                           onRemove: { i in if entries.indices.contains(i) { app.library.remove(at: entries[i].raw, from: pl.id) } }, customArtKey: app.library.playlistArtworkKey(pl.id))
                .toolbar {
                    Menu {
                        PhotosPicker(selection: $photo, matching: .images) { Label("Change artwork", systemImage: "photo") }
                        if app.library.playlistArtworkKey(pl.id) != nil {
                            Button("Use song artwork") { ArtCache.shared.removeCustom("playlist:" + pl.id); app.library.artVersion += 1 }
                        }
                        Button("Rename", systemImage: "pencil") { name = pl.name; renaming = true }
                        Button("Delete playlist", systemImage: "trash", role: .destructive) { deleting = true }
                    } label: { Label("Edit playlist", systemImage: "ellipsis.circle") }
                }
                .task(id: photo) {
                    guard let photo, let data = try? await photo.loadTransferable(type: Data.self) else { return }
                    ArtCache.shared.storeCustom(data, key: "playlist:" + pl.id); app.library.artVersion += 1
                }
                .confirmationDialog("Delete “\(pl.name)”?", isPresented: $deleting, titleVisibility: .visible) {
                    Button("Delete playlist", role: .destructive) { app.library.deletePlaylist(pl.id); dismiss() }
                } message: { Text("Only the playlist is removed. Your songs stay in your library.") }
                .alert("Rename playlist", isPresented: $renaming) { TextField("Name", text: $name); Button("Save") { app.library.rename(pl.id, to: name) }; Button("Cancel", role: .cancel) {} }
        }
    }
}

struct MixView: View {
    var id: String
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var deleting = false
    var body: some View {
        if let m = app.mixes.first(where: { $0.id == id }) {
            CollectionView(title: m.title, kind: "Made for you", subtitle: m.description, art: m.cover, songs: m.songs, mix: m,
                           toolbarExtra: AnyView(IconControl(title: "Delete playlist", symbol: "trash") { deleting = true }))
                .confirmationDialog("Delete “\(m.title)”?", isPresented: $deleting, titleVisibility: .visible) {
                    Button("Delete playlist", role: .destructive) { app.deleteMix(m.id); dismiss() }
                } message: { Text("It won't be made for you again. You can bring deleted mixes back in Settings.") }
        } else { EmptyState(title: "Mix not ready", message: "Keep listening — this one refreshes soon.", icon: "sparkles") }
    }
}

struct SmartView: View {
    var kind: SmartKind
    @AppStorage("allSongsSort") private var sort = "Recently Added"
    @Environment(AppModel.self) private var app
    var body: some View {
        let lib = app.library
        let songs: [Song] = switch kind {
        case .allSongs: sorted(lib.library.songs, counts: lib.playCounts)
        case .recentlyAdded: lib.recentlyAdded
        case .recentlyPlayed: lib.recentlyPlayed
        case .mostPlayed: { let c = lib.playCounts; return lib.library.songs.filter { (c[$0.id] ?? 0) > 0 }.sorted { (c[$0.id] ?? 0) > (c[$1.id] ?? 0) }.prefix(50).map { $0 } }()
        }
        CollectionView(title: kind.rawValue, kind: kind == .allSongs ? "Library" : "Smart playlist", subtitle: kind == .allSongs ? "Every song in your library" : "Updated as you listen", art: songs.first, songs: songs)
            .toolbar {
                if kind == .allSongs {
                    Menu { Picker("Sort songs", selection: $sort) { ForEach(["Title", "Artist", "Album", "Recently Added", "Most Played"], id: \.self) { Text($0).tag($0) } } }
                    label: { Label("Sort: " + sort, systemImage: "arrow.up.arrow.down") }
                }
            }
    }
    private func sorted(_ songs: [Song], counts: [String: Int]) -> [Song] {
        songs.sorted { a, b in
            switch sort {
            case "Most Played": if counts[a.id, default: 0] != counts[b.id, default: 0] { return counts[a.id, default: 0] > counts[b.id, default: 0] }
            case "Recently Added": if a.dateAdded != b.dateAdded { return a.dateAdded > b.dateAdded }
            case "Artist": if a.artist != b.artist { return a.artist.localizedCaseInsensitiveCompare(b.artist) == .orderedAscending }
            case "Album": if a.album != b.album { return a.album.localizedCaseInsensitiveCompare(b.album) == .orderedAscending }; if a.disc != b.disc { return a.disc < b.disc }; if a.track != b.track { return a.track < b.track }
            default: break
            }
            if a.title != b.title { return a.title.localizedCaseInsensitiveCompare(b.title) == .orderedAscending }
            return a.id < b.id
        }
    }
}
