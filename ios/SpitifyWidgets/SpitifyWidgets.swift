import SwiftUI
import WidgetKit
import AppIntents

private struct MusicEntry: TimelineEntry {
    let date: Date
    let music: WidgetMusicSnapshot
}

private struct MusicProvider: TimelineProvider {
    func placeholder(in context: Context) -> MusicEntry { MusicEntry(date: .now, music: .init()) }
    func getSnapshot(in context: Context, completion: @escaping (MusicEntry) -> Void) { completion(MusicEntry(date: .now, music: WidgetMusicStore.read())) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<MusicEntry>) -> Void) {
        completion(Timeline(entries: [MusicEntry(date: .now, music: WidgetMusicStore.read())], policy: .after(.now.addingTimeInterval(900))))
    }
}

private struct Cover: View {
    var item: WidgetMusicItem?
    var body: some View {
        Group {
            if let data = item?.artwork, let image = UIImage(data: data) { Image(uiImage: image).resizable().scaledToFill() }
            else { ZStack { LinearGradient(colors: [.green.opacity(0.4), .black], startPoint: .topLeading, endPoint: .bottomTrailing); Image(systemName: "music.note").font(.title).foregroundStyle(.white.opacity(0.8)) } }
        }.clipped().clipShape(RoundedRectangle(cornerRadius: 9))
    }
}

private struct PlaybackControls: View {
    var playing: Bool
    var hasMusic: Bool
    var compact = false
    var body: some View {
        HStack(spacing: compact ? 12 : 24) {
            Button(intent: WidgetPlaybackIntent("previous")) { Image(systemName: "backward.end.fill").frame(width: 30, height: 32) }.disabled(!hasMusic).accessibilityLabel("Previous song")
            Button(intent: WidgetPlaybackIntent("toggle")) {
                Image(systemName: playing ? "pause.fill" : "play.fill").font(.system(size: compact ? 18 : 22, weight: .bold)).foregroundStyle(.black)
                    .frame(width: compact ? 38 : 46, height: compact ? 38 : 46).background(.green, in: Circle())
            }.accessibilityLabel(playing ? "Pause" : "Play")
            Button(intent: WidgetPlaybackIntent("next")) { Image(systemName: "forward.end.fill").frame(width: 30, height: 32) }.disabled(!hasMusic).accessibilityLabel("Next song")
        }.buttonStyle(.plain).foregroundStyle(.white)
    }
}

private struct NowPlayingWidgetView: View {
    var entry: MusicEntry
    @Environment(\.widgetFamily) private var family
    var body: some View {
        Group {
            if family == .systemMedium {
                HStack(spacing: 14) {
                    Cover(item: entry.music.current).frame(width: 102, height: 102)
                    VStack(alignment: .leading, spacing: 5) {
                        title
                        Text(entry.music.current?.subtitle ?? "Your music, one tap away").font(.caption).foregroundStyle(.white.opacity(0.7)).lineLimit(1)
                        Spacer(minLength: 3)
                        PlaybackControls(playing: entry.music.isPlaying, hasMusic: entry.music.current != nil)
                    }
                }
            } else if family == .systemLarge {
                VStack(alignment: .leading, spacing: 10) {
                    GeometryReader { geometry in
                        let side = min(geometry.size.width, geometry.size.height)
                        Cover(item: entry.music.current).frame(width: side, height: side).clipped()
                            .frame(width: geometry.size.width, height: geometry.size.height)
                    }
                    HStack {
                        VStack(alignment: .leading, spacing: 3) { title; Text(entry.music.current?.subtitle ?? "Start listening in Spitify").font(.caption).foregroundStyle(.white.opacity(0.7)).lineLimit(1) }
                        Spacer(minLength: 0)
                    }
                    HStack { Spacer(); PlaybackControls(playing: entry.music.isPlaying, hasMusic: entry.music.current != nil); Spacer() }
                }
            } else {
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        Cover(item: entry.music.current).frame(width: 44, height: 44)
                        Text("SPITIFY").font(.system(size: 10, weight: .heavy)).tracking(1).foregroundStyle(.white.opacity(0.7))
                    }
                    title
                    Text(entry.music.current?.subtitle ?? "Start listening").font(.system(size: 10)).foregroundStyle(.white.opacity(0.7)).lineLimit(1)
                    Spacer(minLength: 0)
                    PlaybackControls(playing: entry.music.isPlaying, hasMusic: entry.music.current != nil, compact: true)
                }
            }
        }.foregroundStyle(.white).widgetURL(URL(string: "spitify://widget/now-playing"))
            .containerBackground(for: .widget) { widgetBackground(entry.music.current) }
    }
    private var title: some View { Text(entry.music.current?.title ?? "Ready to listen").font(family == .systemSmall ? .caption.weight(.semibold) : .headline).lineLimit(1) }
}

private func widgetBackground(_ item: WidgetMusicItem?) -> some View {
    ZStack {
        Color(red: 0.035, green: 0.05, blue: 0.04)
        if let data = item?.artwork, let image = UIImage(data: data) { Image(uiImage: image).resizable().scaledToFill().blur(radius: 25).overlay(.black.opacity(0.65)) }
    }
}

private struct MusicShelfView: View {
    let entry: MusicEntry
    let shelf: String
    let title: String
    @Environment(\.widgetFamily) private var family
    private var items: [WidgetMusicItem] { Array(entry.music.items(for: shelf).prefix(family == .systemLarge ? 6 : family == .systemMedium ? 4 : 1)) }
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack { Text(title).font(.caption.weight(.bold)).lineLimit(1); Spacer(minLength: 4); Image(systemName: "waveform").foregroundStyle(.green) }
            if items.isEmpty {
                Spacer(minLength: 0)
                Image(systemName: "music.note.list").font(.title).foregroundStyle(.green)
                Text(emptyText).font(.caption).foregroundStyle(.white.opacity(0.75)).fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            } else if family == .systemSmall {
                if let first = items.first {
                    Button(intent: WidgetPlaybackIntent("play", mediaID: first.id)) {
                        VStack(alignment: .leading, spacing: 5) {
                            GeometryReader { geometry in Cover(item: first).frame(width: geometry.size.width, height: geometry.size.height).clipped() }
                            Text(first.title).font(.caption.weight(.semibold)).lineLimit(1)
                        }
                    }.buttonStyle(.plain).accessibilityLabel("Play " + first.title)
                }
            } else {
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: family == .systemLarge ? 12 : 10) {
                    ForEach(items) { item in
                        Button(intent: WidgetPlaybackIntent("play", mediaID: item.id)) {
                            if family == .systemLarge {
                                HStack(alignment: .center, spacing: 8) {
                                    Cover(item: item).frame(width: 56, height: 72)
                                    VStack(alignment: .leading, spacing: 4) { Text(item.title).font(.caption.weight(.semibold)).lineLimit(2); Text(item.subtitle).font(.system(size: 10)).foregroundStyle(.white.opacity(0.65)).lineLimit(2) }
                                    Spacer(minLength: 0)
                                }
                            } else {
                                HStack(spacing: 7) { Cover(item: item).frame(width: 42, height: 42); Text(item.title).font(.system(size: 11, weight: .semibold)).lineLimit(2); Spacer(minLength: 0) }
                            }
                        }.buttonStyle(.plain).accessibilityLabel("Play " + item.title)
                    }
                }
                Spacer(minLength: 0)
            }
        }.foregroundStyle(.white).widgetURL(URL(string: "spitify://widget/" + shelf))
            .containerBackground(for: .widget) { widgetBackground(items.first) }
    }
    private var emptyText: String {
        switch shelf {
        case "playlists": return "Make a playlist in Spitify to see it here."
        case "most-played", "recently-played": return "Play some music to fill this shelf."
        case "liked": return "Like a song to keep it close."
        default: return "Add music in Spitify to see your covers here."
        }
    }
}

private struct FriendsWidgetView: View {
    @Environment(\.widgetFamily) private var family
    private let links = [("friends", "Friends", "person.2.fill"), ("code", "My friend code", "qrcode"), ("rooms", "Listen together", "headphones")]
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("SPITIFY FRIENDS").font(.caption.weight(.heavy))
            Spacer(minLength: 0)
            ForEach(links.prefix(family == .systemSmall ? 1 : 3), id: \.0) { item in
                Link(destination: URL(string: "spitify://widget/" + item.0)!) { Label(item.1, systemImage: item.2).font(.caption.weight(.semibold)).foregroundStyle(.white).padding(8).frame(maxWidth: .infinity, alignment: .leading).background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 10)) }
            }
            Spacer(minLength: 0)
        }.foregroundStyle(.white).widgetURL(URL(string: "spitify://widget/friends"))
            .containerBackground(for: .widget) { Color(red: 0.04, green: 0.11, blue: 0.075) }
    }
}

private struct NowPlayingWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "Spitify.play", provider: MusicProvider()) { NowPlayingWidgetView(entry: $0) }
            .configurationDisplayName("Now Playing").description("Your cover art, with previous, play/pause and next.")
            .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}
private struct ShelfWidget: Widget {
    var kind: String = "library"
    var shelf: String = "playlists"
    var title: String = "Playlists"
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "Spitify." + kind, provider: MusicProvider()) { MusicShelfView(entry: $0, shelf: shelf, title: title) }
            .configurationDisplayName(title).description("Your music and cover art. Tap an item to play it.")
            .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}
private struct FriendsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "Spitify.friends", provider: MusicProvider()) { _ in FriendsWidgetView() }
            .configurationDisplayName("Friends").description("Your friends, picture code and listening rooms.")
            .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

@main struct SpitifyWidgets: WidgetBundle {
    var body: some Widget {
        NowPlayingWidget()
        ShelfWidget(kind: "library", shelf: "playlists", title: "Playlists")
        ShelfWidget(kind: "albums", shelf: "albums", title: "Albums")
        ShelfWidget(kind: "most-played", shelf: "most-played", title: "Most Played")
        ShelfWidget(kind: "recently-played", shelf: "recently-played", title: "Recently Played")
        ShelfWidget(kind: "recently-added", shelf: "recently-added", title: "Recently Added")
        ShelfWidget(kind: "liked", shelf: "liked", title: "Liked Songs")
        FriendsWidget()
    }
}
