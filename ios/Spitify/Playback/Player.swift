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
    private(set) var manualQueueIndices: Set<Int> = []
    private(set) var queueVersion = UUID()
    private(set) var isPlaying = false
    private(set) var position: Double = 0
    private(set) var duration: Double = 0
    private(set) var source: String?
    private(set) var shuffle = UserDefaults.standard.bool(forKey: "shuffle")
    private(set) var repeatMode = RepeatMode(rawValue: UserDefaults.standard.integer(forKey: "repeat")) ?? .off
    private(set) var sleep: SleepTimer?
    private(set) var message: String?
    private(set) var audioOutput = "Audio output unavailable"
    private var roomSpeed: Float?
    var speed: Float { roomSpeed ?? (current?.isSpoken == true ? speedSpoken : speedMusic) }
    func setRoomPlayback(speed: Float?) { roomSpeed = speed; engine.setRate(self.speed); stream.setRate(self.speed) }
    var lockScreenArt = UserDefaults.standard.object(forKey: "lockScreenArt") as? Bool ?? true {
        didSet { UserDefaults.standard.set(lockScreenArt, forKey: "lockScreenArt"); updateNowPlaying() }
    }
    private var artworkSessionActive = false
    private var artworkIdleDeadline: Date?
    private var artworkIdleTask: Task<Void, Never>?
    private var portraitArtKey: String?
    private var portraitArt: Any?
    private var nowPlayingID: String?
    private var artworkLoad: Task<Void, Never>?
    var crossfade: Double = UserDefaults.standard.double(forKey: "crossfade") { didSet { UserDefaults.standard.set(crossfade, forKey: "crossfade") } }
    var keepAlbumsGapless: Bool = UserDefaults.standard.object(forKey: "gaplessAlbums") as? Bool ?? true { didSet { UserDefaults.standard.set(keepAlbumsGapless, forKey: "gaplessAlbums") } }
    var eq: EQSettings = { (UserDefaults.standard.data(forKey: "eq")).flatMap { try? JSONDecoder().decode(EQSettings.self, from: $0) } ?? EQSettings() }() {
        didSet { engine.apply(eq); UserDefaults.standard.set(try? JSONEncoder().encode(eq), forKey: "eq") }
    }
    private var speedMusic: Float = UserDefaults.standard.object(forKey: "speedMusic") as? Float ?? 1
    private var speedSpoken: Float = UserDefaults.standard.object(forKey: "speedSpoken") as? Float ?? 1

    var current: Song? { queue.indices.contains(index) ? queue[index] : nil }
    var upNext: [(Int, Song)] { index < 0 ? [] : Array(queue.enumerated().dropFirst(index + 1)) }
    var manuallyQueued: [(Int, Song)] { upNext.filter { manualQueueIndices.contains($0.0) } }
    var nextFromSource: [(Int, Song)] { upNext.filter { !manualQueueIndices.contains($0.0) } }
    var hasMedia: Bool { current != nil }

    private let engine = EngineBackend()
    private let stream = StreamBackend()
    private var usingStream = false
    private var needsLoad = false
    private var hasStartedPlayback = false
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
        stream.onError = { [weak self] in
            self?.pause(); self?.flash("Couldn’t play this song. Check your connection and try again.")
        }
        configureSession()
        updateAudioOutput()
        configureRemote()
        // Restoring a queue must not revive the previous lock-screen session.
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        for name in [UIApplication.willResignActiveNotification, UIApplication.didBecomeActiveNotification] {
            NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.updateNowPlaying() }
            }
        }
    }

    // MARK: - Public transport

    func play(_ songs: [Song], from start: Int = 0, shuffle wantShuffle: Bool? = nil, source: String? = nil, at positionMs: Int64 = 0) {
        let usable = songs.filter(\.playable)
        guard !usable.isEmpty else { flash("This format isn't supported on iPhone"); return }
        queueVersion = UUID()
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
        manualQueueIndices = []
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
        if needsLoad || usingStream && stream.failed { startCurrent(at: position, play: true); return }
        hasStartedPlayback = true
        activateSession()
        if usingStream { stream.play() } else { engine.play() }
        isPlaying = true
        artworkSessionActive = true; artworkIdleDeadline = nil; artworkIdleTask?.cancel()
        startTicker()
        updateNowPlaying()
    }

    func pause() {
        if usingStream { stream.pause() } else { engine.pause() }
        isPlaying = false
        if artworkIdleDeadline == nil {
            artworkIdleDeadline = Date().addingTimeInterval(600)
            artworkIdleTask?.cancel()
            artworkIdleTask = Task { [weak self] in
                try? await Task.sleep(for: .seconds(600))
                guard !Task.isCancelled, let self, !self.isPlaying else { return }
                self.artworkSessionActive = false; self.updateNowPlaying()
            }
        }
        saveProgress()
        saveQueue()
        updateNowPlaying()
    }

    func stop() {
        queueVersion = UUID()
        pause()
        hasStartedPlayback = false
        artworkSessionActive = false; artworkIdleTask?.cancel(); artworkLoad?.cancel(); artworkLoad = nil; nowPlayingID = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
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
        insertManual(songs.filter(\.playable), at: index + 1)
        flash("Playing next")
    }

    func addToQueue(_ songs: [Song]) {
        guard !songs.isEmpty else { return }
        let usable = songs.filter(\.playable)
        guard !usable.isEmpty else { flash("These files aren't supported"); return }
        if current == nil { play(usable) }
        else { insertManual(usable, at: (manualQueueIndices.filter { $0 > index }.max() ?? index) + 1) }
        let skipped = songs.count - usable.count
        flash("Added \(usable.count) \(usable.count == 1 ? "song" : "songs") to queue" +
              (skipped > 0 ? " • \(skipped) unsupported skipped" : ""))
    }

    private func insertManual(_ songs: [Song], at insertion: Int) {
        guard !songs.isEmpty else { return }
        manualQueueIndices = Set(manualQueueIndices.map { $0 >= insertion ? $0 + songs.count : $0 })
        manualQueueIndices.formUnion(insertion..<(insertion + songs.count))
        queue.insert(contentsOf: songs, at: insertion)
        saveQueue()
    }

    func appendFromSource(_ songs: [Song]) {
        queue.append(contentsOf: songs.filter(\.playable))
        saveQueue()
    }

    func remove(at i: Int) {
        guard queue.indices.contains(i), i != index else { return }
        let wasManual = manualQueueIndices.contains(i)
        let song = queue.remove(at: i)
        manualQueueIndices = Set(manualQueueIndices.filter { $0 != i }.map { $0 > i ? $0 - 1 : $0 })
        if i < index { index -= 1 }
        if !wasManual, let original = unshuffled?.firstIndex(of: song) { unshuffled?.remove(at: original) }
        saveQueue()
    }
    func move(from: Int, to: Int) {
        guard queue.indices.contains(from), queue.indices.contains(to), from != index else { return }
        let song = queue.remove(at: from); queue.insert(song, at: to)
        manualQueueIndices = Set(manualQueueIndices.map { i in
            if i == from { return to }
            if from < to && i > from && i <= to { return i - 1 }
            if from > to && i >= to && i < from { return i + 1 }
            return i
        })
        if from < index && to >= index { index -= 1 } else if from > index && to <= index { index += 1 }
        saveQueue()
    }
    func clearUpNext() {
        queueVersion = UUID()
        if index + 1 < queue.count { queue.removeSubrange((index + 1)...) }
        manualQueueIndices = manualQueueIndices.filter { $0 <= index }
        unshuffled = nil
        saveQueue()
    }

    /// Shuffle changes the album/library order but keeps manually added songs first.
    func toggleShuffle() {
        guard let cur = current else { setShuffle(!shuffle); return }
        let currentWasManual = manualQueueIndices.contains(index)
        let manual = manuallyQueued.map(\.1)
        let background = queue.enumerated().filter { $0.offset != index && !manualQueueIndices.contains($0.offset) }.map(\.element)
        if !shuffle {
            unshuffled = queue.enumerated().filter { !manualQueueIndices.contains($0.offset) }.map(\.element)
            queue = [cur] + manual + background.shuffled()
            index = 0
        } else {
            var remaining = background + (currentWasManual ? [] : [cur])
            var restored: [Song] = []
            for song in unshuffled ?? [] {
                if let i = remaining.firstIndex(of: song) { restored.append(remaining.remove(at: i)) }
            }
            restored += remaining
            let current = currentWasManual ? nil : restored.firstIndex(of: cur)
            let before = current.map { Array(restored.prefix($0)) } ?? []
            let after = current.map { Array(restored.dropFirst($0 + 1)) } ?? restored
            queue = before + [cur] + manual + after
            index = before.count
            unshuffled = nil
        }
        manualQueueIndices = Set((index + 1)..<(index + 1 + manual.count))
        if currentWasManual { manualQueueIndices.insert(index) }
        setShuffle(!shuffle)
        saveQueue()
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
        if let track = MusicStreams.shared.track(s), let local = library?.rawSongs.first(where: {
            SearchMatch.sameSong($0, track) && AudioFallback.sameRelease($0.album, track.album)
        }) { return library?.fileURL(local) }
        if s.kind == .remote { return URL(string: s.location) }
        return library?.fileURL(s)
    }

    private func startCurrent(at seconds: Double, play: Bool) {
        guard let song = current else { return }
        guard let url = url(for: song) else { stop(); flash("This song is no longer available."); return }
        // Publish the selected song before loading audio; never leave the previous title visible during a load.
        needsLoad = false
        position = seconds; duration = Double(song.durationMs) / 1000; isPlaying = play
        if play { hasStartedPlayback = true }
        activateSession()
        updateNowPlaying()
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
            engine.stop(); stream.stop(); isPlaying = false
            flash("Can't play “\(song.title)” — skipping")
            if index + 1 < queue.count { index += 1; startCurrent(at: 0, play: play) }
            else { updateNowPlaying() }
            return
        }
        engine.setRate(speed); stream.setRate(speed)
        position = seconds
        isPlaying = play
        if play { artworkSessionActive = true; artworkIdleDeadline = nil; artworkIdleTask?.cancel() }
        startTicker()
        updateNowPlaying()
        saveQueue()
    }

    private func trackEnded() {
        if fading { return }
        markFinished()
        endListen(auto: true)
        if sleep == .endOfTrack { sleep = nil; pause(); seek(0); return }
        if roomSpeed != nil { pause(); return }
        if repeatMode == .one { startCurrent(at: 0, play: true); return }
        if index + 1 < queue.count { index += 1; startCurrent(at: 0, play: true) }
        else if repeatMode == .all && !queue.isEmpty { index = 0; startCurrent(at: 0, play: true) }
        else { artworkSessionActive = false; artworkIdleTask?.cancel(); isPlaying = false; position = 0; if !usingStream { engine.seek(0) } else { stream.seek(0) }; updateNowPlaying() }
    }

    private func startTicker() {
        ticker?.invalidate()
        let timer = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in MainActor.assumeIsolated { self?.tick() } }
        ticker = timer
        RunLoop.main.add(timer, forMode: .common)
    }

    private func tick() {
        if !isPlaying, artworkSessionActive, let deadline = artworkIdleDeadline, Date() >= deadline {
            artworkSessionActive = false; updateNowPlaying()
        }
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
        d.set(Array(manualQueueIndices), forKey: "queueManualIndices")
        d.set(unshuffled?.map(\.id), forKey: "queueUnshuffled")
        d.set(queue.map(\.id), forKey: "queue"); d.set(index, forKey: "queueIndex"); d.set(position, forKey: "queuePosition"); d.set(source, forKey: "queueSource")
    }

    /// Restores last session's queue, paused, once the library has loaded.
    func restore(lookup: (String) -> Song?) {
        guard queue.isEmpty else { return }
        let d = UserDefaults.standard
        let savedManual = Set(d.array(forKey: "queueManualIndices") as? [Int] ?? [])
        let restored = (d.stringArray(forKey: "queue") ?? []).enumerated().compactMap { i, id in lookup(id).map { (i, $0) } }
        let songs = restored.map(\.1)
        guard !songs.isEmpty else { return }
        queue = songs
        manualQueueIndices = Set(restored.enumerated().compactMap { i, pair in savedManual.contains(pair.0) ? i : nil })
        unshuffled = d.stringArray(forKey: "queueUnshuffled")?.compactMap(lookup)
        index = min(max(0, d.integer(forKey: "queueIndex")), songs.count - 1)
        source = d.string(forKey: "queueSource")
        position = max(0, d.double(forKey: "queuePosition"))
        duration = Double(current?.durationMs ?? 0) / 1000
        needsLoad = true
        isPlaying = false
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
            MainActor.assumeIsolated {
                self?.updateAudioOutput()
                if let raw = n.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                   AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable {
                    self?.pause()
                }
            }
        }
        NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine.engine, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { if self?.isPlaying == true { self?.engine.startEngine(); self?.engine.play() } }
        }
    }

    private func updateAudioOutput() {
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs.map {
            $0.portType == .builtInSpeaker ? "Phone speaker" : $0.portName
        }
        audioOutput = outputs.isEmpty ? "Audio output unavailable" : outputs.joined(separator: " + ")
    }

    private func activateSession() {
        do { try AVAudioSession.sharedInstance().setActive(true) }
        catch { NSLog("Spitify audio session could not activate: %@", error.localizedDescription) }
        updateAudioOutput()
    }

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
        c.stopCommand.addTarget { [weak self] _ in self?.stop(); return .success }
        c.skipForwardCommand.preferredIntervals = [30]
        c.skipBackwardCommand.preferredIntervals = [10]
        c.skipForwardCommand.addTarget { [weak self] _ in self?.skip(by: 30); return .success }
        c.skipBackwardCommand.addTarget { [weak self] _ in self?.skip(by: -10); return .success }
        c.likeCommand.isEnabled = false
    }

    private func updateNowPlaying() {
        guard hasStartedPlayback else { return }
        let c = MPRemoteCommandCenter.shared()
        let spoken = current?.isSpoken == true
        c.skipForwardCommand.isEnabled = spoken; c.skipBackwardCommand.isEnabled = spoken
        c.nextTrackCommand.isEnabled = !spoken || index + 1 < queue.count
        c.previousTrackCommand.isEnabled = !spoken
        guard let s = current else { MPNowPlayingInfoCenter.default().nowPlayingInfo = nil; return }
        if nowPlayingID != s.id {
            nowPlayingID = s.id; artworkLoad?.cancel(); artworkLoad = nil
            portraitArtKey = nil; portraitArt = nil
            // Replace the song in one write without dropping the active session.
        }
        var info: [String: Any] = [
            MPNowPlayingInfoPropertyExternalContentIdentifier: s.id,
            MPNowPlayingInfoPropertyPlaybackQueueIndex: index,
            MPNowPlayingInfoPropertyPlaybackQueueCount: queue.count,
            MPMediaItemPropertyTitle: s.title, MPMediaItemPropertyArtist: s.artist, MPMediaItemPropertyAlbumTitle: s.album,
            MPMediaItemPropertyPlaybackDuration: duration, MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? Double(speed) : 0, MPNowPlayingInfoPropertyDefaultPlaybackRate: Double(speed),
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if artworkSessionActive, let img = ArtCache.shared.image(for: s.albumKey, remote: s.artURL) {
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: img.size) { _ in img }
            if #available(iOS 26.0, *), lockScreenArt,
               MPNowPlayingInfoCenter.supportedAnimatedArtworkKeys.contains(MPNowPlayingInfoProperty3x4AnimatedArtwork) {
                let key = s.albumKey + String(ObjectIdentifier(img).hashValue)
                if portraitArtKey != key {
                    portraitArtKey = key
                    portraitArt = LockScreenArtwork.artwork(image: img)
                }
                if let artwork = portraitArt as? MPMediaItemAnimatedArtwork { info[MPNowPlayingInfoProperty3x4AnimatedArtwork] = artwork }
            }
        } else if artworkSessionActive, let remote = s.artURL, artworkLoad == nil {
            artworkLoad = Task { [weak self] in
                let image = await ArtCache.shared.load(key: s.albumKey, remote: remote)
                guard !Task.isCancelled, let self, self.current?.id == s.id else { return }
                if image != nil { self.updateNowPlaying() }
            }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        #if DEBUG
        let snapshot: [String: Any] = ["songID": s.id, "title": s.title, "playing": isPlaying,
            "position": position, "publishedTitle": MPNowPlayingInfoCenter.default().nowPlayingInfo?[MPMediaItemPropertyTitle] as? String ?? "",
            "updatedAt": Date().timeIntervalSince1970]
        if let data = try? JSONSerialization.data(withJSONObject: snapshot) {
            try? data.write(to: Store.caches.appendingPathComponent("playback-state.json"), options: .atomic)
        }
        #endif
    }

    private func updateNowPlayingElapsed() {
        guard hasStartedPlayback, let current else { return }
        guard var info = MPNowPlayingInfoCenter.default().nowPlayingInfo,
              info[MPNowPlayingInfoPropertyExternalContentIdentifier] as? String == current.id else {
            updateNowPlaying(); return
        }
        info[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? Double(speed) : 0
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = position
        info[MPMediaItemPropertyPlaybackDuration] = duration
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
}
