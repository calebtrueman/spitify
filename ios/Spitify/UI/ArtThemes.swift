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

private struct ThemeBorderMotif: Decodable {
    var id: String
    var colors: [String]
    var rows: [String]
    static let all: [String: ThemeBorderMotif] = {
        guard let url = Bundle.main.url(forResource: "border-motifs", withExtension: "json", subdirectory: "themes"),
              let data = try? Data(contentsOf: url), let drawings = try? JSONDecoder().decode([ThemeBorderMotif].self, from: data) else { return [:] }
        return Dictionary(uniqueKeysWithValues: drawings.map { ($0.id, $0) })
    }()
}

/// Tiny theme-specific drawings leave the middle of the screen clear for music.
struct ThemeFrame: View {
    @Environment(\.themeSettings) private var settings
    var body: some View {
        if settings.hideThemeArt != true, let theme = ArtTheme.selected(settings), let motif = ThemeBorderMotif.all[theme.id] {
            Canvas { context, size in
                let pixel: CGFloat = 1.35
                let width = CGFloat(motif.rows.first?.count ?? 0) * pixel
                let colors = motif.colors.map { Color(hex: UInt32($0, radix: 16) ?? theme.accentHex).opacity(theme.light ? 0.66 : 0.72) }
                let rows = motif.rows.map(Array.init)
                // Stagger the opposite edges so the drawings feel scattered, not boxed in.
                for side in 0..<2 {
                    let start: CGFloat = side == 0 ? 22 : 67
                    for top in stride(from: start, to: size.height - 28, by: 112) {
                        for colorIndex in colors.indices {
                            var shape = Path()
                            for (y, row) in rows.enumerated() {
                                for (x, value) in row.enumerated() where value.wholeNumberValue == colorIndex + 1 {
                                    let dx = side == 0 ? 1 + CGFloat(x) * pixel : size.width - 1 - width + CGFloat(row.count - 1 - x) * pixel
                                    let dy = top + CGFloat(y) * pixel
                                    if dy + pixel <= size.height - 12 { shape.addRect(CGRect(x: dx, y: dy, width: pixel, height: pixel)) }
                                }
                            }
                            context.fill(shape, with: .color(colors[colorIndex]))
                        }
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
