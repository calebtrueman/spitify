import SwiftUI

struct ArtTheme: Codable, Identifiable {
    let id: String
    let name: String
    let group: String
    let background: String
    let accent: String
    let light: Bool
    let border: String
    let detail: String
    var backgroundHex: UInt32 { UInt32(background, radix: 16) ?? 0x09090B }
    var accentHex: UInt32 { UInt32(accent, radix: 16) ?? 0x1ED760 }
    func applying(to current: ThemeSettings) -> ThemeSettings {
        var next = current
        next.artThemeID = id; next.hideThemeArt = false
        next.mode = light ? .light : .dark; next.backdrop = backgroundHex
        next.accentSource = .preset; next.accent = accentHex
        next.font = .figtree; next.artShape = border == "blocks" ? .square : .rounded
        return next
    }
    static let all: [ArtTheme] = {
        guard let url = Bundle.main.url(forResource: "theme-catalog", withExtension: "json", subdirectory: "themes"),
              let data = try? Data(contentsOf: url), let themes = try? JSONDecoder().decode([ArtTheme].self, from: data) else { return [] }
        return themes
    }()
    static func selected(_ settings: ThemeSettings) -> ArtTheme? { all.first { $0.id == settings.artThemeID } }
}

private enum ThemeImages {
    static let cache = NSCache<NSString, UIImage>()
    static func image(_ id: String) -> UIImage? {
        if let image = cache.object(forKey: id as NSString) { return image }
        guard let url = Bundle.main.url(forResource: id, withExtension: "png", subdirectory: "themes/art"),
              let image = UIImage(contentsOfFile: url.path) else { return nil }
        cache.setObject(image, forKey: id as NSString, cost: Int(image.size.width * image.size.height * 4))
        cache.totalCostLimit = 32 * 1024 * 1024
        return image
    }
}

struct ThemeArtImage: View {
    let id: String
    var body: some View {
        if let image = ThemeImages.image(id) {
            Image(uiImage: image).resizable().interpolation(.none).scaledToFit().accessibilityHidden(true)
        }
    }
}

struct ThemeScene: View {
    @Environment(\.themeSettings) private var settings
    var compact = false
    var body: some View {
        if settings.hideThemeArt != true, let theme = ArtTheme.selected(settings) {
            ThemeArtImage(id: theme.id)
                .frame(maxWidth: compact ? 300 : 480).frame(height: compact ? 58 : 110)
                .frame(maxWidth: .infinity).padding(.horizontal, 24).padding(.vertical, compact ? 2 : 8)
                .allowsHitTesting(false)
        }
    }
}

/// Decoration stays in the outer eight points, away from text and touch targets.
struct ThemeFrame: View {
    @Environment(\.themeSettings) private var settings
    @Environment(\.palette) private var palette
    var body: some View {
        if settings.hideThemeArt != true, let theme = ArtTheme.selected(settings) {
            Canvas { context, size in
                let ink = palette.accent.opacity(theme.border == "deco" ? 0.38 : 0.25)
                var edge = Path()
                for x in [CGFloat(3), size.width - 3] {
                    edge.move(to: CGPoint(x: x, y: 20)); edge.addLine(to: CGPoint(x: x, y: size.height - 20))
                }
                context.stroke(edge, with: .color(ink.opacity(0.55)), lineWidth: 1)
                for y in stride(from: CGFloat(28), to: size.height - 20, by: 68) {
                    for x in [CGFloat(4), size.width - 4] {
                        var mark = Path()
                        switch theme.border {
                        case "stars", "crystals":
                            mark.move(to: CGPoint(x: x, y: y - 5)); mark.addLine(to: CGPoint(x: x + 3, y: y)); mark.addLine(to: CGPoint(x: x, y: y + 5)); mark.addLine(to: CGPoint(x: x - 3, y: y)); mark.closeSubpath()
                        case "petals", "leaves":
                            mark.addEllipse(in: CGRect(x: x - 3, y: y - 6, width: 6, height: 9))
                            mark.addEllipse(in: CGRect(x: x - 2, y: y + 4, width: 4, height: 5))
                        case "paws":
                            mark.addEllipse(in: CGRect(x: x - 2, y: y, width: 4, height: 4))
                            for dx in [-2.0, 1.0] { mark.addEllipse(in: CGRect(x: x + dx, y: y - 4, width: 2, height: 2)) }
                        case "waves", "rain":
                            for dy in [0.0, 4.0, 8.0] { mark.addRect(CGRect(x: x - 2 + (dy == 4 ? 1 : 0), y: y + dy, width: 3, height: 2)) }
                        case "dots", "gears":
                            mark.addEllipse(in: CGRect(x: x - 3, y: y - 3, width: 6, height: 6))
                        default:
                            mark.addRect(CGRect(x: x - 2, y: y - 5, width: 4, height: theme.border == "deco" ? 10 : 5))
                            if theme.border == "circuit" || theme.border == "steps" { mark.addRect(CGRect(x: x - 1, y: y + 2, width: 3, height: 4)) }
                        }
                        context.fill(mark, with: .color(ink))
                    }
                }
            }.allowsHitTesting(false).accessibilityHidden(true)
        }
    }
}

struct ArtThemeGallery: View {
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    @Environment(\.dynamicTypeSize) private var textSize
    @State private var group = "All"
    private let groups = ["All", "Cozy", "Nature", "Night", "Places", "Play", "Quiet"]
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("25 little worlds for your music").text(.caption).foregroundStyle(p.secondary)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack { ForEach(groups, id: \.self) { value in Pill(title: value, selected: group == value) { group = value } } }
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: textSize.isAccessibilitySize ? 260 : 145), spacing: 12)], spacing: 12) {
                ForEach(ArtTheme.all.filter { group == "All" || $0.group == group }) { theme in
                    let selected = app.theme.artThemeID == theme.id
                    Button { app.theme = theme.applying(to: app.theme) } label: {
                        VStack(alignment: .leading, spacing: 8) {
                            ThemeArtImage(id: theme.id).frame(height: 76).frame(maxWidth: .infinity)
                            HStack(alignment: .top, spacing: 6) {
                                Text(theme.name).font(.system(size: 15, weight: .semibold)).fixedSize(horizontal: false, vertical: true)
                                Spacer(minLength: 0)
                                if selected { Image(systemName: "checkmark.circle.fill") }
                            }
                            Text(theme.detail).font(.system(size: 12)).opacity(0.78).fixedSize(horizontal: false, vertical: true)
                        }.foregroundStyle(theme.light ? Color(hex: 0x241F25) : Color(hex: 0xF5F3EE))
                            .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color(hex: theme.backgroundHex), in: RoundedRectangle(cornerRadius: theme.border == "blocks" ? 6 : 16))
                            .overlay(RoundedRectangle(cornerRadius: theme.border == "blocks" ? 6 : 16).stroke(selected ? p.accent : Color(hex: theme.accentHex).opacity(0.3), lineWidth: selected ? 3 : 1))
                    }.buttonStyle(.plain).accessibilityLabel(theme.name).accessibilityHint(theme.detail)
                        .accessibilityAddTraits(selected ? .isSelected : []).accessibilityIdentifier("theme-" + theme.id)
                }
            }
            Toggle("Show theme art & borders", isOn: Binding(get: { app.theme.hideThemeArt != true }, set: { app.theme.hideThemeArt = !$0 }))
        }
    }
}
