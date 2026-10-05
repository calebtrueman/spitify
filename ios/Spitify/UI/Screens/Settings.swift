import PhotosUI
import SwiftUI
import AppIntents

struct SettingsView: View {
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @Environment(\.palette) private var p
    var body: some View {
        @Bindable var app = app
        @Bindable var lib = app.library
        @Bindable var lyrics = app.lyrics
        @Bindable var player = app.player
        @Bindable var downloads = app.musicDownloads
        AppForm {
            Section {
                NavigationLink(value: Route.appearance) { Label("Appearance", systemImage: "paintpalette") }
                NavigationLink(value: Route.equalizer) { Label("Equaliser & sound", systemImage: "slider.vertical.3") }
                NavigationLink { FriendsSettingsView() } label: { Label("Sharing connection", systemImage: "network") }
                NavigationLink { HiddenArtistsView() } label: { Label("Hidden artists", systemImage: "eye.slash") }
                NavigationLink { VoiceHelpView() } label: { Label("Siri & Shortcuts", systemImage: "waveform") }
            }
            Section {
                Toggle("Show recommendations", isOn: $app.showRecommendations)
            } header: { Text("Home") } footer: { Text("Show suggested mixes, artist radio, throwbacks and top artists. Turn this off for a simpler Home screen. You can turn it back on anytime.") }
            Section("Playback") { PlaybackSettings().padding(.vertical, 6) }
            Section("Downloads & listening") {
                Toggle("Download over Wi-Fi only", isOn: $downloads.wifiOnly)
                Button("Clear listening cache") { ListeningCache.clear() }
                Text("Streaming uses a temporary cache of up to 1 GB. Older streams are cleared automatically. Downloads stay until you remove them.").text(.caption).foregroundStyle(.secondary)
            }
            Section {
                Toggle("Full-screen lock-screen art", isOn: $player.lockScreenArt)
            } header: { Text("Lock screen") } footer: { Text("Show portrait album art on iOS 26 and later, including while paused. Tap the lock-screen artwork to expand it. Stopping or 10 minutes paused removes the artwork while Spitify is running; your wallpaper is never replaced.") }
            Section {
                Toggle("Find lyrics online automatically", isOn: $lyrics.onlineEnabled)
            } header: { Text("Lyrics") } footer: { Text("Embedded lyrics and matching .lrc files (next to the song or in a “Lyrics” folder) are always used. Online lookup sends only artist, title, album and length.") }
            Section {
                Toggle("Fill in missing song & book info", isOn: $app.autoFix)
                Toggle("Fetch missing album art", isOn: $app.onlineArt)
                Button(app.fixing ? "Working…" : "Run now") { Task { UserDefaults.standard.removeObject(forKey: "missingAttemptsV2"); await app.backgroundFixes() } }.disabled(app.fixing)
            } header: { Text("Metadata & artwork") } footer: { Text("Missing details and covers are filled automatically from matching online results and saved into local files. Existing tags and covers stay in place.") }
            Section {
                Toggle("Include Music app library", isOn: $lib.includeMusicLibrary).onChange(of: lib.includeMusicLibrary) { Task { await app.library.scan() } }
                Button(app.library.scanning ? "Scanning…" : "Rescan") { Task { await app.library.scan() } }
                LabeledContent("Songs", value: "\(app.library.library.songs.count)")
                LabeledContent("Albums", value: "\(app.library.library.albums.count)")
            } header: { Text("Library") } footer: { Text("Spitify plays files in Files › On My iPhone › Spitify, plus downloaded, DRM-free songs from the Music app. Apple Music streaming tracks are protected and can't be played by other apps.") }
            Section("Automatic backup") {
                Text("Settings, profiles, playlists and custom covers are included in your iPhone's device backup when it is enabled. Spitify does not need an account.")
                Text("To remove the app and keep its data on this phone, choose Offload App in iPhone Storage. Delete App removes local data; reinstalling alone does not restore a device backup.").text(.caption)
            }
            Section("Music video quality") {
                LabeledContent("Last video", value: VideoQuality.shared.resolution)
                Text("Spitify requests the highest available HD quality. A wide video is cropped to fill your screen. The original video must offer HD.").text(.caption).foregroundStyle(.secondary)
            }
            Section("About") {
                LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                Link("Source code on GitHub", destination: URL(string: "https://github.com/calebtrueman/spitify")!)
            }
        }
        .scrollContentBackground(.hidden)
        .background(p.background)
        .navigationTitle("Settings")
    }
}

struct VoiceHelpView: View {
    @Environment(\.palette) private var p
    var body: some View {
        AppForm {
            Section {
                Text("This build supports Shortcuts. Apple's built-in “Play … in Spitify” music command also needs a Siri-enabled signing profile.")
                Text("Hey Siri, resume in Spitify player")
                Text("Hey Siri, pause Spitify player")
                Text("Hey Siri, play Liked Songs in Spitify player")
            } header: { Text("Try saying") } footer: {
                Text("Say Spitify as “spit-if-eye.” Open the app and load your library before your first voice request.")
            }
            Section {
                Text("If Siri hears Spotify, give your shortcut a name that sounds different.")
                Text("1. Open Shortcuts and create a new shortcut.")
                Text("2. Search for Spitify and add “Resume Spitify.”")
                Text("3. Name the shortcut “Pocket music.”")
                Text("4. Say “Hey Siri, Pocket music.”")
                ShortcutsLink().shortcutsLinkStyle(.automaticOutline)
                    .frame(maxWidth: .infinity).padding(.vertical, 4)
            } header: { Text("Pick your own voice command") } footer: {
                Text("You can make another shortcut for Pause, Next song, or a favourite playlist. Say the name you gave that shortcut.")
            }
        }
        .scrollContentBackground(.hidden).background(p.background)
        .navigationTitle("Siri & Shortcuts")
    }
}

struct AppearanceView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    var body: some View {
        @Bindable var app = app
        AppForm {
            Section("Preview") {
                HStack(spacing: 14) {
                    ArtworkView(app.library.library.songs.first, cornerRadius: 8).frame(width: 64, height: 64)
                    VStack(alignment: .leading) {
                        Text(app.library.library.songs.first?.title ?? "Song title").text(.title)
                        HStack(spacing: 6) { EqualizerBars(playing: true).frame(width: 14, height: 14); Text("Now playing").text(.label).foregroundStyle(p.accent) }
                    }
                    Spacer()
                    PlayButton(playing: true, size: 46) {}
                }
            }
            Section("Art themes") { ArtThemeGallery() }
            Section { AppIconSettingsLink() }
            ForEach(["Everyday", "Kids"], id: \.self) { group in
                Section(group == "Kids" ? "Made for little listeners" : "Ready-made looks") {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 12) {
                            ForEach(themePresets.filter { $0.group == group }) { preset in
                                Button { app.theme = preset.applying(to: app.theme) } label: {
                                    VStack(spacing: 8) {
                                        ZStack {
                                            RoundedRectangle(cornerRadius: 12).fill(Color(hex: preset.background))
                                            Text(preset.symbol).font(.system(size: 32)).foregroundStyle(Color(hex: preset.accent))
                                        }.frame(width: 104, height: 72)
                                        Text(preset.name).text(.label).foregroundStyle(p.text)
                                    }.padding(8)
                                        .background(p.tint, in: RoundedRectangle(cornerRadius: 16))
                                        .overlay(RoundedRectangle(cornerRadius: 16).stroke(preset.matches(app.theme) ? p.accent : .clear, lineWidth: 2))
                                        .contentShape(Rectangle())
                                }.buttonStyle(.plain).accessibilityAddTraits(preset.matches(app.theme) ? .isSelected : [])
                            }
                        }.padding(.vertical, 4)
                    }
                }
            }
            Section("Theme") {
                Picker("Theme", selection: Binding(get: { app.theme.mode }, set: { app.theme.mode = $0; app.theme.backdrop = nil })) { ForEach(ThemeMode.allCases, id: \.self) { Text($0.rawValue) } }.pickerStyle(.menu)
            }
            Section("Accent colour") {
                Picker("Source", selection: $app.theme.accentSource) { ForEach(AccentSource.allCases, id: \.self) { Text($0.rawValue) } }.pickerStyle(.segmented)
                if app.theme.accentSource == .preset {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 14) {
                            ForEach(accentPresets, id: \.1) { name, hex in
                                Button { app.theme.accent = hex } label: {
                                    VStack(spacing: 6) {
                                        Circle().fill(Color(hex: hex)).frame(width: 44, height: 44)
                                            .overlay { if app.theme.accent == hex { Image(systemName: "checkmark").font(.system(size: 16, weight: .bold)).foregroundStyle(.black) } }
                                            .overlay(Circle().stroke(p.text, lineWidth: app.theme.accent == hex ? 3 : 0))
                                        Text(name).text(.labelS).foregroundStyle(p.secondary)
                                    }
                                }.buttonStyle(.plain)
                            }
                        }.padding(.vertical, 6)
                    }
                } else { Text("The accent follows the album art of whatever's playing.").text(.caption).foregroundStyle(p.secondary) }
            }
            Section("Typeface & text") {
                Picker("Typeface", selection: $app.theme.font) { ForEach(AppFont.allCases, id: \.self) { Text($0.rawValue) } }
                Picker("Text size", selection: $app.theme.textScale) { ForEach(TextScale.allCases, id: \.self) { Text($0.rawValue) } }
            }
            Section("Artwork & player") {
                Picker("Artwork shape", selection: $app.theme.artShape) { ForEach(ArtShape.allCases, id: \.self) { Text($0.rawValue) } }
                Picker("Now Playing style", selection: $app.theme.playerStyle) { ForEach(PlayerStyle.allCases, id: \.self) { Text($0.rawValue) } }
            }
            Section("Effects") {
                Toggle("Artwork colours", isOn: $app.theme.artworkTint)
                Toggle("Blurred backdrop", isOn: $app.theme.blur)
                Toggle("Reduce motion", isOn: $app.theme.reduceMotion)
                Toggle("Haptic feedback", isOn: $app.theme.haptics)
            }
            Section { Button("Reset appearance", role: .destructive) { app.theme = ThemeSettings() } }
        }
        .scrollContentBackground(.hidden).background(p.background)
        .navigationTitle("Appearance")
    }
}

struct EqualizerView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    @State private var dragging: Int?
    private let maxDb: Float = 12

    var body: some View {
        @Bindable var player = app.player
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    VStack(alignment: .leading) { Text("10-band precision EQ").text(.title); Text("Applies to downloads and music files you add").text(.caption).foregroundStyle(p.secondary) }
                    Spacer()
                    Toggle("", isOn: $player.eq.enabled).labelsHidden()
                }.padding(16)
                VStack(alignment: .leading, spacing: 8) {
                    HStack { Text(player.eq.preset).text(.titleS); Spacer(); Text("±12 dB").text(.labelS).foregroundStyle(p.secondary) }
                    curve.frame(height: 220)
                    HStack {
                        ForEach(0..<10, id: \.self) { i in
                            VStack(spacing: 1) {
                                Text(eqFrequencies[i] >= 1000 ? "\(Int(eqFrequencies[i] / 1000))k" : "\(Int(eqFrequencies[i]))").text(.labelS).foregroundStyle(p.secondary)
                                Text(String(format: "%+.0f", player.eq.gains[i])).text(.labelS).foregroundStyle(abs(player.eq.gains[i]) > 0.01 ? p.accent : p.tertiary)
                            }.frame(maxWidth: .infinity)
                        }
                    }
                    Text("Drag the points to shape the sound · double-tap a point to reset it").text(.caption).foregroundStyle(p.secondary)
                }
                .padding(16).background(p.tint, in: RoundedRectangle(cornerRadius: 20)).padding(.horizontal, 16)
                .opacity(player.eq.enabled ? 1 : 0.45)
                SectionHeader(title: "Presets")
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(eqPresets, id: \.0) { name, gains in Pill(title: name, selected: player.eq.preset == name) { player.eq.gains = gains; player.eq.preset = name; player.eq.enabled = true } }
                    }.padding(.horizontal, 16)
                }
                SectionHeader(title: "Sound")
                VStack(spacing: 14) {
                    slider("Bass boost", value: $player.eq.bass, range: 0...1, label: "\(Int(player.eq.bass * 100))%")
                    Label("Clipping protection is always on", systemImage: "checkmark.shield.fill").text(.body)
                    Text("Boosted bands get extra headroom so they cannot turn the whole song up unexpectedly.").text(.caption).foregroundStyle(p.secondary)
                }.padding(.horizontal, 16)
            }.padding(.bottom, 30)
        }
        .background(p.background)
        .navigationTitle("Equaliser")
    }

    private func slider(_ title: String, value: Binding<Float>, range: ClosedRange<Float>, label: String) -> some View {
        VStack(alignment: .leading) {
            HStack { Text(title).text(.body); Spacer(); Text(label).text(.label).foregroundStyle(p.accent) }
            Slider(value: Binding(get: { value.wrappedValue }, set: { value.wrappedValue = $0; app.player.eq.enabled = true }), in: range)
        }
    }

    private func points(_ size: CGSize) -> [CGPoint] {
        app.player.eq.gains.enumerated().map { i, gain in
            CGPoint(x: size.width * CGFloat(i) / 9, y: size.height / 2 - CGFloat(gain / maxDb) * size.height / 2)
        }
    }

    private func smooth(_ pts: [CGPoint]) -> Path {
        Path { path in
            path.move(to: pts[0])
            for i in 0..<(pts.count - 1) {
                let p0 = pts[max(i - 1, 0)], p1 = pts[i], p2 = pts[i + 1], p3 = pts[min(i + 2, pts.count - 1)]
                let c1 = CGPoint(x: p1.x + (p2.x - p0.x) / 6, y: p1.y + (p2.y - p0.y) / 6)
                let c2 = CGPoint(x: p2.x - (p3.x - p1.x) / 6, y: p2.y - (p3.y - p1.y) / 6)
                path.addCurve(to: p2, control1: c1, control2: c2)
            }
        }
    }

    private func gridLine(_ db: Float, _ size: CGSize) -> some View {
        let y = size.height / 2 - CGFloat(db / maxDb) * size.height / 2
        return Path { $0.move(to: CGPoint(x: 0, y: y)); $0.addLine(to: CGPoint(x: size.width, y: y)) }
            .stroke(p.text.opacity(db == 0 ? 0.25 : 0.08), style: StrokeStyle(lineWidth: 1, dash: db == 0 ? [] : [4, 4]))
    }

    private func setBand(_ band: Int, y: CGFloat, height: CGFloat) {
        let db = (Float((height / 2 - y) / (height / 2)) * maxDb).clamped(-maxDb, maxDb)
        app.player.eq.gains[band] = (db * 2).rounded() / 2
        app.player.eq.preset = "Custom"
        app.player.eq.enabled = true
    }

    private var curve: some View {
        GeometryReader { g in
            let size = g.size
            let pts = points(size)
            let line = smooth(pts)
            var fill = line
            let _ = fill.addLine(to: CGPoint(x: size.width, y: size.height / 2))
            let _ = fill.addLine(to: CGPoint(x: 0, y: size.height / 2))
            ZStack {
                ForEach([-12, -6, 0, 6, 12] as [Float], id: \.self) { gridLine($0, size) }
                fill.fill(LinearGradient(colors: [p.accent.opacity(0.35), p.accent.opacity(0.02)], startPoint: .top, endPoint: .bottom))
                line.stroke(p.accent, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                ForEach(0..<10, id: \.self) { i in
                    Circle().fill(p.accent).frame(width: dragging == i ? 22 : 13)
                        .overlay(Circle().fill(.white).frame(width: dragging == i ? 9 : 5))
                        .position(pts[i])
                }
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { v in
                    let band = dragging ?? Int((v.startLocation.x / size.width * 9).rounded()).clamped(0, 9)
                    if dragging == nil { dragging = band; Haptics.soft() }
                    setBand(band, y: v.location.y, height: size.height)
                }
                .onEnded { _ in dragging = nil })
            .simultaneousGesture(SpatialTapGesture(count: 2).onEnded { v in
                app.player.eq.gains[Int((v.location.x / size.width * 9).rounded()).clamped(0, 9)] = 0
            })
        }
    }
}

extension Comparable { func clamped(_ lo: Self, _ hi: Self) -> Self { min(max(self, lo), hi) } }

struct ProfileView: View {
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @Environment(\.palette) private var p
    @State private var photo: PhotosPickerItem?
    @State private var renaming = false
    @State private var name = ""
    @AppStorage("socialAbout") private var about = ""
    var body: some View {
        let month = Date().addingTimeInterval(-30 * 86_400)
        let monthListens = app.library.listens.filter { $0.at > month && !$0.skipped }
        let top = (app.model?.topArtists ?? []).prefix(10).compactMap { app.library.library.artistByName[$0] }
        let genres = (app.model?.genreScore ?? [:]).filter { $0.value > 0 }.sorted { $0.value > $1.value }.prefix(6)
        let maxG = genres.first?.value ?? 1
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 18) {
                    PhotosPicker(selection: $photo, matching: .images) {
                        Avatar(size: 116).overlay(alignment: .bottomTrailing) { Image(systemName: "camera.fill").font(.system(size: 14)).foregroundStyle(.white).frame(width: 34, height: 34).background(.black.opacity(0.7), in: Circle()) }
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text("PROFILE").text(.labelS).foregroundStyle(p.secondary)
                        HStack { Text(app.profile.name.isEmpty ? "Listener" : app.profile.name).text(.display).foregroundStyle(p.text).lineLimit(1)
                            Button { name = app.profile.name; renaming = true } label: { Image(systemName: "pencil").foregroundStyle(p.secondary) } }
                        Text("\(app.library.library.songs.count) songs · \(app.mixes.count) playlists made for you").text(.bodyS).foregroundStyle(p.secondary)
                    }
                }.padding(20)
                VStack(alignment: .leading, spacing: 14) {
                    TextField("About you", text: $about, axis: .vertical).lineLimit(2...4)
                    Toggle("Public profile", isOn: Binding(get: { app.social.publicProfile }, set: { app.social.setPublicProfile($0) }))
                    Text(app.social.publicProfile ? "Anyone with your code can see your name, photo and bio." : "Only people you follow receive your name, photo and bio. Copies already saved elsewhere may remain.").text(.caption).foregroundStyle(p.secondary)
                    NavigationLink { FriendCodeView() } label: { Label("My friend code", systemImage: "qrcode") }.buttonStyle(.bordered)
                }.padding(20)
                if !app.social.playlists.isEmpty {
                    SectionHeader(title: "Your shared music")
                    ForEach(app.social.playlists) { list in
                        NavigationLink { SharedPlaylistView(initial: list) } label: {
                            HStack { Text(list.name); Spacer(); Image(systemName: "chevron.right") }.padding(.horizontal, 20).padding(.vertical, 10)
                        }
                    }
                }
                HStack(spacing: 12) {
                    stat("\(monthListens.count) plays", "This month")
                    stat(monthListens.reduce(Int64(0)) { $0 + $1.listenedMs }.formattedLong, "Listening time")
                }.padding(.horizontal, 16)
                if !top.isEmpty {
                    TileShelf(title: "Your top artists", eyebrow: "What Spitify has learned", tiles: top.map { a in Tile(id: a.name, title: a.name, subtitle: "Artist", song: a.cover, circle: true) { router.go(.artist(a.name)) } }, width: 116)
                }
                if !genres.isEmpty {
                    SectionHeader(title: "Your sound")
                    ForEach(Array(genres), id: \.key) { g, v in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(g.capitalized).text(.body).foregroundStyle(p.text)
                            GeometryReader { geo in Capsule().fill(p.tint).overlay(alignment: .leading) { Capsule().fill(p.accent).frame(width: max(8, geo.size.width * v / maxG)) } }.frame(height: 8)
                        }.padding(.horizontal, 16).padding(.vertical, 5)
                    }
                } else {
                    Text("Play some music and this fills in — Spitify learns from what you finish, skip and play together.").text(.bodyS).foregroundStyle(p.secondary).padding(16)
                }
                Button { router.go(.stats) } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Your stats").text(.title).foregroundStyle(p.text)
                            Text("Top songs, artists and albums").text(.bodyS).foregroundStyle(p.secondary)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(p.secondary)
                    }.padding(16).contentShape(Rectangle())
                }.buttonStyle(.plain)
                if !app.library.hiddenSongs.isEmpty || !app.library.hiddenArtists.isEmpty {
                    Button("Show hidden recommendations again (\(app.library.hiddenSongs.count + app.library.hiddenArtists.count))") { app.library.hiddenSongs = []; app.library.hiddenArtists = [] }.padding(16)
                }
            }.padding(.bottom, 30)
        }
        .background(LinearGradient(colors: [p.accent.mix(.black, 0.4), p.background], startPoint: .top, endPoint: .center).ignoresSafeArea())
        .navigationTitle("Profile")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: photo) { _, item in Task { if let d = try? await item?.loadTransferable(type: Data.self) { saveProfilePhoto(d, app) } } }
        .task(id: about) { do { try await Task.sleep(for: .milliseconds(650)); await app.social.syncProfile() } catch {} }
        .onChange(of: about) { _, value in if value.count > 500 { about = String(value.prefix(500)) } }
        .alert("Your name", isPresented: $renaming) { TextField("Name", text: $name); Button("Save") { app.profile.name = name.trimmingCharacters(in: .whitespaces) }; Button("Cancel", role: .cancel) {} }
    }
    private func stat(_ v: String, _ l: String) -> some View {
        VStack(alignment: .leading) { Text(v).text(.title).foregroundStyle(p.accent); Text(l).text(.caption).foregroundStyle(p.secondary) }
            .padding(14).frame(maxWidth: .infinity, alignment: .leading).background(p.tint, in: RoundedRectangle(cornerRadius: 14))
    }
}

@MainActor func saveProfilePhoto(_ data: Data, _ app: AppModel) {
    guard let jpeg = ArtCache.squareJPEG(data, side: 1536) else { return }
    try? jpeg.write(to: AppModel.photoURL, options: .atomic)
    app.profile.photoVersion += 1
}

struct StatsView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    var body: some View {
        let lib = app.library
        let counts = lib.playCounts
        let total = lib.listens.filter { !$0.skipped }.count
        let time = lib.listens.reduce(Int64(0)) { $0 + $1.listenedMs }
        let topSongs = lib.library.songs.filter { (counts[$0.id] ?? 0) > 0 }.sorted { counts[$0.id]! > counts[$1.id]! }.prefix(10)
        let topArtists = lib.library.artists.map { a in (a, a.songs.reduce(0) { $0 + (counts[$1.id] ?? 0) }) }.filter { $0.1 > 0 }.sorted { $0.1 > $1.1 }.prefix(10)
        AppList {
            Section {
                HStack { stat("\(total)", "Plays"); stat(time.formattedLong, "Listening time"); stat("\(lib.library.songs.count)", "Songs") }.listRowBackground(Color.clear)
            }
            if !topSongs.isEmpty { Section("Top songs") { ForEach(Array(topSongs.enumerated()), id: \.element.id) { i, s in row(i + 1, s.title, "\(counts[s.id]!) plays • \(s.artist)", s) } } }
            if !topArtists.isEmpty { Section("Top artists") { ForEach(Array(topArtists.enumerated()), id: \.element.0.name) { i, e in row(i + 1, e.0.name, "\(e.1) plays", e.0.cover) } } }
            if total == 0 { Text("No listening yet. Plays count once you've heard most of a song.").foregroundStyle(p.secondary) }
        }
        .scrollContentBackground(.hidden).background(p.background).navigationTitle("Your stats")
    }
    private func stat(_ v: String, _ l: String) -> some View {
        VStack(alignment: .leading) { Text(v).text(.title).foregroundStyle(p.accent).lineLimit(1).minimumScaleFactor(0.6); Text(l).text(.caption).foregroundStyle(p.secondary) }
            .padding(12).frame(maxWidth: .infinity, alignment: .leading).background(p.tint, in: RoundedRectangle(cornerRadius: 12))
    }
    private func row(_ n: Int, _ t: String, _ s: String, _ art: Song) -> some View {
        HStack(spacing: 12) {
            Text("\(n)").text(.title).foregroundStyle(p.secondary).frame(width: 28)
            ArtworkView(art, cornerRadius: 4).frame(width: 46, height: 46)
            VStack(alignment: .leading) { Text(t).text(.body).lineLimit(1); Text(s).text(.caption).foregroundStyle(p.secondary).lineLimit(1) }
        }.listRowBackground(Color.clear)
    }
}
