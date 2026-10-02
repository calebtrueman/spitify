import SwiftUI

struct SearchView: View {
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @Environment(\.palette) private var p
    @State private var query = ""
    @State private var online = false

    var body: some View {
        let lib = app.library.library
        let words = foldForSearch(query).split(separator: " ").map(String.init)
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if words.isEmpty {
                    if !lib.genres.isEmpty { SectionHeader(title: "Browse your genres") ; browse(lib.genres.map { ($0.name, $0.songs, Route.genre($0.name)) }) }
                    if lib.folders.count > 1 { SectionHeader(title: "Browse folders"); browse(lib.folders.map { ($0.name, $0.songs, Route.folder($0.path)) }) }
                } else {
                    OnlineMusicView(query: query)
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
