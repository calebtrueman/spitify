import SwiftUI
import WebKit

struct SilentVideoSurface: UIViewRepresentable {
    let videoID: String
    var position: Double
    var playing: Bool
    var speed: Float
    var reduceMotion: Bool
    var onState: (String) -> Void

    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
        var parent: SilentVideoSurface
        init(_ parent: SilentVideoSurface) { self.parent = parent }
        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.frameInfo.isMainFrame, let value = message.body as? String, ["READY", "WAITING", "ERROR"].contains(value) else { return }
            parent.onState(value)
        }
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            let seconds = parent.position.isFinite ? max(0, parent.position) : 0
            webView.evaluateJavaScript("if(window.spitifySync) spitifySync(\(seconds),\(parent.playing ? "true" : "false"),\(parent.speed),\(parent.reduceMotion ? "true" : "false"));", completionHandler: nil)
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
        view.evaluateJavaScript("if(window.spitifyBeginDisplay) spitifyBeginDisplay();", completionHandler: nil)
        return view
    }
    func updateUIView(_ view: WKWebView, context: Context) {
        context.coordinator.parent = self
        let seconds = position.isFinite ? max(0, position) : 0
        view.evaluateJavaScript("if(window.spitifySync) spitifySync(\(seconds),\(playing ? "true" : "false"),\(speed),\(reduceMotion ? "true" : "false"));", completionHandler: nil)
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
    @Environment(\.themeSettings) private var theme
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @State private var video: MusicVideo?
    @State private var alternatives: [MusicVideo] = []
    @State private var loading = true
    @State private var message: String?
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                if let song = app.player.current { ArtworkView(song, cornerRadius: 0).scaledToFill().frame(width: geometry.size.width, height: geometry.size.height).clipped() }
                if let video, message == nil, phase == .active {
                    SilentVideoSurface(videoID: video.id, position: app.player.position, playing: app.player.isPlaying, speed: app.player.speed, reduceMotion: theme.reduceMotion || systemReduceMotion) { state in
                        guard self.video?.id == video.id else { return }
                        loading = state != "READY"
                        if state == "ERROR" { tryNextVideo() }
                    }.id(video.id).frame(width: geometry.size.width, height: geometry.size.height).opacity(loading ? 0 : 1)
                }
                LinearGradient(colors: [.black.opacity(0.3), .clear, .black.opacity(0.8)], startPoint: .top, endPoint: .bottom)
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
    private static var requestedID: String?
    private static var activeID: String?
    private static weak var activeView: WKWebView?
    static func prepare(_ id: String) {
        select(id)
        guard cached == nil, activeID != id || activeView == nil else { return }
        cached = (id, make(id))
    }
    static func take(_ id: String) -> WKWebView {
        select(id)
        let view = cached?.1 ?? make(id)
        cached = nil
        activeID = id; activeView = view
        return view
    }
    static func store(_ view: WKWebView, id: String) {
        if activeView === view { activeID = nil; activeView = nil }
        // The previous surface may leave after the next song's clip is ready.
        guard requestedID == id, activeID != id || activeView == nil else { discard(view); return }
        if let ready = cached?.1 {
            if ready !== view { discard(view) }
            return
        }
        cached = (id, view)
    }
    private static func select(_ id: String) {
        requestedID = id
        if let entry = cached, entry.0 != id { cached = nil; discard(entry.1) }
    }
    private static func discard(_ view: WKWebView) {
        view.stopLoading(); view.loadHTMLString("", baseURL: nil)
    }
    private static func make(_ videoID: String) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.allowsAirPlayForMediaPlayback = false
        configuration.mediaTypesRequiringUserActionForPlayback = []
        if let url = Bundle.main.url(forResource: "video-controls", withExtension: "js"), let script = try? String(contentsOf: url, encoding: .utf8) {
            configuration.userContentController.addUserScript(WKUserScript(source: script, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        }
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.isUserInteractionEnabled = false
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
