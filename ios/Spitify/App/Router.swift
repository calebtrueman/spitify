import SwiftUI
import Observation

enum Route: Hashable {
    case album(String), artist(String), playlist(String), mix(String), smart(SmartKind), genre(String), folder(String)
    case show(String), book(String), localBook(String)
    case settings, appearance, equalizer, profile, stats, releases
    case catalogAlbum(OnlineAlbum), catalogSong(OnlineTrack)
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
    var sharing: SharedPlaylist?
    var sharingError: String?
    func share(name: String, songs: [Song], kind: String = "playlist", app: AppModel) {
        do { sharing = try app.social.create(name: name, songs: songs, kind: kind); playerOpen = false }
        catch { sharingError = error.localizedDescription }
    }
    var info: Song?

    func go(_ r: Route) {
        playerOpen = false
        paths[tab, default: .init()].append(r)
    }
    func path(_ t: Tab) -> Binding<NavigationPath> {
        Binding(get: { self.paths[t] ?? .init() }, set: { self.paths[t] = $0 })
    }
    func reselect(_ t: Tab) { if t == .home || tab == t { paths[t] = .init() }; tab = t }
    @ObservationIgnored lazy var tabTapDelegate = HomeTabDelegate(router: self)
}

@MainActor final class HomeTabDelegate: NSObject, UITabBarControllerDelegate {
    weak var router: Router?
    weak var original: UITabBarControllerDelegate?
    init(router: Router) { self.router = router }
    func tabBarController(_ controller: UITabBarController, shouldSelect viewController: UIViewController) -> Bool {
        if controller.viewControllers?.first === viewController { router?.reselect(.home) }
        return original?.tabBarController?(controller, shouldSelect: viewController) ?? true
    }
    override func responds(to selector: Selector!) -> Bool { super.responds(to: selector) || original?.responds(to: selector) == true }
    override func forwardingTarget(for selector: Selector!) -> Any? { original }
}

struct HomeTabTapObserver: UIViewControllerRepresentable {
    var router: Router
    final class Observer: UIViewController {
        var router: Router?
        override func viewDidAppear(_ animated: Bool) { super.viewDidAppear(animated); connect() }
        override func didMove(toParent parent: UIViewController?) { super.didMove(toParent: parent); DispatchQueue.main.async { self.connect() } }
        func connect() {
            guard let tabs = tabBarController, let router else { return }
            let delegate = router.tabTapDelegate
            if tabs.delegate !== delegate { delegate.original = tabs.delegate; tabs.delegate = delegate }
        }
    }
    func makeUIViewController(context: Context) -> Observer { let view = Observer(); view.router = router; return view }
    func updateUIViewController(_ controller: Observer, context: Context) { controller.router = router; DispatchQueue.main.async { controller.connect() } }
}
