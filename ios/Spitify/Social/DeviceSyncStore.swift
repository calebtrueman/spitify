import Foundation
import Observation
import UIKit

/// "Playing on <device>", remote control, "Listen here", continue where you left off, and device
/// linking (docs/device-sync.md). The rules live in `DeviceSyncState`; this store moves packets
/// through the friend relay and drives the player.
@MainActor @Observable
final class DeviceSyncStore {
    /// How packets leave and arrive; the app uses `SocialStore`, tests wire two relays together.
    struct Transport {
        var send: @MainActor (SocialPacket, _ logical: String, _ recipient: String?, _ expiresIn: Int64, _ extraTags: [[String]]) async throws -> Void
        var lookup: @MainActor (_ tag: String, _ timeout: Duration) async -> Void
        var found: @MainActor (_ tag: String, _ author: String) -> Bool
        var wanted: @MainActor (Bool) -> Void
    }
    enum Pairing: Equatable {
        case idle
        case showing(code: String, expiresAt: Int64)
        case searching
        case waiting(name: String)
        case failed(String)
        case linked(String)
    }
    static let platform = "ios"
    private static let day: Int64 = 24 * 60 * 60 * 1000

    private(set) var state = DeviceSyncState(me: "")
    private(set) var receivedAt: [String: Int64] = [:]
    private(set) var pairing: Pairing = .idle
    private(set) var name: String
    /// A short note shown at the top of the app, e.g. "Now playing on MacBook".
    private(set) var notice: String?
    var message: String?
    /// "Continue from <device>", offered when the app comes to the foreground.
    var continueOffer: DevicePlayback?

    @ObservationIgnored weak var app: AppModel?
    @ObservationIgnored private var transport: Transport?
    @ObservationIgnored private let storage: String
    @ObservationIgnored var lookupTimeout: Duration = .seconds(15)
    /// Tests observe obeyed commands here instead of driving a player.
    @ObservationIgnored var onCommand: ((DeviceCommand, String) -> Void)?
    @ObservationIgnored private var pendingOwner: String?
    @ObservationIgnored private var pendingCode: String?
    @ObservationIgnored private var pendingTag: String?
    @ObservationIgnored private var lookupTask: Task<Void, Never>?
    @ObservationIgnored private var pairingTimeout: Task<Void, Never>?
    @ObservationIgnored private var debounceTask: Task<Void, Never>?
    @ObservationIgnored private var heartbeatTask: Task<Void, Never>?
    @ObservationIgnored private var noticeTask: Task<Void, Never>?
    @ObservationIgnored private var lastSent: (signature: String, state: DevicePlayback, at: Int64)?
    @ObservationIgnored private var lastRevision: Int64 = 0
    @ObservationIgnored private var lastOffered: Int64 = 0
    @ObservationIgnored private var lastLocalChange: Int64 = 0
    @ObservationIgnored private var wasPlaying = false
    @ObservationIgnored private var obeyingUntil: Int64 = 0
    @ObservationIgnored private var offerUntil: Int64 = 0
    @ObservationIgnored private var tracks: [String: SharedTrack] = [:]

    private struct Saved: Codable {
        var devices: [LinkedDevice] = []
        var playback: [DevicePlayback] = []
        var receivedAt: [String: Int64] = [:]
        var lastOffered: Int64?
        var lastLocalChange: Int64?
        var lastRevision: Int64?
    }

    init(storage: String = "deviceSync", name: String? = nil) {
        self.storage = storage
        self.name = name ?? UserDefaults.standard.string(forKey: storage + "Name") ?? Self.defaultName
    }

    static var defaultName: String {
        let value = String(UIDevice.current.name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(60))
        return value.isEmpty ? "iPhone" : value
    }

    var me: String { state.me }
    var devices: [LinkedDevice] { state.devices }
    var linkRequest: PendingDeviceLink? { state.pendingLinks.first }
    /// The device to show as "Playing on …".
    var active: DevicePlayback? { state.me.isEmpty ? nil : state.active(receivedAt) }
    func playback(_ id: String) -> DevicePlayback? { state.playback[id] }
    func expectedPosition(_ playback: DevicePlayback) -> Int64 {
        DeviceSyncState.expectedPosition(playback, receivedAt: receivedAt[playback.device] ?? SocialRules.now)
    }

    // MARK: Setup

    func start(app: AppModel) {
        self.app = app
        let social = app.social
        guard !social.publicKey.isEmpty else { return }
        app.player.onStateChanged = { [weak self] in self?.playerChanged() }
        social.onDevicePacket = { [weak self] author, packet, encrypted in self?.receive(author, packet, encrypted) }
        connect(me: social.publicKey, transport: Transport(
            send: { [weak social] packet, logical, recipient, expiresIn, tags in try await social?.sendDevice(packet, logical: logical, to: recipient, expiresIn: expiresIn, extraTags: tags) },
            lookup: { [weak social] tag, timeout in await social?.lookup(tag, timeout: timeout) },
            found: { [weak social] tag, author in social?.lookupFound(tag, author: author) == true },
            wanted: { [weak social] wanted in social?.devicesWanted = wanted }))
    }

    func connect(me: String, transport: Transport) {
        self.transport = transport
        state = DeviceSyncState(me: me)
        if let saved = Store.load(Saved.self, storage) {
            state.load(DeviceSyncSnapshot(devices: saved.devices, playback: saved.playback))
            receivedAt = saved.receivedAt.filter { state.linked($0.key) }
            lastOffered = saved.lastOffered ?? 0; lastLocalChange = saved.lastLocalChange ?? 0; lastRevision = saved.lastRevision ?? 0
        }
        updateWanted()
    }

    private func persist() {
        let snapshot = state.snapshot
        Store.save(Saved(devices: snapshot.devices, playback: snapshot.playback, receivedAt: receivedAt, lastOffered: lastOffered, lastLocalChange: lastLocalChange, lastRevision: lastRevision), storage)
    }

    private func updateWanted() {
        let pairingNow: Bool
        switch pairing { case .showing, .searching, .waiting: pairingNow = true; default: pairingNow = false }
        transport?.wanted(!state.devices.isEmpty || pairingNow)
    }

    private func send<T: Encodable>(_ type: String, _ body: T, logical: String, to recipient: String?, expiresIn: Int64, extraTags: [[String]] = []) async throws {
        guard let transport else { throw MusicSourceError.message("Device sync isn't ready yet.") }
        try await transport.send(.make(type, body), logical, recipient, expiresIn, extraTags)
    }

    func rename(_ text: String) {
        let value = String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(60))
        guard !value.isEmpty, value != name else { return }
        name = value; UserDefaults.standard.set(value, forKey: storage + "Name")
        Task { await sendList(); await publish(force: true) }
    }

    // MARK: Pairing

    /// Device A: show a code and publish the offer other devices find by it.
    func showCode() async {
        guard !state.me.isEmpty else { message = "Device linking isn't ready yet."; return }
        lookupTask?.cancel(); pendingOwner = nil; pendingTag = nil
        let code = state.newCode()
        let expires = (state.codeIssuedAt() ?? SocialRules.now) + DeviceSyncState.codeLifetime
        pairing = .showing(code: code, expiresAt: expires); updateWanted()
        pairingTimeout?.cancel()
        pairingTimeout = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(DeviceSyncState.codeLifetime))
            guard !Task.isCancelled, let self, case .showing(let shown, _) = self.pairing, shown == code else { return }
            self.state.cancelCode(); self.pairing = .idle; self.updateWanted()
        }
        do {
            try await send("deviceCode", DeviceCodeOffer(owner: state.me, name: name, platform: Self.platform), logical: "deviceCode", to: nil,
                           expiresIn: DeviceSyncState.codeLifetime, extraTags: [["t", DeviceSyncState.lookupTag(code)]])
        } catch { message = error.localizedDescription }
    }

    func cancelPairing() {
        state.cancelCode(); lookupTask?.cancel(); pairingTimeout?.cancel()
        pendingOwner = nil; pendingTag = nil; pendingCode = nil
        pairing = .idle; updateWanted()
    }

    /// Device B: find the device showing this code and ask it to link.
    func enterCode(_ text: String) {
        let code = DeviceSyncState.normalizeCode(text)
        guard code.count == DeviceSyncState.tokenLength else { pairing = .failed("Codes have 8 letters and numbers."); return }
        guard !state.me.isEmpty else { pairing = .failed("Device linking isn't ready yet."); return }
        state.cancelCode(); lookupTask?.cancel(); pairingTimeout?.cancel()
        let tag = DeviceSyncState.lookupTag(code)
        pendingCode = code; pendingTag = tag; pendingOwner = nil
        pairing = .searching; updateWanted()
        let timeout = lookupTimeout
        lookupTask = Task { [weak self] in
            await self?.transport?.lookup(tag, timeout)
            guard !Task.isCancelled, let self, self.pairing == .searching, self.pendingTag == tag else { return }
            self.pendingTag = nil
            self.pairing = .failed("That code didn't match. Check it on your other device — codes last 10 minutes."); self.updateWanted()
        }
    }

    func allow(_ link: PendingDeviceLink) {
        guard state.approve(link.author) else { message = "You can link up to \(DeviceSyncState.maxDevices) devices."; return }
        pairingTimeout?.cancel()
        pairing = .linked(link.request.name); persist(); updateWanted()
        Task { await sendList(); await publish(force: true) }
    }

    func deny(_ link: PendingDeviceLink) { state.decline(link.author) }

    private func sendList() async {
        guard !state.devices.isEmpty else { return }
        let list = state.list(myName: name, myPlatform: Self.platform)
        for device in state.devices {
            do { try await send("deviceList", list, logical: "deviceList", to: device.id, expiresIn: 30 * Self.day) }
            catch { message = error.localizedDescription }
        }
    }

    /// Removes a linked device everywhere, including on that device.
    func remove(_ id: String) async {
        for device in state.devices { try? await send("deviceUnlink", DeviceUnlink(id: id), logical: "deviceUnlink:" + id, to: device.id, expiresIn: 30 * Self.day) }
        state.unlink(id); forgetMissing(); persist(); updateWanted()
    }

    /// Leaves the group: the other devices forget this one and this one forgets them.
    func leaveGroup() async {
        for device in state.devices { try? await send("deviceUnlink", DeviceUnlink(id: state.me), logical: "deviceUnlink:" + state.me, to: device.id, expiresIn: 30 * Self.day) }
        for device in state.devices { state.unlink(device.id) }
        forgetMissing(); persist(); updateWanted()
    }

    private func forgetMissing() {
        receivedAt = receivedAt.filter { state.linked($0.key) }
        if let offer = continueOffer, !state.linked(offer.device) { continueOffer = nil }
        if state.devices.isEmpty { heartbeatTask?.cancel(); debounceTask?.cancel(); lastSent = nil }
    }

    // MARK: Receiving

    func receive(_ author: String, _ packet: SocialPacket, _ encrypted: Bool) {
        guard !state.me.isEmpty else { return }
        do {
            switch packet.type {
            case "deviceCode":
                guard !encrypted, pairing == .searching, let tag = pendingTag, let code = pendingCode, author != state.me, transport?.found(tag, author) == true else { return }
                let offer = try packet.decode(DeviceCodeOffer.self)
                guard offer.valid(), offer.owner == author else { return }
                pendingOwner = author; lookupTask?.cancel()
                let offerName = offer.name.trimmingCharacters(in: .whitespacesAndNewlines)
                pairing = .waiting(name: offerName)
                Task {
                    do { try await send("deviceLinkRequest", DeviceLinkRequest(token: code, name: name, platform: Self.platform), logical: "deviceLink", to: author, expiresIn: DeviceSyncState.codeLifetime) }
                    catch { pairing = .failed(error.localizedDescription); updateWanted() }
                }
                pairingTimeout?.cancel()
                pairingTimeout = Task { [weak self] in
                    try? await Task.sleep(for: .milliseconds(DeviceSyncState.codeLifetime))
                    guard !Task.isCancelled, let self, self.pairing == .waiting(name: offerName) else { return }
                    self.pendingOwner = nil; self.pairing = .failed("\(offerName) didn't answer. Show a new code and try again."); self.updateWanted()
                }
            case "deviceLinkRequest":
                _ = state.receiveLink(try packet.decode(DeviceLinkRequest.self), author: author, encrypted: encrypted)
            case "deviceList":
                let fromOwner = author == pendingOwner
                guard state.acceptList(try packet.decode(DeviceList.self), author: author, encrypted: encrypted, pendingOwner: pendingOwner) else { return }
                persist()
                if fromOwner {
                    pendingOwner = nil; pendingTag = nil; pendingCode = nil; pairingTimeout?.cancel()
                    pairing = .linked(state.device(author)?.name ?? "your device"); updateWanted()
                    Task { await publish(force: true) }
                }
            case "deviceUnlink":
                let unlink = try packet.decode(DeviceUnlink.self)
                guard state.acceptUnlink(unlink.id, author: author, encrypted: encrypted, createdAt: unlink.createdAt ?? .max) else { return }
                forgetMissing(); persist(); updateWanted()
            case "devicePlayback":
                let incoming = try packet.decode(DevicePlayback.self)
                guard state.acceptPlayback(incoming, author: author, encrypted: encrypted) else { return }
                receivedAt[author] = DeviceSyncState.receivedTime(incoming); persist()
                if SocialRules.now <= offerUntil { checkContinue() }
            case "deviceCommand":
                let command = try packet.decode(DeviceCommand.self)
                guard state.acceptCommand(command, author: author, encrypted: encrypted) else { return }
                obey(command, from: author)
            default: break
            }
        } catch { /* An invalid packet changes nothing. */ }
    }

    private func obey(_ command: DeviceCommand, from author: String) {
        if let onCommand { onCommand(command, author); return }
        guard let player = app?.player else { return }
        obeyingUntil = SocialRules.now + 3_000
        switch command.action {
        case "play": player.resume()
        case "pause": player.pause()
        case "next": player.next()
        case "previous": player.previous()
        case "seek": player.seek(Double(command.positionMs) / 1000)
        case "handoff":
            if player.isPlaying { player.pause() }
            show("Now playing on " + (state.device(author)?.name ?? "your other device"))
        default: break
        }
    }

    private func show(_ text: String) {
        noticeTask?.cancel(); notice = text
        noticeTask = Task { [weak self] in try? await Task.sleep(for: .seconds(4)); if !Task.isCancelled { self?.notice = nil } }
    }

    // MARK: Remote control

    /// Sends `action` to a linked device and updates the shown state right away.
    func control(_ device: String, _ action: String, positionMs: Int64 = 0) async {
        guard state.linked(device) else { return }
        if var shown = state.playback[device] {
            shown.positionMs = expectedPosition(shown)
            switch action {
            case "play": shown.playing = true
            case "pause", "handoff": shown.playing = false
            case "seek": shown.positionMs = positionMs
            default: break
            }
            state.playback[device] = shown; receivedAt[device] = SocialRules.now
        }
        let command = DeviceCommand(id: DeviceCommand.newID(), target: device, action: action, positionMs: max(0, min(positionMs, 86_400_000)))
        do { try await send("deviceCommand", command, logical: "deviceCommand", to: device, expiresIn: 120_000) }
        catch { message = error.localizedDescription }
    }

    /// Takes over the remote device's queue here, starting where it is now.
    func listenHere(_ remote: DevicePlayback) async {
        guard let app, let current = remote.current else { return }
        guard !remote.spoken else { message = "Episodes and audiobooks continue on the device that played them."; return }
        continueOffer = nil
        let start = expectedPosition(remote)
        let song: Song
        do { song = try await SharedSongMatch.resolve(current, app: app) }
        catch { message = "“\(current.title)” couldn't be found here."; return }
        obeyingUntil = SocialRules.now + 3_000
        app.player.play([song], shuffle: false, source: remote.source ?? "From \(remote.name)", at: start)
        await control(remote.device, "handoff")
        let version = app.player.queueVersion
        for track in remote.queue.dropFirst(remote.currentIndex + 1) {
            guard let next = try? await SharedSongMatch.resolve(track, app: app) else { continue }
            guard app.player.queueVersion == version else { return }
            app.player.appendFromSource([next])
        }
    }

    // MARK: Continue where you left off

    /// Called when the app launches or comes to the foreground.
    func foreground() {
        offerUntil = SocialRules.now + 20_000
        checkContinue()
    }

    private func checkContinue() {
        guard let app, !app.player.isPlaying, app.rooms.room == nil, let latest = state.latest(receivedAt), latest.device != state.me,
              latest.revision != lastOffered, (receivedAt[latest.device] ?? 0) > lastLocalChange else { return }
        lastOffered = latest.revision; persist()
        continueOffer = latest
    }

    // MARK: Sharing this device's playback

    private func playerChanged() {
        guard let app else { return }
        let playing = app.player.isPlaying
        if playing && !wasPlaying { startedHere() }
        wasPlaying = playing
        guard !state.devices.isEmpty else { return }
        debounceTask?.cancel()
        debounceTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(DeviceSyncState.debounce))
            guard !Task.isCancelled else { return }
            await self?.publish()
        }
    }

    /// Spotify-style single active device: playback started here pauses the other device.
    private func startedHere() {
        continueOffer = nil
        guard SocialRules.now >= obeyingUntil, app?.rooms.room == nil, let other = state.active(receivedAt), other.device != state.me else { return }
        Task { await control(other.device, "handoff") }
    }

    private var roomListener: Bool { guard let rooms = app?.rooms else { return false }; return rooms.room != nil && !rooms.isHost }

    /// Sends this device's state when it changed (or on a heartbeat), to every linked device.
    func publish(force: Bool = false) async {
        guard let app, !state.devices.isEmpty, !roomListener, let playback = snapshot(app.player) else { heartbeatTask?.cancel(); return }
        let signature = [playback.current?.title ?? "", playback.current?.artist ?? "", String(playback.playing), String(playback.currentIndex),
                         playback.queue.map { $0.title + "|" + $0.artist }.joined(separator: "\n"), playback.source ?? "", String(playback.speed)].joined(separator: "\u{1}")
        let moved = lastSent.map { abs(DeviceSyncState.expectedPosition($0.state, receivedAt: $0.at) - playback.positionMs) > 2_000 } ?? true
        if force || lastSent?.signature != signature || moved {
            await sendState(playback)
            lastSent = (signature, playback, SocialRules.now)
        }
        heartbeatTask?.cancel()
        guard playback.playing else { return }
        heartbeatTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(DeviceSyncState.heartbeat))
            guard !Task.isCancelled else { return }
            await self?.publish(force: true)
        }
    }

    /// Sends `playback` (this device's state) to every linked device with a fresh revision.
    func sendState(_ playback: DevicePlayback) async {
        var playback = playback
        let now = SocialRules.now
        lastRevision = max(now, lastRevision + 1); playback.revision = lastRevision
        lastLocalChange = now; persist()
        for device in state.devices {
            do { try await send("devicePlayback", playback, logical: "devicePlayback", to: device.id, expiresIn: 14 * Self.day) }
            catch { message = error.localizedDescription }
        }
    }

    private func snapshot(_ player: Player) -> DevicePlayback? {
        let (window, index) = DevicePlayback.window(player.queue, current: player.index)
        var queue: [SharedTrack] = []
        var currentIndex = -1
        for (offset, song) in window.enumerated() {
            guard let track = track(song) else { continue }
            if offset == index { currentIndex = queue.count }
            queue.append(track)
        }
        if currentIndex < 0 { queue = [] }
        if tracks.count > 400 { tracks = [:] }
        let source = player.source.map { String($0.prefix(200)) }
        let result = DevicePlayback(device: state.me, name: name, platform: Self.platform, revision: max(1, lastRevision), observedAt: SocialRules.now,
                                   playing: player.isPlaying && currentIndex >= 0, positionMs: max(0, min(Int64(player.position * 1000), 86_400_000)),
                                   speed: max(0.25, min(player.speed, 4)), queue: queue, currentIndex: currentIndex, source: source?.isEmpty == true ? nil : source,
                                   spoken: player.current?.isSpoken == true)
        return result.valid() ? result : nil
    }

    /// One shared track per song, reused so the ids stay stable between states.
    private func track(_ song: Song) -> SharedTrack? {
        if let cached = tracks[song.id] { return cached }
        var track = SharedTrack.from(song)
        track.title = String(track.title.trimmingCharacters(in: .whitespacesAndNewlines).prefix(500))
        if track.title.isEmpty { track.title = "Unknown song" }
        track.artist = String(track.artist.prefix(500)); if track.artist.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { track.artist = "Unknown artist" }
        track.album = String(track.album.prefix(500)); track.durationMs = max(0, min(track.durationMs, 86_400_000))
        guard track.valid() else { return nil }
        tracks[song.id] = track
        return track
    }

    /// "Playing: Song" or "Last seen 3 hr ago" for a linked device.
    func status(_ id: String) -> String {
        if let shown = state.playback[id] {
            let heard = receivedAt[id] ?? 0
            if shown.playing, let song = shown.current, SocialRules.now - heard <= DeviceSyncState.fresh { return "Playing: " + song.title }
            if heard > 0 {
                let ago = RelativeDateTimeFormatter().localizedString(for: Date(timeIntervalSince1970: Double(heard) / 1000), relativeTo: Date())
                return "Last seen " + ago
            }
        }
        return "Linked"
    }
}
