import SwiftUI
import WebKit

struct SilentVideoSurface: UIViewRepresentable {
    let videoID: String
    var position: Double
    var playing: Bool
    var speed: Float
    var onState: (String) -> Void

    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
        var parent: SilentVideoSurface
        init(_ parent: SilentVideoSurface) { self.parent = parent }
        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.frameInfo.isMainFrame, let value = message.body as? String, ["READY", "ERROR"].contains(value) else { return }
            parent.onState(value)
        }
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            let seconds = parent.position.isFinite ? max(0, parent.position) : 0
            webView.evaluateJavaScript("if(window.spitifySync) spitifySync(\(seconds),\(parent.playing ? "true" : "false"),\(parent.speed));", completionHandler: nil)
        }
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { parent.onState("ERROR") }
        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            if navigationAction.navigationType == .linkActivated { decisionHandler(.cancel) }
            else { decisionHandler(.allow) }
        }
    }
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> WKWebView {
        let view = VideoWebCache.take(videoID)
        view.configuration.userContentController.add(context.coordinator, name: "videoState")
        view.navigationDelegate = context.coordinator
        view.evaluateJavaScript("if(typeof ready !== 'undefined' && ready && [1,2].includes(player.getPlayerState())) report('READY');", completionHandler: nil)
        return view
    }
    func updateUIView(_ view: WKWebView, context: Context) {
        context.coordinator.parent = self
        let seconds = position.isFinite ? max(0, position) : 0
        view.evaluateJavaScript("if(window.spitifySync) spitifySync(\(seconds),\(playing ? "true" : "false"),\(speed));", completionHandler: nil)
    }
    static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) {
        view.evaluateJavaScript("if(window.spitifySync) spitifySync(0,false,1);", completionHandler: nil)
        VideoWebCache.store(view, id: coordinator.parent.videoID)
        view.configuration.userContentController.removeScriptMessageHandler(forName: "videoState")
        view.navigationDelegate = nil
    }
}

struct MusicVideoBackdrop: View {
    @Environment(AppModel.self) private var app
    @Environment(\.scenePhase) private var phase
    @State private var video: MusicVideo?
    @State private var alternatives: [MusicVideo] = []
    @State private var loading = true
    @State private var message: String?
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                if let song = app.player.current { ArtworkView(song, cornerRadius: 0).scaledToFill().frame(width: geometry.size.width, height: geometry.size.height).clipped() }
                if let video, message == nil, phase == .active {
                    SilentVideoSurface(videoID: video.id, position: app.player.position, playing: app.player.isPlaying, speed: app.player.speed) { state in
                        guard self.video?.id == video.id else { return }
                        loading = false
                        if state == "ERROR" { tryNextVideo() }
                    }.id(video.id).frame(width: geometry.size.width, height: geometry.size.height).opacity(loading ? 0 : 1)
                }
            }.clipped()
        }.ignoresSafeArea().allowsHitTesting(false)
        .task(id: app.player.current?.id) {
            guard let song = app.player.current, !song.isSpoken else { return }
            video = nil; alternatives = []; message = nil; loading = true
            do {
                let found = try await MusicVideoLookup.findAll(title: song.title, artist: song.artist, durationMs: song.durationMs > 0 ? song.durationMs : Int64(app.player.duration * 1000))
                try Task.checkCancellation(); alternatives = Array(found.dropFirst()); video = found.first
                if found.isEmpty { loading = false; message = "No matching music video is available for this song." }
            } catch { if !Task.isCancelled { loading = false; message = "The video couldn't load. Your song is still playing." } }
        }
        .task(id: video?.id) {
            guard video != nil else { return }
            do { try await Task.sleep(for: .seconds(25)); if loading { tryNextVideo() } } catch {}
        }
    }
    private func tryNextVideo() {
        if !alternatives.isEmpty { video = alternatives.removeFirst(); loading = true; message = nil }
        else { loading = false; message = "This video cannot play here. Your song is still playing." }
    }
}

@MainActor enum VideoWebCache {
    private static var cached: (String, WKWebView)?
    static func prepare(_ id: String) {
        guard cached?.0 != id else { return }
        store(make(id), id: id)
    }
    static func take(_ id: String) -> WKWebView {
        if let entry = cached, entry.0 == id { cached = nil; return entry.1 }
        return make(id)
    }
    static func store(_ view: WKWebView, id: String) {
        if let old = cached?.1, old !== view { old.stopLoading(); old.loadHTMLString("", baseURL: nil) }
        cached = (id, view)
    }
    private static func make(_ videoID: String) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.allowsAirPlayForMediaPlayback = false
        configuration.mediaTypesRequiringUserActionForPlayback = []
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.isOpaque = false; view.backgroundColor = .black; view.scrollView.isScrollEnabled = false
        view.scrollView.contentInsetAdjustmentBehavior = .never
        if let url = Bundle.main.url(forResource: "music-video", withExtension: "html"),
           let html = try? String(contentsOf: url, encoding: .utf8),
           videoID.range(of: "^[A-Za-z0-9_-]{11}$", options: .regularExpression) != nil {
            let origin = URL(string: "https://" + (Bundle.main.bundleIdentifier ?? "com.calebtrueman.spitify").lowercased())!
            view.loadHTMLString(html.replacingOccurrences(of: "__VIDEO_ID__", with: videoID), baseURL: origin)
        }
        return view
    }
}
