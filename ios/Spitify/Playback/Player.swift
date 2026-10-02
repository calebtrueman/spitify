import AVFoundation
import MediaPlayer
import Observation
import UIKit

enum RepeatMode: Int, Codable { case off, all, one }

enum SleepTimer: Equatable { case at(Date), endOfTrack }

/// The single playback controller: queue, both backends, lock screen / Control Center,
/// sleep timer, resume, and listen logging for the recommendation engine.
@MainActor @Observable
final class Player {
    private(set) var queue: [Song] = []
    private(set) var index = -1
    private(set) var isPlaying = false
    private(set) var position: Double = 0
    private(set) var duration: Double = 0
    private(set) var source: String?
    private(set) var shuffle = UserDefaults.standard.bool(forKey: "shuffle")
    private(set) var repeatMode = RepeatMode(rawValue: UserDefaults.standard.integer(forKey: "repeat")) ?? .off
    private(set) var sleep: SleepTimer?
    private(set) var message: String?
    var speed: Float { current?.isSpoken == true ? speedSpoken : speedMusic }
    var crossfade: Double = UserDefaults.standard.double(forKey: "crossfade") { didSet { UserDefaults.standard.set(crossfade, forKey: "crossfade") } }
    var keepAlbumsGapless: Bool = UserDefaults.standard.object(forKey: "gaplessAlbums") as? Bool ?? true { didSet { UserDefaults.standard.set(keepAlbumsGapless, forKey: "gaplessAlbums") } }
    var eq: EQSettings = { (UserDefaults.standard.data(forKey: "eq")).flatMap { try? JSONDecoder().decode(EQSettings.self, from: $0) } ?? EQSettings() }() {
        didSet { engine.apply(eq); UserDefaults.standard.set(try? JSONEncoder().encode(eq), forKey: "eq") }
    }
    private var speedMusic: Float = UserDefaults.standard.object(forKey: "speedMusic") as? Float ?? 1
    private var speedSpoken: Float = UserDefaults.standard.object(forKey: "speedSpoken") as? Float ?? 1

    var current: Song? { queue.indices.contains(index) ? queue[index] : nil }
    var upNext: [(Int, Song)] { index < 0 ? [] : Array(queue.enumerated().dropFirst(index + 1)) }
    var hasMedia: Bool { current != nil }

    private let engine = EngineBackend()
    private let stream = StreamBackend()
    private var usingStream = false
    private var ticker: Timer?
    private var unshuffled: [Song]?
    private var listenedMs: Int64 = 0
    private var listenStarted = Date()
    private var sinceSave = 0.0
    private var fading = false

    weak var library: LibraryStore?
    weak var shows: ShowsStore?

    init() {
        engine.apply(eq)
        engine.onFinished = { [weak self] in self?.trackEnded() }
        stream.onFinished = { [weak self] in self?.trackEnded() }
        configureSession()
        configureRemote()
    }

    // MARK: - Public transport

    func play(_ songs: [Song], from start: Int = 0, shuffle wantShuffle: Bool? = nil, source: String? = nil, at positionMs: Int64 = 0) {
        let usable = songs.filter(\.playable)
        guard !usable.isEmpty else { flash("This format isn't supported on iPhone"); return }
        let wanted = songs.indices.contains(start) ? songs[start] : usable[0]
        let startIdx = usable.firstIndex(of: wanted) ?? 0
        let doShuffle = wantShuffle ?? shuffle
        endListen(auto: false)
        if doShuffle {
            let first = (wantShuffle == true && start == 0) ? usable.randomElement()! : usable[startIdx]
            queue = [first] + usable.filter { $0 != first }.shuffled()
            unshuffled = usable
            index = 0
        } else {
            queue = usable; unshuffled = nil; index = startIdx
        }
        setShuffle(doShuffle)
        self.source = source
        startCurrent(at: Double(positionMs) / 1000, play: true)
    }

    func playEpisode(_ song: Song) { play([song], source: song.album, at: shows?.resumePosition(song.resumeKey) ?? 0) }
    func playBook(_ chapters: [Song], from i: Int, title: String) {
        play(chapters, from: i, shuffle: false, source: title, at: shows?.resumePosition(chapters[i].resumeKey) ?? 0)
        repeatMode = .off
    }

    func toggle() { isPlaying ? pause() : resume() }

    func resume() {
        guard current != nil else { return }
        activateSession()
        if usingStream { stream.play() } else { engine.play() }
        isPlaying = true
        startTicker()
        updateNowPlaying()
    }

    func pause() {
        if usingStream { stream.pause() } else { engine.pause() }
        isPlaying = false
        saveProgress()
        saveQueue()
        updateNowPlaying()
    }

    func next() {
        guard !queue.isEmpty else { return }
        endListen(auto: false)
        if index + 1 < queue.count { index += 1 } else if repeatMode == .all { index = 0 } else { pause(); seek(0); return }
        startCurrent(at: 0, play: true)
    }

    /// Spotify behaviour: restart if more than 3 s in, else go back.
    func previous() {
        if position > 3 || index == 0 { seek(0); return }
        endListen(auto: false)
        index -= 1
        startCurrent(at: 0, play: true)
    }

    func skip(to i: Int) {
        guard queue.indices.contains(i) else { return }
        endListen(auto: false)
        index = i
        startCurrent(at: 0, play: true)
    }

    func seek(_ seconds: Double) {
        let s = max(0, min(seconds, max(0, duration - 0.5)))
        if usingStream { stream.seek(s) } else { engine.seek(s) }
        position = s
        updateNowPlaying()
    }

    func skip(by seconds: Double) { seek(position + seconds) }

    func playNext(_ songs: [Song]) {
        guard current != nil else { return play(songs) }
        queue.insert(contentsOf: songs.filter(\.playable), at: index + 1)
        unshuffled?.append(contentsOf: songs)
        flash("Playing next")
    }

    func addToQueue(_ songs: [Song]) {
        guard current != nil else { return play(songs) }
        queue.append(contentsOf: songs.filter(\.playable))
        unshuffled?.append(contentsOf: songs)
        flash("Added to queue")
    }

    func remove(at i: Int) { guard queue.indices.contains(i), i != index else { return }; let s = queue.remove(at: i); if i < index { index -= 1 }; unshuffled?.removeAll { $0 == s } }
    func move(from: Int, to: Int) {
        guard queue.indices.contains(from), queue.indices.contains(to), from != index else { return }
        let s = queue.remove(at: from); queue.insert(s, at: to)
        if from < index && to >= index { index -= 1 } else if from > index && to <= index { index += 1 }
    }
    func clearUpNext() { if index + 1 < queue.count { queue.removeSubrange((index + 1)...) }; unshuffled = nil }

    /// Shuffle reorders the real queue (current stays first), so "Next up" is accurate; off restores the order.
    func toggleShuffle() {
        guard let cur = current else { setShuffle(!shuffle); return }
        if !shuffle {
            unshuffled = queue
            queue = [cur] + queue.enumerated().filter { $0.offset != index }.map(\.element).shuffled()
            index = 0
        } else if let original = unshuffled {
            let extras = queue.filter { !original.contains($0) }
            queue = original.filter { queue.contains($0) } + extras
            index = queue.firstIndex(of: cur) ?? 0
            unshuffled = nil
        }
        setShuffle(!shuffle)
    }

    func cycleRepeat() {
        repeatMode = RepeatMode(rawValue: (repeatMode.rawValue + 1) % 3)!
        UserDefaults.standard.set(repeatMode.rawValue, forKey: "repeat")
    }

    func setSpeed(_ r: Float) {
        if current?.isSpoken == true { speedSpoken = r; UserDefaults.standard.set(r, forKey: "speedSpoken") } else { speedMusic = r; UserDefaults.standard.set(r, forKey: "speedMusic") }
        engine.setRate(r); stream.setRate(r)
        updateNowPlaying()
    }

    func setSleep(minutes: Int?) {
        engine.setVolume(1); stream.setVolume(1)
        sleep = minutes.map { .at(Date().addingTimeInterval(Double($0) * 60)) }
    }
    func sleepAtEndOfTrack() { sleep = .endOfTrack }

    // MARK: - Core

    private func url(for s: Song) -> URL? {
        if s.kind == .remote { return URL(string: s.location) }
        return library?.fileURL(s)
    }

    private func startCurrent(at seconds: Double, play: Bool) {
        guard let song = current, let url = url(for: song) else { return }
        activateSession()
        fading = false
        listenedMs = 0
        listenStarted = Date()
        let local = url.isFileURL && song.kind != .musicLibrary
        do {
            if local {
                if usingStream { stream.stop() }
                usingStream = false
                try engine.load(url, at: seconds, play: play)
                duration = engine.duration
            } else {
                engine.stop()
                usingStream = true
                stream.load(url, at: seconds, play: play)
                duration = Double(song.durationMs) / 1000
            }
        } catch {
            flash("Can't play “\(song.title)” — skipping")
            if index + 1 < queue.count { index += 1; startCurrent(at: 0, play: play) }
            return
        }
        engine.setRate(speed); stream.setRate(speed)
        position = seconds
        isPlaying = play
        startTicker()
        updateNowPlaying()
        saveQueue()
    }

    private func trackEnded() {
        if fading { return }
        markFinished()
        endListen(auto: true)
        if sleep == .endOfTrack { sleep = nil; pause(); seek(0); return }
        if repeatMode == .one { startCurrent(at: 0, play: true); return }
        if index + 1 < queue.count { index += 1; startCurrent(at: 0, play: true) }
        else if repeatMode == .all && !queue.isEmpty { index = 0; startCurrent(at: 0, play: true) }
        else { isPlaying = false; position = 0; if !usingStream { engine.seek(0) } else { stream.seek(0) }; updateNowPlaying() }
    }

    private func startTicker() {
        ticker?.invalidate()
        ticker = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in MainActor.assumeIsolated { self?.tick() } }
    }

    private func tick() {
        let playing = usingStream ? stream.isPlaying : engine.isPlaying
        position = usingStream ? stream.currentTime : engine.currentTime
        if usingStream, stream.duration > 0 { duration = stream.duration }
        guard isPlaying, playing else { return }
        listenedMs += Int64(250 * Double(speed))
        sinceSave += 0.25
        if sinceSave >= 5 { sinceSave = 0; saveProgress(); saveQueue() }

        // Sleep timer: fade out over the last 10 s.
        if case .at(let end)? = sleep {
            let left = end.timeIntervalSinceNow
            if left <= 0 { sleep = nil; pause(); engine.setVolume(1); stream.setVolume(1) }
            else if left < 10 { let v = Float(left / 10); engine.setVolume(v); stream.setVolume(v) }
        }

        // Crossfade (music only; albums stay gapless if asked).
        if crossfade > 0, !usingStream, !fading, repeatMode != .one, let cur = current, !cur.isSpoken, index + 1 < queue.count {
            let nxt = queue[index + 1]
            let sameAlbum = keepAlbumsGapless && nxt.albumKey == cur.albumKey && nxt.track == cur.track + 1
            if !nxt.isSpoken, !sameAlbum, duration > crossfade * 2 + 2, duration - position <= crossfade,
               let u = url(for: nxt), u.isFileURL, nxt.kind == .file {
                fading = true
                markFinished(); endListen(auto: true)
                index += 1
                do {
                    try engine.crossfade(to: u, seconds: crossfade)
                    duration = engine.duration
                    listenStarted = Date(); listenedMs = 0
                    updateNowPlaying(); saveQueue()
                } catch { startCurrent(at: 0, play: true) }
                fading = false
            }
        }
        if Int(position * 4) % 8 == 0 { updateNowPlayingElapsed() }
    }

    // MARK: - Listens, resume

    private func endListen(auto: Bool) {
        guard let s = current, !s.isSpoken, listenedMs >= 3_000 else { listenedMs = 0; return }
        let dur = Int64(duration * 1000)
        let completed = auto || (dur > 0 && Double(listenedMs) >= Double(dur) * 0.85)
        let skipped = !completed && listenedMs < min(30_000, dur > 0 ? dur / 2 : 30_000)
        library?.record(Listen(songId: s.id, at: listenStarted, listenedMs: listenedMs, durationMs: dur, completed: completed, skipped: skipped))
        listenedMs = 0
    }

    private func saveProgress() {
        guard let s = current, s.isSpoken || duration > 20 * 60 else { return }
        shows?.saveProgress(s.resumeKey, Int64(position * 1000), Int64(duration * 1000))
    }

    private func markFinished() {
        guard let s = current, s.isSpoken else { return }
        shows?.setPlayed(s.resumeKey, true, Int64(duration * 1000))
    }

    private func saveQueue() {
        let d = UserDefaults.standard
        d.set(queue.map(\.id), forKey: "queue"); d.set(index, forKey: "queueIndex"); d.set(position, forKey: "queuePosition"); d.set(source, forKey: "queueSource")
    }

    /// Restores last session's queue, paused, once the library has loaded.
    func restore(lookup: (String) -> Song?) {
        guard queue.isEmpty else { return }
        let d = UserDefaults.standard
        let songs = (d.stringArray(forKey: "queue") ?? []).compactMap(lookup)
        guard !songs.isEmpty else { return }
        queue = songs
        index = min(max(0, d.integer(forKey: "queueIndex")), songs.count - 1)
        source = d.string(forKey: "queueSource")
        startCurrent(at: d.double(forKey: "queuePosition"), play: false)
    }

    private func setShuffle(_ on: Bool) { shuffle = on; UserDefaults.standard.set(on, forKey: "shuffle") }

    private func flash(_ m: String) {
        message = m
        Task { try? await Task.sleep(for: .seconds(2)); if message == m { message = nil } }
    }

    // MARK: - System integration

    private func configureSession() {
        let s = AVAudioSession.sharedInstance()
        try? s.setCategory(.playback, mode: .default, policy: .longFormAudio)
        NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] n in
            guard let raw = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt, let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
            MainActor.assumeIsolated {
                if type == .began { self?.pause() }
                else if let o = n.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt, AVAudioSession.InterruptionOptions(rawValue: o).contains(.shouldResume) { self?.resume() }
            }
        }
        NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] n in
            guard let raw = n.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt, AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable else { return }
            MainActor.assumeIsolated { self?.pause() } // headphones unplugged
        }
        NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine.engine, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { if self?.isPlaying == true { self?.engine.startEngine(); self?.engine.play() } }
        }
    }

    private func activateSession() { try? AVAudioSession.sharedInstance().setActive(true) }

    private func configureRemote() {
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { [weak self] _ in self?.resume(); return .success }
        c.pauseCommand.addTarget { [weak self] _ in self?.pause(); return .success }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in self?.toggle(); return .success }
        c.nextTrackCommand.addTarget { [weak self] _ in self?.next(); return .success }
        c.previousTrackCommand.addTarget { [weak self] _ in self?.previous(); return .success }
        c.changePlaybackPositionCommand.addTarget { [weak self] e in
            if let e = e as? MPChangePlaybackPositionCommandEvent { self?.seek(e.positionTime) }
            return .success
        }
        c.skipForwardCommand.preferredIntervals = [30]
        c.skipBackwardCommand.preferredIntervals = [10]
        c.skipForwardCommand.addTarget { [weak self] _ in self?.skip(by: 30); return .success }
        c.skipBackwardCommand.addTarget { [weak self] _ in self?.skip(by: -10); return .success }
        c.likeCommand.addTarget { [weak self] _ in
            if let id = self?.current?.id { self?.library?.toggleLike(id) }
            return .success
        }
    }

    private func updateNowPlaying() {
        let c = MPRemoteCommandCenter.shared()
        let spoken = current?.isSpoken == true
        c.skipForwardCommand.isEnabled = spoken; c.skipBackwardCommand.isEnabled = spoken
        c.nextTrackCommand.isEnabled = !spoken || index + 1 < queue.count
        c.previousTrackCommand.isEnabled = !spoken
        guard let s = current else { MPNowPlayingInfoCenter.default().nowPlayingInfo = nil; return }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: s.title, MPMediaItemPropertyArtist: s.artist, MPMediaItemPropertyAlbumTitle: s.album,
            MPMediaItemPropertyPlaybackDuration: duration, MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? Double(speed) : 0, MPNowPlayingInfoPropertyDefaultPlaybackRate: Double(speed),
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let img = ArtCache.shared.image(for: s.albumKey, remote: s.artURL) {
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: img.size) { _ in img }
        } else if let remote = s.artURL {
            Task { if await ArtCache.shared.load(key: s.albumKey, remote: remote) != nil, self.current == s { self.updateNowPlaying() } }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        MPNowPlayingInfoCenter.default().playbackState = isPlaying ? .playing : .paused
    }

    private func updateNowPlayingElapsed() {
        guard var info = MPNowPlayingInfoCenter.default().nowPlayingInfo else { return }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = position
        info[MPMediaItemPropertyPlaybackDuration] = duration
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
}
