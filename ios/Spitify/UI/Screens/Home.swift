import SwiftUI

struct HomeView: View {
    enum Filter: String, CaseIterable { case all = "All", albums = "Albums", artists = "Artists", playlists = "Playlists" }
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @Environment(\.palette) private var p
    @State private var filter: Filter = .all
    @State private var glow = Color(hex: 0x2A2A2E)

    private var greeting: String {
        let h = Calendar.current.component(.hour, from: Date())
        return h < 5 ? "Good evening" : h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening"
    }

    var body: some View {
        let lib = app.library.library
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                topBar
                Text(app.profile.name.isEmpty ? greeting : "\(greeting), \(app.profile.name)").text(.headlineL).foregroundStyle(p.text)
                    .padding(.horizontal, 16).padding(.top, 14).padding(.bottom, 6)
                if lib.isEmpty {
                    if app.library.scanning { ProgressView().frame(maxWidth: .infinity).padding(40) } else { emptyLibrary }
                } else {
                    switch filter {
                    case .all: allContent
                    case .albums: grid(lib.albums.map { a in Tile(id: a.id, title: a.title, subtitle: a.artist, song: a.cover) { router.go(.album(a.id)) } })
                    case .artists: grid(lib.artists.map { a in Tile(id: a.name, title: a.name, subtitle: songCount(a.songs.count), song: a.cover, circle: true) { router.go(.artist(a.name)) } })
                    case .playlists: grid(playlistTiles)
                    }
                }
            }
            .padding(.bottom, 24)
        }
        .background(alignment: .top) {
            LinearGradient(colors: [glow.opacity(p.isDark ? 0.9 : 0.35), .clear], startPoint: .top, endPoint: .bottom).frame(height: 380).ignoresSafeArea()
        }
        .background(p.background)
        .artColor(app.player.current, into: $glow)
        .toolbar(.hidden, for: .navigationBar)
        .refreshable { await app.library.scan() }
    }

    private var topBar: some View {
        HStack(spacing: 10) {
            Button { router.go(.profile) } label: { Avatar(size: 34) }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) { ForEach(Filter.allCases, id: \.self) { f in Pill(title: f.rawValue, selected: filter == f) { filter = f } } }
            }
            Button { router.go(.stats) } label: { Image(systemName: "chart.bar.xaxis").font(.system(size: 18, weight: .semibold)).foregroundStyle(p.text).frame(width: 36, height: 36) }
        }
        .padding(.horizontal, 16).padding(.top, 8)
    }

    private var emptyLibrary: some View {
        VStack(spacing: 16) {
            EmptyState(title: "Add your music", message: "Open the Files app › On My iPhone › Spitify and drop songs into the Music folder — or import them here. FLAC, ALAC, MP3, AAC, WAV and AIFF all work.", icon: "square.and.arrow.down.on.square")
            ImportButton()
        }
    }

    private var playlistTiles: [Tile] {
        var t = [Tile(id: "liked", title: "Liked Songs", subtitle: songCount(app.library.liked.count), song: app.library.likedSongs.first) { router.go(.smart(.liked)) }]
        t += app.library.playlists.map { pl in Tile(id: pl.id, title: pl.name, subtitle: songCount(pl.songIds.count), song: app.library.songs(of: pl).first) { router.go(.playlist(pl.id)) } }
        t += app.mixes.map { m in Tile(id: m.id, title: m.title, subtitle: m.description, song: m.cover, mix: m) { router.go(.mix(m.id)) } }
        return t
    }

    private func grid(_ tiles: [Tile]) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 14)], spacing: 18) {
            ForEach(tiles) { t in MediaTile(tile: t, width: (UIScreen.main.bounds.width - 46) / 2) }
        }.padding(.horizontal, 16).padding(.top, 12)
    }

    @ViewBuilder private var allContent: some View {
        let lib = app.library.library
        hero
        // Quick picks
        let recentAlbums = uniqueAlbums(app.library.recentlyPlayed)
        var quick: [Tile] = [Tile(id: "liked", title: "Liked Songs", subtitle: "", song: app.library.likedSongs.first) { router.go(.smart(.liked)) }]
        let _ = app.library.playlists.prefix(2).forEach { pl in quick.append(Tile(id: pl.id, title: pl.name, subtitle: "", song: app.library.songs(of: pl).first) { router.go(.playlist(pl.id)) }) }
        let _ = recentAlbums.prefix(3).forEach { a in quick.append(Tile(id: "q" + a.id, title: a.title, subtitle: "", song: a.cover) { router.go(.album(a.id)) }) }
        let _ = app.mixes.prefix(4).forEach { m in quick.append(Tile(id: "q" + m.id, title: m.title, subtitle: "", song: m.cover, mix: m) { router.go(.mix(m.id)) }) }
        let _ = lib.albums.prefix(8).forEach { a in quick.append(Tile(id: "qa" + a.id, title: a.title, subtitle: "", song: a.cover) { router.go(.album(a.id)) }) }
        let picks = Array(quick.reduce(into: [Tile]()) { acc, t in if !acc.contains(where: { $0.title == t.title }) { acc.append(t) } }.prefix(6))
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) { ForEach(picks) { QuickTile(tile: $0) } }
            .padding(.horizontal, 16).padding(.top, 10)

        ForEach(Mix.Section.allCases, id: \.self) { section in
            let inSection = app.mixes.filter { $0.section == section }
            TileShelf(title: section == .madeForYou && !app.profile.name.isEmpty ? "Made for \(app.profile.name)" : section.rawValue,
                      tiles: inSection.map { m in Tile(id: m.id, title: m.title, subtitle: m.description, song: m.cover, mix: m) { router.go(.mix(m.id)) } })
        }
        TileShelf(title: "Jump back in", tiles: recentAlbums.prefix(12).map { a in Tile(id: "j" + a.id, title: a.title, subtitle: a.artist, song: a.cover) { router.go(.album(a.id)) } })
        TileShelf(title: "Recently added", tiles: uniqueAlbums(app.library.recentlyAdded).prefix(12).map { a in Tile(id: "n" + a.id, title: a.title, subtitle: a.artist, song: a.cover) { router.go(.album(a.id)) } },
                  action: "Show all") { router.go(.smart(.recentlyAdded)) }
        let topArtists = (app.model?.topArtists ?? lib.artists.map(\.name)).prefix(12).compactMap { lib.artistByName[$0] }
        TileShelf(title: "Your top artists", tiles: topArtists.map { a in Tile(id: "ar" + a.name, title: a.name, subtitle: "Artist", song: a.cover, circle: true) { router.go(.artist(a.name)) } })
    }

    @ViewBuilder private var hero: some View {
        // While something is playing the mini player already shows it, so the hero is only the idle prompt.
        if app.player.current == nil, !app.library.library.isEmpty {
            Button { app.player.play(app.library.library.songs, shuffle: true, source: "All songs") } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("START LISTENING").text(.labelS).foregroundStyle(p.secondary)
                        Text("Shuffle your library").text(.title).foregroundStyle(p.text)
                        Text("\(app.library.library.songs.count) songs ready to play").text(.bodyS).foregroundStyle(p.secondary)
                    }
                    Spacer()
                    PlayButton(playing: false, size: 50) { app.player.play(app.library.library.songs, shuffle: true, source: "All songs") }
                }
                .padding(16).frame(height: 108).background(p.tint, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }.buttonStyle(.pressable(0.98)).padding(.horizontal, 16).padding(.vertical, 8)
        }
    }

    private func uniqueAlbums(_ songs: [Song]) -> [Album] {
        var seen = Set<String>()
        return songs.compactMap { s in seen.insert(s.albumKey).inserted ? app.library.library.albumById[s.albumKey] : nil }
    }
}

/// Import files or whole folders from Files / iCloud Drive / USB storage.
struct ImportButton: View {
    var audiobooks = false
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    @State private var picking = false
    @State private var importing = false
    var body: some View {
        Button { picking = true } label: {
            Label(importing ? "Importing…" : (audiobooks ? "Import audiobooks" : "Import music"), systemImage: "plus.circle.fill")
                .text(.label).foregroundStyle(p.onAccent).padding(.horizontal, 20).padding(.vertical, 12).background(p.accent, in: Capsule())
        }
        .buttonStyle(.pressable)
        .fileImporter(isPresented: $picking, allowedContentTypes: [.audio, .folder], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result else { return }
            importing = true
            Task { _ = await app.library.importItems(urls, asAudiobooks: audiobooks); importing = false }
        }
    }
}
