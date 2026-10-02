import SwiftUI

/// Album artwork with a gradient fallback. Honors the "artwork shape" appearance setting.
struct ArtworkView: View {
    var key: String
    var remote: String? = nil
    var cornerRadius: CGFloat = 6
    var circle = false
    @Environment(\.themeSettings) private var theme
    @Environment(AppModel.self) private var app
    @State private var image: UIImage?

    init(_ song: Song?, cornerRadius: CGFloat = 6, circle: Bool = false) {
        key = song?.albumKey ?? "none"; remote = song?.artURL
        self.cornerRadius = cornerRadius; self.circle = circle
    }
    init(key: String, remote: String?, cornerRadius: CGFloat = 6, circle: Bool = false) {
        self.key = key; self.remote = remote; self.cornerRadius = cornerRadius; self.circle = circle
    }

    var body: some View {
        let radius: CGFloat = theme.artShape == .square ? 0 : theme.artShape == .soft ? max(cornerRadius, 16) : cornerRadius
        ZStack {
            LinearGradient(colors: [fallbackColor(key), fallbackColor(key).mix(.black, 0.55)], startPoint: .topLeading, endPoint: .bottomTrailing)
            Image(systemName: "music.note").font(.system(size: 22, weight: .semibold)).foregroundStyle(.white.opacity(0.55))
            // Overlay on a clear view so a fill-scaled image never reports a size bigger than its frame.
            if let image { Color.clear.overlay { Image(uiImage: image).resizable().scaledToFill() }.clipped().transaction { $0.animation = nil; $0.disablesAnimations = true } }
        }
        .clipShape(circle ? AnyShape(Circle()) : AnyShape(RoundedRectangle(cornerRadius: radius, style: .continuous)))
        .task(id: "\(key)|\(remote ?? "")|\(app.library.artVersion)") {
            guard let img = await ArtCache.shared.load(key: key, remote: remote), !Task.isCancelled else { return }
            guard image !== img else { return }
            var transaction = Transaction(animation: nil); transaction.disablesAnimations = true
            withTransaction(transaction) { image = img }
        }
    }
}

/// The colour behind headers and the player, extracted from the cover.
struct ArtColorReader: ViewModifier {
    var key: String
    var remote: String?
    @Binding var color: Color
    @Environment(\.themeSettings) private var theme
    func body(content: Content) -> some View {
        content.task(id: "\(key)|\(remote ?? "")|\(theme.artworkTint)") {
            guard theme.artworkTint, key != "none" else { withAnimation { color = Color(hex: 0x2A2A2E) }; return }
            if let img = await ArtCache.shared.load(key: key, remote: remote), !Task.isCancelled {
                let c = ArtCache.shared.color(for: key, image: img)
                withAnimation(.easeInOut(duration: 0.6)) { color = c }
            } else { withAnimation { color = fallbackColor(key).mix(.black, 0.35) } }
        }
    }
}
extension View {
    func artColor(_ song: Song?, into color: Binding<Color>) -> some View { modifier(ArtColorReader(key: song?.albumKey ?? "none", remote: song?.artURL, color: color)) }
    func artColor(key: String, remote: String?, into color: Binding<Color>) -> some View { modifier(ArtColorReader(key: key, remote: remote, color: color)) }
}
