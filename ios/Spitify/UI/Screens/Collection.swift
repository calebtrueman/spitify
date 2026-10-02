import SwiftUI

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
    @ViewBuilder var extra: () -> Extra
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    @State private var color = Color(hex: 0x2A2A2E)
    @State private var offset: CGFloat = 0

    var body: some View {
        let isThis = app.player.source == title
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                HStack(spacing: 6) {
                    if let toolbarExtra { toolbarExtra }
                    Button { app.player.addToQueue(songs) } label: { Image(systemName: "text.badge.plus").font(.system(size: 20)).frame(width: 40, height: 40) }.disabled(songs.isEmpty)
                    Spacer()
                    Button { app.player.play(songs, shuffle: true, source: title) } label: { Image(systemName: "shuffle").font(.system(size: 22, weight: .semibold)).frame(width: 44, height: 44) }.disabled(songs.isEmpty)
                    PlayButton(playing: isThis && app.player.isPlaying) {
                        if isThis && app.player.hasMedia { app.player.toggle() } else { app.player.play(songs, shuffle: false, source: title) }
                    }.disabled(songs.isEmpty).opacity(songs.isEmpty ? 0.4 : 1)
                }
                .foregroundStyle(p.secondary).padding(.horizontal, 12).padding(.vertical, 4)
                if let mix, mix.why != nil || mix.refresh != nil {
                    Text([mix.why, mix.refresh].compactMap { $0 }.joined(separator: " · ")).text(.caption).foregroundStyle(p.secondary).padding(.horizontal, 16).padding(.bottom, 6)
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
                    } else { ForEach(Array(songs.enumerated()), id: \.offset) { i, s in
                        SongRow(song: s, trackNumber: trackNumbers ? (s.track > 0 ? s.track : i + 1) : nil, subtitle: songSubtitle?(s),
                                onTap: { app.player.play(songs, from: i, shuffle: false, source: title) },
                                removeLabel: removeLabel, onRemove: onRemove.map { f in { f(i) } })
                    } }
                }
            }
            .padding(.bottom, 30)
            .background(GeometryReader { g in Color.clear.preference(key: OffsetKey.self, value: g.frame(in: .named("scroll")).minY) })
        }
        .coordinateSpace(name: "scroll")
        .onPreferenceChange(OffsetKey.self) { offset = $0 }
        .background(p.background)
        .artColor(art, into: $color)
        .navigationTitle(offset < -260 ? title : "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(offset < -260 ? .visible : .hidden, for: .navigationBar)
        .toolbarBackground(color.mix(p.background, 0.3), for: .navigationBar)
    }

    private var header: some View {
        let collapse = min(1, max(0, -offset / 300))
        let headerColor = p.isDark ? color : color.mix(.white, 0.55)
        return VStack(alignment: .leading, spacing: 6) {
            if hero {
                ArtworkView(art, cornerRadius: 0).frame(height: 340).clipped()
                    .overlay { LinearGradient(stops: [.init(color: .clear, location: 0.4), .init(color: p.background.opacity(0.95), location: 1)], startPoint: .top, endPoint: .bottom) }
                    .overlay(alignment: .bottomLeading) { Text(title).text(.display).foregroundStyle(p.text).lineLimit(2).padding(16) }
                    .opacity(1 - collapse)
            } else {
                Group {
                    if let mix { MixCover(mix: mix) } else if art == nil, let remoteArt { ArtworkView(key: remoteArt, remote: remoteArt, cornerRadius: 8) } else { ArtworkView(art, cornerRadius: 8) }
                }
                .frame(width: 236, height: 236)
                .shadow(color: .black.opacity(0.45), radius: 24, y: 12)
                .scaleEffect(1 - collapse * 0.25).opacity(1 - collapse * 1.1)
                .frame(maxWidth: .infinity).padding(.top, 8)
                Text(title).text(.headlineL).foregroundStyle(p.text).lineLimit(2).padding(.horizontal, 16).padding(.top, 14)
            }
            Text(subtitle).text(.bodyS).foregroundStyle(p.text.opacity(0.85)).padding(.horizontal, 16)
            Text("\(kind) • \(songCount(catalogTracks?.count ?? songs.count)), \((catalogTracks?.reduce(Int64(0)) { $0 + $1.durationMs } ?? songs.reduce(Int64(0)) { $0 + $1.durationMs }).formattedLong)").text(.caption).foregroundStyle(p.secondary).padding(.horizontal, 16)
        }
        .padding(.bottom, 8)
        .background(LinearGradient(colors: [headerColor, headerColor.mix(p.background, 0.75), p.background], startPoint: .top, endPoint: .bottom).padding(.top, -400))
    }
}

extension CollectionView where Extra == EmptyView {
    init(title: String, kind: String, subtitle: String, art: Song?, songs: [Song], trackNumbers: Bool = false, hero: Bool = false, mix: Mix? = nil,
         songSubtitle: ((Song) -> String)? = nil, removeLabel: String? = nil, onRemove: ((Int) -> Void)? = nil, toolbarExtra: AnyView? = nil, catalogTracks: [OnlineTrack]? = nil, remoteArt: String? = nil) {
        self.init(title: title, kind: kind, subtitle: subtitle, art: art, songs: songs, trackNumbers: trackNumbers, hero: hero, mix: mix, songSubtitle: songSubtitle,
                  removeLabel: removeLabel, onRemove: onRemove, toolbarExtra: toolbarExtra, catalogTracks: catalogTracks, remoteArt: remoteArt, extra: { EmptyView() })
    }
}

private struct OffsetKey: PreferenceKey { static let defaultValue: CGFloat = 0; static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() } }

struct AlbumView: View {
    var id: String
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    var body: some View {
        if let a = app.library.library.albumById[id] {
            if let track = app.musicDownloads.jobs.map(\.track).first(where: {
                SearchMatch.fold($0.album) == SearchMatch.fold(a.title) && SearchMatch.fold($0.artist) == SearchMatch.fold(a.artist) && !$0.releaseID.isEmpty
            }) {
                OnlineAlbumView(album: OnlineAlbum(id: track.releaseID, title: a.title, artist: a.artist, artwork: track.artwork))
            } else {
            let more = (app.library.library.artistByName[a.artist]?.albums ?? []).filter { $0.id != a.id }
            CollectionView(title: a.title, kind: "Album", subtitle: [a.artist, a.year > 0 ? String(a.year) : nil].compactMap { $0 }.joined(separator: " • "), art: a.cover, songs: a.songs,
                           trackNumbers: true, songSubtitle: { $0.artist },
                           toolbarExtra: AnyView(Button { router.editing = (a.songs, true) } label: { Image(systemName: "pencil").font(.system(size: 20)).frame(width: 40, height: 40) })) {
                EmptyView()
            }
            .toolbar { ToolbarItem(placement: .topBarTrailing) { if !more.isEmpty { Menu { ForEach(more) { m in Button(m.title) { router.go(.album(m.id)) } } } label: { Image(systemName: "square.stack") } } } }
            }
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
            CollectionView(title: a.name, kind: "Artist", subtitle: "\(a.albums.count) albums • \(songCount(a.songs.count))", art: a.cover, songs: popular, hero: true,
                           songSubtitle: { s in (counts[s.id] ?? 0) > 0 ? "\(counts[s.id]!) plays • \(s.album)" : s.album },
                           toolbarExtra: AnyView(Button { app.player.play(app.artistRadio(a.name).isEmpty ? a.songs : app.artistRadio(a.name), shuffle: false, source: "\(a.name) Radio") } label: {
                               Image(systemName: "dot.radiowaves.left.and.right").font(.system(size: 20)).frame(width: 40, height: 40) })) {
                if !a.albums.isEmpty {
                    TileShelf(title: "Discography", tiles: a.albums.map { al in Tile(id: al.id, title: al.title, subtitle: al.year > 0 ? String(al.year) : "Album", song: al.cover) { router.go(.album(al.id)) } })
                    SectionHeader(title: "Popular in your library")
                }
            }
        } else { EmptyState(title: "Artist not found", message: "") }
    }
}

struct PlaylistView: View {
    var id: String
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @Environment(\.dismiss) private var dismiss
    @State private var renaming = false
    @State private var name = ""
    var body: some View {
        if let pl = app.library.playlists.first(where: { $0.id == id }) {
            let songs = app.library.songs(of: pl)
            CollectionView(title: pl.name, kind: "Playlist", subtitle: "Your playlist", art: songs.first, songs: songs, removeLabel: "Remove from this playlist",
                           onRemove: { app.library.remove(at: $0, from: pl.id) })
                .toolbar {
                    Menu {
                        Button("Rename", systemImage: "pencil") { name = pl.name; renaming = true }
                        Button("Delete playlist", systemImage: "trash", role: .destructive) { dismiss(); app.library.deletePlaylist(pl.id) }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
                .alert("Rename playlist", isPresented: $renaming) { TextField("Name", text: $name); Button("Save") { app.library.rename(pl.id, to: name) }; Button("Cancel", role: .cancel) {} }
        }
    }
}

struct MixView: View {
    var id: String
    @Environment(AppModel.self) private var app
    var body: some View {
        if let m = app.mixes.first(where: { $0.id == id }) {
            CollectionView(title: m.title, kind: "Made for you", subtitle: m.description, art: m.cover, songs: m.songs, mix: m)
        } else { EmptyState(title: "Mix not ready", message: "Keep listening — this one refreshes soon.", icon: "sparkles") }
    }
}

struct SmartView: View {
    var kind: SmartKind
    @Environment(AppModel.self) private var app
    var body: some View {
        let lib = app.library
        let songs: [Song] = switch kind {
        case .allSongs: lib.library.songs
        case .recentlyAdded: lib.recentlyAdded
        case .recentlyPlayed: lib.recentlyPlayed
        case .mostPlayed: { let c = lib.playCounts; return lib.library.songs.filter { (c[$0.id] ?? 0) > 0 }.sorted { (c[$0.id] ?? 0) > (c[$1.id] ?? 0) }.prefix(50).map { $0 } }()
        }
        CollectionView(title: kind.rawValue, kind: kind == .allSongs ? "Library" : "Smart playlist", subtitle: kind == .allSongs ? "Every song in your library" : "Updated as you listen", art: songs.first, songs: songs)
    }
}
