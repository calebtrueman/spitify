import SwiftUI

struct SongRow: View {
    var song: Song
    var trackNumber: Int? = nil
    var subtitle: String? = nil
    var onTap: () -> Void
    var removeLabel: String? = nil
    var onRemove: (() -> Void)? = nil
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p

    var body: some View {
        let isCurrent = app.player.current?.id == song.id
        Button(action: { Haptics.tap(); onTap() }) {
            HStack(spacing: 12) {
                if let n = trackNumber {
                    ZStack {
                        if isCurrent { EqualizerBars(playing: app.player.isPlaying).frame(width: 14, height: 14) }
                        else { Text("\(n)").text(.bodyS).foregroundStyle(p.secondary) }
                    }.frame(width: 26, alignment: .leading)
                } else {
                    ZStack {
                        ArtworkView(song).frame(width: 50, height: 50)
                        if isCurrent { Color.black.opacity(0.45).clipShape(RoundedRectangle(cornerRadius: 6)); EqualizerBars(playing: app.player.isPlaying, color: .white).frame(width: 18, height: 18) }
                    }.frame(width: 50, height: 50)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(song.title).text(.body).fontWeight(.semibold).foregroundStyle(isCurrent ? p.accent : p.text).lineLimit(1)
                    HStack(spacing: 4) {
                        if app.library.isLiked(song.id) { Image(systemName: "heart.fill").font(.system(size: 10)).foregroundStyle(p.accent) }
                        Text(song.playable ? (subtitle ?? "\(song.artist) • \(song.album)") : "Unsupported format (.\(song.fileExtension))").text(.bodyS).foregroundStyle(p.secondary).lineLimit(1)
                    }
                }
                Spacer(minLength: 4)
                SongMenu(song: song, removeLabel: removeLabel, onRemove: onRemove) {
                    Image(systemName: "ellipsis").font(.system(size: 16, weight: .bold)).foregroundStyle(p.secondary).frame(width: 36, height: 44).contentShape(Rectangle())
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 6)
            .contentShape(Rectangle())
            .opacity(song.playable ? 1 : 0.45)
        }
        .buttonStyle(.pressable(0.98))
        .contextMenu { SongMenuItems(song: song, removeLabel: removeLabel, onRemove: onRemove) }
    }
}

struct SongMenu<Label: View>: View {
    var song: Song
    var removeLabel: String? = nil
    var onRemove: (() -> Void)? = nil
    @ViewBuilder var label: () -> Label
    var body: some View { Menu { SongMenuItems(song: song, removeLabel: removeLabel, onRemove: onRemove) } label: { label() } }
}

struct SongMenuItems: View {
    var song: Song
    var removeLabel: String?
    var onRemove: (() -> Void)?
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    var body: some View {
        if !song.isSpoken {
            Button(app.library.isLiked(song.id) ? "Remove from Liked Songs" : "Add to Liked Songs", systemImage: app.library.isLiked(song.id) ? "heart.slash" : "heart") { app.library.toggleLike(song.id); Haptics.success() }
        }
        Button("Play next", systemImage: "text.line.first.and.arrowtriangle.forward") { app.player.playNext([song]) }
        Button("Add to queue", systemImage: "text.line.last.and.arrowtriangle.forward") { app.player.addToQueue([song]) }
        if !song.isSpoken {
            Button("Go to song radio", systemImage: "dot.radiowaves.left.and.right") { app.player.play(app.songRadio(song), shuffle: false, source: "\(song.title) Radio") }
            Button("Add to playlist", systemImage: "text.badge.plus") { router.addingToPlaylist = [song] }
            Divider()
            Button("Go to album", systemImage: "square.stack") { router.go(.album(song.albumKey)) }
            Button("Go to artist", systemImage: "person") { router.go(.artist(song.artist)) }
        }
        if song.kind != .remote { Button("Edit info & artwork", systemImage: "pencil") { router.playerOpen = false; router.editing = ([song], false) } }
        if let onRemove { Button(removeLabel ?? "Remove", systemImage: "minus.circle", role: .destructive, action: onRemove) }
        if !song.isSpoken {
            Divider()
            Button("Don't recommend this song", systemImage: "hand.thumbsdown") { app.library.hiddenSongs.insert(song.id) }
            Button("Don't recommend \(song.artist)", systemImage: "person.slash") { app.library.hiddenArtists.insert(song.artist) }
        }
        Button("Song info", systemImage: "info.circle") { router.info = song }
    }
}

struct SectionHeader: View {
    var title: String
    var eyebrow: String? = nil
    var action: String? = nil
    var onAction: (() -> Void)? = nil
    @Environment(\.palette) private var p
    var body: some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 2) {
                if let eyebrow { Text(eyebrow.uppercased()).text(.labelS).foregroundStyle(p.secondary) }
                Text(title).text(.headlineS).foregroundStyle(p.text)
            }
            Spacer()
            if let action, let onAction { Button(action, action: onAction).text(.label).foregroundStyle(p.secondary).buttonStyle(.pressable) }
        }
        .padding(.horizontal, 16).padding(.top, 26).padding(.bottom, 10)
    }
}

struct Tile: Identifiable {
    var id: String
    var title: String
    var subtitle: String
    var song: Song?
    var circle = false
    var mix: Mix? = nil
    var remoteArt: String? = nil
    var action: () -> Void
}

struct MediaTile: View {
    var tile: Tile
    var width: CGFloat
    @Environment(\.palette) private var p
    var body: some View {
        Button(action: { Haptics.tap(); tile.action() }) {
            VStack(alignment: tile.circle ? .center : .leading, spacing: 8) {
                Group {
                    if let mix = tile.mix { MixCover(mix: mix) }
                    else if let r = tile.remoteArt { ArtworkView(key: tile.id, remote: r, cornerRadius: 8) }
                    else { ArtworkView(tile.song, cornerRadius: 8, circle: tile.circle) }
                }
                .frame(width: width, height: width)
                .shadow(color: .black.opacity(0.35), radius: 10, y: 6)
                Text(tile.title).text(.titleS).foregroundStyle(p.text).lineLimit(1)
                Text(tile.subtitle).text(.caption).foregroundStyle(p.secondary).lineLimit(2).multilineTextAlignment(tile.circle ? .center : .leading)
            }
            .frame(width: width, alignment: tile.circle ? .center : .leading)
        }
        .buttonStyle(.pressable)
    }
}

struct TileShelf: View {
    var title: String
    var eyebrow: String? = nil
    var tiles: [Tile]
    var width: CGFloat = 146
    var action: String? = nil
    var onAction: (() -> Void)? = nil
    @State private var available: CGFloat = 0
    var body: some View {
        if !tiles.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                SectionHeader(title: title, eyebrow: eyebrow, action: action, onAction: onAction)
                // Only whole tiles on screen: size them so N fit exactly, with the gap equal to the edge margin,
                // and snap to tile edges so a half-cut tile never rests at the left or right.
                let count = max(2, Int((available - 32 + 16) / (width + 16)))
                let fitted = available > 0 ? (available - 32 - 16 * CGFloat(count - 1)) / CGFloat(count) : width
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 16) { ForEach(tiles) { MediaTile(tile: $0, width: fitted) } }
                        .scrollTargetLayout()
                }
                .contentMargins(.horizontal, 16, for: .scrollContent)
                .scrollTargetBehavior(.viewAligned)
            }
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { available = $0 }
        }
    }
}

struct QuickTile: View {
    var tile: Tile
    @Environment(\.palette) private var p
    var body: some View {
        Button(action: { Haptics.tap(); tile.action() }) {
            HStack(spacing: 0) {
                Group { if let m = tile.mix { MixCover(mix: m, compact: true) } else { ArtworkView(tile.song, cornerRadius: 0) } }.frame(width: 54, height: 54)
                Text(tile.title).text(.titleS).foregroundStyle(p.text).lineLimit(2).multilineTextAlignment(.leading).padding(.horizontal, 10)
                Spacer(minLength: 0)
            }
            .frame(height: 54).frame(maxWidth: .infinity)
            .background(p.tint).clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        }.buttonStyle(.pressable)
    }
}

struct PlayButton: View {
    var playing: Bool
    var size: CGFloat = 54
    var color: Color? = nil
    var action: () -> Void
    @Environment(\.palette) private var p
    var body: some View {
        let bg = color ?? p.accent
        Button(action: { Haptics.tap(); action() }) {
            Image(systemName: playing ? "pause.fill" : "play.fill")
                .font(.system(size: size * 0.4, weight: .bold))
                .foregroundStyle(bg.luminance > 0.45 ? Color.black : .white)
                .contentTransition(.symbolEffect(.replace))
                .frame(width: size, height: size)
                .background(bg, in: Circle())
                .shadow(color: bg.opacity(0.4), radius: 10, y: 4)
        }.buttonStyle(.pressable(0.9))
    }
}

struct LikeButton: View {
    var song: Song
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    var body: some View {
        let liked = app.library.isLiked(song.id)
        Button { app.library.toggleLike(song.id); Haptics.success() } label: {
            Image(systemName: liked ? "heart.fill" : "heart").font(.system(size: 22, weight: .semibold))
                .foregroundStyle(liked ? p.accent : .white.opacity(0.9))
                .symbolEffect(.bounce, value: liked)
                .frame(width: 44, height: 44)
        }
    }
}

struct Pill: View {
    var title: String
    var selected: Bool
    var action: () -> Void
    @Environment(\.palette) private var p
    var body: some View {
        Button(action: { Haptics.tap(); action() }) {
            Text(title).text(.label).foregroundStyle(selected ? p.onAccent : p.text)
                .padding(.horizontal, 15).padding(.vertical, 8)
                .background(selected ? p.accent : p.tint, in: Capsule())
        }.buttonStyle(.pressable)
    }
}

struct EqualizerBars: View {
    var playing: Bool
    var color: Color? = nil
    @Environment(\.palette) private var p
    var body: some View {
        TimelineView(.animation(paused: !playing)) { ctx in
            let t = ctx.date.timeIntervalSinceReferenceDate
            Canvas { g, size in
                let w = size.width / 5
                for i in 0..<3 {
                    let h = playing ? size.height * (0.3 + 0.7 * abs(sin(t * (3.5 + Double(i) * 1.3) + Double(i)))) : size.height * 0.3
                    g.fill(Path(roundedRect: CGRect(x: w * Double(i * 2), y: size.height - h, width: w, height: h), cornerRadius: w / 2), with: .color(color ?? p.accent))
                }
            }
        }
    }
}

struct EmptyState: View {
    var title: String
    var message: String
    var icon: String = "music.note.list"
    @Environment(\.palette) private var p
    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: icon).font(.system(size: 40)).foregroundStyle(p.accent)
            Text(title).text(.title).foregroundStyle(p.text)
            Text(message).text(.bodyS).foregroundStyle(p.secondary).multilineTextAlignment(.center)
        }.padding(32).frame(maxWidth: .infinity)
    }
}

/// Covers for generated playlists: a 2×2 collage with a colour band (Daily Mix style) or bold type (Discover Weekly style).
struct MixCover: View {
    var mix: Mix
    var compact = false
    var body: some View {
        GeometryReader { geo in
            let side = geo.size.width
            let accent = Color(hex: mix.accent)
            let arts = Array(Dictionary(grouping: mix.songs, by: \.albumKey).values.compactMap(\.first).prefix(4))
            ZStack {
                if mix.style == .collage || compact {
                    if arts.count >= 4 {
                        VStack(spacing: 0) {
                            HStack(spacing: 0) { ArtworkView(arts[0], cornerRadius: 0); ArtworkView(arts[1], cornerRadius: 0) }
                            HStack(spacing: 0) { ArtworkView(arts[2], cornerRadius: 0); ArtworkView(arts[3], cornerRadius: 0) }
                        }
                    } else { ArtworkView(mix.cover, cornerRadius: 0) }
                } else {
                    LinearGradient(colors: [accent.mix(.white, 0.15), accent, accent.mix(.black, 0.55)], startPoint: .topLeading, endPoint: .bottomTrailing)
                    ArtworkView(mix.cover, cornerRadius: 6).frame(width: side * 0.38, height: side * 0.38).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing).padding(side * 0.08)
                    Text(mix.id == "daylist" ? "daylist" : mix.title).font(.custom("FigtreeLight-Black", size: side / 8.5)).foregroundStyle(.white).lineLimit(3)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading).padding(side * 0.09).padding(.top, side * 0.05)
                }
            }
            .frame(width: side, height: side)
            .clipShape(RoundedRectangle(cornerRadius: compact ? 0 : 8, style: .continuous))
        }
        .aspectRatio(1, contentMode: .fit)
    }
}

struct Avatar: View {
    var size: CGFloat
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    var body: some View {
        ZStack {
            Circle().fill(p.accent)
            if app.profile.photoVersion > 0, let img = UIImage(contentsOfFile: AppModel.photoURL.path) {
                Image(uiImage: img).resizable().scaledToFill()
            } else if let c = app.profile.name.first {
                Text(String(c).uppercased()).font(.system(size: size * 0.45, weight: .black)).foregroundStyle(p.onAccent)
            } else { Image(systemName: "person.fill").foregroundStyle(p.onAccent) }
        }
        .frame(width: size, height: size).clipShape(Circle())
        .id(app.profile.photoVersion)
    }
}
