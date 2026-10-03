import SwiftUI

struct MiniPlayer: View {
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @State private var tint = Color(hex: 0x2A2A2E)
    @State private var drag: CGFloat = 0

    var body: some View {
        if let s = app.player.current {
            let progress = app.player.duration > 0 ? app.player.position / app.player.duration : 0
            Button { router.playerOpen = true } label: {
                VStack(spacing: 0) {
                    HStack(spacing: 10) {
                        ArtworkView(s, cornerRadius: 5).frame(width: 40, height: 40)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(s.title).text(.titleS).foregroundStyle(.white).lineLimit(1)
                            Text(s.artist).text(.caption).foregroundStyle(.white.opacity(0.75)).lineLimit(1)
                        }
                        Spacer()
                        if !s.isSpoken { PlaylistButton(song: s) }
                        Button { Haptics.tap(); app.player.toggle() } label: {
                            Image(systemName: app.player.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 22, weight: .bold)).foregroundStyle(.white)
                                .contentTransition(.symbolEffect(.replace)).frame(width: 44, height: 44).contentShape(Rectangle())
                        }
                    }
                    .padding(.horizontal, 8).padding(.vertical, 7)
                    GeometryReader { g in
                        Capsule().fill(.white.opacity(0.2)).overlay(alignment: .leading) { Capsule().fill(.white).frame(width: g.size.width * progress) }
                    }.frame(height: 2).padding(.horizontal, 8).padding(.bottom, 2)
                }
                .background(tint.mix(.black, 0.15), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .offset(x: drag)
            }
            .buttonStyle(.pressable(0.98))
            .accessibilityLabel("Now playing: \(s.title) by \(s.artist)")
            .accessibilityIdentifier("miniPlayer")
            .padding(.horizontal, 8).padding(.bottom, 4)
            .artColor(s, into: $tint)
            .gesture(DragGesture(minimumDistance: 20).onChanged { drag = $0.translation.width * 0.5 }.onEnded { v in
                if v.translation.width < -80 { Haptics.soft(); app.player.next() } else if v.translation.width > 80 { Haptics.soft(); app.player.previous() }
                withAnimation(.spring) { drag = 0 }
            })
            .simultaneousGesture(DragGesture(minimumDistance: 20).onEnded { v in if v.translation.height < -40 { router.playerOpen = true } })
        }
    }
}

struct NowPlayingView: View {
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @Environment(\.dismiss) private var dismiss
    @Environment(\.themeSettings) private var theme
    @State private var tint = Color(hex: 0x2A2A2E)
    @State private var sheet: Sheet?
    @State private var dragDown: CGFloat = 0
    @State private var scrollTop: CGFloat = 0
    @State private var canCollapse: Bool?
    @AppStorage("musicVideoEnabled") private var videoOpen = false
    enum Sheet: String, Identifiable { case lyrics, queue, sleep, playback; var id: String { rawValue } }

    var body: some View {
        let player = app.player
        ZStack {
            Color.clear
            if let s = player.current {
                // Measure once, outside the scroll view, so the player page always fits the screen exactly.
                GeometryReader { outer in
                    ScrollView {
                        VStack(spacing: 0) {
                            VStack(spacing: 0) {
                                header(s)
                                if videoOpen && !s.isSpoken { Color.clear.frame(maxHeight: .infinity).padding(.vertical, 12) }
                                else { ArtPager(side: max(1, min(outer.size.width - 44, outer.size.height * (outer.size.width < 350 ? 0.30 : 0.38)))).frame(maxHeight: .infinity).padding(.vertical, 12) }
                                titleRow(s)
                                SeekBar().padding(.top, 6)
                                Transport().padding(.top, 2)
                                Secondary(sheet: $sheet).padding(.top, 6).padding(.bottom, 10)
                            }
                            .padding(.horizontal, 22)
                            .frame(width: outer.size.width).frame(minHeight: outer.size.height)
                            cards(s).padding(.horizontal, 16).padding(.bottom, 40).frame(width: outer.size.width)
                        }
                        .background(GeometryReader { geometry in
                            Color.clear.preference(key: PlayerScrollTop.self, value: geometry.frame(in: .named("player-scroll")).minY)
                        })
                    }
                    .coordinateSpace(name: "player-scroll")
                    .onPreferenceChange(PlayerScrollTop.self) { scrollTop = $0 }
                    .scrollIndicators(.hidden)
                }
            }
        }
        .background { if videoOpen && player.current?.isSpoken == false { MusicVideoBackdrop() } else { Backdrop(song: player.current, tint: tint) } }
        .offset(y: max(0, dragDown))
        .simultaneousGesture(DragGesture(minimumDistance: 14).onChanged { value in
            if canCollapse == nil { canCollapse = scrollTop >= -2 && value.translation.height > abs(value.translation.width) * 1.3 }
            if canCollapse == true { dragDown = max(0, value.translation.height) }
        }.onEnded { value in
            if canCollapse == true && PlayerDismissGesture.shouldDismiss(distance: dragDown, predicted: value.predictedEndTranslation.height) { dismiss() }
            canCollapse = nil
            withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { dragDown = 0 }
        })
        .artColor(player.current, into: $tint)
        .preferredColorScheme(.dark)
        .sheet(item: $sheet) { which in
            Group {
                switch which {
                case .lyrics: LyricsSheet()
                case .queue: QueueSheet()
                case .sleep: SleepSheet()
                case .playback: PlaybackSheet()
                }
            }
            .presentationDetents(which == .sleep || which == .playback ? [.medium] : [.large])
            .presentationDragIndicator(.visible)
            .presentationBackground(tint.mix(.black, 0.25))
            .preferredColorScheme(.dark)
        }
    }

    private func header(_ s: Song) -> some View {
        HStack {
            Button { dismiss() } label: { Image(systemName: "chevron.down").font(.system(size: 20, weight: .bold)).frame(width: 44, height: 44) }.accessibilityLabel("Close player")
            Spacer()
            VStack(spacing: 2) {
                Text("PLAYING FROM").text(.labelS).foregroundStyle(.white.opacity(0.7))
                Text(app.player.source ?? "Your library").text(.titleS).lineLimit(1)
            }
            Spacer()
            SongMenu(song: s) { Image(systemName: "ellipsis").font(.system(size: 18, weight: .bold)).frame(width: 44, height: 44) }
        }
        .foregroundStyle(.white)
    }

    private func titleRow(_ s: Song) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            VStack(alignment: .leading, spacing: 3) {
                Text(s.title).text(.headlineS).foregroundStyle(.white).lineLimit(2).fixedSize(horizontal: false, vertical: true)
                Button { if !s.isSpoken { router.go(.artist(s.primaryArtist)) } } label: { Text(s.artist).text(.body).foregroundStyle(.white.opacity(0.78)).lineLimit(2).multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true).frame(minHeight: 44, alignment: .top).contentShape(Rectangle()) }
            }
            .id(s.id).transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .opacity))
            if !s.isSpoken {
                HStack(spacing: 8) {
                    ArtworkStyleButton(videoEnabled: videoOpen)
                    Button { videoOpen.toggle() } label: { Image(systemName: videoOpen ? "photo" : "video").font(.system(size: 22)).frame(width: 44, height: 44) }.accessibilityLabel(videoOpen ? "Show album art" : "Watch music video")
                    Spacer()
                    PlaylistButton(song: s)
                }
            }
        }
        .foregroundStyle(.white)
        .animation(.spring(duration: 0.35), value: s.id)
    }

    @ViewBuilder private func cards(_ s: Song) -> some View {
        VStack(spacing: 14) {
            if s.isSpoken { NotesCard(song: s) } else { LyricsCard(song: s) { sheet = .lyrics } }
            UpNextCard { sheet = .queue }
            if !s.isSpoken { AboutArtistCard(song: s) }
            CreditsCard(song: s)
        }
    }
}

/// Blurred artwork + tint gradient behind the player.
struct Backdrop: View {
    var song: Song?
    var tint: Color
    @Environment(\.themeSettings) private var theme
    var body: some View {
        ZStack {
            Color.black
            if theme.blur, let s = song {
                GeometryReader { g in
                    ArtworkView(s, cornerRadius: 0).frame(width: g.size.width, height: g.size.height).scaleEffect(1.6).blur(radius: 70).opacity(0.55)
                }.id(s.albumKey).transition(.opacity)
            }
            LinearGradient(stops: [.init(color: tint.opacity(0.85), location: 0), .init(color: tint.mix(.black, 0.6).opacity(0.9), location: 0.55), .init(color: .black, location: 1)],
                           startPoint: .top, endPoint: .bottom)
        }
        .clipped()
        .ignoresSafeArea()
        .animation(.easeInOut(duration: 0.8), value: song?.albumKey)
    }
}

/// Artwork carousel over the real queue - swipe to skip. Breathes down while paused; vinyl style spins.
struct ArtPager: View {
    var side: CGFloat
    @Environment(AppModel.self) private var app
    @Environment(\.themeSettings) private var theme
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @State private var selection = 0

    var body: some View {
        let player = app.player
        let size = side * (theme.playerStyle == .minimal ? 0.72 : 1)
        if theme.playerStyle == .vinyl, let song = player.current {
            ScrubbableVinyl(song: song).frame(width: size, height: size).frame(maxWidth: .infinity).frame(height: side + 24)
        } else {
        TabView(selection: $selection) {
            ForEach(Array(player.queue.enumerated()), id: \.offset) { i, s in
                cover(s, current: i == player.index).frame(width: size, height: size).tag(i)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .frame(height: side + 24)
        .onAppear { selection = max(0, player.index) }
        .onChange(of: player.index) { _, i in withAnimation(.spring(duration: 0.4)) { selection = max(0, i) } }
        .onChange(of: selection) { _, i in if i != player.index, player.queue.indices.contains(i) { Haptics.soft(); player.skip(to: i) } }
        }
    }

    @ViewBuilder private func cover(_ s: Song, current: Bool) -> some View {
        let breathe = current && !app.player.isPlaying && !theme.reduceMotion && !systemReduceMotion && theme.playerStyle != .vinyl
        Group {
            if theme.playerStyle == .vinyl {
                TimelineView(.animation(paused: !(current && app.player.isPlaying) || theme.reduceMotion || systemReduceMotion)) { ctx in
                    Vinyl(song: s, rotation: current ? ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 9) / 9 * 360 : 0)
                }
            } else {
                ArtworkView(s, cornerRadius: 10).shadow(color: .black.opacity(0.5), radius: 24, y: 12)
            }
        }
        .scaleEffect(breathe ? 0.88 : 1)
        .animation(.spring(response: 0.5, dampingFraction: 0.6), value: breathe)
    }
}

enum VinylScrub {
    static func delta(_ previous: Double, _ current: Double) -> Double {
        var value = current - previous
        while value > .pi { value -= 2 * .pi }
        while value < -.pi { value += 2 * .pi }
        return value
    }
    static func position(_ start: Double, _ radians: Double, _ duration: Double) -> Double {
        min(max(0, start + radians / (2 * .pi) * 30), max(0, duration))
    }
}

struct ScrubbableVinyl: View {
    let song: Song
    @Environment(AppModel.self) private var app
    @Environment(\.themeSettings) private var theme
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @State private var anchor = Date()
    @State private var rotation = 0.0
    @State private var dragging = false
    @GestureState private var touching = false
    @State private var previousAngle = 0.0
    @State private var total = 0.0
    @State private var start = 0.0
    @State private var lastSeek = Date.distantPast

    private var reducedMotion: Bool { theme.reduceMotion || systemReduceMotion }

    private func angle(at date: Date) -> Double {
        rotation + (app.player.isPlaying && !reducedMotion && !dragging ? date.timeIntervalSince(anchor) * 40 : 0)
    }
    var body: some View {
        GeometryReader { geo in
            TimelineView(.animation(paused: !app.player.isPlaying || reducedMotion || dragging)) { context in
                Vinyl(song: song, rotation: angle(at: context.date))
            }
            .contentShape(Circle())
            .highPriorityGesture(DragGesture(minimumDistance: 2).updating($touching) { _, active, _ in active = true }.onChanged { value in
                guard app.player.duration > 0 else { return }
                let x = value.location.x - geo.size.width / 2, y = value.location.y - geo.size.height / 2
                let current = atan2(Double(y), Double(x))
                if !dragging {
                    rotation = angle(at: Date()); anchor = Date(); dragging = true
                    previousAngle = atan2(Double(value.startLocation.y - geo.size.height / 2), Double(value.startLocation.x - geo.size.width / 2))
                    total = 0; start = app.player.position
                }
                if x * x + y * y > geo.size.width * geo.size.width * 0.01 {
                    let step = VinylScrub.delta(previousAngle, current)
                    total += step; rotation += step * 180 / .pi
                    if Date().timeIntervalSince(lastSeek) >= 0.06 {
                        app.player.seek(VinylScrub.position(start, total, app.player.duration)); lastSeek = Date()
                    }
                }
                previousAngle = current
            }.onEnded { _ in
                app.player.seek(VinylScrub.position(start, total, app.player.duration))
                anchor = Date(); dragging = false
            })
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Record. \(song.album) by \(song.primaryArtist).")
        .accessibilityHint("Turn clockwise to move forward, or counterclockwise to rewind.")
        .accessibilityAdjustableAction { direction in app.player.skip(by: direction == .increment ? 10 : -10) }
        .onChange(of: app.player.isPlaying) { old, _ in
            if old && !dragging && !reducedMotion { rotation += Date().timeIntervalSince(anchor) * 40 }
            anchor = Date()
        }
        .onChange(of: reducedMotion) { old, _ in
            if !old && app.player.isPlaying && !dragging { rotation += Date().timeIntervalSince(anchor) * 40 }
            anchor = Date()
        }
        .onChange(of: touching) { _, active in
            if !active && dragging { anchor = Date(); dragging = false }
        }
        .onChange(of: song.id) { _, _ in dragging = false; rotation = 0; anchor = Date() }
    }
}

/// The light stays in place while the printed label turns under it.
struct Vinyl: View {
    var song: Song
    var rotation = 0.0
    var body: some View {
        GeometryReader { geometry in
            let side = min(geometry.size.width, geometry.size.height)
            let label = side * 0.45
            ZStack {
                Circle().fill(RadialGradient(colors: [Color(hex: 0x252526), Color(hex: 0x080809), Color(hex: 0x171719)], center: .center, startRadius: 0, endRadius: side / 2))
                Canvas { context, size in
                    let radius = min(size.width, size.height) / 2
                    let center = CGPoint(x: size.width / 2, y: size.height / 2)
                    for index in 0..<74 {
                        let r = radius * (0.96 - Double(index) * 0.0066)
                        let path = Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: r * 2, height: r * 2))
                        context.stroke(path, with: .color(index % 5 == 0 ? .black.opacity(0.55) : .white.opacity(index % 3 == 0 ? 0.09 : 0.045)), lineWidth: index % 5 == 0 ? 0.8 : 0.5)
                    }
                }
                AngularGradient(stops: [
                    .init(color: .clear, location: 0), .init(color: .white.opacity(0.025), location: 0.10),
                    .init(color: .white.opacity(0.18), location: 0.17), .init(color: .clear, location: 0.24),
                    .init(color: .clear, location: 0.55), .init(color: .white.opacity(0.12), location: 0.66),
                    .init(color: .clear, location: 0.74), .init(color: .clear, location: 1)
                ], center: .center).clipShape(Circle())
                Circle().stroke(.black.opacity(0.65), lineWidth: 2).frame(width: label + side * 0.04, height: label + side * 0.04)
                ZStack {
                    Circle().fill(Color(hex: 0xE9DFC5))
                    ArtworkView(song, circle: true).saturation(0.87).contrast(0.96).padding(side * 0.006)
                    Circle().fill(Color(hex: 0xE7CE9B).opacity(0.12))
                    Image(uiImage: VinylPaper.image).resizable(resizingMode: .tile).blendMode(.softLight).opacity(0.32).clipShape(Circle())
                    Circle().stroke(.black.opacity(0.20), lineWidth: 0.8).padding(side * 0.009)
                    Circle().stroke(.white.opacity(0.23), lineWidth: 0.7).frame(width: label * 0.38, height: label * 0.38)
                    Circle().stroke(.black.opacity(0.24), lineWidth: 1.1).frame(width: label * 0.36, height: label * 0.36)
                }
                .frame(width: label, height: label).compositingGroup().rotationEffect(.degrees(rotation))
                .shadow(color: .black.opacity(0.55), radius: 1.5, y: 1)
                Circle().fill(Color.black.opacity(0.16)).frame(width: side * 0.055, height: side * 0.055)
                Circle().fill(Color(hex: 0x08090A)).frame(width: side * 0.023, height: side * 0.023)
                    .overlay(Circle().stroke(.white.opacity(0.30), lineWidth: 0.7))
                Circle().stroke(.white.opacity(0.15), lineWidth: 0.8).padding(1)
                Circle().stroke(.black.opacity(0.8), lineWidth: 1.4).padding(3)
            }
            .frame(width: side, height: side)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .shadow(color: .black.opacity(0.55), radius: 24, y: 12)
        .accessibilityHidden(true)
    }
}

/// A small, repeatable paper texture built once, then shared by every album label.
private enum VinylPaper {
    static let image: UIImage = {
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: 96, height: 96), format: format).image { renderer in
            let context = renderer.cgContext
            var seed: UInt64 = 0x51F17
            func next() -> CGFloat {
                seed = seed &* 6364136223846793005 &+ 1
                return CGFloat((seed >> 32) & 0xffff) / 65535
            }
            for _ in 0..<1300 {
                let x = next() * 96, y = next() * 96
                context.setFillColor((next() > 0.5 ? UIColor.white : UIColor.black).withAlphaComponent(0.13 + next() * 0.22).cgColor)
                context.fill(CGRect(x: x, y: y, width: 0.35 + next() * 0.8, height: 0.35 + next() * 0.6))
            }
        }
    }()
}

struct SeekBar: View {
    @Environment(AppModel.self) private var app
    @State private var dragging: Double?
    var body: some View {
        let p = app.player
        let dur = max(1, p.duration)
        let shown = dragging ?? min(1, p.position / dur)
        VStack(spacing: 4) {
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(.white.opacity(0.22)).frame(height: dragging == nil ? 4 : 7)
                    Capsule().fill(.white).frame(width: g.size.width * shown, height: dragging == nil ? 4 : 7)
                    Circle().fill(.white).frame(width: dragging == nil ? 12 : 18).offset(x: g.size.width * shown - (dragging == nil ? 6 : 9)).shadow(radius: 3)
                }
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0).onChanged { v in dragging = min(1, max(0, v.location.x / g.size.width)) }
                    .onEnded { _ in if let d = dragging { p.seek(d * dur) }; dragging = nil })
                .animation(.spring(duration: 0.2), value: dragging == nil)
            }.frame(height: 24)
            HStack {
                Text(Int64(shown * dur * 1000).formattedDuration)
                Spacer()
                Text("-" + Int64((1 - shown) * dur * 1000).formattedDuration)
            }.text(.labelS).foregroundStyle(.white.opacity(0.7)).monospacedDigit()
        }
    }
}

struct Transport: View {
    var large = true
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var palette
    var body: some View {
        let p = app.player
        let spoken = p.current?.isSpoken == true
        HStack {
            if spoken { toggle("gobackward.10", "Back 10 seconds", false) { p.skip(by: -10) } }
            else { toggle("shuffle", "Shuffle", p.shuffle) { p.toggleShuffle() } }
            Spacer()
            Button { Haptics.tap(); p.previous() } label: { Image(systemName: "backward.end.fill").font(.system(size: large ? 32 : 26)).frame(width: 44, height: 44).contentShape(Rectangle()) }.accessibilityLabel("Previous")
            Spacer()
            Button { Haptics.tap(); p.toggle() } label: {
                Image(systemName: p.isPlaying ? "pause.fill" : "play.fill").font(.system(size: large ? 30 : 24, weight: .bold)).foregroundStyle(.black)
                    .contentTransition(.symbolEffect(.replace))
                    .frame(width: large ? 72 : 58, height: large ? 72 : 58).background(.white, in: Circle()).shadow(color: .black.opacity(0.3), radius: 12, y: 6)
            }.buttonStyle(.pressable(0.9)).accessibilityLabel(p.isPlaying ? "Pause" : "Play")
            Spacer()
            Button { Haptics.tap(); p.next() } label: { Image(systemName: "forward.end.fill").font(.system(size: large ? 32 : 26)).frame(width: 44, height: 44).contentShape(Rectangle()) }.accessibilityLabel("Next")
            Spacer()
            if spoken { toggle("goforward.30", "Forward 30 seconds", false) { p.skip(by: 30) } }
            else { toggle(p.repeatMode == .one ? "repeat.1" : "repeat", "Repeat", p.repeatMode != .off) { p.cycleRepeat() } }
        }
        .foregroundStyle(.white)
    }
    private func toggle(_ icon: String, _ label: String, _ on: Bool, _ action: @escaping () -> Void) -> some View {
        Button { Haptics.tap(); action() } label: {
            VStack(spacing: 3) {
                Image(systemName: icon).font(.system(size: 21, weight: .semibold)).foregroundStyle(on ? palette.accent : .white.opacity(0.85))
                Circle().fill(palette.accent).frame(width: 4, height: 4).opacity(on ? 1 : 0)
            }.frame(width: 44, height: 50)
        }
        .accessibilityLabel(label)
        .accessibilityValue(on ? "On" : "Off")
    }
}

struct Secondary: View {
    @Binding var sheet: NowPlayingView.Sheet?
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @Environment(\.palette) private var palette
    var body: some View {
        let p = app.player
        VStack(spacing: 0) {
        HStack {
            item(p.sleep == nil ? "moon.zzz" : "moon.zzz.fill", "Sleep timer", p.sleep != nil) { sheet = .sleep }
            Spacer()
            item("slider.horizontal.3", "Playback settings", p.speed != 1 || p.crossfade > 0) { sheet = .playback }
            Spacer()
            item("slider.vertical.3", "Equaliser", p.eq.enabled) { router.playerOpen = false; router.tab = .library; router.paths[.library] = NavigationPath([Route.equalizer]) }
            Spacer()
            item(p.current?.isSpoken == true ? "text.alignleft" : "quote.bubble", p.current?.isSpoken == true ? "Notes" : "Lyrics", false) { sheet = .lyrics }
            Spacer()
            item("list.bullet", "Queue", false) { sheet = .queue }
        }
        AudioOutputPicker(output: p.audioOutput, accent: palette.accent).frame(height: 44)
        }
    }
    private func item(_ icon: String, _ label: String, _ on: Bool, _ a: @escaping () -> Void) -> some View {
        Button { Haptics.tap(); a() } label: {
            VStack(spacing: 3) {
                Image(systemName: icon).font(.system(size: 19, weight: .semibold)).foregroundStyle(on ? palette.accent : .white.opacity(0.85))
                Circle().fill(palette.accent).frame(width: 4, height: 4).opacity(on ? 1 : 0)
            }.frame(width: 44, height: 44)
        }
        .accessibilityLabel(label)
        .accessibilityValue(on ? "On" : "")
    }
}

// MARK: - Cards under the player

struct Card<Content: View>: View {
    var title: String
    var action: String? = nil
    var onAction: (() -> Void)? = nil
    @ViewBuilder var content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(title).text(.title)
                Spacer()
                if let action, let onAction { Button(action, action: onAction).text(.label).padding(.horizontal, 12).padding(.vertical, 6).background(.white.opacity(0.14), in: Capsule()) }
            }
            content()
        }
        .foregroundStyle(.white)
        .padding(20).frame(maxWidth: .infinity, alignment: .leading)
        .background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

struct LyricsCard: View {
    var song: Song
    var open: () -> Void
    @Environment(AppModel.self) private var app
    var body: some View {
        Button(action: open) {
            Card(title: "Lyrics") {
                switch app.lyrics.states[song.id] {
                case .found(let l)?:
                    let active = l.synced ? max(0, LRC.activeIndex(l.lines, app.player.position + l.offset)) : 0
                    ForEach(Array(l.lines.dropFirst(active).prefix(4).enumerated()), id: \.offset) { i, line in
                        Text(line.text.isEmpty ? "♪" : line.text).text(.title).foregroundStyle(i == 0 && l.synced ? .white : .white.opacity(0.5)).lineLimit(2).multilineTextAlignment(.leading)
                    }
                case .missing(let online)?: Text(online ? "No lyrics found for this song." : "No lyrics on this iPhone — tap to search online.").text(.body).foregroundStyle(.white.opacity(0.8))
                default: ProgressView().tint(.white)
                }
            }
        }
        .buttonStyle(.pressable(0.98))
        .task(id: song.id) { app.lyrics.request(song, fileURL: app.library.fileURL(song)) }
    }
}

struct NotesCard: View {
    var song: Song
    @Environment(AppModel.self) private var app
    @State private var expanded = false
    var body: some View {
        let ep = app.shows.shows.flatMap(\.episodes).first { song.episodeId?.hasSuffix("/\($0.id)") == true }
        let show = app.shows.shows.first { song.episodeId?.hasPrefix($0.id + "/") == true }
        Card(title: song.isAudiobook ? "About this book" : "Episode notes", action: expanded ? "Less" : "More", onAction: { expanded.toggle() }) {
            Text((ep?.summary.isEmpty == false ? ep?.summary : show?.summary) ?? "No notes.").text(.bodyS).foregroundStyle(.white.opacity(0.8)).lineLimit(expanded ? nil : 6)
        }
    }
}

struct UpNextCard: View {
    var open: () -> Void
    @Environment(AppModel.self) private var app
    var body: some View {
        Card(title: "Next in queue", action: "Open queue", onAction: open) {
            let next = app.player.upNext.prefix(4)
            if next.isEmpty { Text("End of the queue.").text(.bodyS).foregroundStyle(.white.opacity(0.7)) }
            ForEach(Array(next), id: \.0) { i, s in
                Button { app.player.skip(to: i) } label: {
                    HStack(spacing: 12) {
                        ArtworkView(s, cornerRadius: 5).frame(width: 44, height: 44)
                        VStack(alignment: .leading) { Text(s.title).text(.body).lineLimit(1); Text(s.artist).text(.caption).foregroundStyle(.white.opacity(0.7)).lineLimit(1) }
                        Spacer()
                        Text(s.durationMs.formattedDuration).text(.caption).foregroundStyle(.white.opacity(0.7))
                    }
                }
            }
        }
    }
}

struct AboutArtistCard: View {
    var song: Song
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    var body: some View {
        if let a = app.library.library.artistByName[song.primaryArtist] {
            let plays = a.songs.reduce(0) { $0 + app.library.playCount($1.id) }
            Button { router.go(.artist(a.name)) } label: {
                VStack(alignment: .leading, spacing: 0) {
                    ArtworkView(a.cover, cornerRadius: 0).frame(height: 180).overlay(alignment: .topLeading) { Text("About the artist").text(.title).padding(20) }
                        .overlay { LinearGradient(colors: [.clear, .black.opacity(0.6)], startPoint: .top, endPoint: .bottom) }
                    VStack(alignment: .leading, spacing: 4) {
                        Text(a.name).text(.title)
                        Text("\(a.albums.count) album\(a.albums.count == 1 ? "" : "s") • \(songCount(a.songs.count))\(plays > 0 ? " • \(plays) plays by you" : "")").text(.bodyS).foregroundStyle(.white.opacity(0.7))
                    }.padding(20)
                }
                .foregroundStyle(.white)
                .background(.white.opacity(0.08)).clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }.buttonStyle(.pressable(0.98))
        }
    }
}

struct CreditsCard: View {
    var song: Song
    var body: some View {
        Card(title: "Credits") {
            ForEach(credits, id: \.0) { k, v in
                VStack(alignment: .leading, spacing: 1) { Text(v).text(.body).lineLimit(2); Text(k).text(.caption).foregroundStyle(.white.opacity(0.6)) }
            }
        }
    }
    private var credits: [(String, String)] {
        var c = [("Artist", song.artist), ("Album", song.album)]
        if song.year > 0 { c.append(("Year", String(song.year))) }
        if let g = song.genre { c.append(("Genre", g)) }
        c.append(("Format", song.kind == .musicLibrary ? "Music library" : song.fileExtension.uppercased()))
        if song.kind == .file { c.append(("File", "/\(song.location)")) }
        return c
    }
}

// MARK: - Sheets

struct LyricsSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        if let s = app.player.current {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    VStack(alignment: .leading) { Text(s.title).text(.titleS); Text(s.artist).text(.caption).foregroundStyle(.white.opacity(0.7)) }
                    Spacer()
                    Button { dismiss() } label: { Image(systemName: "chevron.down").font(.system(size: 17, weight: .bold)).frame(width: 36, height: 36).background(.white.opacity(0.15), in: Circle()).frame(width: 44, height: 44).contentShape(Rectangle()) }.accessibilityLabel("Close lyrics")
                        .accessibilityLabel("Close lyrics")
                }.padding(20)
                if s.isSpoken { ScrollView { NotesCard(song: s).padding() } } else { LyricsView(song: s) }
                VStack { SeekBar(); Transport(large: false) }.padding(.horizontal, 20).padding(.bottom, 12)
            }
            .foregroundStyle(.white)
        }
    }
}

struct LyricsView: View {
    var song: Song
    var lineFont: TextRole = .headlineS
    @Environment(AppModel.self) private var app
    @State private var userScrolledAt = Date.distantPast

    var body: some View {
        switch app.lyrics.states[song.id] {
        case .found(let l)?:
            if l.synced {
                let active = LRC.activeIndex(l.lines, app.player.position + l.offset)
                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(alignment: .leading, spacing: 14) {
                            ForEach(Array(l.lines.enumerated()), id: \.offset) { i, line in
                                Button { app.player.seek(max(0, line.time - l.offset)) } label: {
                                    Text(line.text.isEmpty ? "♪" : line.text).text(lineFont).multilineTextAlignment(.leading)
                                        .foregroundStyle(i == active ? .white : .white.opacity(i < active ? 0.55 : 0.32))
                                        .scaleEffect(i == active ? 1 : 0.96, anchor: .leading)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                }
                                .id(i)
                                .animation(.easeOut(duration: 0.3), value: active)
                            }
                            footer(l)
                        }.padding(.horizontal, 24).padding(.vertical, 120)
                    }
                    .simultaneousGesture(DragGesture().onChanged { _ in userScrolledAt = Date() })
                    .onChange(of: active) { _, a in
                        if a >= 0, Date().timeIntervalSince(userScrolledAt) > 3 { withAnimation(.easeInOut(duration: 0.5)) { proxy.scrollTo(a, anchor: UnitPoint(x: 0, y: 0.35)) } }
                    }
                    .mask(LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.08), .init(color: .black, location: 0.9), .init(color: .clear, location: 1)], startPoint: .top, endPoint: .bottom))
                }
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("These lyrics aren't time-synced.").text(.label).foregroundStyle(.white.opacity(0.6)).padding(.bottom, 8)
                        ForEach(Array(l.lines.enumerated()), id: \.offset) { _, line in Text(line.text).text(lineFont).foregroundStyle(.white.opacity(0.9)) }
                        Text("Source: \(l.source)").text(.caption).foregroundStyle(.white.opacity(0.5)).padding(.top, 20)
                    }.padding(24)
                }
            }
        case .missing(let online)?:
            VStack(spacing: 14) {
                Spacer()
                Image(systemName: "quote.bubble").font(.system(size: 40)).foregroundStyle(.white.opacity(0.6))
                Text(online ? "No lyrics found" : "No lyrics on this iPhone").text(.title)
                Text(online ? "LRCLIB doesn't have this one. Drop a matching .lrc file next to the song." : "Spitify checked the file's tags and nearby .lrc files.")
                    .text(.bodyS).foregroundStyle(.white.opacity(0.7)).multilineTextAlignment(.center).padding(.horizontal, 30)
                Button { app.lyrics.request(song, fileURL: app.library.fileURL(song), force: true) } label: {
                    Label(online ? "Search again" : "Find lyrics online", systemImage: "icloud.and.arrow.down").text(.label).foregroundStyle(.black).padding(.horizontal, 18).padding(.vertical, 10).background(.white, in: Capsule())
                }
                Spacer()
            }.frame(maxWidth: .infinity)
        default: ProgressView().tint(.white).frame(maxWidth: .infinity, maxHeight: .infinity).task { app.lyrics.request(song, fileURL: app.library.fileURL(song)) }
        }
    }

    private func footer(_ l: Lyrics) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Source: \(l.source)").text(.caption).foregroundStyle(.white.opacity(0.6))
            HStack {
                Text("Timing").text(.caption).foregroundStyle(.white.opacity(0.6))
                Button("−0.25s") { app.lyrics.setOffset(song, l.offset - 0.25) }
                Text(String(format: "%+.2fs", l.offset)).text(.label)
                Button("+0.25s") { app.lyrics.setOffset(song, l.offset + 0.25) }
            }.text(.label)
        }.padding(.top, 30)
    }
}

struct QueueSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var palette
    var body: some View {
        let p = app.player
        NavigationStack {
            List {
                if let cur = p.current {
                    Section("Now playing") { row(cur, playing: true) }
                }
                if p.upNext.isEmpty {
                    Text("Nothing queued. Use “Play next” or “Add to queue” from any song's menu.").text(.bodyS).foregroundStyle(palette.secondary)
                }
                queueSection(p.manuallyQueued, title: "Added by you")
                queueSection(p.nextFromSource, title: p.source.map { "Next from: \($0)" } ?? "From your playback list")
                queueSection(p.automaticallyQueued, title: "Autoplay · similar music")
                if !p.upNext.isEmpty { Button("Clear upcoming songs") { p.clearUpNext() } }

            }
            .listStyle(.plain).scrollContentBackground(.hidden).background(palette.background)
            .environment(\.editMode, .constant(.active))
            .navigationTitle("Queue").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { DismissButton() } }
        }
    }
    @ViewBuilder private func queueSection(_ entries: [(Int, Song)], title: String) -> some View {
        if !entries.isEmpty {
            Section(title) {
                ForEach(entries, id: \.0) { i, song in row(song, playing: false).onTapGesture { app.player.skip(to: i) } }
                    .onDelete { offsets in offsets.map { entries[$0].0 }.sorted(by: >).forEach { app.player.remove(at: $0) } }
                    .onMove { from, to in
                        if let f = from.first {
                            let target = min(entries.count - 1, to > f ? to - 1 : to)
                            app.player.move(from: entries[f].0, to: entries[target].0)
                        }
                    }
            }
        }
    }
    private func row(_ s: Song, playing: Bool) -> some View {
        HStack(spacing: 12) {
            ArtworkView(s, cornerRadius: 5).frame(width: 44, height: 44)
            VStack(alignment: .leading) {
                Text(s.title).text(.body).foregroundStyle(playing ? palette.accent : palette.text).lineLimit(1)
                Text(s.artist).text(.caption).foregroundStyle(palette.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
        }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle()).listRowBackground(Color.clear)
    }
}

struct SleepSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List {
                if case .at(let end)? = app.player.sleep { Text("Stopping at \(end.formatted(date: .omitted, time: .shortened)) — the last 10 seconds fade out.").foregroundStyle(.white.opacity(0.7)) }
                ForEach([5, 15, 30, 45, 60, 90], id: \.self) { m in Button("\(m) minutes") { app.player.setSleep(minutes: m); dismiss() } }
                Button("End of track") { app.player.sleepAtEndOfTrack(); dismiss() }
                if app.player.sleep != nil { Button("Turn off", role: .destructive) { app.player.setSleep(minutes: nil); dismiss() } }
            }
            .listRowBackground(Color.clear).scrollContentBackground(.hidden)
            .navigationTitle("Sleep timer").navigationBarTitleDisplayMode(.inline)
        }
    }
}

struct PlaybackSheet: View {
    var body: some View { NavigationStack { ScrollView { PlaybackSettings().padding(20) }.navigationTitle("Playback").navigationBarTitleDisplayMode(.inline) } }
}

struct PlaybackSettings: View {
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    var body: some View {
        @Bindable var player = app.player
        VStack(alignment: .leading, spacing: 18) {
            Toggle("Normalize volume", isOn: $player.normalizeVolume).text(.body)
            Text("Lowers loud recordings for steadier listening. Your device volume stays under your control.").text(.caption).foregroundStyle(p.secondary)
            Toggle("Keep music playing", isOn: $player.autoplay).text(.body)
            Text("Similar music follows when your queue ends. Repeat, sleep timers, and clearing the queue still take priority.").text(.caption).foregroundStyle(p.secondary)
            Text("Speed\(app.player.current?.isSpoken == true ? " (podcasts & books)" : "")").text(.titleS)
            ScrollView(.horizontal, showsIndicators: false) { HStack(spacing: 8) {
                ForEach([0.75, 1, 1.25, 1.5, 2] as [Float], id: \.self) { s in
                    Pill(title: s == 1 ? "1×" : String(format: "%g×", s), selected: app.player.speed == s) { app.player.setSpeed(s) }
                }
            } }
            VStack(alignment: .leading) {
                HStack { Text("Crossfade").text(.titleS); Spacer(); Text(player.crossfade == 0 ? "Off" : "\(Int(player.crossfade))s").text(.label).foregroundStyle(p.accent) }
                Slider(value: $player.crossfade, in: 0...12, step: 1)
                Toggle("Keep albums gapless", isOn: $player.keepAlbumsGapless).text(.body)
                Text("Crossfade applies to downloads and music files you add. Live streams, podcasts and books do not crossfade.").text(.caption).foregroundStyle(p.secondary)
            }
        }
    }
}

struct DismissButton: View {
    @Environment(\.dismiss) private var dismiss
    var body: some View { Button("Done") { dismiss() }.bold() }
}

private struct PlayerScrollTop: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

enum PlayerDismissGesture {
    static func shouldDismiss(distance: CGFloat, predicted: CGFloat) -> Bool {
        distance >= 110 || (distance >= 45 && predicted >= 220)
    }
}
