import SwiftUI

struct Palette {
    var accent: Color
    var background: Color
    var surface: Color
    var surfaceHigh: Color
    var text: Color
    var secondary: Color
    var tertiary: Color
    var isDark: Bool
    var tint: Color { text.opacity(isDark ? 0.08 : 0.06) }
    var onAccent: Color { accent.luminance > 0.179 ? .black : .white }

    static func make(_ t: ThemeSettings, scheme: ColorScheme, artAccent: Color?) -> Palette {
        let dark = t.mode == .system ? scheme == .dark : t.mode != .light
        let accent = (t.accentSource == .artwork ? artAccent : nil) ?? Color(hex: t.accent)
        if let backdrop = t.backdrop, t.mode != .amoled, t.mode != .system {
            let bg = Color(hex: backdrop)
            let ink: Color = dark ? .white : .black
            return Palette(accent: accent.readable(on: bg.mix(ink, dark ? 0.085 : 0.05)), background: bg, surface: bg.mix(ink, dark ? 0.045 : 0.018),
                surfaceHigh: bg.mix(ink, dark ? 0.085 : 0.05), text: dark ? .white : Color(hex: 0x111114),
                secondary: dark ? Color(hex: 0xB9BAC4) : Color(hex: 0x535361),
                tertiary: dark ? Color(hex: 0xAEAEBA) : Color(hex: 0x595968), isDark: dark)
        }
        if !dark {
            return Palette(accent: accent.readable(on: Color(hex: 0xEDEDF1)), background: Color(hex: 0xF7F7F9), surface: .white, surfaceHigh: Color(hex: 0xEDEDF1),
                           text: Color(hex: 0x111114), secondary: Color(hex: 0x5E5E66), tertiary: Color(hex: 0x63636D), isDark: false)
        }
        let amoled = t.mode == .amoled
        return Palette(accent: accent.readable(on: Color(hex: amoled ? 0x141416 : 0x1C1C21)), background: amoled ? .black : Color(hex: 0x09090B), surface: amoled ? Color(hex: 0x0A0A0B) : Color(hex: 0x131316),
                       surfaceHigh: amoled ? Color(hex: 0x141416) : Color(hex: 0x1C1C21), text: .white, secondary: Color(hex: 0xA7A7AE), tertiary: Color(hex: 0x92929D), isDark: true)
    }
}

private struct PaletteKey: EnvironmentKey { static let defaultValue = Palette.make(ThemeSettings(), scheme: .dark, artAccent: nil) }
private struct ThemeKey: EnvironmentKey { static let defaultValue = ThemeSettings() }
extension EnvironmentValues {
    var palette: Palette { get { self[PaletteKey.self] } set { self[PaletteKey.self] = newValue } }
    var themeSettings: ThemeSettings { get { self[ThemeKey.self] } set { self[ThemeKey.self] = newValue } }
}

extension Color {
    /// Accents also label selected items, so pale colours need deeper ink on light cards.
    func readable(on background: Color) -> Color {
        let backdrop = background.luminance
        func contrast(_ color: Color) -> Double {
            let ink = color.luminance
            return (max(ink, backdrop) + 0.05) / (min(ink, backdrop) + 0.05)
        }
        guard contrast(self) < 4.5 else { return self }
        let target: Color = backdrop > 0.179 ? .black : .white
        var low = 0.0, high = 1.0
        for _ in 0..<12 {
            let amount = (low + high) / 2
            if contrast(mix(target, amount)) < 4.5 { low = amount } else { high = amount }
        }
        return mix(target, high)
    }

    var luminance: Double {
        let c = UIColor(self); var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        c.getRed(&r, green: &g, blue: &b, alpha: &a)
        func linear(_ c: CGFloat) -> Double { let v = Double(c); return v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4) }
        return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
    }
    func mix(_ other: Color, _ amount: Double) -> Color {
        let a = UIColor(self), b = UIColor(other)
        var r1: CGFloat = 0, g1: CGFloat = 0, b1: CGFloat = 0, a1: CGFloat = 0, r2: CGFloat = 0, g2: CGFloat = 0, b2: CGFloat = 0, a2: CGFloat = 0
        a.getRed(&r1, green: &g1, blue: &b1, alpha: &a1); b.getRed(&r2, green: &g2, blue: &b2, alpha: &a2)
        let t = CGFloat(amount)
        return Color(red: r1 + (r2 - r1) * t, green: g1 + (g2 - g1) * t, blue: b1 + (b2 - b1) * t)
    }
}

// MARK: - Typography

enum TextRole { case display, headlineL, headline, headlineS, title, titleS, body, bodyS, caption, label, labelS }

struct AppText: ViewModifier {
    @Environment(\.themeSettings) private var theme
    var role: TextRole
    func body(content: Content) -> some View {
        let (size, weight): (CGFloat, Font.Weight) = switch role {
        case .display: (34, .black)
        case .headlineL: (30, .heavy)
        case .headline: (25, .heavy)
        case .headlineS: (21, .bold)
        case .title: (18, .bold)
        case .titleS: (15, .bold)
        case .body: (16, .medium)
        case .bodyS: (14, .medium)
        case .caption: (12, .medium)
        case .label: (13, .bold)
        case .labelS: (11, .bold)
        }
        let scale: CGFloat = switch theme.textScale { case .small: 0.9; case .normal: 1; case .large: 1.12; case .huge: 1.25 }
        let s = size * scale
        return content
            .font(font(s, weight))
            .tracking(role == .display || role == .headlineL ? -0.8 : role == .labelS ? 0.6 : 0)
    }
    private func font(_ s: CGFloat, _ w: Font.Weight) -> Font {
        switch theme.font {
        case .figtree:
            let name = switch w { case .black: "Black"; case .heavy: "ExtraBold"; case .bold: "Bold"; case .semibold: "SemiBold"; case .medium: "Medium"; default: "Regular" }
            return .custom("FigtreeLight-\(name)", size: s)
        case .system: return .system(size: s, weight: w)
        case .rounded: return .system(size: s, weight: w, design: .rounded)
        case .serif: return .system(size: s, weight: w, design: .serif)
        }
    }
}

extension View {
    func text(_ role: TextRole) -> some View { modifier(AppText(role: role)) }
}

// MARK: - Haptics & motion

@MainActor enum Haptics {
    static var enabled = true
    static func tap() { if enabled { UIImpactFeedbackGenerator(style: .light).impactOccurred() } }
    static func soft() { if enabled { UIImpactFeedbackGenerator(style: .soft).impactOccurred() } }
    static func success() { if enabled { UINotificationFeedbackGenerator().notificationOccurred(.success) } }
}

/// Spring-y shrink on press, used for every card and tile.
struct PressableStyle: ButtonStyle {
    var scale: CGFloat = 0.96
    @Environment(\.themeSettings) private var theme
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !theme.reduceMotion ? scale : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.6), value: configuration.isPressed)
    }
}
extension ButtonStyle where Self == PressableStyle {
    static var pressable: PressableStyle { PressableStyle() }
    static func pressable(_ scale: CGFloat) -> PressableStyle { PressableStyle(scale: scale) }
}
