import SwiftUI

struct SearchView: View {
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @Environment(\.palette) private var p
    @State private var query = ""

    var body: some View {
        let lib = app.library.library
        let words = foldForSearch(query).split(separator: " ").map(String.init)
        let match: (String) -> Bool = { hay in let h = foldForSearch(hay); return words.allSatisfy { h.contains($0) } }
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if words.isEmpty {
                    if !lib.genres.isEmpty { SectionHeader(title: "Browse your genres") ; browse(lib.genres.map { ($0.name, $0.songs, Route.genre($0.name)) }) }
                    if lib.folders.count > 1 { SectionHeader(title: "Browse folders"); browse(lib.folders.map { ($0.name, $0.songs, Route.folder($0.path)) }) }
                } else {
                    let artists = lib.artists.filter { match($0.name) }.prefix(20)
                    let albums = lib.albums.filter { match("\($0.title) \($0.artist)") }.prefix(20)
                    let playlists = app.library.playlists.filter { match($0.name) }
                    let songs = Array(lib.songs.filter { match("\($0.title) \($0.artist) \($0.album)") }.prefix(60))
                    if artists.isEmpty && albums.isEmpty && songs.isEmpty && playlists.isEmpty {
                        EmptyState(title: "No results for “\(query)”", message: "Check the spelling, or try fewer words.", icon: "magnifyingglass")
                    }
                    TileShelf(title: "Artists", tiles: artists.map { a in Tile(id: a.name, title: a.name, subtitle: "Artist", song: a.cover, circle: true) { router.go(.artist(a.name)) } }, width: 116)
                    TileShelf(title: "Albums", tiles: albums.map { a in Tile(id: a.id, title: a.title, subtitle: a.artist, song: a.cover) { router.go(.album(a.id)) } }, width: 136)
                    TileShelf(title: "Playlists", tiles: playlists.map { pl in Tile(id: pl.id, title: pl.name, subtitle: "Playlist", song: app.library.songs(of: pl).first) { router.go(.playlist(pl.id)) } }, width: 136)
                    if !songs.isEmpty {
                        SectionHeader(title: "Songs")
                        ForEach(Array(songs.enumerated()), id: \.element.id) { i, s in SongRow(song: s) { app.player.play(songs, from: i, shuffle: false, source: "Search: \(query)") } }
                    }
                }
            }.padding(.bottom, 24)
        }
        .background(p.background)
        .navigationTitle("Search")
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "What do you want to listen to?")
    }

    private func browse(_ items: [(String, [Song], Route)]) -> some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
            ForEach(items, id: \.0) { name, songs, route in
                Button { router.go(route) } label: {
                    ZStack(alignment: .topLeading) {
                        fallbackColor(name)
                        VStack(alignment: .leading) { Text(name).text(.title).foregroundStyle(.white).lineLimit(2); Text(songCount(songs.count)).text(.caption).foregroundStyle(.white.opacity(0.8)) }.padding(12)
                        ArtworkView(songs.first, cornerRadius: 4).frame(width: 64, height: 64).rotationEffect(.degrees(25)).offset(x: 14, y: 14)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                    }
                    .frame(height: 100).clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                }.buttonStyle(.pressable)
            }
        }.padding(.horizontal, 16)
    }
}
