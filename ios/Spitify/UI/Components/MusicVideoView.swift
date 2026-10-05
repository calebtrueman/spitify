import SwiftUI
import WebKit

@MainActor @Observable final class VideoQuality {
    static let shared = VideoQuality()
    var resolution = "No video played yet"
}

struct SilentVideoSurface: UIViewRepresentable {
    let videoID: String
    var position: Double
    var playing: Bool
    var speed: Float
    var reduceMotion: Bool
    var visible: Bool
    var onState: (String) -> Void

    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
        var parent: SilentVideoSurface
        private weak var view: WKWebView?
        private var wasVisible = false
        private var recovering = false
        private var bridgeReady = false
        init(_ parent: SilentVideoSurface) { self.parent = parent }

        func attach(_ view: WKWebView) {
            self.view = view
            view.configuration.userContentController.add(self, name: "videoState")
            view.navigationDelegate = self
            (view as? VideoWebView)?.onWindowChanged = { [weak self] in self?.sync() }
            sync()
        }
        func sync(reveal: Bool = false) {
            guard let view else { return }
            let visible = parent.visible && view.window != nil
            let begin = visible && (reveal || !wasVisible)
            wasVisible = visible
            let seconds = parent.position.isFinite ? max(0, parent.position) : 0
            let speed = parent.speed.isFinite && parent.speed > 0 ? parent.speed : 1
            // Send the current song state before asking a reused surface to show its frame.
            let script = """
                if(window.spitifySetVisible) spitifySetVisible(\(visible));
                if(window.spitifySync) spitifySync(\(seconds),\(parent.playing),\(speed),\(parent.reduceMotion));
                \(begin ? "if(window.spitifyBeginDisplay) spitifyBeginDisplay();" : "")
                """
            view.evaluateJavaScript(script) { [weak self, weak view] _, error in
                guard let self, let view, self.view === view,
                      let error = error as? WKError, error.code == .webContentProcessTerminated else { return }
                self.recover(view)
            }
        }
        func detach(_ view: WKWebView) {
            view.evaluateJavaScript("if(window.spitifySetVisible) spitifySetVisible(false);", completionHandler: nil)
            (view as? VideoWebView)?.onWindowChanged = nil
            view.configuration.userContentController.removeScriptMessageHandler(forName: "videoState")
            view.navigationDelegate = nil
            self.view = nil; wasVisible = false
            VideoWebCache.store(view, id: parent.videoID)
        }
        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.webView === view, message.frameInfo.isMainFrame,
                  let value = message.body as? String else { return }
            if value.hasPrefix("QUALITY:") {
                let size = String(value.dropFirst(8))
                if size.range(of: "^[0-9]{1,5}x[0-9]{1,5}$", options: .regularExpression) != nil { VideoQuality.shared.resolution = size.replacingOccurrences(of: "x", with: " × ") }
                return
            }
            guard ["READY", "WAITING", "ERROR"].contains(value) else { return }
            // An embed can become ready before WebKit finishes the whole navigation.
            // The first state message confirms that early, otherwise missed commands can run.
            if !bridgeReady, value != "ERROR" { bridgeReady = true; sync(reveal: true) }
            parent.onState(value)
        }
        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            bridgeReady = false
        }
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            recovering = false
            sync(reveal: true)
        }
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            guard (error as NSError).code != NSURLErrorCancelled else { return }
            parent.onState("ERROR")
        }
        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { recover(webView) }
        private func recover(_ webView: WKWebView) {
            guard view === webView, !recovering else { return }
            recovering = true; wasVisible = false; bridgeReady = false
            parent.onState("WAITING")
            VideoWebCache.reload(webView, id: parent.videoID)
        }
        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            if navigationAction.navigationType == .linkActivated { decisionHandler(.cancel) }
            else { decisionHandler(.allow) }
        }
    }
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> WKWebView {
        let view = VideoWebCache.take(videoID)
        context.coordinator.attach(view)
        return view
    }
    func updateUIView(_ view: WKWebView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.sync()
    }
    static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) {
        coordinator.detach(view)
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
                if let video, message == nil {
                    SilentVideoSurface(videoID: video.id, position: app.player.position, playing: app.player.isPlaying, speed: app.player.speed, reduceMotion: theme.reduceMotion || systemReduceMotion, visible: phase == .active) { state in
                        guard self.video?.id == video.id else { return }
                        loading = state != "READY"
                        if state == "ERROR" { tryNextVideo() }
                    }.id(video.id).frame(width: geometry.size.width, height: geometry.size.height).opacity(loading ? 0 : 1)
                }
                LinearGradient(colors: [.black.opacity(0.3), .clear, .black.opacity(0.8)], startPoint: .top, endPoint: .bottom)
            }.clipped()
        }.ignoresSafeArea().allowsHitTesting(false)
        .task(id: app.player.current?.id) {
            video = nil; alternatives = []; message = nil; loading = true
            guard let song = app.player.current, !song.isSpoken else { loading = false; return }
            do {
                let found = try await MusicVideoLookup.findAll(title: song.title, artist: song.artist, durationMs: song.durationMs > 0 ? song.durationMs : Int64(app.player.duration * 1000))
                try Task.checkCancellation(); alternatives = Array(found.dropFirst()); video = found.first
                if found.isEmpty { loading = false; message = "No matching music video is available for this song." }
            } catch { if !Task.isCancelled { loading = false; message = "The video couldn't load. Your song is still playing." } }
        }
        .task(id: phase == .active ? video?.id : nil) {
            guard video != nil, phase == .active else { return }
            do { try await Task.sleep(for: .seconds(25)); if loading { tryNextVideo() } } catch {}
        }
    }
    private func tryNextVideo() {
        if !alternatives.isEmpty { video = alternatives.removeFirst(); loading = true; message = nil }
        else { loading = false; message = "This video cannot play here. Your song is still playing." }
    }
}

final class VideoWebView: WKWebView {
    var onWindowChanged: (() -> Void)?
    override func didMoveToWindow() {
        super.didMoveToWindow()
        onWindowChanged?()
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
        view.evaluateJavaScript("if(window.spitifySetVisible) spitifySetVisible(false);", completionHandler: nil)
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
        let view = VideoWebView(frame: .zero, configuration: configuration)
        view.isUserInteractionEnabled = false
        view.isOpaque = false; view.backgroundColor = .black; view.scrollView.isScrollEnabled = false
        view.scrollView.contentInsetAdjustmentBehavior = .never
        reload(view, id: videoID)
        return view
    }
    static func reload(_ view: WKWebView, id videoID: String) {
        if let url = Bundle.main.url(forResource: "music-video", withExtension: "html"),
           let html = try? String(contentsOf: url, encoding: .utf8),
           videoID.range(of: "^[A-Za-z0-9_-]{11}$", options: .regularExpression) != nil {
            let origin = URL(string: "https://" + (Bundle.main.bundleIdentifier ?? "com.calebtrueman.spitify").lowercased())!
            view.loadHTMLString(html.replacingOccurrences(of: "__VIDEO_ID__", with: videoID), baseURL: origin)
        }
    }
}
