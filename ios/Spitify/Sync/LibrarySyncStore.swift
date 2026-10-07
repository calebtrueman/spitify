import Foundation
import Network
import Observation

/// Shared by the store and its host while one operation runs in the store's lane.
struct SyncContext: @unchecked Sendable {
    var me: String
    var engine: LibrarySync
}

/// What a host builds off the main thread: collection → key → value, plus songs it learned keys for.
struct SyncCapture: Sendable {
    var collections: [String: [String: SyncObject]] = [:]
    var learned: [String: SharedTrack] = [:]
    /// Playlist covers encoded during a report, by local playlist id.
    var images: [String: SyncPlaylistImage] = [:]
}

/// The app side of library sync: what each collection holds and how remote changes are made locally.
/// The app uses `AppLibrarySyncHost`; tests use an in-memory host.
@MainActor protocol LibrarySyncHost: AnyObject {
    /// True once the library has loaded, so an empty one isn't read as "remove everything".
    var loaded: Bool { get }
    /// Long reports (progress) wait while something plays.
    var isPlaying: Bool { get }
    /// A finished or skipped local listen, as a history key and value.
    var onPlay: ((String, SyncObject) -> Void)? { get set }
    /// Groups to report again although nothing changed here (e.g. local progress newer than a remote one).
    var onDirty: ((Set<String>) -> Void)? { get set }
    /// Collections the user just cleared on purpose (e.g. "Bring back deleted mixes"), consumed by the next report.
    func takeMassRemovals() -> Set<String>
    /// Reads what `group` depends on, so the store can observe it. Groups are the fixed collection
    /// names, plus "stats" (this device's counts) and "playlists" (lists and their songs).
    func touch(_ group: String)
    /// Cheap reads on the main actor; the returned work builds the values off it.
    func capture(_ groups: Set<String>, context: SyncContext) -> @Sendable () -> SyncCapture
    func learned(_ capture: SyncCapture)
    /// Recent local listens to seed history the first time this device syncs.
    func historySeed(me: String) -> @Sendable () -> [(String, SyncObject)]
    /// Makes remote changes true locally; returns the ones done (or already true).
    func apply(_ changes: [SyncChange], context: SyncContext) async -> [SyncChange]
    /// Songs that couldn't be matched may be searched for again (network back, hourly).
    func forgetFailures()
    /// Drops what a removed device contributed (its play counts).
    func forgetDevice(_ id: String)
    /// The extra state the host keeps in the sync file (playlist id map, matched songs…).
    func savedState() -> @Sendable () -> SyncJSON
    func loadState(_ json: SyncJSON)
}

/// Keeps the library the same on every linked device (docs/library-sync.md). Rides on the device-sync
/// group and relay: `syncDoc` and `syncDigest` packets to every linked device, end-to-end encrypted.
@MainActor @Observable
final class LibrarySyncStore {
    struct Transport {
        var send: @MainActor (SocialPacket, _ logical: String, _ recipient: String, _ expiresIn: Int64) async throws -> Void
        var devices: @MainActor () -> [String]
    }

    static let groups = [LibrarySync.liked, LibrarySync.savedTracks, LibrarySync.followedArtists, LibrarySync.hiddenSongs, LibrarySync.hiddenArtists,
                         LibrarySync.hiddenMixes, LibrarySync.podcasts, LibrarySync.progress, LibrarySync.playlists, "stats", LibrarySync.profile,
                         LibrarySync.friends, LibrarySync.savedShared, LibrarySync.settings]
    private static let day: Int64 = 24 * 60 * 60 * 1000
    /// Most recent listens sent the first time; older history stays on the device that has it.
    static let historySeedLimit = 2_000

    /// Remote items not made here yet (songs still being matched, shows loading…).
    private(set) var syncing = 0
    private(set) var lastSync: Int64?
    private(set) var started = false

    @ObservationIgnored private(set) var engine = LibrarySync(me: "")
    @ObservationIgnored private weak var host: LibrarySyncHost?
    @ObservationIgnored private var transport: Transport?
    @ObservationIgnored private let storage: String
    @ObservationIgnored var reportDelay: Duration = .seconds(2)
    @ObservationIgnored var sendDelay: Duration = .seconds(3)
    @ObservationIgnored var saveDelay: Duration = .seconds(2)
    /// While playing, progress is reported at most this often.
    @ObservationIgnored var progressInterval: Int64 = 60_000
    @ObservationIgnored private var lane: Task<Void, Never>?
    @ObservationIgnored private var dirty = Set<String>()
    @ObservationIgnored private var reportTask: Task<Void, Never>?
    @ObservationIgnored private var outgoing = Set<String>()
    @ObservationIgnored private var sendTask: Task<Void, Never>?
    @ObservationIgnored private var saveTask: Task<Void, Never>?
    @ObservationIgnored private var timers: [Task<Void, Never>] = []
    @ObservationIgnored private var lastProgressReport: Int64 = 0
    @ObservationIgnored private var lastDigest: Int64 = 0
    @ObservationIgnored private var answered: [String: Int64] = [:]
    @ObservationIgnored private var monitor: NWPathMonitor?
    @ObservationIgnored private var online = true

    init(storage: String = "librarySync") { self.storage = storage }

    var me: String { engine.me }
    var linked: Bool { !(transport?.devices().isEmpty ?? true) }
    private var file: URL { Store.directory.appendingPathComponent(storage + ".json") }

    // MARK: Setup

    /// The app: rides on `SocialStore`'s relay and the device-sync group.
    func start(app: AppModel, host: LibrarySyncHost) {
        let social = app.social, devices = app.devices
        guard !social.publicKey.isEmpty else { return }
        social.onSyncPacket = { [weak self] author, packet, encrypted in self?.receive(author, packet, encrypted) }
        devices.onLinked = { [weak self] in self?.linkedDevice() }
        devices.onUnlinked = { [weak self] id in self?.unlinked(id) }
        let transport = Transport(
            send: { [weak social] packet, logical, recipient, expiresIn in try await social?.sendDevice(packet, logical: logical, to: recipient, expiresIn: expiresIn) },
            devices: { [weak devices] in devices?.devices.map(\.id) ?? [] })
        Task { await connect(me: social.publicKey, host: host, transport: transport) }
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            let up = path.status == .satisfied
            Task { @MainActor in
                guard let self else { return }
                let back = up && !self.online; self.online = up
                if back { self.host?.forgetFailures(); self.retry() }
            }
        }
        monitor.start(queue: .global(qos: .utility)); self.monitor = monitor
        timers.append(Task { [weak self] in
            while !Task.isCancelled { try? await Task.sleep(for: .seconds(3600)); self?.host?.forgetFailures(); self?.retry() }
        })
        timers.append(Task { [weak self] in
            while !Task.isCancelled { try? await Task.sleep(for: .seconds(6 * 3600)); await self?.sendDigest() }
        })
    }

    /// Loads the saved state, observes the host and sends a first digest.
    func connect(me: String, host: LibrarySyncHost, transport: Transport) async {
        self.host = host; self.transport = transport
        engine = LibrarySync(me: me)
        let file = self.file
        let saved = await Task.detached(priority: .utility) { try? Data(contentsOf: file) }.value.flatMap(SyncJSON.parse)
        let engine = self.engine
        let isNew = saved == nil
        if let saved {
            await Task.detached(priority: .utility) { engine.load(saved["engine"] ?? .null); engine.trim() }.value
            host.loadState(saved["host"] ?? .null)
        }
        started = true
        host.onPlay = { [weak self] key, value in self?.played(key, value) }
        host.onDirty = { [weak self] groups in self?.markDirty(groups) }
        for group in Self.groups { observe(group) }
        observeLoaded(); observeLibrary()
        if isNew { seedHistory() }
        markDirty(Set(Self.groups))
        await sendDigest()
        enqueue { [weak self] in await self?.updateSyncing() }
    }

    private func observe(_ group: String) {
        guard let host else { return }
        withObservationTracking { host.touch(group) } onChange: { [weak self] in
            Task { @MainActor in self?.markDirty([group]); self?.observe(group) }
        }
    }

    /// Reports wait for the library to load.
    private func observeLoaded() {
        guard let host else { return }
        withObservationTracking { _ = host.loaded } onChange: { [weak self] in
            Task { @MainActor in guard let self else { return }; self.markDirty(Set(Self.groups)); self.retry(); self.observeLoaded() }
        }
    }

    /// Library changes retry what couldn't be matched.
    private func observeLibrary() {
        guard let host else { return }
        withObservationTracking { host.touch("library") } onChange: { [weak self] in
            Task { @MainActor in guard let self else { return }; self.retry(); self.observeLibrary() }
        }
    }

    private func enqueue(_ work: @escaping @MainActor () async -> Void) {
        let previous = lane
        lane = Task { await previous?.value; await work() }
    }

    /// Waits for everything queued so far (tests).
    func settle() async { while let current = lane { await current.value; if lane == current { break } } }

    // MARK: Reporting

    private func markDirty(_ groups: Set<String>) {
        dirty.formUnion(groups)
        reportTask?.cancel()
        let delay = reportDelay
        reportTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            self?.flushReports()
        }
    }

    private func flushReports() {
        guard let host, host.loaded, started else { return }
        var groups = dirty
        // Progress at most once a minute while playing; at once on pause, stop and finish.
        if groups.contains(LibrarySync.progress), host.isPlaying, SocialRules.now - lastProgressReport < progressInterval {
            groups.remove(LibrarySync.progress)
            let wait = progressInterval - (SocialRules.now - lastProgressReport)
            Task { [weak self] in try? await Task.sleep(for: .milliseconds(wait)); self?.markDirty([LibrarySync.progress]) }
        }
        dirty.subtract(groups)
        guard !groups.isEmpty else { return }
        if groups.contains(LibrarySync.progress) { lastProgressReport = SocialRules.now }
        enqueue { [weak self] in await self?.report(groups) }
    }

    private func report(_ groups: Set<String>) async {
        guard let host, host.loaded else { dirty.formUnion(groups); return }
        let context = SyncContext(me: engine.me, engine: engine)
        let work = host.capture(groups, context: context)
        let mass = host.takeMassRemovals()
        let engine = self.engine
        let (capture, changed) = await Task.detached(priority: .utility) { () -> (SyncCapture, Set<String>) in
            let capture = work()
            var changed = Set<String>()
            for (collection, current) in capture.collections {
                changed.formUnion(engine.report(collection, current, allowMassRemoval: mass.contains(collection)))
            }
            // A deleted playlist's songs go with it.
            if capture.collections[LibrarySync.playlists] != nil {
                for name in engine.collections() where name.hasPrefix("playlist:") {
                    let id = String(name.dropFirst(9))
                    if engine.item(LibrarySync.playlists, id)?.present == false { engine.forget(name) }
                }
            }
            return (capture, changed)
        }.value
        host.learned(capture)
        queueSend(changed)
        scheduleSave()
        await updateSyncing()
    }

    private func played(_ key: String, _ value: SyncObject) {
        enqueue { [weak self] in
            guard let self else { return }
            self.queueSend(self.engine.add(LibrarySync.history, key, value)); self.scheduleSave()
        }
    }

    private func seedHistory() {
        guard let host else { return }
        let work = host.historySeed(me: engine.me)
        enqueue { [weak self] in
            guard let self else { return }
            let engine = self.engine
            let changed = await Task.detached(priority: .utility) { () -> Set<String> in
                var changed = Set<String>()
                for (key, value) in work() { changed.formUnion(engine.add(LibrarySync.history, key, value)) }
                return changed
            }.value
            self.queueSend(changed); self.scheduleSave()
        }
    }

    // MARK: Sending

    private func queueSend(_ docs: Set<String>) {
        guard !docs.isEmpty else { return }
        outgoing.formUnion(docs)
        sendTask?.cancel()
        let delay = sendDelay
        sendTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled, let self else { return }
            let names = self.outgoing; self.outgoing = []
            self.enqueue { [weak self] in await self?.send(names, to: nil) }
        }
    }

    /// Sends documents to one device, or to every linked device.
    private func send(_ names: Set<String>, to device: String?) async {
        guard let transport else { return }
        let targets = device.map { [$0] } ?? transport.devices()
        guard !targets.isEmpty, !names.isEmpty else { return }
        let engine = self.engine
        let packets = await Task.detached(priority: .utility) { names.sorted().compactMap { name in (try? SocialPacket.make("syncDoc", engine.doc(name))).map { (name, $0) } } }.value
        for target in targets {
            for (name, packet) in packets { try? await transport.send(packet, "sync:" + name, target, 60 * Self.day) }
        }
    }

    func sendDigest() async {
        guard let transport, started, !transport.devices().isEmpty else { return }
        lastDigest = SocialRules.now
        enqueue { [weak self] in
            guard let self else { return }
            let engine = self.engine
            let digest = await Task.detached(priority: .utility) { engine.digest() }.value
            guard let packet = try? SocialPacket.make("syncDigest", SyncDigest(docs: digest)) else { return }
            for device in transport.devices() { try? await transport.send(packet, "syncDigest", device, 2 * Self.day) }
        }
    }

    /// The app came to the foreground: a digest at most every 10 minutes, and another try at matching.
    func foreground() {
        guard started else { return }
        if SocialRules.now - lastDigest >= 10 * 60_000 { Task { await sendDigest() } }
    }

    private func linkedDevice() {
        guard started else { return }
        Task { await sendDigest() }
    }

    private func unlinked(_ id: String) {
        guard started else { return }
        host?.forgetDevice(id)
        enqueue { [weak self] in self?.engine.forget(LibrarySync.stats(id)); self?.scheduleSave() }
    }

    // MARK: Receiving

    func receive(_ author: String, _ packet: SocialPacket, _ encrypted: Bool) {
        guard started, encrypted, let transport, author != engine.me, transport.devices().contains(author) else { return }
        switch packet.type {
        case "syncDigest":
            guard let theirs = try? packet.decode(SyncDigest.self) else { return }
            enqueue { [weak self] in
                guard let self else { return }
                let engine = self.engine
                let mine = await Task.detached(priority: .utility) { engine.digest() }.value
                let differ = Set(mine.filter { theirs.docs[$0.key] != $0.value }.keys)
                await self.send(differ, to: author)
                // They hold documents we lack or have differently: our digest lets them send theirs.
                if theirs.docs.contains(where: { mine[$0.key] != $0.value }), SocialRules.now - (self.answered[author] ?? 0) >= 60_000,
                   let packet = try? SocialPacket.make("syncDigest", SyncDigest(docs: mine)) {
                    self.answered[author] = SocialRules.now
                    try? await transport.send(packet, "syncDigest", author, 2 * Self.day)
                }
                self.lastSync = SocialRules.now
            }
        case "syncDoc":
            guard let doc = SyncJSON.parse(packet.body) else { return }
            enqueue { [weak self] in
                guard let self else { return }
                let engine = self.engine
                let changes = await Task.detached(priority: .utility) { engine.receive(doc) }.value
                await self.apply(changes)
                self.lastSync = SocialRules.now
                self.scheduleSave()
            }
        default: break
        }
    }

    /// `always`: let the host refresh what it derives from the library even with nothing to apply.
    private func apply(_ changes: [SyncChange], always: Bool = false) async {
        guard let host, !changes.isEmpty || always else { await updateSyncing(); return }
        let done = await host.apply(changes, context: SyncContext(me: engine.me, engine: engine))
        // Mark them before anything reports again, so a report can't undo them.
        for change in done { engine.applied(change) }
        await updateSyncing()
    }

    /// Tries everything still waiting again: after library changes, when the network returns, and hourly.
    func retry() {
        guard started else { return }
        enqueue { [weak self] in
            guard let self, let host = self.host, host.loaded else { return }
            let engine = self.engine
            let waiting = await Task.detached(priority: .utility) { engine.collections().sorted().flatMap { engine.pending($0) } }.value
            await self.apply(waiting, always: true)
            self.scheduleSave()
        }
    }

    /// Runs in the lane, like everything that touches the engine.
    private func updateSyncing() async {
        let engine = self.engine
        syncing = await Task.detached(priority: .utility) {
            engine.collections().filter { $0 != LibrarySync.settings && !$0.hasPrefix("stats:") }.reduce(0) { $0 + engine.pending($1).count }
        }.value
    }

    // MARK: Saving

    private func scheduleSave() {
        guard saveTask == nil else { return }
        let delay = saveDelay
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard let self else { return }
            self.saveTask = nil
            self.enqueue { [weak self] in await self?.save() }
        }
    }

    func save() async {
        guard let host, started else { return }
        let engine = self.engine, extra = host.savedState(), file = self.file
        await Task.detached(priority: .utility) {
            let json = SyncJSON.object(["engine": engine.json(), "host": extra()])
            try? json.data.write(to: file, options: .atomic)
        }.value
    }
}

struct SyncDigest: Codable { var docs: [String: String] }

extension SyncJSON {
    subscript(_ key: String) -> SyncJSON? { object?[key] }
}
