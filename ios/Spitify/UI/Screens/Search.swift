import SwiftUI

struct SearchView: View {
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @Environment(\.palette) private var p
    @Environment(\.themeSettings) private var theme
    @Environment(\.dynamicTypeSize) private var textSize
    @State private var query = ""

    var body: some View {
        let lib = app.library.library
        let words = foldForSearch(query).split(separator: " ").map(String.init)
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ThemeScene(compact: true)
                if words.isEmpty {
                    if !lib.albums.isEmpty { SectionHeader(title: "Your albums"); browse(lib.albums.map { ($0.title, $0.songs, Route.album($0.id)) }) }
                    if !lib.genres.isEmpty { SectionHeader(title: "Browse your genres") ; browse(lib.genres.map { ($0.name, $0.songs, Route.genre($0.name)) }) }
                } else {
                    OnlineMusicView(query: query)
                }
            }.padding(.bottom, 24)
        }
        .background(p.background)
        .navigationTitle("Search")
        .toolbar { ToolbarItem(placement: .topBarTrailing) { NavigationLink { SpotifyCodeScanView() } label: { Image(systemName: "barcode.viewfinder") }.accessibilityLabel("Scan Spotify code") } }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "What do you want to listen to?")
    }

    private func browse(_ items: [(String, [Song], Route)]) -> some View {
        let roomyText = textSize >= .xxLarge || theme.textScale == .huge
        return LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: roomyText ? 1 : 2), spacing: 12) {
            ForEach(items, id: \.2) { name, songs, route in
                let background = fallbackColor(name)
                let ink: Color = background.luminance > 0.179 ? .black : .white
                Button { router.go(route) } label: {
                    ZStack(alignment: .topLeading) {
                        background
                        VStack(alignment: .leading) { Text(name).text(.title).lineLimit(2); Text(songCount(songs.count)).text(.caption) }.foregroundStyle(ink).fixedSize(horizontal: false, vertical: true).padding(12).padding(.bottom, 40)
                        ArtworkView(songs.first, cornerRadius: 4).frame(width: 64, height: 64).rotationEffect(.degrees(25)).offset(x: 14, y: 14)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                    }
                    .frame(minHeight: roomyText ? 140 : 100).clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                }.buttonStyle(.pressable)
            }
        }.padding(.horizontal, 16)
    }
}
