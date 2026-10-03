import XCTest
import SwiftUI
import WebKit
@testable import Spitify

final class VideoLifecycleTests: XCTestCase {
    @MainActor func testRealVideoMovesAfterResumeVisibilityAndPausedSongSwap() async throws {
        guard ProcessInfo.processInfo.environment["SPITIFY_VIDEO_LIVE_CHECK"] == "1" else {
            throw XCTSkip("Set SPITIFY_VIDEO_LIVE_CHECK=1 for the real video provider check")
        }
        let fixture = try Fixture()
        defer { fixture.close() }
        let first = liveVideo("W8r-tXRLazs")
        let firstProbe = first.1
        var firstSurface = fixture.surface("W8r-tXRLazs", playing: true)
        var firstCoordinator = firstSurface.makeCoordinator()
        firstCoordinator.attach(first.0); fixture.show(first.0)
        defer {
            firstCoordinator.detach(first.0)
            first.0.configuration.userContentController.removeScriptMessageHandler(forName: "nativeVideoProbe")
            first.0.stopLoading()
        }
        try await liveAdvance(firstProbe, timeout: 60)

        firstSurface.playing = false; firstCoordinator.parent = firstSurface; firstCoordinator.sync()
        try await liveHold(firstProbe)
        firstSurface.playing = true; firstCoordinator.parent = firstSurface; firstCoordinator.sync()
        try await liveAdvance(firstProbe)

        firstSurface.visible = false; firstCoordinator.parent = firstSurface; firstCoordinator.sync()
        try await liveHold(firstProbe)
        firstSurface.visible = true; firstCoordinator.parent = firstSurface; firstCoordinator.sync()
        try await liveAdvance(firstProbe)

        firstCoordinator.detach(first.0); first.0.removeFromSuperview()
        // WebKit may release decoded frames while a cached view is out of its window.
        // It must remain marked hidden and decode again when it returns.
        try await waitFor(first.0, "spitifyVideoState().visible === false")
        let returned = VideoWebCache.take("W8r-tXRLazs")
        XCTAssertTrue(returned === first.0)
        firstCoordinator = firstSurface.makeCoordinator(); firstCoordinator.attach(returned); fixture.show(returned)
        try await liveAdvance(firstProbe)

        try await waitFor(first.0, "typeof spitifyVideoState === 'function' && spitifyVideoState().transition !== null", timeout: 35)
        print("REAL_VIDEO_PAUSE_DURING_CUT: \(try await first.0.evaluateJavaScript("JSON.stringify(spitifyVideoState())"))")
        firstSurface.playing = false; firstCoordinator.parent = firstSurface; firstCoordinator.sync()
        try await liveHold(firstProbe)
        firstSurface.playing = true; firstCoordinator.parent = firstSurface; firstCoordinator.sync()
        try await liveAdvance(firstProbe)

        firstSurface.playing = false; firstCoordinator.parent = firstSurface; firstCoordinator.sync()
        try await liveHold(firstProbe)
        let second = liveVideo("TdrL3QxjyVw")
        let secondSurface = fixture.surface("TdrL3QxjyVw", playing: false)
        let secondCoordinator = secondSurface.makeCoordinator()
        firstCoordinator.detach(first.0); first.0.removeFromSuperview()
        secondCoordinator.attach(second.0); fixture.show(second.0)
        defer {
            secondCoordinator.detach(second.0)
            second.0.configuration.userContentController.removeScriptMessageHandler(forName: "nativeVideoProbe")
            second.0.stopLoading()
        }
        try await liveHold(second.1, timeout: 60)
        secondCoordinator.parent.playing = true; secondCoordinator.sync()
        try await liveAdvance(second.1)
        print("REAL_VIDEO_LIFECYCLE_PASS: pause/resume, visibility return, cache return, and paused song swap delivered moving, muted frames")
    }

    @MainActor func testDecodedVideoResumesAfterPauseVisibilityAndCacheReturn() async throws {
        let fixture = try Fixture()
        defer { fixture.close() }
        let view = try fixture.video("lifecycle-local-video")
        var surface = fixture.surface("lifecycle-local-video", playing: true)
        var coordinator = surface.makeCoordinator()
        coordinator.attach(view)
        fixture.show(view)
        try await advancing(view)

        surface.playing = false; coordinator.parent = surface; coordinator.sync()
        try await held(view)

        surface.playing = true; coordinator.parent = surface; coordinator.sync()
        try await advancing(view)

        surface.visible = false; coordinator.parent = surface; coordinator.sync()
        try await held(view)
        let desiredPlaying = try await flag(view, "desiredPlaying")
        XCTAssertTrue(desiredPlaying, "Hiding the screen must preserve the song's playing state")

        surface.visible = true; coordinator.parent = surface; coordinator.sync()
        try await advancing(view)

        coordinator.detach(view); view.removeFromSuperview()
        try await held(view)
        let returned = VideoWebCache.take("lifecycle-local-video")
        XCTAssertTrue(returned === view)
        coordinator = surface.makeCoordinator(); coordinator.attach(returned)
        fixture.show(returned)
        try await advancing(returned)
        coordinator.detach(returned)
    }

    @MainActor func testChangingPausedSongShowsItsOwnHeldFrame() async throws {
        let fixture = try Fixture()
        defer { fixture.close() }
        let first = try fixture.video("paused-first-local-video")
        let firstCoordinator = fixture.surface("paused-first-local-video", playing: false).makeCoordinator()
        firstCoordinator.attach(first); fixture.show(first)
        try await held(first)

        let second = try fixture.video("paused-second-local-video")
        let secondCoordinator = fixture.surface("paused-second-local-video", playing: false).makeCoordinator()
        firstCoordinator.detach(first); first.removeFromSuperview()
        secondCoordinator.attach(second); fixture.show(second)
        try await held(second)
        let desiredPlaying = try await flag(second, "desiredPlaying")
        let visible = try await flag(second, "visible")
        XCTAssertFalse(desiredPlaying)
        XCTAssertTrue(visible)
        let ready = try await second.evaluateJavaScript("video.readyState >= 2 && video.videoWidth > 0") as? Bool
        XCTAssertEqual(ready, true, "A paused replacement still needs a decoded frame")

        secondCoordinator.parent.playing = true; secondCoordinator.sync()
        try await advancing(second)
        secondCoordinator.detach(second)
    }

    @MainActor private func flag(_ view: WKWebView, _ name: String) async throws -> Bool {
        try await view.evaluateJavaScript(name) as? Bool ?? false
    }

    @MainActor private func sample(_ view: WKWebView) async throws -> (time: Double, frames: Int, paused: Bool) {
        let value = try await view.evaluateJavaScript("({time:video.currentTime,frames:frames,paused:video.paused})")
        let data = try XCTUnwrap(value as? [String: Any])
        return (try XCTUnwrap(data["time"] as? Double), try XCTUnwrap(data["frames"] as? Int), try XCTUnwrap(data["paused"] as? Bool))
    }

    @MainActor private func waitFor(_ view: WKWebView, _ script: String, timeout: TimeInterval = 10) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if (try? await view.evaluateJavaScript(script)) as? Bool == true { return }
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTFail("Video did not reach the expected state: \(script)")
        throw NSError(domain: "VideoLifecycleTests", code: 1)
    }

    @MainActor private func advancing(_ view: WKWebView) async throws {
        try await waitFor(view, "typeof video !== 'undefined' && video.readyState >= 2 && !video.paused")
        let before = try await sample(view)
        try await waitFor(view, "frames > \(before.frames + 3)")
        let after = try await sample(view)
        XCTAssertNotEqual(after.time, before.time, "Decoded frames and the video clock must both move after resume")
        XCTAssertGreaterThan(after.frames, before.frames + 3)
        let muted = try await flag(view, "video.muted")
        XCTAssertTrue(muted)
    }

    @MainActor private func held(_ view: WKWebView) async throws {
        try await waitFor(view, "typeof video !== 'undefined' && video.readyState >= 2 && video.paused")
        try await Task.sleep(for: .milliseconds(100))
        let before = try await sample(view)
        try await Task.sleep(for: .milliseconds(250))
        let after = try await sample(view)
        XCTAssertEqual(after.time, before.time, accuracy: 0.06)
        XCTAssertLessThanOrEqual(after.frames, before.frames + 1)
    }

    @MainActor private func liveVideo(_ id: String) -> (WKWebView, FrameProbe) {
        let view = VideoWebCache.take(id), probe = FrameProbe()
        probe.view = view
        view.configuration.userContentController.add(probe, name: "nativeVideoProbe")
        view.configuration.userContentController.addUserScript(WKUserScript(source: FrameProbe.script, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        VideoWebCache.reload(view, id: id)
        return (view, probe)
    }

    @MainActor private func liveAdvance(_ probe: FrameProbe, timeout: TimeInterval = 12) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        let before = probe.latest
        while Date() < deadline {
            if let current = probe.latest, !current.paused, current.width > 0,
               current.frames > (before?.frames ?? 0) + 6,
               abs(current.time - (before?.time ?? 0)) > 0.1 {
                XCTAssertTrue(current.muted)
                print("REAL_VIDEO_ADVANCE: time=\(current.time), frames=\(current.frames)")
                return
            }
            try await Task.sleep(for: .milliseconds(100))
        }
        await probe.diagnose()
        XCTFail("The real video did not resume decoded frames: \(String(describing: probe.latest))")
        throw NSError(domain: "VideoLifecycleTests", code: 2)
    }

    @MainActor private func liveHold(_ probe: FrameProbe, timeout: TimeInterval = 12) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let current = probe.latest, current.paused, current.width > 0 {
                try await Task.sleep(for: .milliseconds(450))
                let after = try XCTUnwrap(probe.latest)
                XCTAssertTrue(after.paused); XCTAssertTrue(after.muted)
                XCTAssertEqual(after.time, current.time, accuracy: 0.08)
                XCTAssertLessThanOrEqual(after.frames, current.frames + 2)
                print("REAL_VIDEO_HOLD: time=\(after.time), frames=\(after.frames)")
                return
            }
            try await Task.sleep(for: .milliseconds(100))
        }
        await probe.diagnose()
        XCTFail("The real video did not hold its decoded frame: \(String(describing: probe.latest))")
        throw NSError(domain: "VideoLifecycleTests", code: 3)
    }

    @MainActor private final class FrameProbe: NSObject, WKScriptMessageHandler {
        struct Sample: CustomStringConvertible {
            var time: Double; var frames: Int; var paused: Bool; var muted: Bool; var width: Int
            var description: String { "time=\(time) frames=\(frames) paused=\(paused) muted=\(muted) width=\(width)" }
        }
        var latest: Sample?
        weak var view: WKWebView?
        var details = ""
        func diagnose() async {
            let state = try? await view?.evaluateJavaScript("typeof spitifyVideoState === 'function' ? JSON.stringify(spitifyVideoState()) : document.readyState")
            print("REAL_VIDEO_DIAGNOSTICS: \(String(describing: state)); child=\(details)")
        }
        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.frameInfo.securityOrigin.host == "www.youtube.com",
                  let data = message.body as? [String: Any], let time = data["time"] as? Double,
                  let frames = data["frames"] as? Int, let paused = data["paused"] as? Bool,
                  let muted = data["muted"] as? Bool, let width = data["width"] as? Int else { return }
            latest = Sample(time: time, frames: frames, paused: paused, muted: muted, width: width)
            details = "ready=\(data["ready"] ?? "?") network=\(data["network"] ?? "?") visible=\(data["visible"] ?? "?") paused=\(data["requestedPaused"] ?? "?") error=\(data["error"] ?? "none")"
        }
        static let script = """
            (()=>{
              if(location.hostname!=='www.youtube.com'||!location.pathname.startsWith('/embed/'))return;
              let current,frames=0;
              function count(video){if(current!==video)return;frames++;video.requestVideoFrameCallback(()=>count(video));}
              setInterval(()=>{
                if(document.querySelector('.ad-showing'))return;
                const video=document.querySelector('video.html5-main-video');if(!video)return;
                if(current!==video){current=video;video.requestVideoFrameCallback(()=>count(video));}
                window.webkit.messageHandlers.nativeVideoProbe.postMessage({time:video.currentTime,frames:frames,paused:video.paused,muted:video.muted,width:video.videoWidth,ready:video.readyState,network:video.networkState,visible:document.documentElement.getAttribute('data-spitify-visible'),requestedPaused:document.documentElement.getAttribute('data-spitify-paused'),error:document.querySelector('.ytp-error-content-wrap-reason')?.textContent?.slice(0,250)||video.error?.message||''});
              },150);
            })();
            """
    }

    @MainActor private final class Fixture {
        let directory: URL
        let window: UIWindow
        private let oldWindow: UIWindow?
        init() throws {
            directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let clip = try XCTUnwrap(Bundle(for: VideoLifecycleTests.self).url(forResource: "motion", withExtension: "mp4"))
            try FileManager.default.copyItem(at: clip, to: directory.appendingPathComponent("motion.mp4"))
            let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
            oldWindow = scene.windows.first { $0.isKeyWindow }
            window = UIWindow(windowScene: scene)
            window.frame = CGRect(x: 0, y: 0, width: 320, height: 640)
            window.rootViewController = UIViewController()
            window.makeKeyAndVisible()
        }
        func surface(_ id: String, playing: Bool) -> SilentVideoSurface {
            SilentVideoSurface(videoID: id, position: 42, playing: playing, speed: 1, reduceMotion: false, visible: true) { _ in }
        }
        func show(_ view: WKWebView) {
            view.frame = window.bounds
            window.rootViewController?.view.addSubview(view)
        }
        func video(_ id: String) throws -> WKWebView {
            let page = directory.appendingPathComponent(id + ".html")
            try Self.html.write(to: page, atomically: true, encoding: .utf8)
            let view = VideoWebCache.take(id)
            view.loadFileURL(page, allowingReadAccessTo: directory)
            return view
        }
        func close() {
            window.rootViewController?.view.subviews.compactMap { $0 as? WKWebView }.forEach { view in
                view.evaluateJavaScript("if(window.spitifySetVisible)spitifySetVisible(false);document.querySelectorAll('video').forEach(video=>video.pause())", completionHandler: nil)
                view.stopLoading(); view.removeFromSuperview()
            }
            window.isHidden = true; oldWindow?.makeKeyAndVisible()
            try? FileManager.default.removeItem(at: directory)
        }
        private static let html = """
            <!doctype html><html><body style="margin:0;background:black">
            <video id="video" src="motion.mp4" muted playsinline preload="auto" loop style="width:100%;height:100%;object-fit:cover"></video>
            <script>
            let desiredPlaying=false, visible=false, frames=0;
            const video=document.querySelector('video'); video.muted=true; video.volume=0;
            function countFrame(){frames++;if(!desiredPlaying||!visible)video.pause();video.requestVideoFrameCallback(countFrame)}
            video.requestVideoFrameCallback(countFrame);
            function apply(){video.muted=true;if(visible&&(desiredPlaying||frames===0))video.play().catch(()=>{});else video.pause()}
            function spitifySync(_position,playing){desiredPlaying=playing;apply()}
            function spitifySetVisible(value){visible=value;apply()}
            function spitifyBeginDisplay(){apply()}
            </script></body></html>
            """
    }
}
