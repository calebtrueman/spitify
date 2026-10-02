import SwiftUI
import Observation

enum Route: Hashable {
    case album(String), artist(String), playlist(String), mix(String), smart(SmartKind), genre(String), folder(String)
    case show(String), book(String), localBook(String)
    case settings, appearance, equalizer, profile, stats
}

enum SmartKind: String, Hashable, CaseIterable {
    case allSongs = "All Songs", recentlyAdded = "Recently added", recentlyPlayed = "Recently played", mostPlayed = "On repeat"
}

enum Tab: Hashable { case home, search, podcasts, books, library }

/// Tab selection + one navigation path per tab, so menus anywhere can navigate.
@MainActor @Observable
final class Router {
    var tab: Tab = .home
    var paths: [Tab: NavigationPath] = [.home: .init(), .search: .init(), .podcasts: .init(), .books: .init(), .library: .init()]
    var playerOpen = false
    var editing: (songs: [Song], albumMode: Bool)?
    var addingToPlaylist: [Song]?
    var info: Song?

    func go(_ r: Route) {
        playerOpen = false
        paths[tab, default: .init()].append(r)
    }
    func path(_ t: Tab) -> Binding<NavigationPath> {
        Binding(get: { self.paths[t] ?? .init() }, set: { self.paths[t] = $0 })
    }
    func reselect(_ t: Tab) { if tab == t { paths[t] = .init() } else { tab = t } }
}
