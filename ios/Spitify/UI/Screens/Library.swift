import SwiftUI

struct LibraryView: View {
    enum Filter: String, CaseIterable { case playlists = "Playlists", albums = "Albums", artists = "Artists", songs = "Songs", genres = "Genres", folders = "Folders" }
    enum Sort: String, CaseIterable { case recents = "Recents", alpha = "Alphabetical", plays = "Most played" }
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @Environment(\.palette) private var p
    @State private var filter: Filter?
    @State private var sort: Sort = .recents
    @State private var creating = false
    @State private var newName = ""

    struct Entry: Identifiable { var id: String; var title: String; var subtitle: String; var song: Song?; var circle = false; var date: Date; var plays: Int; var route: Route }

    var body: some View {
        let lib = app.library
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) { ForEach(Filter.allCases, id: \.self) { f in Pill(title: f.rawValue, selected: filter == f) { filter = filter == f ? nil : f } } }.padding(.horizontal, 16)
                }.padding(.vertical, 8)
                HStack {
                    Menu { ForEach(Sort.allCases, id: \.self) { s in Button(s.rawValue) { sort = s } } } label: {
                        Label(sort.rawValue, systemImage: "arrow.up.arrow.down").text(.label).foregroundStyle(p.text)
                    }
                    Spacer()
                    ImportButton().scaleEffect(0.85)
                }.padding(.horizontal, 16).padding(.bottom, 6)

                if filter == nil || filter == .playlists {
                    ForEach(app.social.playlists, id: \.key) { playlist in
                        NavigationLink { SharedPlaylistView(initial: playlist) } label: {
                            HStack(spacing: 12) {
                                PlaylistCover(url: playlist.image).frame(width: 60, height: 60)
                                VStack(alignment: .leading) {
                                    Text(playlist.name).text(.body).foregroundStyle(p.text)
                                    Text("Playlist · \(playlist.tracks.count) songs").text(.bodyS).foregroundStyle(p.secondary)
                                }
                                Spacer()
                            }.padding(.horizontal, 16).padding(.vertical, 6)
                        }.buttonStyle(.plain)
                    }
                    pinned("All Songs", "Pinned • \(songCount(lib.library.songs.count))", "music.note.list", [Color(hex: 0x4B2BD6), Color(hex: 0x9AB8F0)]) { router.go(.smart(.allSongs)) }
                    if !lib.recentlyPlayed.isEmpty { pinned("On repeat", "Smart playlist", "sparkles", [Color(hex: 0x0E7A55), Color(hex: 0x1ED760)]) { router.go(.smart(.mostPlayed)) } }
                    pinned("Recently added", "Smart playlist • \(songCount(lib.recentlyAdded.count))", "clock.fill", [Color(hex: 0x0E7A55), Color(hex: 0x1ED760)]) { router.go(.smart(.recentlyAdded)) }
                }
                if filter == .songs {
                    let songs = sortedSongs
                    ForEach(Array(songs.enumerated()), id: \.element.id) { i, s in SongRow(song: s) { app.player.play(songs, from: i, shuffle: false, source: "All songs") } }
                } else {
                    ForEach(entries) { e in
                        Button { router.go(e.route) } label: {
                            HStack(spacing: 12) {
                                ArtworkView(e.song, cornerRadius: 4, circle: e.circle).frame(width: 60, height: 60)
                                VStack(alignment: .leading, spacing: 2) { Text(e.title).text(.body).foregroundStyle(p.text).lineLimit(1); Text(e.subtitle).text(.bodyS).foregroundStyle(p.secondary).lineLimit(1) }
                                Spacer()
                            }.padding(.horizontal, 16).padding(.vertical, 6).contentShape(Rectangle())
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
            ToolbarItem(placement: .topBarTrailing) { Button { creating = true } label: { Image(systemName: "plus") } }
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
                LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing).frame(width: 60, height: 60).clipShape(RoundedRectangle(cornerRadius: 4))
                    .overlay(Image(systemName: icon).font(.system(size: 22, weight: .bold)).foregroundStyle(.white))
                VStack(alignment: .leading, spacing: 2) { Text(title).text(.body).foregroundStyle(p.text); Text(sub).text(.bodyS).foregroundStyle(p.secondary) }
                Spacer()
            }.padding(.horizontal, 16).padding(.vertical, 6).contentShape(Rectangle())
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
            all += lib.library.artists.map { a in Entry(id: "ar" + a.name, title: a.name, subtitle: "Artist", song: a.cover, circle: true, date: a.songs.map(\.dateAdded).max() ?? .distantPast, plays: plays(a.songs), route: .artist(a.name)) }
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
