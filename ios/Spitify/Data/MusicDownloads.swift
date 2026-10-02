import Foundation
import Observation
import UIKit
import AVFoundation

enum MusicDownloadState: String, Codable {
    case queued, waiting, downloading, checking, finding, complete, failed, cancelled
    var active: Bool { self == .queued || self == .waiting || self == .downloading || self == .checking || self == .finding }
}

struct MusicDownload: Codable, Identifiable {
    var id: String { track.id }
    var track: OnlineTrack
    var attempt: String
    var state: MusicDownloadState
    var relativePath: String
    var error: String?
    var quality: String?
    var wifiOnly: Bool? = true
    var retryCount: Int? = nil
    var retryAt: Date? = nil
    var lastFailure: String? = nil
}

@MainActor @Observable
final class MusicDownloads {
    static let sessionID = "com.calebtrueman.spitify.music-downloads"
    static let shared = MusicDownloads()
    private(set) var jobs: [MusicDownload] = []
    private(set) var progress: [String: Double] = [:]
    private(set) var message: String?
    var onImported: (() async -> Void)?
    var backgroundCompletion: (() -> Void)?
    private var backgroundEventsFinished = false
    private var started = false
    private var restoring = true
    private let root: URL
    private let stateFile: URL
    private let incoming: URL
    private var delegate: MusicDownloadDelegate!
    private var session: URLSession!
    private var retryWakeup: Task<Void, Never>?
    private var scheduledAttempts: Set<String> = []
    private let backgroundTransfers: Bool
    private let downloadURL: (OnlineTrack) throws -> URL
    private let alternate: (OnlineTrack) async throws -> OnlineTrack?
    var wifiOnly: Bool {
        didSet { UserDefaults.standard.set(wifiOnly, forKey: "musicDownloadWiFiOnly") }
    }

    init(root: URL = Store.documents, stateDirectory: URL = Store.directory, configuration: URLSessionConfiguration? = nil, alternate: @escaping (OnlineTrack) async throws -> OnlineTrack? = AudioFallback.resolve,
         downloadURL: @escaping (OnlineTrack) throws -> URL = { track in
             if let value = track.audioURL, AudioFallback.validAudioURL(value), let url = URL(string: value) { return url }
             return try MonochromeClient.audioURL(track.id)
         }) {
        self.downloadURL = downloadURL
        backgroundTransfers = configuration == nil || configuration?.identifier != nil
        self.alternate = alternate
        self.root = root
        stateFile = stateDirectory.appendingPathComponent("music-downloads.json")
        incoming = stateDirectory.appendingPathComponent("MusicIncoming", isDirectory: true)
        wifiOnly = UserDefaults.standard.object(forKey: "musicDownloadWiFiOnly") as? Bool ?? true
        do {
            try FileManager.default.createDirectory(at: incoming, withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: stateFile.path) { jobs = try JSONDecoder().decode([MusicDownload].self, from: Data(contentsOf: stateFile)) }
        } catch { message = "Could not read the download queue: \(error.localizedDescription)" }
        delegate = MusicDownloadDelegate(incoming: incoming)
        delegate.owner = self
        let config = configuration ?? URLSessionConfiguration.background(withIdentifier: Self.sessionID)
        config.httpMaximumConnectionsPerHost = 2
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 60 * 60
        session = URLSession(configuration: config, delegate: delegate, delegateQueue: .main)
    }

    func start() {
        guard !started else { return }
        started = true
        session.getAllTasks { tasks in
            Task { @MainActor in
                let attempts = Set(tasks.compactMap(\.taskDescription))
                self.scheduledAttempts = attempts
                for id in self.jobs.map(\.id) {
                    guard let i = self.jobs.firstIndex(where: { $0.id == id }) else { continue }
                    let job = self.jobs[i]
                    if job.state == .complete && !FileManager.default.fileExists(atPath: self.root.appendingPathComponent(job.relativePath).path) {
                        self.jobs[i].state = .failed
                        self.jobs[i].error = "The downloaded file was moved or deleted."
                    } else if job.state == .waiting {
                        continue // Keep the saved retry time after a restart.
                    } else if job.state == .failed, job.retryCount == nil, DownloadRetry.legacyTemporaryFailure(job.error) {
                        self.jobs[i].state = .queued
                        self.jobs[i].track.fallbackTried = nil
                        self.jobs[i].retryCount = 0
                        self.jobs[i].error = nil
                    } else if job.state.active, !attempts.contains(job.attempt) {
                        let temp = self.incoming.appendingPathComponent(job.attempt + ".flac")
                        if FileManager.default.fileExists(atPath: temp.path) {
                            await self.received(attempt: job.attempt, file: temp)
                        } else if let quality = try? await self.validateAudio(self.root.appendingPathComponent(job.relativePath), track: job.track) {
                            // Recover a crash between moving the file and saving "complete".
                            self.jobs[i].state = .complete
                            self.jobs[i].quality = quality
                            await self.onImported?()
                        } else {
                            self.jobs[i].state = .queued
                        }
                    }
                }
                // Remove orphaned tasks, for example if saving the queue failed.
                for task in tasks where !self.jobs.contains(where: { $0.attempt == task.taskDescription && $0.state.active }) { task.cancel() }
                self.restoring = false
                self.persist()
                self.pump()
            }
        }
    }

    func refreshMissingFiles() {
        for i in jobs.indices where jobs[i].state == .complete {
            if !FileManager.default.fileExists(atPath: root.appendingPathComponent(jobs[i].relativePath).path) {
                jobs[i].state = .failed
                jobs[i].error = "The downloaded file was moved or deleted."
            }
        }
        persist()
    }

    @discardableResult func enqueue(_ tracks: [OnlineTrack]) async -> String {
        let previous = jobs
        var added = 0, saved = 0, active = 0
        for requested in tracks {
            var track = requested
            track.audioURL = nil; track.audioExtension = nil; track.fallbackTried = nil; track.attemptedSources = nil
            if let old = jobs.first(where: { $0.id == track.id }) {
                if old.state.active { active += 1; continue }
                if old.state == .complete && FileManager.default.fileExists(atPath: root.appendingPathComponent(old.relativePath).path) { saved += 1; continue }
            }
            guard (try? MonochromeClient.audioURL(track.id)) != nil else { continue }
            jobs.removeAll { $0.id == track.id }
            let folder = MonochromeClient.id(track.releaseID) ?? "Singles"
            let job = MusicDownload(track: track, attempt: UUID().uuidString, state: .queued,
                                    relativePath: "Music/Monochrome/\(folder)/\(track.id).\(track.audioExtension ?? "flac")", wifiOnly: wifiOnly)
            jobs.append(job); added += 1
        }
        guard persist() else { jobs = previous; return message ?? "Could not save the download queue." }
        pump()
        if added > 0 { return "\(added) \(added == 1 ? "song" : "songs") queued." + (wifiOnly ? " Downloads use Wi-Fi." : "") }
        if active > 0 { return "Already in your download queue." }
        if saved > 0 { return "Already saved in your library." }
        return "This album did not return any songs. Please reload it and try again."
    }

    func cancel(_ id: String) {
        guard let i = jobs.firstIndex(where: { $0.id == id && $0.state.active }) else { return }
        let attempt = jobs[i].attempt
        scheduledAttempts.remove(attempt)
        jobs[i].state = .cancelled
        progress[id] = nil
        persist()
        session.getAllTasks { tasks in tasks.filter { $0.taskDescription == attempt }.forEach { $0.cancel() } }
        pump()
    }

    func resumePending() { pump() }

    private func pump() {
        guard !restoring else { return }
        retryWakeup?.cancel()
        for i in jobs.indices where jobs[i].state == .waiting && !scheduledAttempts.contains(jobs[i].attempt) && (jobs[i].retryAt ?? .distantPast) <= Date() {
            jobs[i].state = .queued; jobs[i].retryAt = nil; jobs[i].error = nil
        }
        if let next = jobs.filter({ $0.state == .waiting && !scheduledAttempts.contains($0.attempt) }).compactMap(\.retryAt).min() {
            retryWakeup = Task { [weak self] in
                do { try await Task.sleep(for: .seconds(max(0.05, next.timeIntervalSinceNow))) }
                catch { return }
                self?.pump()
            }
        }
        // Let URLSession own delayed retries so the phone can continue them while the app is suspended.
        if backgroundTransfers {
            for i in jobs.indices where jobs[i].state == .waiting && !scheduledAttempts.contains(jobs[i].attempt) {
                startTransfer(i, earliest: jobs[i].retryAt)
            }
        }
        var available = 2 - jobs.filter { $0.state == .downloading || $0.state == .checking || $0.state == .finding }.count
        for i in jobs.indices where jobs[i].state == .queued && available > 0 {
            jobs[i].state = .downloading
            guard persist() else { jobs[i].state = .queued; return }
            startTransfer(i)
            available -= 1
        }
    }

    private func startTransfer(_ i: Int, earliest: Date? = nil) {
        do {
            var request = URLRequest(url: try downloadURL(jobs[i].track))
            request.allowsCellularAccess = !(jobs[i].wifiOnly ?? true)
            let task = session.downloadTask(with: request)
            task.taskDescription = jobs[i].attempt
            task.earliestBeginDate = earliest
            scheduledAttempts.insert(jobs[i].attempt)
            task.resume()
        } catch {
            jobs[i].state = .failed; jobs[i].error = error.localizedDescription; persist()
        }
    }

    func updated(attempt: String, received: Int64, total: Int64) {
        guard let i = jobs.firstIndex(where: { $0.attempt == attempt && ($0.state == .downloading || $0.state == .waiting) }) else { return }
        if jobs[i].state == .waiting { jobs[i].state = .downloading; jobs[i].retryAt = nil; jobs[i].error = nil; persist() }
        progress[jobs[i].id] = total > 0 ? min(1, Double(received) / Double(total)) : 0
    }

    func received(attempt: String, file: URL) async {
        var audioFile = file
        defer { try? FileManager.default.removeItem(at: audioFile); try? FileManager.default.removeItem(at: file) }
        guard let index = jobs.firstIndex(where: { $0.attempt == attempt && $0.state.active }) else { return }
        scheduledAttempts.remove(attempt)
        jobs[index].state = .checking
        persist()
        var audioChecked = false
        do {
            let track = jobs[index].track
            if let ext = track.audioExtension, ["m4a", "mp3"].contains(ext) {
                audioFile = file.deletingPathExtension().appendingPathExtension(ext)
                try FileManager.default.moveItem(at: file, to: audioFile)
            }
            let quality = try await validateAudio(audioFile, track: track)
            audioChecked = true
            var artwork: Data?
            if let art = track.artwork {
                guard let data = await HTTP.get(art) else { throw MusicSourceError.message("Could not download the cover. Please retry.") }
                artwork = data
            }
            try await FileTags.shared.write(audioFile, edit: MetadataOverride(title: track.title, artist: track.artist,
                album: track.album.isEmpty ? nil : track.album, albumArtist: track.albumArtist ?? Song.albumArtist(track.artist),
                track: track.trackNumber > 0 ? track.trackNumber : nil, disc: track.discNumber, source: "online"), artwork: artwork)
            guard let currentIndex = jobs.firstIndex(where: { $0.attempt == attempt && $0.state == .checking }) else { throw CancellationError() }
            let destination = root.appendingPathComponent(jobs[currentIndex].relativePath)
            try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: destination.path) {
                // A crash may have happened after the final move. Never overwrite an existing file.
                _ = try await validateAudio(destination, track: track)
            } else { try FileManager.default.moveItem(at: audioFile, to: destination) }
            jobs[currentIndex].quality = quality
            jobs[currentIndex].error = nil
            jobs[currentIndex].state = .complete
            progress[jobs[currentIndex].id] = nil
            persist()
            await onImported?()
        } catch { failed(attempt: attempt, error: error, audioFailure: !audioChecked) }
        pump()
        finishBackgroundEventsIfReady()
    }

    private func validateAudio(_ file: URL, track: OnlineTrack) async throws -> String {
        if !["m4a", "mp3"].contains(track.audioExtension ?? "flac") { return try FLACInfo.read(file, expectedDurationMs: track.durationMs).label }
        let asset = AVURLAsset(url: file)
        let duration = try await asset.load(.duration).seconds
        let audio = try await asset.loadTracks(withMediaType: .audio)
        guard !audio.isEmpty, duration.isFinite, duration > 0, abs(duration * 1000 - Double(track.durationMs)) <= 5000 else {
            throw MusicSourceError.message("The downloaded audio does not match this song.")
        }
        return track.audioExtension == "mp3" ? "MP3" : "M4A"
    }

    func failed(attempt: String, error: Error, audioFailure: Bool = false) {
        guard let i = jobs.firstIndex(where: { $0.attempt == attempt && $0.state.active && $0.state != .finding && ($0.state != .waiting || scheduledAttempts.contains(attempt)) }) else { return }
        scheduledAttempts.remove(attempt)
        // Once the audio is checked, an artwork/tag/storage problem must not replace the recording.
        let transferFailed = jobs[i].state == .downloading || jobs[i].state == .waiting || audioFailure
        let temporary = transferFailed && (DownloadRetry.isTemporary(error) || audioFailure)
        jobs[i].lastFailure = error.localizedDescription
        let tries = jobs[i].retryCount ?? 0
        if temporary && (tries < 3 || jobs[i].track.fallbackTried == true) {
            waitToRetry(i)
            return
        }
        if transferFailed && jobs[i].track.fallbackTried != true && (error as NSError).code != NSURLErrorCancelled {
            jobs[i].state = .finding; jobs[i].error = "Looking for another matching copy…"
            let track = jobs[i].track
            persist()
            Task {
                let replacement = try? await alternate(track)
                guard let index = jobs.firstIndex(where: { $0.attempt == attempt && $0.state == .finding }) else { return }
                if let replacement {
                    jobs[index].track = replacement
                    jobs[index].relativePath = (jobs[index].relativePath as NSString).deletingPathExtension + "." + (replacement.audioExtension ?? "flac")
                    jobs[index].attempt = UUID().uuidString
                    jobs[index].retryCount = 0
                    jobs[index].state = .queued; jobs[index].error = nil
                } else if temporary {
                    jobs[index].track.fallbackTried = true
                    waitToRetry(index)
                    return
                } else {
                    jobs[index].state = .failed
                    jobs[index].error = "No matching copy is available from the checked sources. You can retry this song."
                }
                persist(); pump(); finishBackgroundEventsIfReady()
            }
            return
        }
        jobs[i].state = .failed
        jobs[i].error = error.localizedDescription
        progress[jobs[i].id] = nil
        persist(); pump(); finishBackgroundEventsIfReady()
    }

    private func waitToRetry(_ i: Int) {
        let count = (jobs[i].retryCount ?? 0) + 1
        jobs[i].retryCount = count
        jobs[i].retryAt = Date().addingTimeInterval(DownloadRetry.delay(count))
        jobs[i].attempt = UUID().uuidString
        jobs[i].state = .waiting
        jobs[i].error = "Waiting to retry automatically."
        progress[jobs[i].id] = nil
        persist(); pump(); finishBackgroundEventsIfReady()
    }

    func eventsFinished() {
        backgroundEventsFinished = true
        finishBackgroundEventsIfReady()
    }

    private func finishBackgroundEventsIfReady() {
        guard backgroundEventsFinished, !jobs.contains(where: { $0.state == .checking || $0.state == .finding }) else { return }
        backgroundEventsFinished = false
        let completion = backgroundCompletion
        backgroundCompletion = nil
        completion?()
    }

    @discardableResult private func persist() -> Bool {
        do {
            try JSONEncoder().encode(jobs).write(to: stateFile, options: .atomic)
            return true
        } catch {
            message = "Could not save the download queue. Check your free storage."
            return false
        }
    }
}

private final class MusicDownloadDelegate: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    weak var owner: MusicDownloads?
    let incoming: URL
    init(incoming: URL) { self.incoming = incoming }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let attempt = downloadTask.taskDescription else { return }
        do {
            guard let response = downloadTask.response else { throw MusicSourceError.message("The download had no response.") }
            try MonochromeClient.check(response)
            let size = (try location.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0
            if response.expectedContentLength > 0 && Int64(size) != response.expectedContentLength { throw MusicSourceError.message("The audio download is incomplete.") }
            let staged = incoming.appendingPathComponent(attempt + ".flac")
            try FileManager.default.moveItem(at: location, to: staged)
            Task { @MainActor in await owner?.received(attempt: attempt, file: staged) }
        } catch { Task { @MainActor in owner?.failed(attempt: attempt, error: error) } }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error, let attempt = task.taskDescription else { return }
        Task { @MainActor in owner?.failed(attempt: attempt, error: error) }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let attempt = downloadTask.taskDescription else { return }
        Task { @MainActor in owner?.updated(attempt: attempt, received: totalBytesWritten, total: totalBytesExpectedToWrite) }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor in owner?.eventsFinished() }
    }
}

final class MusicBackgroundAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        guard identifier == MusicDownloads.sessionID else { completionHandler(); return }
        MusicDownloads.shared.backgroundCompletion = completionHandler
        MusicDownloads.shared.start()
    }
}
