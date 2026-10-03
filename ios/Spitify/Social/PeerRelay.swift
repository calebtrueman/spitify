import Foundation
import Security
import NostrSDK

/// Keys never go in a playlist, link, relay event or normal app preference.
enum PeerIdentity {
    static func load() throws -> Keys {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.calebtrueman.spitify.peers", kSecAttrAccount as String: "identity"]
        var read = query; read[kSecReturnData as String] = true
        var result: CFTypeRef?
        let status = SecItemCopyMatching(read as CFDictionary, &result)
        if status == errSecSuccess, let data = result as? Data, let value = String(data: data, encoding: .utf8) {
            // Encrypted device backups may restore the same friend identity.
            SecItemUpdate(query as CFDictionary, [kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock] as CFDictionary)
            return try Keys.parse(secretKey: value)
        }
        guard status == errSecItemNotFound else { throw MusicSourceError.message("Your friend identity could not be opened. Unlock the phone and try again.") }
        let keys = Keys.generate()
        var write = query; write[kSecValueData as String] = Data(keys.secretKey().toHex().utf8); write[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        guard SecItemAdd(write as CFDictionary, nil) == errSecSuccess else { throw MusicSourceError.message("Your friend identity could not be saved.") }
        return keys
    }
}

struct RelayOutgoing: Codable {
    var id: String
    var json: String
    var logical: String
    var expiresAt: Int64
}

@MainActor final class PeerRelay {
    static let defaults = ["wss://relay.damus.io", "wss://nos.lol"]
    let keys: Keys
    var publicKey: String { keys.publicKey().toHex() }
    var onPacket: ((String, SocialPacket, Bool) -> Void)?
    var onStatus: ((Int, Int) -> Void)?
    private(set) var outgoing: [RelayOutgoing]
    private var sockets: [String: URLSessionWebSocketTask] = [:]
    private var connected: Set<String> = []
    private var loops: [String: Task<Void, Never>] = [:]
    private var known: Set<String> = []
    private var requestedPlaylists: [String: SocialLink] = [:]
    private var discover = false
    private var enabled = false
    private let storage: String
    private let testMode: Bool
    private var seen: Set<String> = []
    private var seenOrder: [String] = []
    private var stamp: [String: UInt64] = [:]
    private struct Assembly { var author: String; var encrypted: Bool; var firstAt: Int64; var total: Int; var digest: String; var parts: [Int: Data] }
    private var assemblies: [String: Assembly] = [:]

    init(keys: Keys, storage: String = "peerOutbox", testMode: Bool = false) {
        self.keys = keys; self.storage = storage; self.testMode = testMode
        outgoing = Store.load([RelayOutgoing].self, storage) ?? []
    }
    func start(relays: [String], authors: Set<String>, discover: Bool = false) {
        self.known = Set(authors.filter(SocialRules.key)); self.discover = discover; enabled = true
        let allowed = relays.filter { address in
            guard let c = URLComponents(string: address), let host = c.host, c.user == nil, c.password == nil else { return false }
            return c.scheme == "wss" && host.contains(".") || testMode && c.scheme == "ws" && ["127.0.0.1", "localhost", "10.0.2.2"].contains(host)
        }.prefix(4)
        let wanted = Set(allowed)
        for url in Array(loops.keys) where !wanted.contains(url) { loops[url]?.cancel(); loops.removeValue(forKey: url); sockets.removeValue(forKey: url)?.cancel(with: .goingAway, reason: nil); connected.remove(url) }
        for url in wanted {
            if loops[url] == nil { loops[url] = Task { [weak self] in await self?.run(url) } }
            else if let socket = sockets[url] { Task { try? await subscribe(socket) } }
        }
        report()
    }
    func stop() {
        enabled = false; loops.values.forEach { $0.cancel() }; loops.removeAll()
        sockets.values.forEach { $0.cancel(with: .goingAway, reason: nil) }; sockets.removeAll(); connected.removeAll(); report()
    }
    private func run(_ address: String) async {
        var delay: Double = 1
        while enabled && !Task.isCancelled {
            guard let url = URL(string: address) else { return }
            let socket = URLSession.shared.webSocketTask(with: url); socket.maximumMessageSize = 128 * 1024
            sockets[address] = socket; socket.resume()
            do {
                try await subscribe(socket)
                connected.insert(address); report(); delay = 1
                await flush(socket)
                while enabled && !Task.isCancelled {
                    let message = try await socket.receive()
                    switch message { case .string(let value): receive(value); case .data(let value): if let s = String(data: value, encoding: .utf8) { receive(s) }; @unknown default: break }
                }
            } catch { /* Keep queued shares; a relay outage must not erase them. */ }
            socket.cancel(with: .goingAway, reason: nil); sockets.removeValue(forKey: address); connected.remove(address); report()
            if !enabled || Task.isCancelled { break }
            try? await Task.sleep(for: .seconds(delay)); delay = min(60, delay * 2)
        }
    }
    func requestPlaylist(_ link: SocialLink) {
        guard link.type == "playlist", let id = link.id else { return }
        if requestedPlaylists.count >= 32 { requestedPlaylists.removeAll() }
        requestedPlaylists[link.owner + ":" + id] = link
        for socket in sockets.values { Task { try? await subscribe(socket) } }
    }
    private func subscribe(_ socket: URLSessionWebSocketTask) async throws {
        var request: [Any] = ["REQ", "spitify-v1"]
        let authors = Array(known.union([publicKey])).sorted().prefix(129)
        request.append(["kinds": [30078], "authors": Array(authors), "#d": ["spitify:v1:profile"], "limit": 129] as [String: Any])
        request.append(["kinds": [30078], "authors": Array(authors), "#t": ["spitify"], "limit": 500] as [String: Any])
        request.append(["kinds": [30078], "#p": [publicKey], "#t": ["spitify"], "limit": 500] as [String: Any])
        for link in requestedPlaylists.values {
            guard let id = link.id else { continue }
            let identifiers = (0..<120).flatMap { index in ["spitify:v1:playlist:\(id):public:\(index)", "spitify:v1:playlist:\(id):\(publicKey):\(index)"] }
            request.append(["kinds": [30078], "authors": [link.owner], "#d": identifiers, "limit": 240] as [String: Any])
        }
        if discover { request.append(["kinds": [30078], "#d": ["spitify:v1:profile"], "limit": 150] as [String: Any]) }
        try await socket.send(.string(String(data: JSONSerialization.data(withJSONObject: request), encoding: .utf8)!))
    }
    private func flush(_ socket: URLSessionWebSocketTask) async {
        outgoing.removeAll { $0.expiresAt < SocialRules.now }; persist()
        for item in outgoing {
            if Task.isCancelled { return }
            try? await socket.send(.string("[\"EVENT\",\(item.json)]"))
            try? await Task.sleep(for: .milliseconds(40))
        }
    }
    func send(_ packet: SocialPacket, logical: String, to recipient: String? = nil, expiresIn: Int64 = 30 * 24 * 60 * 60 * 1000) async throws {
        guard packet.v == 1, logical.count <= 220, recipient == nil || SocialRules.key(recipient!) else { throw MusicSourceError.message("That friend code is not valid.") }
        let data = try JSONEncoder().encode(packet)
        guard data.count <= 1_000_000 else { throw MusicSourceError.message("This share is too large. Try a smaller playlist.") }
        let transfer = UUID().uuidString.lowercased(); let digest = SocialRules.hash(data)
        let chunkSize = logical == "profile" ? 36_000 : 9_000
        guard logical != "profile" || data.count <= chunkSize else { throw MusicSourceError.message("Your profile photo is too large.") }
        let chunks = stride(from: 0, to: data.count, by: chunkSize).map { data.subdata(in: $0..<min($0 + chunkSize, data.count)) }
        let prefix = "\(logical):\(recipient ?? "public")"
        guard outgoing.filter({ $0.logical != prefix }).count + chunks.count <= 1_000 else { throw MusicSourceError.message("There are many shares waiting to send. Connect before adding more.") }
        let created = max(UInt64(Date().timeIntervalSince1970), (stamp[prefix] ?? 0) + 1)
        guard created <= UInt64(Date().timeIntervalSince1970) + 120 else { throw MusicSourceError.message("Please wait a moment before sharing again.") }
        var prepared: [RelayOutgoing] = []
        for (index, bytes) in chunks.enumerated() {
            let contentPacket = chunks.count == 1 ? packet : try SocialPacket.make("part", SocialPart(transfer: transfer, index: index, total: chunks.count, digest: digest, content: bytes))
            let raw = String(data: try JSONEncoder().encode(contentPacket), encoding: .utf8)!
            let content = try recipient.map { try keys.nip44Encrypt(publicKey: PublicKey.parse(publicKey: $0), content: raw) } ?? raw
            let identifier = logical == "profile" && recipient == nil ? "spitify:v1:profile" : "spitify:v1:\(prefix):\(index)"
            var tags = [["d", identifier], ["t", "spitify"], ["expiration", String((SocialRules.now + expiresIn) / 1000)]]
            if let recipient { tags.append(["p", recipient]); tags.append(["encrypted", "nip44"]) }
            let event = try EventBuilder(kind: Kind(kind: 30078), content: content)
                .tags(tags: tags.map { try Tag.parse(data: $0) }).customCreatedAt(createdAt: Timestamp.fromSecs(secs: created)).finalize(signer: keys)
            prepared.append(RelayOutgoing(id: event.id().toHex(), json: try event.asJson(), logical: prefix, expiresAt: SocialRules.now + expiresIn))
        }
        outgoing.removeAll { $0.logical == prefix }; outgoing += prepared; stamp[prefix] = created
        persist(); await Store.flush()
        for socket in sockets.values { await flush(socket) }
    }
    func receive(_ raw: String) {
        guard raw.utf8.count <= 128 * 1024, let data = raw.data(using: .utf8), let values = try? JSONSerialization.jsonObject(with: data) as? [Any], let type = values.first as? String else { return }
        if type == "OK", values.count >= 3, let id = values[1] as? String, values[2] as? Bool == true {
            outgoing.removeAll { $0.id == id }; persist(); return
        }
        guard type == "EVENT", values.count >= 3, let object = values[2] as? [String: Any], let json = try? JSONSerialization.data(withJSONObject: object),
              let event = try? Event.fromJson(json: String(data: json, encoding: .utf8)!), event.verify(), object["kind"] as? Int == 30078,
              let tags = object["tags"] as? [[String]], tags.contains(where: { $0 == ["t", "spitify"] }), tags.contains(where: { $0.count > 1 && $0[0] == "d" && $0[1].hasPrefix("spitify:v1:") }),
              let timestamp = object["created_at"] as? Int64, timestamp <= SocialRules.now / 1000 + 300 else { return }
        if let expiration = tags.first(where: { $0.first == "expiration" && $0.count > 1 }).flatMap({ Int64($0[1]) }), expiration < SocialRules.now / 1000 { return }
        let id = event.id().toHex(); guard !seen.contains(id) else { return }
        let encrypted = tags.contains(["encrypted", "nip44"])
        let recipients = tags.filter { $0.first == "p" && $0.count > 1 }.map { $0[1] }
        guard encrypted ? recipients == [publicKey] : recipients.isEmpty else { return }
        let author = event.author().toHex()
        let content: String
        if encrypted { guard let opened = try? keys.nip44Decrypt(publicKey: event.author(), payload: event.content()) else { return }; content = opened }
        else { content = event.content() }
        guard content.utf8.count <= 48_000, let decoded = content.data(using: .utf8), let packet = try? JSONDecoder().decode(SocialPacket.self, from: decoded), packet.v == 1, packet.body.count <= 36_000 else { return }
        seen.insert(id); seenOrder.append(id)
        if seenOrder.count > 4_000 { seen.remove(seenOrder.removeFirst()) }
        if packet.type != "part" { onPacket?(author, packet, encrypted); return }
        guard let part = try? packet.decode(SocialPart.self), part.transfer.count <= 100, part.total > 1, part.total <= 120, part.index >= 0, part.index < part.total, part.content.count <= 9_000 else { return }
        assemblies = assemblies.filter { SocialRules.now - $0.value.firstAt < 600_000 }
        let assemblyKey = author + ":" + part.transfer
        if assemblies[assemblyKey] == nil {
            guard assemblies.count < 24 else { return }
            assemblies[assemblyKey] = Assembly(author: author, encrypted: encrypted, firstAt: SocialRules.now, total: part.total, digest: part.digest, parts: [:])
        }
        guard var assembly = assemblies[assemblyKey], assembly.total == part.total, assembly.digest == part.digest, assembly.encrypted == encrypted else { return }
        assembly.parts[part.index] = part.content; assemblies[assemblyKey] = assembly
        if assembly.parts.count == assembly.total {
            let whole = (0..<assembly.total).reduce(into: Data()) { $0.append(assembly.parts[$1]!) }
            assemblies.removeValue(forKey: assemblyKey)
            guard whole.count <= 1_000_000, SocialRules.hash(whole) == assembly.digest, let packet = try? JSONDecoder().decode(SocialPacket.self, from: whole), packet.v == 1, packet.type != "part" else { return }
            onPacket?(author, packet, encrypted)
        }
    }
    private func persist() { Store.save(outgoing, storage); report() }
    private func report() { onStatus?(connected.count, outgoing.count) }
}
