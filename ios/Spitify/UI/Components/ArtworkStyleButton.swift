import SwiftUI

extension PlayerStyle {
    var next: PlayerStyle {
        switch self { case .artwork: return .vinyl; case .vinyl: return .minimal; case .minimal: return .artwork }
    }
    var symbol: String {
        switch self { case .artwork: return "photo"; case .vinyl: return "opticaldisc"; case .minimal: return "square" }
    }
}

/// Keep changing the look separate from turning the record to seek through a song.
struct ArtworkStyleButton: View {
    @Environment(AppModel.self) private var app
    var body: some View {
        let style = app.theme.playerStyle
        let hint = "Switch to " + style.next.rawValue.lowercased()
        Button { Haptics.tap(); app.theme.playerStyle = style.next } label: {
            Image(systemName: style.symbol).font(.system(size: 21)).frame(width: 44, height: 44)
        }
        .accessibilityLabel("Artwork style").accessibilityValue(style.rawValue).accessibilityHint(hint)
        .help("Artwork style: " + style.rawValue + ". " + hint)
        .accessibilityIdentifier("artworkStyle")
    }
}
