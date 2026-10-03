import AVFoundation
import MediaToolbox

/// Equaliser settings shared by the UI and the engine.
struct EQSettings: Codable, Equatable {
    var enabled = false
    var preset = "Flat"
    var gains: [Float] = Array(repeating: 0, count: 10)
    var bass: Float = 0        // 0...1
    var loudness: Float = 0    // dB 0...10
    var limiter = true
}

let eqFrequencies: [Float] = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]

let eqPresets: [(String, [Float])] = [
    ("Flat", [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]),
    ("Bass boost", [6, 5, 4, 2, 0.5, 0, 0, 0, 0, 0]),
    ("Bass reducer", [-6, -5, -4, -2, -0.5, 0, 0, 0, 0, 0]),
    ("Treble boost", [0, 0, 0, 0, 0, 1, 2.5, 4, 5, 6]),
    ("Vocal", [-2, -2, -1, 1, 3, 4, 3.5, 2, 0, -1]),
    ("Rock", [4.5, 3.5, 2, 0, -1, -0.5, 1.5, 3, 4, 4.5]),
    ("Pop", [-1, 0.5, 2, 3.5, 4, 3, 1, 0, -0.5, -1]),
    ("Jazz", [3, 2, 1, 2, -1.5, -1.5, 0, 1.5, 3, 3.5]),
    ("Classical", [4, 3, 2.5, 1.5, -1, -1, 0, 2, 3, 3.5]),
    ("Electronic", [5, 4.5, 1.5, 0, -2, 1.5, 0.5, 1.5, 4.5, 5]),
    ("Hip-hop", [5, 4.5, 2, 3, -1, -1, 1.5, -0.5, 2, 3]),
    ("Acoustic", [4, 4, 3, 1, 2, 2, 3, 3.5, 3, 2]),
    ("Late night", [3, 2, 1, 0, 0, 0, 0, -1, -2, -3]),
    ("Small speakers", [-6, -4, 2, 3, 2, 1, 1, 2, 1, 0]),
    ("Podcast / speech", [-6, -4, -1, 1, 3, 4, 4, 2, 0, -2]),
]

/// Local-file playback: two player nodes (so tracks can crossfade) → time-pitch (speed) → 10-band EQ → limiter.
final class EngineBackend {
    let engine = AVAudioEngine()
    private let nodes = [AVAudioPlayerNode(), AVAudioPlayerNode()]
    private let mixer = AVAudioMixerNode()
    private let pitch = AVAudioUnitTimePitch()
    private let eq = AVAudioUnitEQ(numberOfBands: 10)
    private let limiter = AVAudioUnitEffect(audioComponentDescription: AudioComponentDescription(
        componentType: kAudioUnitType_Effect, componentSubType: kAudioUnitSubType_PeakLimiter,
        componentManufacturer: kAudioUnitManufacturer_Apple, componentFlags: 0, componentFlagsMask: 0))
    private var files: [AVAudioFile?] = [nil, nil]
    private var startFrame: [AVAudioFramePosition] = [0, 0]
    private var pausedAt: [Double?] = [nil, nil]
    private var generation = [0, 0]
    private var trackGains: [Float] = [1, 1]
    var normalizeVolume = true {
        didSet {
            for i in nodes.indices {
                trackGains[i] = normalizeVolume ? files[i].map(AudioLeveling.fileGain) ?? 1 : 1
                nodes[i].volume = trackGains[i]
            }
        }
    }
    private(set) var active = 0
    private var fadeTimer: Timer?
    /// Called on the main queue when the active node plays to the end of its file.
    var onFinished: (() -> Void)?

    init() {
        for n in nodes { engine.attach(n) }
        [mixer, pitch, eq, limiter].forEach(engine.attach)
        engine.connect(mixer, to: pitch, format: nil)
        engine.connect(pitch, to: eq, format: nil)
        engine.connect(eq, to: limiter, format: nil)
        engine.connect(limiter, to: engine.mainMixerNode, format: nil)
        for (i, f) in eqFrequencies.enumerated() {
            let b = eq.bands[i]
            b.filterType = i == 0 ? .lowShelf : i == eqFrequencies.count - 1 ? .highShelf : .parametric
            b.frequency = f; b.bandwidth = 1.0; b.gain = 0; b.bypass = false
        }
        engine.prepare()
    }

    var duration: Double { files[active].map { Double($0.length) / $0.processingFormat.sampleRate } ?? 0 }
    var isPlaying: Bool { nodes[active].isPlaying }

    var currentTime: Double {
        guard let file = files[active] else { return 0 }
        if let p = pausedAt[active] { return p }
        let sr = file.processingFormat.sampleRate
        guard let nt = nodes[active].lastRenderTime, let pt = nodes[active].playerTime(forNodeTime: nt) else { return Double(startFrame[active]) / sr }
        return min(duration, (Double(startFrame[active]) + Double(pt.sampleTime)) / sr)
    }

    func startEngine() {
        if !engine.isRunning { try? engine.start() }
    }

    func load(_ url: URL, at seconds: Double, play: Bool) throws {
        cancelFade()
        let file = try AVAudioFile(forReading: url)
        stopNode(1 - active)
        stopNode(active)
        connect(active, file: file)
        files[active] = file
        trackGains[active] = normalizeVolume ? AudioLeveling.fileGain(file) : 1
        nodes[active].volume = trackGains[active]
        if play { startEngine(); schedule(active, from: seconds); nodes[active].play() }
        else { pausedAt[active] = seconds; engine.pause() }
    }

    func play() {
        startEngine()
        if let p = pausedAt[active] { pausedAt[active] = nil; schedule(active, from: p) }
        nodes[active].play()
    }

    func pause() {
        let t = currentTime
        cancelFade()
        stopNode(1 - active)
        stopNode(active)
        pausedAt[active] = t
        engine.pause()
    }

    func seek(_ seconds: Double) {
        let playing = isPlaying
        cancelFade(); stopNode(1 - active)
        stopNode(active)
        if playing { schedule(active, from: seconds); nodes[active].play() } else { pausedAt[active] = seconds }
    }

    func stop() { cancelFade(); stopNode(0); stopNode(1); files = [nil, nil]; engine.stop() }

    /// Starts `url` on the idle node and fades between them over `seconds` (equal-power).
    func crossfade(to url: URL, seconds: Double) throws {
        let next = 1 - active, old = active
        let file = try AVAudioFile(forReading: url)
        stopNode(next)
        connect(next, file: file)
        files[next] = file
        trackGains[next] = normalizeVolume ? AudioLeveling.fileGain(file) : 1
        schedule(next, from: 0)
        nodes[next].volume = 0
        nodes[next].play()
        generation[old] += 1 // old node's end is no longer "the track finished"
        active = next
        let start = Date()
        cancelFade()
        fadeTimer = Timer.scheduledTimer(withTimeInterval: 0.03, repeats: true) { [weak self] t in
            guard let self else { t.invalidate(); return }
            let p = min(1, Date().timeIntervalSince(start) / seconds)
            self.nodes[next].volume = Float(sin(p * .pi / 2)) * self.trackGains[next]
            self.nodes[old].volume = Float(cos(p * .pi / 2)) * self.trackGains[old]
            if p >= 1 { t.invalidate(); self.stopNode(old); self.files[old] = nil }
        }
    }

    func setRate(_ rate: Float) { pitch.rate = rate.isFinite ? min(3, max(0.5, rate)) : 1 }
    func recoverAfterRouteChange(at seconds: Double, play: Bool) {
        guard files[active] != nil else { return }
        cancelFade(); stopNode(1 - active); stopNode(active)
        // An output change clears AVAudioPlayerNode's scheduled data even when isPlaying was true.
        if play { startEngine(); schedule(active, from: seconds); nodes[active].play() }
        else { pausedAt[active] = seconds; engine.pause() }
    }
    func setVolume(_ v: Float) { engine.mainMixerNode.outputVolume = v.isFinite ? min(1, max(0, v)) : 0 }

    func apply(_ s: EQSettings) {
        for (i, b) in eq.bands.enumerated() {
            let bass: Float = i < 3 && s.bass.isFinite ? min(1, max(0, s.bass)) * Float(8 - i * 2) : 0
            b.gain = s.enabled ? max(-12, min(12, (s.gains.indices.contains(i) && s.gains[i].isFinite ? s.gains[i] : 0) + bass)) : 0
        }
        eq.globalGain = s.enabled ? -max(0, eq.bands.map(\.gain).max() ?? 0) : 0
        eq.bypass = !s.enabled
        limiter.bypass = false // Crossfades and boosted EQ bands always need a peak ceiling.
    }

    // MARK: private

    private func connect(_ i: Int, file: AVAudioFile) {
        let fmt = file.processingFormat
        if engine.outputConnectionPoints(for: nodes[i], outputBus: 0).isEmpty || nodes[i].outputFormat(forBus: 0) != fmt {
            engine.disconnectNodeOutput(nodes[i])
            engine.connect(nodes[i], to: mixer, format: fmt)
        }
    }

    private func schedule(_ i: Int, from seconds: Double) {
        guard let file = files[i] else { return }
        let sr = file.processingFormat.sampleRate
        let frame = AVAudioFramePosition(max(0, min(seconds, Double(file.length) / sr - 0.05)) * sr)
        startFrame[i] = frame
        pausedAt[i] = nil
        generation[i] += 1
        let g = generation[i]
        let count = AVAudioFrameCount(max(0, file.length - frame))
        guard count > 0 else { return }
        nodes[i].scheduleSegment(file, startingFrame: frame, frameCount: count, at: nil, completionCallbackType: .dataPlayedBack) { [weak self] _ in
            DispatchQueue.main.async {
                guard let self, self.generation[i] == g, self.active == i else { return }
                self.onFinished?()
            }
        }
    }

    private func stopNode(_ i: Int) {
        generation[i] += 1
        nodes[i].stop()
    }

    private func cancelFade() {
        fadeTimer?.invalidate(); fadeTimer = nil
        nodes[active].volume = trackGains[active]
    }
}

/// Streams (podcasts, LibriVox) and Music-library songs, which AVAudioEngine can't open.
@MainActor
final class StreamBackend {
    let player = AVPlayer()
    private let makeLoader: (OnlineTrack) -> MusicResourceLoader
    private let makeLevelingTap: () -> MTAudioProcessingTap?
    var onFinished: (() -> Void)?
    private var endObserver: Any?
    private var resourceLoader: MusicResourceLoader?
    private var statusObserver: NSKeyValueObservation?
    var onError: (() -> Void)?
    private var rate: Float = 1
    private var loadTask: Task<Void, Never>?
    private var retryTask: Task<Void, Never>?
    private var wantsPlay = false
    private var pendingSeek: Double?
    private var seeking = false
    private var seekCooldown: Task<Void, Never>?
    private var loadVersion = UUID()
    var normalizeVolume = true {
        didSet { if let item = player.currentItem { configureLeveling(item, play: wantsPlay) } }
    }

    init(makeLevelingTap: @escaping () -> MTAudioProcessingTap? = { StreamLeveling.makeTap() },
         makeLoader: @escaping (OnlineTrack) -> MusicResourceLoader = { MusicResourceLoader(track: $0) }) {
        self.makeLevelingTap = makeLevelingTap; self.makeLoader = makeLoader
    }

    func load(_ url: URL, at seconds: Double, play: Bool) {
        load(url, at: seconds, play: play, retryFirstFailure: true)
    }

    private func load(_ url: URL, at seconds: Double, play: Bool, retryFirstFailure: Bool) {
        retryTask?.cancel(); retryTask = nil
        seekCooldown?.cancel(); seekCooldown = nil
        loadVersion = UUID(); pendingSeek = seconds > 0 ? seconds : nil; seeking = false
        loadTask?.cancel(); wantsPlay = play
        resourceLoader?.stop(); resourceLoader = nil
        let item: AVPlayerItem
        if url.scheme == "spitify", let track = MusicStreams.shared.tracks[url.lastPathComponent] {
            let loader = makeLoader(track); resourceLoader = loader
            item = AVPlayerItem(asset: loader.asset())
        } else { item = AVPlayerItem(url: url) }
        item.preferredForwardBufferDuration = 10
        statusObserver = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            Task { @MainActor in
                guard let self, self.player.currentItem === item else { return }
                if item.status == .readyToPlay { self.finishPendingSeek(); return }
                guard item.status == .failed else { return }
                self.resourceLoader?.stop(discard: true); self.resourceLoader = nil
                self.loadTask?.cancel(); self.loadTask = nil
                // A fresh connection or decoder can fail once and work immediately on retry.
                // Keep the selected song while giving that first start one more chance.
                guard self.wantsPlay else { return }
                if retryFirstFailure, self.currentTime < seconds + 1 {
                    let version = self.loadVersion
                    let retryPosition = self.pendingSeek ?? max(seconds, self.currentTime)
                    self.retryTask = Task { [weak self, weak item] in
                        try? await Task.sleep(for: .milliseconds(350))
                        guard let self, !Task.isCancelled, self.loadVersion == version,
                              self.player.currentItem === item, self.wantsPlay else { return }
                        self.load(url, at: retryPosition, play: true, retryFirstFailure: false)
                    }
                } else { self.onError?() }
            }
        }
        // At 1× there is no pitch to correct. Spectral processing can mute short
        // Opus passages between seeks; the direct path keeps those passages audible.
        item.audioTimePitchAlgorithm = rate == 1 ? .varispeed : .spectral
        if let o = endObserver { NotificationCenter.default.removeObserver(o) }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self, weak item] _ in
            MainActor.assumeIsolated { guard let self, self.player.currentItem === item else { return }; self.onFinished?() }
        }
        player.replaceCurrentItem(with: item)
        configureLeveling(item, play: play)
    }
    private func configureLeveling(_ item: AVPlayerItem, play: Bool) {
        loadTask?.cancel(); loadTask = nil
        if !normalizeVolume {
            item.audioMix = nil
            if play { self.play() }
            return
        }
        loadTask = Task { [weak self, weak item] in
            guard let item else { return }
            let tracks = try? await item.asset.loadTracks(withMediaType: .audio)
            guard let self, !Task.isCancelled, self.player.currentItem === item else { return }
            let parameters = (tracks ?? []).compactMap { track -> AVMutableAudioMixInputParameters? in
                guard let tap = self.makeLevelingTap() else { return nil }
                let parameters = AVMutableAudioMixInputParameters(track: track)
                parameters.audioTapProcessor = tap
                return parameters
            }
            if !parameters.isEmpty { let mix = AVMutableAudioMix(); mix.inputParameters = parameters; item.audioMix = mix }
            self.loadTask = nil
            if self.wantsPlay, !self.seeking { self.player.playImmediately(atRate: self.rate) }
        }
    }
    func play() { wantsPlay = true; if loadTask == nil, !seeking { player.playImmediately(atRate: rate) } }
    func pause() {
        wantsPlay = false; retryTask?.cancel(); retryTask = nil
        seekCooldown?.cancel(); seekCooldown = nil; player.pause(); finishPendingSeek()
    }
    func stop() {
        wantsPlay = false; loadVersion = UUID(); pendingSeek = nil; seeking = false
        seekCooldown?.cancel(); seekCooldown = nil
        retryTask?.cancel(); retryTask = nil; loadTask?.cancel(); loadTask = nil
        player.pause(); statusObserver = nil; player.replaceCurrentItem(with: nil); resourceLoader?.stop(); resourceLoader = nil
    }
    func seek(_ seconds: Double) {
        guard seconds.isFinite else { return }
        pendingSeek = max(0, seconds)
        finishPendingSeek()
    }
    private func finishPendingSeek() {
        guard !seeking, seekCooldown == nil, let seconds = pendingSeek,
              let item = player.currentItem, item.status == .readyToPlay else { return }
        seeking = true; pendingSeek = nil
        let version = loadVersion
        // Starting another exact seek cancels the decoder's previous request. While a
        // finger moves the record, finish this request, then jump to its latest position.
        let tolerance = CMTime(seconds: 0.04, preferredTimescale: 600)
        player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600), toleranceBefore: tolerance, toleranceAfter: tolerance) { [weak self, weak item] _ in
            Task { @MainActor in
                guard let self, self.loadVersion == version, self.player.currentItem === item else { return }
                self.seeking = false
                if self.wantsPlay {
                    if self.loadTask == nil { self.player.playImmediately(atRate: self.rate) }
                    // Opus needs a short run of decoded audio between jumps. Seeking
                    // every display update can otherwise stay silent for the whole drag.
                    self.seekCooldown = Task { [weak self, weak item] in
                        try? await Task.sleep(for: .milliseconds(120))
                        guard let self, !Task.isCancelled, self.loadVersion == version,
                              self.player.currentItem === item else { return }
                        self.seekCooldown = nil; self.finishPendingSeek()
                    }
                } else { self.finishPendingSeek() }
            }
        }
    }
    func setRate(_ r: Float) {
        rate = r.isFinite ? min(3, max(0.5, r)) : 1
        player.currentItem?.audioTimePitchAlgorithm = rate == 1 ? .varispeed : .spectral
        if player.rate != 0 { player.rate = rate }
    }
    func setVolume(_ v: Float) { player.volume = v.isFinite ? min(1, max(0, v)) : 0 }
    var currentTime: Double { player.currentTime().seconds.isFinite ? player.currentTime().seconds : 0 }
    var duration: Double { let d = player.currentItem?.duration.seconds ?? 0; return d.isFinite ? d : 0 }
    var isPlaying: Bool { player.rate != 0 }
    var failed: Bool { player.currentItem?.status == .failed }
}
