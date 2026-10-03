import Intents
import MediaPlayer
import UIKit

/// Handles Siri's built-in “play … in Spitify” request when the app has a Siri-enabled signature.
final class SiriMediaHandler: NSObject, INPlayMediaIntentHandling {
    func resolveMediaItems(for intent: INPlayMediaIntent, with completion: @escaping ([INPlayMediaMediaItemResolutionResult]) -> Void) {
        Task { @MainActor in
            guard AppModel.shared.profile.onboarded else { completion([.unsupported()]); return }
            await VoiceLibrary.ready()
            let matches = await Self.matches(intent)
            if matches.count == 1 { completion([.success(with: Self.item(matches[0]))]) }
            else if !matches.isEmpty { completion([.disambiguation(with: matches.prefix(6).map(Self.item))]) }
            else if intent.resumePlayback == true || Self.query(intent).isEmpty { completion([.notRequired()]) }
            else { completion([.unsupported()]) }
        }
    }

    func handle(intent: INPlayMediaIntent, completion: @escaping (INPlayMediaIntentResponse) -> Void) {
        Task { @MainActor in
            let app = AppModel.shared
            guard app.profile.onboarded else { completion(INPlayMediaIntentResponse(code: .failureRequiringAppLaunch, userActivity: nil)); return }
            await VoiceLibrary.ready()
            let matches = await Self.matches(intent)
            var success = false
            if let media = matches.first { success = await VoiceLibrary.playMedia(media.id) }
            else if intent.resumePlayback == true || Self.query(intent).isEmpty {
                if app.player.hasMedia { app.player.resume(); success = true }
                else { success = VoiceLibrary.play("allSongs") }
            }
            if success {
                if let shuffled = intent.playShuffled, app.player.shuffle != shuffled { app.player.toggleShuffle() }
                switch intent.playbackRepeatMode {
                case .all: app.player.setRepeat(.all)
                case .one: app.player.setRepeat(.one)
                case .none: app.player.setRepeat(.off)
                default: break
                }
                if let speed = intent.playbackSpeed { app.player.setSpeed(Float(speed)) }
            }
            let response = INPlayMediaIntentResponse(code: success ? .success : .failureNoUnplayedContent, userActivity: nil)
            if success { response.nowPlayingInfo = MPNowPlayingInfoCenter.default().nowPlayingInfo }
            completion(response)
        }
    }

    static func query(_ intent: INPlayMediaIntent) -> String {
        let search = intent.mediaSearch
        return [search?.mediaName, search?.artistName, search?.albumName].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
    }

    @MainActor static func matches(_ intent: INPlayMediaIntent) async -> [SpitifyMedia] {
        let items = VoiceLibrary.items()
        let supplied = (intent.mediaItems ?? []) + (intent.mediaContainer.map { [$0] } ?? [])
        let ids = Set(supplied.compactMap(\.identifier))
        let resolved = items.filter { ids.contains($0.id) }
        if !resolved.isEmpty { return resolved }
        let query = query(intent)
        guard !query.isEmpty else { return [] }
        let type = intent.mediaSearch?.mediaType ?? .unknown
        let matches = VoiceLibrary.match(query, in: items).filter { type == .unknown || Self.type($0.id) == type }
        if !matches.isEmpty { return matches }
        if type == .artist { return await VoiceLibrary.search(query).filter { $0.id.hasPrefix("artist:") || $0.id.hasPrefix("onlineArtist:") } }
        guard type == .song || type == .unknown, let tracks = try? await MonochromeClient().search(query) else { return [] }
        return tracks.filter { track in
            guard track.playable else { return false }
            if let title = intent.mediaSearch?.mediaName, !title.isEmpty,
               foldForSearch(title) != foldForSearch(track.title) { return false }
            if let artist = intent.mediaSearch?.artistName, !artist.isEmpty,
               !foldForSearch(track.artist).contains(foldForSearch(artist)) { return false }
            return true
        }.prefix(6).map { track in
            let song = MusicStreams.shared.register(track)
            return SpitifyMedia(id: "song:" + song.id, title: song.title, subtitle: "Song by " + song.artist)
        }
    }

    static func type(_ id: String) -> INMediaItemType {
        switch id.split(separator: ":", maxSplits: 1).first {
        case "song": return .song
        case "album": return .album
        case "artist", "onlineArtist": return .artist
        case "playlist", "allSongs", "liked": return .playlist
        case "episode": return .podcastEpisode
        case "show": return .podcastShow
        case "book": return .audioBook
        default: return .unknown
        }
    }
    static func item(_ media: SpitifyMedia) -> INMediaItem {
        INMediaItem(identifier: media.id, title: media.title, type: type(media.id), artwork: nil)
    }
}

extension MusicBackgroundAppDelegate {
    func application(_ application: UIApplication, handlerFor intent: INIntent) -> Any? {
        intent is INPlayMediaIntent ? SiriMediaHandler() : nil
    }
}
