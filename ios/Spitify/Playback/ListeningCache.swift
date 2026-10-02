import AVFoundation
import CryptoKit
import UniformTypeIdentifiers

/// All file operations and resource requests use one serial queue. Only finished files survive a stream.
final class ListeningCache {
    static let queue = DispatchQueue(label: "spitify.listening-cache")
    static let limit: Int64 = 1024 * 1024 * 1024
    static var active: Set<String> = []
    static var clearWhenFinished: Set<String> = []
    static let directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("Listening", isDirectory: true)
    static func key(_ track: OnlineTrack) -> String { SHA256.hash(data: Data(track.id.utf8)).map { String(format: "%02x", $0) }.joined() }
    static func file(_ key: String) -> URL { directory.appendingPathComponent(key + ".audio") }
    static func clear() { queue.async {
        clearWhenFinished.formUnion(active)
        for url in files() where !active.contains(url.deletingPathExtension().lastPathComponent) { try? FileManager.default.removeItem(at: url) }
    } }
    static func files() -> [URL] { (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey])) ?? [] }
    static func trim(reserving bytes: Int64 = 0) {
        let entries = files().map { ($0, try? $0.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])) }
        var size = entries.reduce(Int64(0)) { $0 + Int64($1.1?.fileSize ?? 0) } + bytes
        for (url, values) in entries.sorted(by: { ($0.1?.contentModificationDate ?? .distantPast) < ($1.1?.contentModificationDate ?? .distantPast) }) {
            guard !active.contains(url.deletingPathExtension().lastPathComponent) else { continue }
            if url.pathExtension == "partial" || size > limit {
                try? FileManager.default.removeItem(at: url); size -= Int64(values?.fileSize ?? 0)
            }
        }
    }
}

/// AVPlayer reads each available byte while URLSession writes the next bytes. It never waits for a full download to start.
final class MusicResourceLoader: NSObject, AVAssetResourceLoaderDelegate, URLSessionDataDelegate, @unchecked Sendable {
    private let sourceURL: (OnlineTrack) -> URL?
    private let alternate: (OnlineTrack) async throws -> OnlineTrack?
    private let original: OnlineTrack
    private var candidate: OnlineTrack
    private let key: String
    private var requests: [AVAssetResourceLoadingRequest] = []
    private var session: URLSession?
    private var transfer: URLSessionDataTask?
    private var lookup: Task<Void, Never>?
    private var file: FileHandle?
    private var size: Int64 = 0
    private var expected: Int64 = 0
    private var complete = false
    private var cancelled = false
    private var started = false
    private var attempts = 0
    private var contentType = "public.audio"
    private var failure: Error?
    private var headerChecked = false
    private var header = Data()
    private var readURL: URL
    private var lastTrim: Int64 = 0
    init(track: OnlineTrack,
         sourceURL: @escaping (OnlineTrack) -> URL? = { track in track.audioURL.flatMap { AudioFallback.validAudioURL($0) ? URL(string: $0) : nil } ?? (try? MonochromeClient.audioURL(track.id)) },
         alternate: @escaping (OnlineTrack) async throws -> OnlineTrack? = AudioFallback.resolve) {
        self.sourceURL = sourceURL; self.alternate = alternate
        original = track; candidate = track; key = ListeningCache.key(track)
        readURL = ListeningCache.file(ListeningCache.key(track))
        super.init()
    }
    func asset() -> AVURLAsset {
        let asset = AVURLAsset(url: URL(string: "spitify-cache://music/\(key)")!)
        asset.resourceLoader.setDelegate(self, queue: ListeningCache.queue)
        return asset
    }
    func stop(discard: Bool = false) { ListeningCache.queue.async { [self] in
        if discard { ListeningCache.clearWhenFinished.insert(key) }
        cancelled = true; lookup?.cancel(); transfer?.cancel(); session?.invalidateAndCancel()
        requests.forEach { $0.finishLoading(with: URLError(.cancelled)) }; requests.removeAll()
        try? file?.close(); file = nil
        if started && !complete { try? FileManager.default.removeItem(at: readURL) }
        ListeningCache.active.remove(key)
        if ListeningCache.clearWhenFinished.remove(key) != nil { try? FileManager.default.removeItem(at: ListeningCache.file(key)) }
        ListeningCache.trim()
    } }
    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource request: AVAssetResourceLoadingRequest) -> Bool {
        requests.append(request)
        if !started { started = true; start() }
        serve()
        return true
    }
    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel request: AVAssetResourceLoadingRequest) { requests.removeAll { $0 === request } }
    private func start() {
        ListeningCache.active.insert(key)
        try? FileManager.default.createDirectory(at: ListeningCache.directory, withIntermediateDirectories: true)
        if let values = try? readURL.resourceValues(forKeys: [.fileSizeKey]), let length = values.fileSize, length > 0 {
            size = Int64(length); expected = size; complete = true
            contentType = detectType(readURL)
            try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: readURL.path)
            return
        }
        readURL = ListeningCache.directory.appendingPathComponent(key + ".partial")
        ListeningCache.trim()
        FileManager.default.createFile(atPath: readURL.path, contents: nil)
        do { file = try FileHandle(forWritingTo: readURL); fetch() } catch { fail(error) }
    }
    private func fetch() {
        guard !cancelled else { return }
        attempts += 1; expected = 0; headerChecked = false; header.removeAll()
        guard let url = sourceURL(candidate) else { fail(URLError(.badURL)); return }
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 12; config.timeoutIntervalForResource = 600
        let delegateQueue = OperationQueue(); delegateQueue.maxConcurrentOperationCount = 1; delegateQueue.underlyingQueue = ListeningCache.queue
        session = URLSession(configuration: config, delegate: self, delegateQueue: delegateQueue)
        var request = URLRequest(url: url); request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        transfer = session!.dataTask(with: request); transfer!.resume()
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard !cancelled, let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            completionHandler(.cancel)
            failOrFindCopy(MusicSourceError.http((response as? HTTPURLResponse)?.statusCode ?? 0)); return
        }
        expected = response.expectedContentLength > 0 ? response.expectedContentLength : (candidate.audioByteCount ?? -1)
        guard expected <= ListeningCache.limit else { completionHandler(.cancel); fail(MusicSourceError.message("This song is too large to cache. Download it to listen.")); return }
        contentType = (response.mimeType?.hasPrefix("audio/") == true ? UTType(mimeType: response.mimeType!)?.identifier : nil) ?? UTType(filenameExtension: candidate.audioExtension ?? "flac")?.identifier ?? "public.audio"
        completionHandler(.allow); serve()
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard dataTask === transfer, !cancelled, failure == nil else { return }
        var bytes = data
        if !headerChecked {
            header.append(data)
            guard header.count >= 12 else { return }
            guard let type = Self.audioType(header) else {
                header.removeAll(); failOrFindCopy(MusicSourceError.message("The source did not return audio.")); return
            }
            contentType = type; headerChecked = true; bytes = header; header.removeAll(keepingCapacity: false)
        }
        do {
            guard size + Int64(bytes.count) <= ListeningCache.limit else { throw MusicSourceError.message("This song is too large to cache. Download it to listen.") }
            try file?.write(contentsOf: bytes); size += Int64(bytes.count)
            if size - lastTrim >= 4 * 1024 * 1024 { ListeningCache.trim(reserving: 4 * 1024 * 1024); lastTrim = size }
            serve()
        } catch { fail(error); transfer?.cancel() }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard task === transfer, !cancelled, failure == nil else { return }
        if let error { if (error as NSError).code != NSURLErrorCancelled { failOrFindCopy(error) }; return }
        guard size > 0 else { failOrFindCopy(MusicSourceError.message("The source did not return audio.")); return }
        guard expected <= 0 || size == expected else { fail(URLError(.networkConnectionLost)); return }
        do {
            try file?.close(); file = nil
            let destination = ListeningCache.file(key)
            try? FileManager.default.removeItem(at: destination)
            try FileManager.default.moveItem(at: readURL, to: destination)
            readURL = destination; expected = size; complete = true; contentType = detectType(destination)
            serve(); session.finishTasksAndInvalidate(); ListeningCache.trim()
        } catch { fail(error) }
    }
    private func failOrFindCopy(_ error: Error) {
        guard size == 0, attempts < 4, lookup == nil else { fail(error); return }
        let previous = candidate
        let alternate = alternate
        session?.invalidateAndCancel(); transfer = nil
        lookup = Task { [weak self] in
            let next = try? await alternate(previous)
            guard !Task.isCancelled else { return }
            ListeningCache.queue.async { [weak self] in
                guard let self, !self.cancelled else { return }
                self.lookup = nil
                if let next { self.candidate = next; self.fetch() } else { self.fail(error) }
            }
        }
    }
    private func fail(_ error: Error) { failure = error; serve() }
    private func serve() {
        for request in requests {
            if let failure { request.finishLoading(with: failure); continue }
            guard expected > 0, headerChecked || complete else { continue }
            if let info = request.contentInformationRequest {
                info.contentType = contentType; info.contentLength = expected; info.isByteRangeAccessSupported = true
            }
            guard let data = request.dataRequest else { request.finishLoading(); continue }
            let offset = data.currentOffset
            let end = data.requestsAllDataToEndOfResource ? expected : min(expected, data.requestedOffset + Int64(data.requestedLength))
            if offset < size, offset < end {
                do {
                    let reader = try FileHandle(forReadingFrom: readURL); defer { try? reader.close() }
                    try reader.seek(toOffset: UInt64(offset))
                    let available = min(size, end)
                    while data.currentOffset < available && !request.isCancelled {
                        let count = Int(min(256 * 1024, available - data.currentOffset))
                        guard let bytes = try reader.read(upToCount: count), !bytes.isEmpty else { break }
                        data.respond(with: bytes)
                    }
                } catch { request.finishLoading(with: error); continue }
            }
            if data.currentOffset >= end { request.finishLoading() }
        }
        requests.removeAll { $0.isFinished || $0.isCancelled }
    }
    private func detectType(_ url: URL) -> String {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return contentType }
        defer { try? handle.close() }
        let bytes = (try? handle.read(upToCount: 12)) ?? Data()
        return Self.audioType(bytes) ?? contentType
    }
    static func audioType(_ bytes: Data) -> String? {
        if bytes.starts(with: Data("fLaC".utf8)) { return UTType(filenameExtension: "flac")?.identifier ?? "org.xiph.flac" }
        if bytes.count >= 8, bytes.subdata(in: 4..<8) == Data("ftyp".utf8) { return UTType.mpeg4Audio.identifier }
        if bytes.starts(with: Data("ID3".utf8)) || (bytes.count >= 2 && bytes[0] == 0xff && bytes[1] & 0xe0 == 0xe0) { return UTType.mp3.identifier }
        return nil
    }
}
