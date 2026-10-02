import Foundation
import Observation
import UIKit

enum MusicDownloadState: String, Codable {
    case queued, downloading, checking, complete, failed, cancelled
    var active: Bool { self == .queued || self == .downloading || self == .checking }
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
    var wifiOnly: Bool {
        didSet { UserDefaults.standard.set(wifiOnly, forKey: "musicDownloadWiFiOnly") }
    }

    init(root: URL = Store.documents, stateDirectory: URL = Store.directory, configuration: URLSessionConfiguration? = nil) {
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
                for id in self.jobs.map(\.id) {
                    guard let i = self.jobs.firstIndex(where: { $0.id == id }) else { continue }
                    let job = self.jobs[i]
                    if job.state == .complete && !FileManager.default.fileExists(atPath: self.root.appendingPathComponent(job.relativePath).path) {
                        self.jobs[i].state = .failed
                        self.jobs[i].error = "The downloaded file was moved or deleted."
                    } else if job.state.active, !attempts.contains(job.attempt) {
                        let temp = self.incoming.appendingPathComponent(job.attempt + ".flac")
                        if FileManager.default.fileExists(atPath: temp.path) {
                            await self.received(attempt: job.attempt, file: temp)
                        } else if let info = try? FLACInfo.read(self.root.appendingPathComponent(job.relativePath), expectedDurationMs: job.track.durationMs) {
                            // Recover a crash between moving the file and saving "complete".
                            self.jobs[i].state = .complete
                            self.jobs[i].quality = info.label
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

    func enqueue(_ tracks: [OnlineTrack]) {
        for track in tracks where track.playable {
            if let old = jobs.first(where: { $0.id == track.id }), old.state.active || (old.state == .complete && FileManager.default.fileExists(atPath: root.appendingPathComponent(old.relativePath).path)) { continue }
            guard (try? MonochromeClient.audioURL(track.id)) != nil else { continue }
            jobs.removeAll { $0.id == track.id }
            let folder = MonochromeClient.id(track.releaseID) ?? "Singles"
            let job = MusicDownload(track: track, attempt: UUID().uuidString, state: .queued,
                                    relativePath: "Music/Monochrome/\(folder)/\(track.id).flac", wifiOnly: wifiOnly)
            jobs.append(job)
        }
        if persist() { pump() }
    }

    func cancel(_ id: String) {
        guard let i = jobs.firstIndex(where: { $0.id == id && $0.state.active }) else { return }
        let attempt = jobs[i].attempt
        jobs[i].state = .cancelled
        progress[id] = nil
        persist()
        session.getAllTasks { tasks in tasks.filter { $0.taskDescription == attempt }.forEach { $0.cancel() } }
        pump()
    }

    private func pump() {
        guard !restoring else { return }
        var available = 2 - jobs.filter { $0.state == .downloading || $0.state == .checking }.count
        for i in jobs.indices where jobs[i].state == .queued && available > 0 {
            guard let url = try? MonochromeClient.audioURL(jobs[i].id) else { continue }
            jobs[i].state = .downloading
            guard persist() else { jobs[i].state = .queued; return }
            var request = URLRequest(url: url)
            request.allowsCellularAccess = !(jobs[i].wifiOnly ?? true)
            let task = session.downloadTask(with: request)
            task.taskDescription = jobs[i].attempt
            task.resume()
            available -= 1
        }
    }

    func updated(attempt: String, received: Int64, total: Int64) {
        guard let job = jobs.first(where: { $0.attempt == attempt && $0.state == .downloading }) else { return }
        progress[job.id] = total > 0 ? min(1, Double(received) / Double(total)) : 0
    }

    func received(attempt: String, file: URL) async {
        defer { try? FileManager.default.removeItem(at: file) }
        guard let index = jobs.firstIndex(where: { $0.attempt == attempt && $0.state.active }) else { return }
        jobs[index].state = .checking
        persist()
        do {
            let info = try FLACInfo.read(file, expectedDurationMs: jobs[index].track.durationMs)
            let track = jobs[index].track
            var artwork: Data?
            if let art = track.artwork {
                guard let data = await HTTP.get(art) else { throw MusicSourceError.message("Could not download the cover. Please retry.") }
                artwork = data
            }
            try await FileTags.shared.write(file, edit: MetadataOverride(title: track.title, artist: track.artist,
                album: track.album.isEmpty ? nil : track.album, albumArtist: track.artist,
                track: track.trackNumber > 0 ? track.trackNumber : nil, disc: track.discNumber, source: "online"), artwork: artwork)
            guard let currentIndex = jobs.firstIndex(where: { $0.attempt == attempt && $0.state == .checking }) else { throw CancellationError() }
            let destination = root.appendingPathComponent(jobs[currentIndex].relativePath)
            try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
            if FileManager.default.fileExists(atPath: destination.path) {
                // A crash may have happened after the final move. Never overwrite an existing file.
                _ = try FLACInfo.read(destination, expectedDurationMs: jobs[currentIndex].track.durationMs)
            } else { try FileManager.default.moveItem(at: file, to: destination) }
            jobs[currentIndex].quality = info.label
            jobs[currentIndex].error = nil
            jobs[currentIndex].state = .complete
            progress[jobs[currentIndex].id] = nil
            persist()
            await onImported?()
        } catch { failed(attempt: attempt, error: error) }
        pump()
        finishBackgroundEventsIfReady()
    }

    func failed(attempt: String, error: Error) {
        guard let i = jobs.firstIndex(where: { $0.attempt == attempt && $0.state.active }) else { return }
        jobs[i].state = .failed
        jobs[i].error = error.localizedDescription
        progress[jobs[i].id] = nil
        persist()
        pump()
        finishBackgroundEventsIfReady()
    }

    func eventsFinished() {
        backgroundEventsFinished = true
        finishBackgroundEventsIfReady()
    }

    private func finishBackgroundEventsIfReady() {
        guard backgroundEventsFinished, !jobs.contains(where: { $0.state == .checking }) else { return }
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
