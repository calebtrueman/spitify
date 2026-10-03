import SwiftUI

struct LibraryView: View {
    enum Filter: String, CaseIterable { case playlists = "Playlists", albums = "Albums", artists = "Artists", songs = "Songs", genres = "Genres", folders = "Folders" }
    enum Sort: String, CaseIterable { case recents = "Recents", alpha = "Alphabetical", plays = "Most played" }
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @Environment(\.palette) private var p
    @State private var filter: Filter?
    @AppStorage("librarySort") private var sort: Sort = .recents
    @State private var creating = false
    @State private var newName = ""

    struct Entry: Identifiable { var id: String; var title: String; var subtitle: String; var song: Song?; var circle = false; var date: Date; var plays: Int; var route: Route }

    var body: some View {
        let lib = app.library
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ThemeScene(compact: true)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) { ForEach(Filter.allCases, id: \.self) { f in Pill(title: f.rawValue, selected: filter == f) { filter = filter == f ? nil : f } } }.padding(.horizontal, 16)
                }.padding(.vertical, 8)
                HStack {
                    Menu { ForEach(Sort.allCases, id: \.self) { s in Button(s.rawValue) { sort = s } } } label: {
                        Label(sort.rawValue, systemImage: "arrow.up.arrow.down").text(.label).foregroundStyle(p.text)
                    }
                    Spacer()
                    ImportButton()
                }.padding(.horizontal, 16).padding(.bottom, 6)

                if filter == nil || filter == .playlists {
                    NavigationLink { SpotifyImportView() } label: {
                        Label("Add from Spotify", systemImage: "link").text(.label)
                            .foregroundStyle(p.accent).padding(.horizontal, 16).padding(.vertical, 12)
                    }.buttonStyle(.plain)
                    ForEach(app.social.playlists, id: \.key) { playlist in
                        NavigationLink { SharedPlaylistView(initial: playlist) } label: {
                            HStack(spacing: 12) {
                                PlaylistCover(url: playlist.image).frame(width: MediaLayout.rowArt, height: MediaLayout.rowArt)
                                MediaRowText(title: playlist.name, subtitle: "Playlist · \(playlist.tracks.count) songs")
                                Spacer()
                            }.frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.vertical, MediaLayout.rowPadding).contentShape(Rectangle())
                        }.buttonStyle(.plain)
                    }
                    pinned("All Songs", "Pinned • \(songCount(lib.library.songs.count))", "music.note.list", [Color(hex: 0x4B2BD6), Color(hex: 0x9AB8F0)]) { router.go(.smart(.allSongs)) }
                    if !lib.recentlyPlayed.isEmpty { pinned("On repeat", "Smart playlist", "sparkles", [Color(hex: 0x0E7A55), Color(hex: 0x1ED760)]) { router.go(.smart(.mostPlayed)) } }
                    pinned("Recently added", "Smart playlist • \(songCount(lib.recentlyAdded.count))", "clock.fill", [Color(hex: 0x0E7A55), Color(hex: 0x1ED760)]) { router.go(.smart(.recentlyAdded)) }
                }
                if filter == .songs {
                    let songs = sortedSongs
                    if songs.isEmpty { EmptyState(title: "No songs yet", message: "Find music in Search, then add it to your library. You can also import your own files.", icon: "music.note") }
                    ForEach(Array(songs.enumerated()), id: \.element.id) { i, s in SongRow(song: s) { app.player.play(songs, from: i, shuffle: false, source: "All songs") } }
                } else {
                    if let filter, filter != .playlists, entries.isEmpty {
                        EmptyState(title: "No \(filter.rawValue.lowercased()) yet", message: "Add music to your library to see it here.", icon: "music.note")
                    }
                    ForEach(entries) { e in
                        Button { router.go(e.route) } label: {
                            HStack(spacing: 12) {
                                Group { if e.circle { ArtistPicture(name: e.title, fallback: e.song) } else { ArtworkView(e.song, cornerRadius: 4) } }.frame(width: MediaLayout.rowArt, height: MediaLayout.rowArt)
                                MediaRowText(title: e.title, subtitle: e.subtitle)
                                Spacer()
                            }.frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.vertical, MediaLayout.rowPadding).contentShape(Rectangle())
                        }.buttonStyle(.pressable(0.98))
                    }
                }
            }.padding(.bottom, 24)
        }
        .background(p.background)
        .navigationTitle("Your Library")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) { NavigationLink { ArtistReleasesView() } label: { Image(systemName: "bell") }.accessibilityLabel("New releases") }
            ToolbarItem(placement: .topBarTrailing) { NavigationLink { FriendsView() } label: { Image(systemName: "person.2") }.accessibilityLabel("Friends") }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button("Create playlist", systemImage: "plus") { creating = true }
                    NavigationLink { SpotifyCodeScanView() } label: { Label("Scan Spotify code", systemImage: "barcode.viewfinder") }
                    NavigationLink { SpotifyImportView() } label: { Label("Add from Spotify", systemImage: "link") }
                } label: { Image(systemName: "plus") }.accessibilityLabel("Add to library")
            }
        }
        .alert("Give your playlist a name", isPresented: $creating) {
            TextField("My playlist", text: $newName)
            Button("Create") { let pl = app.library.createPlaylist(newName); newName = ""; router.go(.playlist(pl.id)) }
            Button("Cancel", role: .cancel) {}
        }
        .refreshable { await app.library.scan() }
    }

    private func pinned(_ title: String, _ sub: String, _ icon: String, _ colors: [Color], action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing).frame(width: MediaLayout.rowArt, height: MediaLayout.rowArt).clipShape(RoundedRectangle(cornerRadius: 4))
                    .overlay(Image(systemName: icon).font(.system(size: 22, weight: .bold)).foregroundStyle(.white))
                MediaRowText(title: title, subtitle: sub)
                Spacer()
            }.frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.vertical, MediaLayout.rowPadding).contentShape(Rectangle())
        }.buttonStyle(.pressable(0.98))
    }

    private var sortedSongs: [Song] {
        let lib = app.library
        switch sort {
        case .alpha: return lib.library.songs
        case .plays: let c = lib.playCounts; return lib.library.songs.sorted { (c[$0.id] ?? 0) > (c[$1.id] ?? 0) }
        case .recents: return lib.library.songs.sorted { $0.dateAdded > $1.dateAdded }
        }
    }

    private var entries: [Entry] {
        let lib = app.library, counts = lib.playCounts
        func plays(_ s: [Song]) -> Int { s.reduce(0) { $0 + (counts[$1.id] ?? 0) } }
        var all: [Entry] = []
        if filter == nil || filter == .playlists {
            all += lib.playlists.map { pl in let s = lib.songs(of: pl); return Entry(id: pl.id, title: pl.name, subtitle: "Playlist • \(songCount(s.count))", song: s.first, date: pl.updatedAt, plays: plays(s), route: .playlist(pl.id)) }
        }
        if filter == nil || filter == .albums {
            all += lib.library.albums.map { a in Entry(id: a.id, title: a.title, subtitle: "Album • \(a.artist)", song: a.cover, date: a.songs.map(\.dateAdded).max() ?? .distantPast, plays: plays(a.songs), route: .album(a.id)) }
        }
        if filter == nil || filter == .artists {
            all += lib.library.artists.filter { !ArtistChoices.shared.contains($0.name) }.map { a in Entry(id: "ar" + a.name, title: a.name, subtitle: "Artist", song: a.cover, circle: true, date: a.songs.map(\.dateAdded).max() ?? .distantPast, plays: plays(a.songs), route: .artist(a.name)) }
        }
        if filter == .genres { all += lib.library.genres.map { g in Entry(id: "g" + g.name, title: g.name, subtitle: "Genre • \(songCount(g.songs.count))", song: g.songs.first, date: .distantPast, plays: plays(g.songs), route: .genre(g.name)) } }
        if filter == .folders { all += lib.library.folders.map { f in Entry(id: "f" + f.path, title: f.name, subtitle: "Folder • /\(f.path)", song: f.songs.first, date: .distantPast, plays: plays(f.songs), route: .folder(f.path)) } }
        switch sort {
        case .recents: return all.sorted { $0.date > $1.date }
        case .alpha: return all.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        case .plays: return all.sorted { $0.plays > $1.plays }
        }
    }
}
