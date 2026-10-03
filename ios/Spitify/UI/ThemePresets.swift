import SwiftUI

struct ThemePreset: Identifiable {
    let name: String
    let group: String
    let symbol: String
    let background: UInt32
    let accent: UInt32
    let light: Bool
    var id: String { name }
    func applying(to current: ThemeSettings) -> ThemeSettings {
        var t = current
        t.artThemeID = nil
        t.mode = light ? .light : .dark; t.backdrop = background; t.accentSource = .preset; t.accent = accent
        t.font = group == "Kids" ? .rounded : .figtree; t.artShape = group == "Kids" ? .soft : .rounded
        return t
    }
    func matches(_ t: ThemeSettings) -> Bool {
        t.artThemeID == nil && t.backdrop == background && t.accent == accent && t.accentSource == .preset && t.mode == (light ? .light : .dark)
    }
}
let themePresets: [ThemePreset] = [
    .init(name: "Classic", group: "Everyday", symbol: "●", background: 0x09090B, accent: 0x1ED760, light: false),
    .init(name: "Midnight", group: "Everyday", symbol: "☾", background: 0x071326, accent: 0x8FB8FF, light: false),
    .init(name: "Aurora", group: "Everyday", symbol: "✦", background: 0x171027, accent: 0xC5A0FF, light: false),
    .init(name: "Rose", group: "Everyday", symbol: "❀", background: 0x28131E, accent: 0xFF9ABC, light: false),
    .init(name: "Ocean", group: "Everyday", symbol: "≈", background: 0x06232C, accent: 0x62DDD1, light: false),
    .init(name: "Paper", group: "Everyday", symbol: "◒", background: 0xF5F0E8, accent: 0x316344, light: true),
    .init(name: "Space crew", group: "Kids", symbol: "🚀", background: 0x101738, accent: 0x97BDFF, light: false),
    .init(name: "Dino park", group: "Kids", symbol: "🦕", background: 0x112C23, accent: 0xA8E66C, light: false),
    .init(name: "Race day", group: "Kids", symbol: "🏁", background: 0x202126, accent: 0xFFCA63, light: false),
    .init(name: "Candy cloud", group: "Kids", symbol: "🍬", background: 0xFFF0F5, accent: 0x9A2861, light: true),
    .init(name: "Magic garden", group: "Kids", symbol: "🦋", background: 0xEEE8FF, accent: 0x6245A5, light: true),
    .init(name: "Rainbow pop", group: "Kids", symbol: "🌈", background: 0xE9F7F5, accent: 0x126E70, light: true),
]
