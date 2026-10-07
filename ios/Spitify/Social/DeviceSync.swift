import Foundation

/// Your own devices, linked once with a short code, share what they're playing ("Playing on
/// MacBook") and can continue each other's playback. Each device keeps its own friend key; the
/// packets travel NIP-44 encrypted to each linked device over the same relays as friend shares.
/// Mirrors core/.../data/social/DeviceSync.kt with identical JSON (docs/device-sync.md).
struct LinkedDevice: Codable, Hashable, Identifiable {
    static let platforms = ["android", "ios", "macos", "windows", "linux"]
    var id: String
    var name: String
    var platform: String
    var linkedAt: Int64 = SocialRules.now
    func valid() -> Bool { SocialRules.key(id) && (1...60).contains(name.trimmingCharacters(in: .whitespacesAndNewlines).count) && Self.platforms.contains(platform) }
    init(id: String, name: String, platform: String, linkedAt: Int64 = SocialRules.now) { self.id = id; self.name = name; self.platform = platform; self.linkedAt = linkedAt }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id); name = try c.decode(String.self, forKey: .name); platform = try c.decode(String.self, forKey: .platform)
        linkedAt = try c.decodeIfPresent(Int64.self, forKey: .linkedAt) ?? SocialRules.now
    }
}

/// What a device is playing: the queue around the current song and where it is. Packet type "devicePlayback".
struct DevicePlayback: Codable, Hashable {
    static let maxBefore = 10
    static let maxAfter = 40
    var device: String
    var name: String
    var platform: String
    /// Increases with every change; also the sender's clock at the change.
    var revision: Int64
    /// When `positionMs` was read (sender's clock).
    var observedAt: Int64
    var playing: Bool
    var positionMs: Int64
    var speed: Float = 1
    /// Up to `maxBefore` songs before the current one, the current one, then up to `maxAfter` after.
    var queue: [SharedTrack] = []
    var currentIndex = -1
    /// "Playing from" label, e.g. "Liked Songs".
    var source: String? = nil
    /// Episodes and audiobooks: other devices only show these.
    var spoken = false

    private enum CodingKeys: String, CodingKey { case device, name, platform, revision, observedAt, playing, positionMs, speed, queue, currentIndex, source, spoken }

    var current: SharedTrack? { queue.indices.contains(currentIndex) ? queue[currentIndex] : nil }
    func valid() -> Bool {
        SocialRules.key(device) && (1...60).contains(name.count) && LinkedDevice.platforms.contains(platform) && revision > 0 && observedAt > 0
        && (0...86_400_000).contains(positionMs) && speed.isFinite && (0.25...4).contains(speed) && queue.count <= Self.maxBefore + 1 + Self.maxAfter
        && queue.allSatisfy { $0.valid() } && (queue.isEmpty && currentIndex == -1 || queue.indices.contains(currentIndex)) && (source?.count ?? 0) <= 200
    }
    /// Cuts `queue` to the window that's sent; returns the window and the current song's index in it.
    static func window<T>(_ queue: [T], current: Int) -> ([T], Int) {
        guard queue.indices.contains(current) else { return ([], -1) }
        let from = max(0, current - maxBefore), to = min(queue.count, current + maxAfter + 1)
        return (Array(queue[from..<to]), current - from)
    }

    init(device: String, name: String, platform: String, revision: Int64, observedAt: Int64, playing: Bool, positionMs: Int64, speed: Float = 1,
         queue: [SharedTrack] = [], currentIndex: Int = -1, source: String? = nil, spoken: Bool = false) {
        self.device = device; self.name = name; self.platform = platform; self.revision = revision; self.observedAt = observedAt; self.playing = playing
        self.positionMs = positionMs; self.speed = speed; self.queue = queue; self.currentIndex = currentIndex; self.source = source; self.spoken = spoken
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        device = try c.decode(String.self, forKey: .device); name = try c.decode(String.self, forKey: .name); platform = try c.decode(String.self, forKey: .platform)
        revision = try c.decode(Int64.self, forKey: .revision); observedAt = try c.decode(Int64.self, forKey: .observedAt)
        playing = try c.decode(Bool.self, forKey: .playing); positionMs = try c.decode(Int64.self, forKey: .positionMs)
        speed = Float(try c.decodeIfPresent(Double.self, forKey: .speed) ?? 1)
        queue = try c.decodeIfPresent([SharedTrack].self, forKey: .queue) ?? []
        currentIndex = try c.decodeIfPresent(Int.self, forKey: .currentIndex) ?? -1
        source = try c.decodeIfPresent(String.self, forKey: .source).flatMap { $0.isEmpty ? nil : $0 }
        spoken = try c.decodeIfPresent(Bool.self, forKey: .spoken) ?? false
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(device, forKey: .device); try c.encode(name, forKey: .name); try c.encode(platform, forKey: .platform)
        try c.encode(revision, forKey: .revision); try c.encode(observedAt, forKey: .observedAt); try c.encode(playing, forKey: .playing)
        try c.encode(positionMs, forKey: .positionMs); try c.encode(Double(speed), forKey: .speed); try c.encode(queue, forKey: .queue)
        try c.encode(currentIndex, forKey: .currentIndex); try c.encode(source, forKey: .source); try c.encode(spoken, forKey: .spoken)
    }
}

/// A remote-control request to one linked device. Packet type "deviceCommand".
struct DeviceCommand: Codable, Hashable {
    /// "handoff" = pause because playback moved to the sender.
    static let actions = ["play", "pause", "next", "previous", "seek", "handoff"]
    var id: String
    var target: String
    var action: String
    var positionMs: Int64 = 0
    var createdAt: Int64 = SocialRules.now
    func valid() -> Bool { (8...64).contains(id.count) && SocialRules.key(target) && Self.actions.contains(action) && (0...86_400_000).contains(positionMs) }
    init(id: String, target: String, action: String, positionMs: Int64 = 0, createdAt: Int64 = SocialRules.now) { self.id = id; self.target = target; self.action = action; self.positionMs = positionMs; self.createdAt = createdAt }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id); target = try c.decode(String.self, forKey: .target); action = try c.decode(String.self, forKey: .action)
        positionMs = try c.decodeIfPresent(Int64.self, forKey: .positionMs) ?? 0; createdAt = try c.decode(Int64.self, forKey: .createdAt)
    }
    /// A fresh random id, 16 hex characters.
    static func newID() -> String { (0..<8).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined() }
}

/// Pairing, step 2: device B, having found A through the code's lookup tag, asks A to link.
/// Packet type "deviceLinkRequest", encrypted to A. A shows "Link <name>?" before accepting.
struct DeviceLinkRequest: Codable, Hashable {
    var token: String
    var name: String
    var platform: String
    var createdAt: Int64 = SocialRules.now
    func valid() -> Bool { token.count == DeviceSyncState.tokenLength && (1...60).contains(name.trimmingCharacters(in: .whitespacesAndNewlines).count) && LinkedDevice.platforms.contains(platform) }
}

/// Pairing, step 1: device A shows an 8-character code and publishes this public packet
/// ("deviceCode") tagged `["t", DeviceSyncState.lookupTag(code)]`, so B can find A's key from the
/// code alone. The tag is a hash; the code itself never leaves A except through the user.
struct DeviceCodeOffer: Codable, Hashable {
    var owner: String
    var name: String
    var platform: String
    var createdAt: Int64 = SocialRules.now
    func valid() -> Bool { SocialRules.key(owner) && (1...60).contains(name.trimmingCharacters(in: .whitespacesAndNewlines).count) && LinkedDevice.platforms.contains(platform) }
}

/// The whole group as the sender sees it (including the sender). Packet type "deviceList".
struct DeviceList: Codable, Hashable {
    var devices: [LinkedDevice]
    var revision: Int64 = SocialRules.now
    func valid() -> Bool { (1...DeviceSyncState.maxDevices).contains(devices.count) && devices.allSatisfy { $0.valid() } && Set(devices.map(\.id)).count == devices.count }
    init(devices: [LinkedDevice], revision: Int64 = SocialRules.now) { self.devices = devices; self.revision = revision }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        devices = try c.decodeIfPresent([LinkedDevice].self, forKey: .devices) ?? []
        revision = try c.decodeIfPresent(Int64.self, forKey: .revision) ?? SocialRules.now
    }
}

/// "deviceUnlink": the device `id` left the group.
struct DeviceUnlink: Codable, Hashable {
    var id: String
    /// Sender's clock; missing from older senders (then always accepted).
    var createdAt: Int64? = SocialRules.now
}

/// A request that matched our code, waiting for the user to allow it.
struct PendingDeviceLink: Identifiable, Hashable { var author: String; var request: DeviceLinkRequest; var id: String { author } }

/// What's saved: the group and the last state from each device. Same fields as Kotlin's `json()`.
struct DeviceSyncSnapshot: Codable {
    var devices: [LinkedDevice] = []
    var playback: [DevicePlayback] = []
}

/// The rules, mirrored from Kotlin: which packets to trust, which remote playback to show, and
/// whether to offer "continue where you left off".
struct DeviceSyncState {
    static let tokenLength = 8
    private static let alphabet = Array("ABCDEFGHJKMNPQRSTUVWXYZ23456789")
    static let codeLifetime: Int64 = 10 * 60_000
    static let maxDevices = 10
    static let commandLifetime: Int64 = 60_000
    /// Playing devices send at least every `heartbeat`; after `fresh` without one they're not shown.
    static let heartbeat: Int64 = 30_000
    static let fresh: Int64 = 75_000
    static let resumeWindow: Int64 = 14 * 24 * 60 * 60_000
    /// Changes are sent after this quiet period (seeks and skips come in bursts).
    static let debounce: Int64 = 1_500

    let me: String
    /// Linked devices in the order they joined.
    var devices: [LinkedDevice] = []
    var playback: [String: DevicePlayback] = [:]
    private var handledCommands: [String] = []
    private var issued: (code: String, at: Int64)?
    /// Requests that matched our code, waiting for the user to allow them.
    private(set) var pendingLinks: [PendingDeviceLink] = []

    init(me: String) { self.me = me }

    func device(_ id: String) -> LinkedDevice? { devices.first { $0.id == id } }
    func linked(_ id: String) -> Bool { devices.contains { $0.id == id } }

    /// A new one-time code (8 characters, no look-alikes); replaces any earlier one.
    mutating func newCode(now: Int64 = SocialRules.now) -> String {
        var random = SystemRandomNumberGenerator()
        let token = String((0..<Self.tokenLength).map { _ in Self.alphabet[Int.random(in: 0..<Self.alphabet.count, using: &random)] })
        issued = (token, now); pendingLinks = []
        return token
    }

    /// The code currently shown, or nil when none or it expired.
    func currentCode(now: Int64 = SocialRules.now) -> String? { issued.flatMap { now - $0.at <= Self.codeLifetime ? $0.code : nil } }
    func codeIssuedAt() -> Int64? { issued?.at }
    mutating func cancelCode() { issued = nil; pendingLinks = [] }

    /// A pairing request from `author`; true when it matched our live code. The user still has to `approve` it.
    mutating func receiveLink(_ request: DeviceLinkRequest, author: String, encrypted: Bool, now: Int64 = SocialRules.now) -> Bool {
        guard let token = currentCode(now: now) else { return false }
        guard encrypted, request.valid(), author != me, SocialRules.key(author), Self.normalizeCode(request.token) == token else { return false }
        guard !linked(author), pendingLinks.count < 4 else { return false }
        var trimmed = request; trimmed.name = request.name.trimmingCharacters(in: .whitespacesAndNewlines)
        pendingLinks.removeAll { $0.author == author }
        pendingLinks.append(PendingDeviceLink(author: author, request: trimmed))
        return true
    }

    /// The user allowed `author`: link it and use up the code. False when there's no such request or no room.
    mutating func approve(_ author: String, now: Int64 = SocialRules.now) -> Bool {
        guard let index = pendingLinks.firstIndex(where: { $0.author == author }) else { return false }
        let request = pendingLinks.remove(at: index).request
        guard devices.count + 1 < Self.maxDevices else { return false }
        devices.append(LinkedDevice(id: author, name: request.name, platform: request.platform, linkedAt: now))
        issued = nil; pendingLinks = []
        return true
    }

    mutating func decline(_ author: String) { pendingLinks.removeAll { $0.author == author } }

    /// The group as we see it, us included, for a "deviceList" packet.
    func list(myName: String, myPlatform: String) -> DeviceList { DeviceList(devices: [LinkedDevice(id: me, name: myName, platform: myPlatform)] + devices) }

    /// The group from an already-linked device, or from the device whose code we just entered
    /// (`pendingOwner`). Adds everyone except us; removal only happens through `unlink`.
    mutating func acceptList(_ list: DeviceList, author: String, encrypted: Bool, pendingOwner: String?) -> Bool {
        guard encrypted, list.valid(), linked(author) || author == pendingOwner, list.devices.contains(where: { $0.id == author }), list.devices.contains(where: { $0.id == me }) else { return false }
        var changed = false
        for d in list.devices where d.id != me {
            let existing = devices.firstIndex { $0.id == d.id }
            guard existing.map({ devices[$0] }) != d, devices.count < Self.maxDevices else { continue }
            if let existing { devices[existing].name = d.name; devices[existing].platform = d.platform } else { devices.append(d) }
            changed = true
        }
        return changed
    }

    mutating func unlink(_ id: String) { devices.removeAll { $0.id == id }; playback.removeValue(forKey: id) }

    /// "deviceUnlink" {id, createdAt} from a linked device: `id` left the group. If it's us, we were
    /// removed and forget the whole group. Unlinks older than the link they'd undo are ignored:
    /// relays replay them for 30 days. Returns true when something changed.
    mutating func acceptUnlink(_ id: String, author: String, encrypted: Bool, createdAt: Int64 = .max) -> Bool {
        guard encrypted, linked(author), SocialRules.key(id) else { return false }
        let linkedAt = devices.first { $0.id == (id == me ? author : id) }?.linkedAt ?? 0
        guard createdAt >= linkedAt - 60_000 else { return false }
        if id == me { devices = []; playback = [:]; return true }
        guard linked(id) else { return false }
        unlink(id); return true
    }

    mutating func acceptPlayback(_ state: DevicePlayback, author: String, encrypted: Bool) -> Bool {
        guard encrypted, author != me, linked(author), state.device == author, state.valid() else { return false }
        guard (playback[author]?.revision ?? 0) < state.revision else { return false }
        playback[author] = state
        if let index = devices.firstIndex(where: { $0.id == author }), devices[index].name != state.name { devices[index].name = state.name }
        return true
    }

    mutating func acceptCommand(_ command: DeviceCommand, author: String, encrypted: Bool, now: Int64 = SocialRules.now) -> Bool {
        guard encrypted, author != me, linked(author), command.target == me, command.valid(), !handledCommands.contains(command.id) else { return false }
        guard command.createdAt >= now - Self.commandLifetime, command.createdAt <= now + 60_000 else { return false }
        handledCommands.append(command.id); if handledCommands.count > 200 { handledCommands.removeFirst() }
        return true
    }

    /// The device to show as "Playing on …": playing, heard from recently and with a song.
    /// Clocks differ between devices, so freshness uses when we *received* the state (`receivedAt`).
    func active(_ receivedAt: [String: Int64], now: Int64 = SocialRules.now) -> DevicePlayback? {
        playback.values.filter { $0.playing && $0.current != nil && now - (receivedAt[$0.device] ?? 0) <= Self.fresh }
            .max { (receivedAt[$0.device] ?? 0) < (receivedAt[$1.device] ?? 0) }
    }

    /// The most recent state from any device within `resumeWindow`, for "continue where you left off".
    func latest(_ receivedAt: [String: Int64], now: Int64 = SocialRules.now) -> DevicePlayback? {
        playback.values.filter { $0.current != nil && !$0.spoken && now - (receivedAt[$0.device] ?? 0) <= Self.resumeWindow }
            .max { (receivedAt[$0.device] ?? 0) < (receivedAt[$1.device] ?? 0) }
    }

    var snapshot: DeviceSyncSnapshot { DeviceSyncSnapshot(devices: devices, playback: Array(playback.values)) }

    mutating func load(_ saved: DeviceSyncSnapshot) {
        devices = []; playback = [:]
        for d in saved.devices where d.valid() && d.id != me && !linked(d.id) { devices.append(d) }
        for p in saved.playback where p.valid() && linked(p.device) { playback[p.device] = p }
    }

    /// Upper-cases and drops spaces/dashes, so "k7qx-m2pa" matches "K7QXM2PA".
    static func normalizeCode(_ text: String) -> String { String(text.uppercased().filter { $0.isLetter || $0.isNumber }) }

    /// Shown as "K7QX M2PA".
    static func displayCode(_ code: String) -> String {
        stride(from: 0, to: code.count, by: 4).map { start in String(code.dropFirst(start).prefix(4)) }.joined(separator: " ")
    }

    /// The relay tag a code's "deviceCode" offer is published under and looked up by.
    static func lookupTag(_ code: String) -> String { "spitify-link-" + SocialRules.hash(Data(("spitify-device-link:" + normalizeCode(code)).utf8)).prefix(32) }

    /// Where the remote device is now, from its last state. Uses receive time to avoid clock skew.
    /// The time to record as "received": now, unless the state is old (a relay replaying it after
    /// a reconnect), then its own time, so it doesn't look live.
    static func receivedTime(_ state: DevicePlayback, now: Int64 = SocialRules.now) -> Int64 {
        now - state.observedAt > 10 * 60_000 ? min(state.observedAt, now) : now
    }

    static func expectedPosition(_ state: DevicePlayback, receivedAt: Int64, now: Int64 = SocialRules.now) -> Int64 {
        let elapsed = state.playing ? min(max(now - receivedAt, 0), 10 * 60_000) : 0
        let value = state.positionMs + Int64(Float(elapsed) * state.speed)
        let duration = state.current?.durationMs ?? 0
        return duration > 0 ? min(value, duration) : value
    }
}
