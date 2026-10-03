import SwiftUI

/// Shared sizes for the same jobs: page edges, media rows, and tappable controls.
enum MediaLayout {
    static let inset: CGFloat = 16
    static let rowArt: CGFloat = 52
    static let rowSpacing: CGFloat = 12
    static let rowPadding: CGFloat = 8
    static let touch: CGFloat = 44
    static let cover: CGFloat = 236
}

struct MediaRowText: View {
    var title: String
    var subtitle: String
    var highlighted = false
    var badge: String? = nil
    var explicit = false
    @Environment(\.palette) private var p
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).text(.body).fontWeight(.semibold).foregroundStyle(highlighted ? p.accent : p.text)
                .lineLimit(2).fixedSize(horizontal: false, vertical: true)
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                if explicit { Text("E").font(.system(size: 9, weight: .bold)).foregroundStyle(p.background).frame(width: 13, height: 13).background(p.secondary, in: RoundedRectangle(cornerRadius: 2)).accessibilityLabel("Explicit") }
                if let badge { Image(systemName: badge).font(.system(size: 12)).foregroundStyle(p.accent) }
                Text(subtitle).text(.bodyS).foregroundStyle(p.secondary).lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }.multilineTextAlignment(.leading).frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct MediaRowContent<Artwork: View, Detail: View, Trailing: View>: View {
    var inset: CGFloat = MediaLayout.inset
    @ViewBuilder var artwork: () -> Artwork
    @ViewBuilder var detail: () -> Detail
    @ViewBuilder var trailing: () -> Trailing
    var body: some View {
        HStack(spacing: MediaLayout.rowSpacing) {
            artwork()
            detail().frame(maxWidth: .infinity, alignment: .leading)
            trailing()
        }
        .frame(maxWidth: .infinity, minHeight: MediaLayout.rowArt, alignment: .leading)
        .padding(.horizontal, inset).padding(.vertical, MediaLayout.rowPadding)
        .contentShape(Rectangle())
    }
}

struct IconControlLabel: View {
    var symbol: String
    var selected = false
    @Environment(\.palette) private var p
    var body: some View {
        Image(systemName: symbol).font(.system(size: 22, weight: .medium))
            .foregroundStyle(selected ? p.accent : p.secondary)
            .frame(width: MediaLayout.touch, height: MediaLayout.touch).contentShape(Rectangle())
    }
}

struct IconControl: View {
    var title: String
    var symbol: String
    var selected = false
    var action: () -> Void
    var body: some View {
        Button(action: action) { IconControlLabel(symbol: symbol, selected: selected) }
            .buttonStyle(.pressable).accessibilityLabel(title)
    }
}

/// The album action bar is also the action bar for playlists, artists, and spoken collections.
struct CollectionActionBar<Actions: View>: View {
    var playing = false
    var enabled = true
    var playLabel: String? = nil
    var shuffle: (() -> Void)? = nil
    var play: () -> Void
    @ViewBuilder var actions: () -> Actions
    @Environment(\.palette) private var p
    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 4) { actions(); Spacer(minLength: 4); playback }
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 4) { actions(); Spacer(minLength: 0) }
                HStack(spacing: 4) { Spacer(minLength: 0); playback }
            }
        }
        .buttonStyle(.plain).foregroundStyle(p.secondary)
        .padding(.horizontal, 12).padding(.vertical, 8)
    }
    private var playback: some View {
        HStack(spacing: 8) {
            if let shuffle { IconControl(title: "Shuffle", symbol: "shuffle", action: shuffle).disabled(!enabled) }
            PlayButton(playing: playing, action: play).disabled(!enabled).opacity(enabled ? 1 : 0.4)
                .accessibilityLabel(playLabel ?? (playing ? "Pause" : "Play"))
        }
    }
}

/// The original album header, shared by every collection instead of restyled in each screen.
struct CollectionLayout<Cover: View, Actions: View, Content: View>: View {
    var title: String
    var subtitle: String
    var metadata: String
    var artKey: String
    var remoteArt: String? = nil
    var portrait = false
    @ViewBuilder var cover: () -> Cover
    @ViewBuilder var actions: () -> Actions
    @ViewBuilder var content: () -> Content
    @Environment(\.palette) private var p
    @Environment(\.themeSettings) private var theme
    @State private var color = Color(hex: 0x2A2A2E)
    @State private var offset: CGFloat = 0
    @Namespace private var scrollSpace
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                actions()
                content()
            }
            .padding(.bottom, 30)
            .background(GeometryReader { geometry in
                Color.clear.preference(key: CollectionOffsetKey.self, value: geometry.frame(in: .named(scrollSpace)).minY)
            })
        }
        .coordinateSpace(name: scrollSpace)
        .onPreferenceChange(CollectionOffsetKey.self) { offset = $0 }
        .background(p.background)
        .artColor(key: artKey, remote: remoteArt, into: $color)
        .navigationTitle(offset < -280 ? title : "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(offset < -280 ? .visible : .hidden, for: .navigationBar)
        .toolbarBackground(color.mix(p.background, 0.3), for: .navigationBar)
    }
    private var header: some View {
        let collapse = theme.reduceMotion ? 0 : min(1, max(0, -offset / 300))
        let headerColor = p.isDark ? color : color.mix(.white, 0.55)
        return VStack(alignment: .leading, spacing: 6) {
            cover()
                .frame(width: portrait ? 180 : MediaLayout.cover, height: portrait ? 250 : MediaLayout.cover)
                .shadow(color: .black.opacity(p.isDark ? 0.45 : 0.2), radius: 24, y: 12)
                .scaleEffect(1 - collapse * 0.25).opacity(1 - collapse * 1.1)
                .frame(maxWidth: .infinity).padding(.top, 8)
            Text(title).text(.headlineL).foregroundStyle(p.text)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, MediaLayout.inset).padding(.top, 14)
            if !subtitle.isEmpty {
                Text(subtitle).text(.bodyS).foregroundStyle(p.text.opacity(0.85))
                    .fixedSize(horizontal: false, vertical: true).padding(.horizontal, MediaLayout.inset)
            }
            if !metadata.isEmpty {
                Text(metadata).text(.caption).foregroundStyle(p.secondary)
                    .fixedSize(horizontal: false, vertical: true).padding(.horizontal, MediaLayout.inset)
            }
        }
        .padding(.bottom, 8)
        .background(LinearGradient(colors: [headerColor, headerColor.mix(p.background, 0.75), p.background], startPoint: .top, endPoint: .bottom).padding(.top, -400))
    }
}

private struct CollectionOffsetKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

struct AppForm<Content: View>: View {
    @ViewBuilder var content: () -> Content
    @Environment(\.palette) private var p
    var body: some View { Form { content().listRowBackground(p.surface) }.appFormStyle() }
}

struct AppList<Content: View>: View {
    @ViewBuilder var content: () -> Content
    @Environment(\.palette) private var p
    var body: some View { List { content().listRowBackground(p.surface) }.appFormStyle() }
}

private struct AppFormStyle: ViewModifier {
    @Environment(\.palette) private var p
    func body(content: Content) -> some View {
        content.text(.body).foregroundStyle(p.text).tint(p.accent)
            .environment(\.defaultMinListRowHeight, 52)
            .scrollContentBackground(.hidden).background(p.background)
    }
}

extension View {
    func appFormStyle() -> some View { modifier(AppFormStyle()) }
}
