import XCTest
import Observation
@testable import Spitify

/// An in-memory library for store tests: likes, playlists, history and progress by song key.
@MainActor @Observable
private final class FakeLibrary: LibrarySyncHost {
    struct List: Equatable { var name: String; var songs: [String] }
    struct Progress: Equatable { var positionMs: Int64; var updatedAt: Int64 }

    var loaded = true
    var liked: Set<String> = []
    /// Playlists by global id (this fake uses the global id as its local id).
    var playlists: [String: List] = [:]
    /// Listens: history key → song key.
    var history: [String: String] = [:]
    var progress: [String: Progress] = [:]
    /// Songs this device can't find.
    @ObservationIgnored var missing: Set<String> = []
    @ObservationIgnored var tracks: [String: SharedTrack] = [:]
    @ObservationIgnored var onPlay: ((String, SyncObject) -> Void)?
    @ObservationIgnored var onDirty: ((Set<String>) -> Void)?
    @ObservationIgnored var massRemovals = Set<String>()
    var isPlaying: Bool { false }

    func track(_ title: String) -> String {
        let t = SharedTrack(title: title, artist: "Band", durationMs: 200_000)
        let key = LibrarySync.trackKey(t); var keyed = t; keyed.id = key; tracks[key] = keyed
        return key
    }
    func play(_ key: String, me: String, at: Int64) {
        let id = "\(me.prefix(8)):\(at):\(key)"
        history[id] = key
        var value = LibrarySync.trackValue(tracks[key]!); value["playedAt"] = .int(at)
        onPlay?(id, value)
    }

    func takeMassRemovals() -> Set<String> { defer { massRemovals = [] }; return massRemovals }
    func touch(_ group: String) {
        switch group {
        case LibrarySync.liked: _ = liked
        case LibrarySync.playlists: _ = playlists
        case LibrarySync.progress: _ = progress
        default: break
        }
    }

    func capture(_ groups: Set<String>, context: SyncContext) -> @Sendable () -> SyncCapture {
        let liked = liked, playlists = playlists, progress = progress, tracks = tracks, engine = context.engine
        return {
            var out = SyncCapture()
            if groups.contains(LibrarySync.liked) {
                out.collections[LibrarySync.liked] = Dictionary(liked.compactMap { k in tracks[k].map { (k, LibrarySync.trackValue($0)) } }, uniquingKeysWith: { a, _ in a })
            }
            if groups.contains(LibrarySync.progress) {
                out.collections[LibrarySync.progress] = progress.mapValues { ["positionMs": .int($0.positionMs), "durationMs": .int(3_600_000), "played": .bool(false), "_updatedAt": .int($0.updatedAt)] }
            }
            if groups.contains(LibrarySync.playlists) {
                out.collections[LibrarySync.playlists] = playlists.mapValues { ["name": .string($0.name), "description": .string("")] }
                for (gid, list) in playlists {
                    let songs = list.songs.compactMap { tracks[$0] }
                    let known = engine.present(LibrarySync.playlist(gid))
                    var items: [String: SyncObject] = [:], previous: Int64 = -1
                    for (key, t) in zip(LibrarySync.entryKeys(songs), songs) {
                        let pos = known[key]?["pos"]?.long.flatMap { $0 > previous ? $0 : nil } ?? previous + 1
                        previous = pos; items[key] = LibrarySync.entryValue(t, pos: Int(pos))
                    }
                    out.collections[LibrarySync.playlist(gid)] = items
                }
            }
            return out
        }
    }
    func learned(_ capture: SyncCapture) {}
    func historySeed(me: String) -> @Sendable () -> [(String, SyncObject)] { { [] } }

    func apply(_ changes: [SyncChange], context: SyncContext) async -> [SyncChange] {
        var done: [SyncChange] = []
        for change in changes.sorted(by: { ($0.collection == LibrarySync.playlists ? 0 : 1) < ($1.collection == LibrarySync.playlists ? 0 : 1) }) {
            let track = LibrarySync.track(change.value)
            if let track { tracks[track.id] = track }
            switch change.collection {
            case LibrarySync.liked:
                if change.present { guard !missing.contains(change.key) else { continue }; liked.insert(change.key) } else { liked.remove(change.key) }
            case LibrarySync.history:
                guard let track, !missing.contains(track.id) else { continue }
                history[change.key] = track.id
            case LibrarySync.progress:
                if change.present, let v = change.value {
                    let updated = v["_updatedAt"]?.long ?? 0
                    if let mine = progress[change.key], mine.updatedAt > updated { done.append(change); onDirty?([LibrarySync.progress]); continue }
                    progress[change.key] = Progress(positionMs: v["positionMs"]?.long ?? 0, updatedAt: updated)
                } else { progress[change.key] = nil }
            case LibrarySync.playlists:
                if change.present {
                    let isNew = playlists[change.key] == nil
                    playlists[change.key, default: List(name: "", songs: [])].name = change.value?["name"]?.string ?? ""
                    if isNew { rebuild(change.key, context) }
                }
                else { playlists[change.key] = nil; context.engine.forget(LibrarySync.playlist(change.key)) }
            default:
                guard change.collection.hasPrefix("playlist:") else { continue }
                let gid = String(change.collection.dropFirst(9))
                guard playlists[gid] != nil else { continue }
                rebuild(gid, context)
                if change.present && missing.contains(String(change.key[..<change.key.lastIndex(of: "#")!])) { continue }
            }
            done.append(change)
        }
        return done
    }

    private func rebuild(_ gid: String, _ context: SyncContext) {
        let collection = LibrarySync.playlist(gid)
        let entries = context.engine.present(collection).sorted { a, b in
            let pa = a.value["pos"]?.long ?? 0, pb = b.value["pos"]?.long ?? 0
            return pa != pb ? pa < pb : utf16Less(a.key, b.key)
        }
        var songs = entries.map { String($0.key[..<$0.key.lastIndex(of: "#")!]) }.filter { !missing.contains($0) }
        let local = playlists[gid]?.songs ?? []
        for (key, song) in zip(LibrarySync.entryKeys(local.compactMap { tracks[$0] }), local) where context.engine.item(collection, key) == nil { songs.append(song) }
        playlists[gid]?.songs = songs
    }

    func forgetDevice(_ id: String) {}
    func forgetFailures() {}
    func savedState() -> @Sendable () -> SyncJSON { { .null } }
    func loadState(_ json: SyncJSON) {}
}

/// Two `LibrarySyncStore`s talking through an in-memory relay that can be held.
@MainActor
final class LibrarySyncStoreTests: XCTestCase {
    private var files: [String] = []
    private var held: [() -> Void] = []
    private var holding = false
    private var linked = false

    override func tearDown() async throws {
        for name in files { try? FileManager.default.removeItem(at: Store.directory.appendingPathComponent(name + ".json")) }
    }

    private func makeStore(_ me: String) -> LibrarySyncStore {
        let name = "librarySync-test-" + UUID().uuidString; files.append(name)
        let store = LibrarySyncStore(storage: name)
        store.reportDelay = .milliseconds(20); store.sendDelay = .milliseconds(20); store.saveDelay = .milliseconds(20)
        return store
    }

    private func pair(_ phoneHost: FakeLibrary, _ macHost: FakeLibrary) async -> (LibrarySyncStore, LibrarySyncStore) {
        let phoneID = String(repeating: "a", count: 64), macID = String(repeating: "b", count: 64)
        let phone = makeStore(phoneID), mac = makeStore(macID)
        func transport(to other: @escaping () -> LibrarySyncStore, from me: String, peer: String) -> LibrarySyncStore.Transport {
            .init(send: { [weak self] packet, _, recipient, _ in
                guard let self, recipient == peer else { return }
                let wire = try JSONDecoder().decode(SocialPacket.self, from: JSONEncoder().encode(packet))
                let deliver = { other().receive(me, wire, true) }
                if self.holding { self.held.append(deliver) } else { deliver() }
            }, devices: { [weak self] in self?.linked == true ? [peer] : [] })
        }
        await phone.connect(me: phoneID, host: phoneHost, transport: transport(to: { mac }, from: phoneID, peer: macID))
        await mac.connect(me: macID, host: macHost, transport: transport(to: { phone }, from: macID, peer: phoneID))
        return (phone, mac)
    }

    private func link(_ stores: LibrarySyncStore...) async {
        linked = true
        for store in stores { await store.sendDigest() }
    }

    private func release() { holding = false; let pending = held; held = []; pending.forEach { $0() } }

    private func until(_ what: String, _ done: () -> Bool) async throws {
        for _ in 0..<200 {
            if done() { return }
            try await Task.sleep(for: .milliseconds(25))
        }
        XCTFail("Timed out: " + what)
    }

    func testLinkingCombinesLikesAndPlaylistsAndUnlikesFollow() async throws {
        let phoneLib = FakeLibrary(), macLib = FakeLibrary()
        let one = phoneLib.track("One"), two = phoneLib.track("Two"), three = macLib.track("Three"); _ = macLib.track("Two")
        phoneLib.liked = [one, two]; phoneLib.playlists["p1"] = .init(name: "Road trip", songs: [one, two])
        macLib.liked = [two, three]
        let (phone, mac) = await pair(phoneLib, macLib)
        try await until("first reports") { phone.engine.docNames().contains("playlists#" + String(LibrarySync.shardOf(LibrarySync.playlists, "p1"))) && !mac.engine.docNames().isEmpty }
        await link(phone, mac)
        try await until("union") { phoneLib.liked == [one, two, three] && macLib.liked == [one, two, three] && macLib.playlists["p1"] == .init(name: "Road trip", songs: [one, two]) }

        // Unliking on the phone removes it on the Mac, and it stays removed after the Mac reports again.
        phoneLib.liked.remove(one)
        try await until("unlike") { macLib.liked == [two, three] }
        macLib.liked.insert(macLib.track("Four"))
        try await until("later like") { phoneLib.liked.contains(LibrarySync.trackKey("Four", "Band")) }
        XCTAssertFalse(macLib.liked.contains(one)); XCTAssertFalse(phoneLib.liked.contains(one))
        await phone.settle(); await mac.settle()
        XCTAssertEqual(phone.syncing, 0); XCTAssertEqual(mac.syncing, 0)
    }

    func testConcurrentPlaylistAdditionsMerge() async throws {
        let phoneLib = FakeLibrary(), macLib = FakeLibrary()
        let one = phoneLib.track("One"), two = phoneLib.track("Two")
        phoneLib.playlists["p1"] = .init(name: "Mix", songs: [one, two])
        let (phone, mac) = await pair(phoneLib, macLib)
        await link(phone, mac)
        try await until("playlist arrives") { macLib.playlists["p1"]?.songs == [one, two] }
        holding = true
        let fromPhone = phoneLib.track("Phone add"), fromMac = macLib.track("Mac add")
        _ = phoneLib.track("Mac add"); _ = macLib.track("Phone add")
        phoneLib.playlists["p1"]?.songs.append(fromPhone)
        macLib.playlists["p1"]?.songs.append(fromMac)
        try await until("both reported") { !self.held.isEmpty && phone.engine.present(LibrarySync.playlist("p1")).count == 3 && mac.engine.present(LibrarySync.playlist("p1")).count == 3 }
        try await Task.sleep(for: .milliseconds(100))
        release()
        try await until("merged") { Set(phoneLib.playlists["p1"]?.songs ?? []) == [one, two, fromPhone, fromMac] && Set(macLib.playlists["p1"]?.songs ?? []) == [one, two, fromPhone, fromMac] }
        try await until("same order") { phoneLib.playlists["p1"] == macLib.playlists["p1"] }
        XCTAssertEqual(phoneLib.playlists["p1"]?.songs.prefix(2), [one, two])
    }

    func testRemoteHistoryAppearsWithItsTime() async throws {
        let phoneLib = FakeLibrary(), macLib = FakeLibrary()
        let song = phoneLib.track("Played")
        let (phone, mac) = await pair(phoneLib, macLib)
        await link(phone, mac)
        phoneLib.play(song, me: phone.me, at: 1_759_000_000_000)
        let key = "\(phone.me.prefix(8)):1759000000000:\(song)"
        try await until("history") { macLib.history[key] == song }
        XCTAssertEqual(mac.engine.present(LibrarySync.history)[key]?["playedAt"]?.long, 1_759_000_000_000)
    }

    func testAnUnloadedLibraryDoesntWipeTheOtherDevice() async throws {
        let phoneLib = FakeLibrary(), macLib = FakeLibrary()
        let keys = (1...40).map { phoneLib.track("Song \($0)") }
        phoneLib.liked = Set(keys)
        let (phone, mac) = await pair(phoneLib, macLib)
        await link(phone, mac)
        try await until("all likes") { macLib.liked.count == 40 }
        // Not loaded yet: nothing is reported at all.
        phoneLib.loaded = false; phoneLib.liked = []
        try await Task.sleep(for: .milliseconds(200)); await phone.settle()
        XCTAssertEqual(phone.engine.present(LibrarySync.liked).count, 40)
        // Loaded but still empty (half-read): the mass-removal guard keeps everything.
        phoneLib.loaded = true
        try await Task.sleep(for: .milliseconds(200)); await phone.settle(); await mac.settle()
        XCTAssertEqual(phone.engine.present(LibrarySync.liked).count, 40)
        XCTAssertEqual(macLib.liked.count, 40)
        // An explicit "clear all" does go through.
        phoneLib.massRemovals = [LibrarySync.liked]; phoneLib.liked = [keys[0]]
        try await until("cleared") { macLib.liked == [keys[0]] }
    }

    func testProgressNewerWinsEvenWhenItsReportIsOlder() async throws {
        let phoneLib = FakeLibrary(), macLib = FakeLibrary()
        macLib.progress["ep:show/1"] = .init(positionMs: 90_000, updatedAt: 3_000)
        let (phone, mac) = await pair(phoneLib, macLib)
        try await until("mac reported") { mac.engine.present(LibrarySync.progress).count == 1 }
        // The phone listened earlier but reports later (it was offline): its stamp is newer, its progress older.
        try await Task.sleep(for: .milliseconds(20))
        phoneLib.progress["ep:show/1"] = .init(positionMs: 60_000, updatedAt: 2_000)
        try await until("phone reported") { phone.engine.present(LibrarySync.progress).count == 1 }
        await link(phone, mac)
        try await until("newest progress everywhere") { phoneLib.progress["ep:show/1"]?.positionMs == 90_000 && macLib.progress["ep:show/1"]?.positionMs == 90_000 }
        try await until("winner agrees") { phone.engine.present(LibrarySync.progress)["ep:show/1"]?["positionMs"]?.long == 90_000 && mac.engine.digest() == phone.engine.digest() }
    }

    func testUnmatchedSongsWaitAndStateSurvivesARestart() async throws {
        let phoneLib = FakeLibrary(), macLib = FakeLibrary()
        let rare = phoneLib.track("Rare"), common = phoneLib.track("Common")
        phoneLib.liked = [rare, common]
        macLib.missing = [rare]
        let (phone, mac) = await pair(phoneLib, macLib)
        await link(phone, mac)
        try await until("common arrives") { macLib.liked == [common] }
        await mac.settle()
        try await until("one waiting") { mac.syncing == 1 }
        // Found later (e.g. the library changed): a retry applies it.
        macLib.missing = []
        mac.retry()
        try await until("rare arrives") { macLib.liked == [rare, common] }
        await mac.settle(); await mac.save()
        // A restart loads the same state: nothing to send again.
        let reopened = LibrarySyncStore(storage: files[1])
        reopened.reportDelay = .milliseconds(20)
        await reopened.connect(me: mac.me, host: macLib, transport: .init(send: { _, _, _, _ in XCTFail("nothing changed") }, devices: { [] }))
        await reopened.settle()
        XCTAssertEqual(reopened.engine.digest(), mac.engine.digest())
        _ = phone
    }
}
