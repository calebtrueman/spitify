import SwiftUI

@main
struct SpitifyApp: App {
    @UIApplicationDelegateAdaptor(MusicBackgroundAppDelegate.self) private var downloadDelegate
    @State private var app = AppModel.shared
    @State private var router = Router()
    @Environment(\.colorScheme) private var scheme

    var body: some Scene {
        WindowGroup {
            ThemedRoot().environment(app).environment(router)
        }
    }
}

/// Applies the user's appearance settings (palette, font, accent from artwork) to everything.
struct ThemedRoot: View {
    @Environment(AppModel.self) private var app
    @Environment(\.colorScheme) private var scheme
    @State private var artAccent: Color?
    var body: some View {
        let t = app.theme
        let palette = Palette.make(t, scheme: scheme, artAccent: artAccent)
        Group {
            if app.profile.onboarded { RootView() } else { OnboardingView() }
        }
        .environment(\.palette, palette)
        .environment(\.themeSettings, t)
        .tint(palette.accent)
        .preferredColorScheme(t.mode == .system ? nil : (t.mode == .light ? .light : .dark))
        .onChange(of: t.haptics, initial: true) { Haptics.enabled = t.haptics }
        .task(id: t.accentSource == .artwork ? app.player.current?.albumKey : nil) {
            guard t.accentSource == .artwork, let s = app.player.current, let img = await ArtCache.shared.load(key: s.albumKey, remote: s.artURL) else { artAccent = nil; return }
            artAccent = ArtCache.shared.color(for: s.albumKey, image: img).mix(.white, 0.3)
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @Environment(\.palette) private var p
    @Environment(\.scenePhase) private var phase

    var body: some View {
        @Bindable var router = router
        TabView(selection: Binding(get: { router.tab }, set: { router.reselect($0) })) {
            tab(.home, "Home", "house.fill") { HomeView() }
            tab(.search, "Search", "magnifyingglass") { SearchView() }
            tab(.podcasts, "Podcasts", "dot.radiowaves.left.and.right") { PodcastsView() }
            tab(.books, "Books", "book.fill") { BooksView() }
            tab(.library, "Your Library", "books.vertical.fill") { LibraryView() }
        }
        .tint(p.text)
        .fullScreenCover(isPresented: $router.playerOpen) { NowPlayingView() }
        .sheet(item: Binding(get: { router.info }, set: { router.info = $0 })) { SongInfoSheet(song: $0) }
        .sheet(isPresented: Binding(get: { router.addingToPlaylist != nil }, set: { if !$0 { router.addingToPlaylist = nil } })) {
            AddToPlaylistSheet(songs: router.addingToPlaylist ?? [])
        }
        .fullScreenCover(isPresented: Binding(get: { router.editing != nil }, set: { if !$0 { router.editing = nil } })) {
            if let e = router.editing { MetadataEditor(songs: e.songs, albumMode: e.albumMode) }
        }
        .overlay(alignment: .top) {
            if let m = app.player.message {
                Text(m).text(.label).foregroundStyle(p.text).padding(.horizontal, 16).padding(.vertical, 10)
                    .background(.ultraThinMaterial, in: Capsule()).padding(.top, 8).transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.spring(duration: 0.35), value: app.player.message)
        .task { await app.start() }
        .onChange(of: phase) { _, new in if new == .active { app.musicDownloads.resumePending(); Task { await app.library.scan() } } }
        .onOpenURL { url in Task { await openExternal(url) } }
    }

    private func tab<V: View>(_ t: Tab, _ title: String, _ icon: String, @ViewBuilder content: () -> V) -> some View {
        NavigationStack(path: router.path(t)) {
            content()
                .navigationDestination(for: Route.self) { RouteView(route: $0).toolbar(.visible, for: .navigationBar) }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            MiniPlayer().background(p.background)
        }
        .tint(p.accent)
        .background(p.background)
        .tabItem { Label(title, systemImage: icon) }
        .tag(t)
        .toolbarBackground(p.background.opacity(0.94), for: .tabBar)
        .toolbarBackground(.visible, for: .tabBar)
    }

    /// "Open in Spitify" from Files/AirDrop: copy into the Music folder and play it.
    private func openExternal(_ url: URL) async {
        _ = await app.library.importItems([url], asAudiobooks: url.pathExtension.lowercased() == "m4b")
        if let s = app.library.library.songs.first(where: { $0.fileName == url.lastPathComponent }) { app.player.play([s], source: "Opened file") }
    }
}

struct RouteView: View {
    var route: Route
    @Environment(AppModel.self) private var app
    var body: some View {
        switch route {
        case .album(let id): AlbumView(id: id)
        case .catalogAlbum(let album): OnlineAlbumView(album: album)
        case .catalogSong(let track): OnlineAlbumView(album: OnlineAlbum(id: track.releaseID, title: track.album, artist: track.artist, artwork: track.artwork), single: track)
        case .artist(let name): ArtistView(name: name)
        case .playlist(let id): PlaylistView(id: id)
        case .mix(let id): MixView(id: id)
        case .smart(let k): SmartView(kind: k)
        case .genre(let g): CollectionView(title: g, kind: "Genre", subtitle: "Every \(g) track you have", art: app.library.library.genres.first { $0.name == g }?.songs.first,
                                          songs: app.library.library.genres.first { $0.name == g }?.songs ?? [])
        case .folder(let f): CollectionView(title: (f as NSString).lastPathComponent, kind: "Folder", subtitle: "/\(f)", art: app.library.library.folders.first { $0.path == f }?.songs.first,
                                           songs: app.library.library.folders.first { $0.path == f }?.songs ?? [])
        case .show(let id): ShowView(id: id)
        case .book(let id): BookView(showId: id)
        case .localBook(let key): BookView(localKey: key)
        case .settings: SettingsView()
        case .appearance: AppearanceView()
        case .equalizer: EqualizerView()
        case .profile: ProfileView()
        case .stats: StatsView()
        }
    }
}
