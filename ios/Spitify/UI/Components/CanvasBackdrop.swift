import AVFoundation
import SwiftUI

/// Full-bleed Canvas behind the player: the album art first, then (if the song has a music video)
/// a silent 10-second loop of it, cropped to fill and graded so text stays readable. Plays only while
/// the song plays and the app is in front; no web view.
struct CanvasBackdrop: View {
    @Environment(AppModel.self) private var app
    @Environment(\.scenePhase) private var phase
    @State private var clip: URL?
    @State private var ready = false

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                if let song = app.player.current { ArtworkView(song, cornerRadius: 0).scaledToFill().frame(width: geometry.size.width, height: geometry.size.height).clipped() }
                if let clip {
                    LoopingVideo(url: clip, playing: app.player.isPlaying && phase == .active) { ready = true }
                        .id(clip).frame(width: geometry.size.width, height: geometry.size.height)
                        .opacity(ready ? 1 : 0).animation(.easeInOut(duration: 0.6), value: ready)
                }
                RadialGradient(colors: [.clear, .black.opacity(0.45)], center: .center, startRadius: 0, endRadius: max(geometry.size.width, geometry.size.height) * 0.75)
                LinearGradient(colors: [.black.opacity(0.35), .clear, .black.opacity(0.8)], startPoint: .top, endPoint: .bottom)
            }.clipped()
        }.ignoresSafeArea().allowsHitTesting(false)
        .task(id: app.player.current?.id) {
            clip = nil; ready = false
            guard let song = app.player.current, !song.isSpoken,
                  let source = await CanvasLookup.find(title: song.title, artist: song.primaryArtist), !Task.isCancelled else { return }
            let local = await CanvasClips.clip(from: source)
            if !Task.isCancelled { clip = local }
        }
    }
}

/// Muted, looping AVPlayer drawn aspect-fill.
private struct LoopingVideo: UIViewRepresentable {
    let url: URL
    var playing: Bool
    var onFirstFrame: () -> Void

    final class PlayerView: UIView {
        override static var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
        let player = AVQueuePlayer()
        var looper: AVPlayerLooper?
        var readyObservation: NSKeyValueObservation?
    }

    func makeUIView(context: Context) -> PlayerView {
        let view = PlayerView()
        view.backgroundColor = .clear
        view.playerLayer.videoGravity = .resizeAspectFill
        view.player.isMuted = true
        view.player.preventsDisplaySleepDuringVideoPlayback = false
        view.player.audiovisualBackgroundPlaybackPolicy = .pauses
        view.playerLayer.player = view.player
        view.looper = AVPlayerLooper(player: view.player, templateItem: AVPlayerItem(url: url))
        view.readyObservation = view.playerLayer.observe(\.isReadyForDisplay, options: [.new]) { layer, _ in
            if layer.isReadyForDisplay { DispatchQueue.main.async { onFirstFrame() } }
        }
        if playing { view.player.play() }
        return view
    }

    func updateUIView(_ view: PlayerView, context: Context) {
        if playing, view.player.rate == 0 { view.player.play() }
        else if !playing, view.player.rate != 0 { view.player.pause() }
    }

    static func dismantleUIView(_ view: PlayerView, coordinator: ()) {
        view.readyObservation = nil
        view.player.pause(); view.looper?.disableLooping(); view.looper = nil
        view.player.removeAllItems(); view.playerLayer.player = nil
    }
}
