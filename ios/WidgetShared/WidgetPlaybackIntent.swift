import AppIntents
import Foundation

struct WidgetPlaybackIntent: AudioPlaybackIntent {
    static var title: LocalizedStringResource = "Control Spitify"
    static var openAppWhenRun = false
    @Parameter(title: "Action") var action: String
    @Parameter(title: "Music") var mediaID: String

    init() { action = "toggle"; mediaID = "" }
    init(_ action: String, mediaID: String = "") { self.action = action; self.mediaID = mediaID }

    @MainActor func perform() async throws -> some IntentResult {
        #if SPITIFY_WIDGET_EXTENSION
        // AudioPlaybackIntent runs in the containing app, which owns the player.
        try WidgetPlaybackError.requireContainingApp()
        #else
        await VoiceLibrary.ready()
        let app = AppModel.shared
        guard app.profile.onboarded else { throw WidgetPlaybackError.setupNeeded }
        switch action {
        case "toggle": if app.player.hasMedia { app.player.toggle() } else { _ = VoiceLibrary.play("allSongs") }
        case "previous": app.player.previous()
        case "next": app.player.next()
        case "play": guard VoiceLibrary.play(mediaID) else { throw WidgetPlaybackError.musicUnavailable }
        default: throw WidgetPlaybackError.musicUnavailable
        }
        WidgetPublisher.publish(app)
        #endif
        return .result()
    }
}

enum WidgetPlaybackError: LocalizedError {
    case appUnavailable, setupNeeded, musicUnavailable
    static func requireContainingApp() throws { throw WidgetPlaybackError.appUnavailable }
    var errorDescription: String? {
        switch self {
        case .appUnavailable: return "Open Spitify once, then try the widget again."
        case .setupNeeded: return "Open Spitify to finish setting up your library."
        case .musicUnavailable: return "This music is no longer in your library."
        }
    }
}
