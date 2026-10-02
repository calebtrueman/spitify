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
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.allowsAirPlayForMediaPlayback = false
        configuration.mediaTypesRequiringUserActionForPlayback = []
        configuration.userContentController.add(context.coordinator, name: "videoState")
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.isOpaque = false; view.backgroundColor = .black; view.scrollView.isScrollEnabled = false
        view.navigationDelegate = context.coordinator
        if let url = Bundle.main.url(forResource: "music-video", withExtension: "html"),
           let html = try? String(contentsOf: url, encoding: .utf8),
           videoID.range(of: "^[A-Za-z0-9_-]{11}$", options: .regularExpression) != nil {
            let origin = URL(string: "https://" + (Bundle.main.bundleIdentifier ?? "com.calebtrueman.spitify").lowercased())!
            view.loadHTMLString(html.replacingOccurrences(of: "__VIDEO_ID__", with: videoID), baseURL: origin)
        } else { DispatchQueue.main.async { onState("ERROR") } }
        return view
    }
    func updateUIView(_ view: WKWebView, context: Context) {
        context.coordinator.parent = self
        let seconds = position.isFinite ? max(0, position) : 0
        view.evaluateJavaScript("if(window.spitifySync) spitifySync(\(seconds),\(playing ? "true" : "false"),\(speed));", completionHandler: nil)
    }
    static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) {
        view.evaluateJavaScript("if(window.spitifyStop) spitifyStop();", completionHandler: nil)
        view.stopLoading(); view.loadHTMLString("", baseURL: nil)
        view.configuration.userContentController.removeScriptMessageHandler(forName: "videoState")
        view.navigationDelegate = nil
    }
}

struct MusicVideoBackdrop: View {
    @Environment(AppModel.self) private var app
    @Environment(\.scenePhase) private var phase
    @State private var video: MusicVideo?
    @State private var loading = true
    @State private var message: String?
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                if let song = app.player.current { ArtworkView(song, cornerRadius: 0).scaledToFill().frame(width: geometry.size.width, height: geometry.size.height).clipped() }
                if let video, message == nil, phase == .active {
                    SilentVideoSurface(videoID: video.id, position: app.player.position, playing: app.player.isPlaying, speed: app.player.speed) { state in
                        loading = false
                        if state == "ERROR" { message = "This video cannot play here. Your song is still playing." }
                    }.id(video.id).frame(width: geometry.size.width, height: geometry.size.height)
                }
                LinearGradient(stops: [.init(color: .black.opacity(0.45), location: 0), .init(color: .clear, location: 0.25), .init(color: .black.opacity(0.2), location: 0.5), .init(color: .black.opacity(0.85), location: 1)], startPoint: .top, endPoint: .bottom)
                VStack(spacing: 12) {
                    if loading { ProgressView("Finding music video…").tint(.white) }
                    if let message { Text(message).multilineTextAlignment(.center) }
                }.font(.callout).foregroundStyle(.white).padding(24).frame(width: geometry.size.width).position(x: geometry.size.width / 2, y: geometry.size.height * 0.34)
            }.clipped()
        }.ignoresSafeArea().allowsHitTesting(false)
        .task(id: app.player.current?.id) {
            guard let song = app.player.current, !song.isSpoken else { return }
            video = nil; message = nil; loading = true
            do {
                let found = try await MusicVideoLookup.find(title: song.title, artist: song.artist, durationMs: song.durationMs > 0 ? song.durationMs : Int64(app.player.duration * 1000))
                try Task.checkCancellation(); video = found
                if found == nil { loading = false; message = "No matching music video is available for this song." }
            } catch { if !Task.isCancelled { loading = false; message = "The video couldn't load. Your song is still playing." } }
        }
        .task(id: video?.id) {
            guard video != nil else { return }
            do { try await Task.sleep(for: .seconds(25)); if loading { loading = false; message = "The video couldn't load. Your song is still playing." } } catch {}
        }
    }
}
