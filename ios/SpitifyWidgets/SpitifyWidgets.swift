import SwiftUI
import WidgetKit

private struct MusicEntry: TimelineEntry { let date: Date }
private struct MusicProvider: TimelineProvider {
    func placeholder(in context: Context) -> MusicEntry { MusicEntry(date: .now) }
    func getSnapshot(in context: Context, completion: @escaping (MusicEntry) -> Void) { completion(MusicEntry(date: .now)) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<MusicEntry>) -> Void) { completion(Timeline(entries: [MusicEntry(date: .now)], policy: .never)) }
}
private struct WidgetChoice: Identifiable {
    let id: String; let name: String; let symbol: String
    var url: URL { URL(string: "spitify://widget/" + id)! }
}
private struct MusicWidgetView: View {
    var kind: String
    @Environment(\.widgetFamily) private var family
    private var choices: [WidgetChoice] {
        switch kind {
        case "library": return [.init(id: "library", name: "All songs", symbol: "music.note.list"), .init(id: "recent", name: "Recently added", symbol: "clock"), .init(id: "liked", name: "Liked songs", symbol: "heart.fill"), .init(id: "search", name: "Search", symbol: "magnifyingglass")]
        case "friends": return [.init(id: "friends", name: "Friends", symbol: "person.2.fill"), .init(id: "code", name: "My friend code", symbol: "qrcode"), .init(id: "rooms", name: "Listen together", symbol: "headphones"), .init(id: "library", name: "Share music", symbol: "square.and.arrow.up")]
        default: return [.init(id: "resume", name: "Resume", symbol: "play.fill"), .init(id: "shuffle", name: "Shuffle library", symbol: "shuffle"), .init(id: "liked", name: "Liked songs", symbol: "heart.fill"), .init(id: "recent-play", name: "Play recent", symbol: "clock.fill")]
        }
    }
    var body: some View {
        VStack(alignment: .leading, spacing: family == .systemLarge ? 22 : 12) {
            HStack { Text("SPITIFY").font(.caption.weight(.heavy)).tracking(2); Spacer(); Image(systemName: kind == "friends" ? "person.2.wave.2.fill" : "opticaldisc.fill").foregroundStyle(.green) }
            if family == .systemSmall {
                Spacer(minLength: 0)
                Image(systemName: choices[0].symbol).font(.system(size: 34, weight: .semibold)).foregroundStyle(.green)
                Text(choices[0].name).font(.title3.bold())
                Text("Open & listen").font(.caption).foregroundStyle(.white.opacity(0.65))
            } else {
                if family == .systemLarge { Text(kind == "friends" ? "Music together." : "Your next good song.").font(.largeTitle.bold()).fixedSize(horizontal: false, vertical: true) }
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                    ForEach(choices) { choice in Link(destination: choice.url) {
                        HStack(spacing: 8) { Image(systemName: choice.symbol).foregroundStyle(.green); Text(choice.name).font(.caption.weight(.semibold)); Spacer(minLength: 0) }.padding(10).frame(maxWidth: .infinity, minHeight: family == .systemLarge ? 56 : 36).background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                    } }
                }
            }
        }.foregroundStyle(.white)
            .containerBackground(for: .widget) { LinearGradient(colors: [Color(red: 0.07, green: 0.16, blue: 0.12), Color(red: 0.035, green: 0.045, blue: 0.04)], startPoint: .topLeading, endPoint: .bottomTrailing) }
            .widgetURL(choices[0].url)
    }
}
private struct SpitifyWidget: Widget {
    var kind: String = "play"
    var title: String = "Quick play"
    var description: String = "Open Spitify and start listening."
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "Spitify." + kind, provider: MusicProvider()) { _ in MusicWidgetView(kind: kind) }
            .configurationDisplayName(title).description(description).supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}
@main struct SpitifyWidgets: WidgetBundle {
    var body: some Widget {
        SpitifyWidget(kind: "play", title: "Quick play", description: "Open Spitify and resume, shuffle, or play your favourites.")
        SpitifyWidget(kind: "library", title: "Your Library", description: "Jump to saved music, recently added songs, and search.")
        SpitifyWidget(kind: "friends", title: "Friends", description: "Open your friends, picture code, and listening rooms.")
    }
}
