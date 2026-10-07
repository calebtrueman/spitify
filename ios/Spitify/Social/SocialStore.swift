import Foundation
import Observation

@MainActor @Observable
final class SocialStore {
    private(set) var state = Store.load(SocialState.self, "socialLibrary") ?? SocialState()
    private(set) var publicKey = ""
    private(set) var connected = 0
    private(set) var pending = 0
    var rooms = RoomState()
    var onRoomUpdate: ((ListeningRoom) -> Void)?
    var onRoomRequest: ((IncomingRoomRequest) -> Void)?
    /// Packets whose type starts with "device", for `DeviceSyncStore`.
    var onDevicePacket: ((String, SocialPacket, Bool) -> Void)?
    /// Packets whose type starts with "sync", for `LibrarySyncStore`.
    var onSyncPacket: ((String, SocialPacket, Bool) -> Void)?
    /// Linked devices or pairing need the relay even when friend sharing is off.
    var devicesWanted = false { didSet { if devicesWanted != oldValue { refreshConnection() } } }
    var publicProfile = UserDefaults.standard.object(forKey: "socialPublicProfile") as? Bool ?? true
    var message: String?
    var enabled = UserDefaults.standard.bool(forKey: "socialEnabled")
    var discovery = UserDefaults.standard.bool(forKey: "socialDiscovery")
    var relayAddresses = UserDefaults.standard.stringArray(forKey: "socialRelays") ?? PeerRelay.defaults
    private var relay: PeerRelay?
    var playlists: [SharedPlaylist] { state.playlists.values.sorted { $0.updatedAt > $1.updatedAt } }
    var friends: [FriendProfile] { state.profiles.values.filter { state.following.contains($0.id) }.sorted { $0.name < $1.name } }

    func prepare() throws {
        guard relay == nil else { return }
        let connection = PeerRelay(keys: try PeerIdentity.load())
        publicKey = connection.publicKey; relay = connection
        connection.onStatus = { [weak self] connected, pending in self?.connected = connected; self?.pending = pending }
        connection.onPacket = { [weak self] author, packet, encrypted in self?.receive(author, packet, encrypted) }
        refreshConnection()
    }
    func configure(enabled: Bool, discovery: Bool, relays: [String]? = nil) {
        self.enabled = enabled; self.discovery = discovery
        if let relays { relayAddresses = relays }
        UserDefaults.standard.set(enabled, forKey: "socialEnabled"); UserDefaults.standard.set(discovery, forKey: "socialDiscovery")
        UserDefaults.standard.set(relayAddresses, forKey: "socialRelays"); refreshConnection()
        if enabled { Task { await syncProfile(force: true) } }
    }
    private func refreshConnection() {
        if enabled { relay?.start(relays: relayAddresses, authors: state.following, discover: discovery) }
        else if devicesWanted { relay?.start(relays: relayAddresses, authors: [], inboxOnly: true) }
        else { relay?.stop() }
    }
    func follow(_ input: String) throws {
        try prepare()
        guard let link = SocialLink.parse(input), link.type != "room", link.owner != publicKey else { throw MusicSourceError.message("Paste another person's friend code or playlist link.") }
        guard state.following.count < 128 || state.following.contains(link.owner) else { throw MusicSourceError.message("You can follow up to 128 people.") }
        state.following.insert(link.owner); persist(); relay?.requestPlaylist(link); configure(enabled: true, discovery: discovery)
        Task { await syncProfile() }
    }
    func unfollow(_ id: String) { state.following.remove(id); persist(); refreshConnection() }
    /// A follow made on another linked device. Unlike `follow`, it doesn't turn sharing on here.
    func followFromSync(_ id: String) {
        guard SocialRules.key(id), id != publicKey, !state.following.contains(id), state.following.count < 128 else { return }
        state.following.insert(id); persist(); refreshConnection()
    }
    /// Asks the relays for a shared playlist another linked device saved; it's kept when it arrives.
    func requestFromSync(owner: String, id: String) {
        guard SocialRules.key(owner), owner != publicKey, (try? prepare()) != nil else { return }
        relay?.requestPlaylist(SocialLink(type: "playlist", owner: owner, id: id))
    }
    func save(_ playlist: SharedPlaylist) throws {
        try prepare()
        guard playlist.owner == publicKey, playlist.valid(), state.playlists.count < 500 || state.playlists[playlist.key] != nil else { throw MusicSourceError.message("This playlist could not be saved.") }
        state.playlists[playlist.key] = playlist; persist()
    }
    func create(name: String, songs: [Song], kind: String = "playlist") throws -> SharedPlaylist {
        try prepare()
        let playlist = SharedPlaylist(owner: publicKey, name: name, image: songs.first?.artURL.flatMap { SocialRules.publicURL($0) ? $0 : nil }, kind: kind, tracks: songs.map(SharedTrack.from))
        try save(playlist); return playlist
    }
    func copy(_ playlist: SharedPlaylist) throws -> SharedPlaylist {
        try prepare()
        var own = playlist; own.id = UUID().uuidString.lowercased(); own.owner = publicKey; own.editors = []; own.isPublic = false; own.revision = 1; own.updatedAt = SocialRules.now
        try save(own); return own
    }
    func remove(_ playlist: SharedPlaylist) { state.playlists.removeValue(forKey: playlist.key); persist() }
    func publishProfile(name: String, about: String) async throws {
        AppModel.shared.profile.name = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))
        UserDefaults.standard.set(String(about.prefix(500)), forKey: "socialAbout")
        await syncProfile()
    }
    func setPublicProfile(_ value: Bool) {
        publicProfile = value; UserDefaults.standard.set(value, forKey: "socialPublicProfile")
        Task { await syncProfile(force: true) }
    }
    func syncProfile(force: Bool = false) async {
        do {
            try prepare()
            let app = AppModel.shared
            let name = app.profile.name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty else { return }
            let sourcePhoto = try? Data(contentsOf: AppModel.photoURL)
            let photo = sourcePhoto.flatMap(ProfilePhotos.preview)
            let photoHD = sourcePhoto.flatMap(ProfilePhotos.shared)
            let old = state.profiles[publicKey]
            let about = UserDefaults.standard.string(forKey: "socialAbout") ?? old?.about ?? ""
            let profile = FriendProfile(photo: photo, photoHD: photoHD, isPublic: publicProfile, id: publicKey, name: String(name.prefix(80)), about: String(about.prefix(500)))
            let changed = old?.name != profile.name || old?.about != profile.about || old?.photo != profile.photo || old?.photoHD != profile.photoHD || old?.isPublic != profile.isPublic
            if changed { state.profiles[publicKey] = profile; persist() }
            guard enabled, changed || force || UserDefaults.standard.double(forKey: "socialProfileSent") < Date().timeIntervalSince1970 - 86400 else { return }
            if publicProfile { try await relay?.send(.make("profile", profile), logical: "profile") }
            else {
                // Replace the public entry with a notice containing no profile details.
                try await relay?.send(.make("profileHidden", ["id": publicKey]), logical: "profile")
                for person in state.following { try await relay?.send(.make("profile", profile), logical: "profile", to: person) }
            }
            UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: "socialProfileSent")
            message = nil
        } catch { message = "Profile saved on this phone. Sharing will retry when connected. " + error.localizedDescription }
    }
    func share(_ playlist: SharedPlaylist, with person: String? = nil) async throws {
        try requireConnection()
        guard playlist.owner == publicKey else { throw MusicSourceError.message("Make your own copy before sharing this playlist.") }
        var updated = state.playlists[playlist.key] ?? playlist
        if let person {
            guard SocialRules.key(person), state.following.contains(person) else { throw MusicSourceError.message("Follow this person before sending a private share.") }
            state.recipients[playlist.key, default: []].insert(person)
        } else { updated.isPublic = true; updated.revision += 1; updated.updatedAt = SocialRules.now; state.playlists[updated.key] = updated }
        persist()
        try await relay?.send(.make("playlist", updated), logical: "playlist:" + updated.id, to: person)
    }
    func setEditor(_ person: String, playlist: SharedPlaylist, allowed: Bool) async throws {
        try requireConnection()
        guard playlist.owner == publicKey, SocialRules.key(person), var updated = state.playlists[playlist.key] else { throw MusicSourceError.message("Only the owner can change who edits this playlist.") }
        updated.editors.removeAll { $0 == person }
        if allowed { updated.editors.append(person); state.recipients[playlist.key, default: []].insert(person) }
        // Removing edit permission keeps their read access and sends the new permission list.
        updated.revision += 1; updated.updatedAt = SocialRules.now
        guard updated.valid() else { throw MusicSourceError.message("A playlist can have up to 32 editors.") }
        if updated.kind == "mix", !allowed {
            state.contributions[updated.key]?.removeValue(forKey: person)
            let people = state.contributions[updated.key] ?? [:]
            updated.tracks = SocialRules.mix(people.keys.sorted().compactMap { people[$0] })
        }
        state.playlists[playlist.key] = updated; persist(); try await broadcast(updated)
    }
    func edit(_ edit: SharedEdit) async throws {
        try prepare()
        if edit.owner == publicKey {
            guard let updated = state.apply(edit, author: publicKey, me: publicKey) else { throw MusicSourceError.message("That edit could not be applied.") }
            persist(); if enabled { try await broadcast(updated) }
        } else {
            try requireConnection()
            guard let playlist = state.playlists[edit.owner + ":" + edit.playlistID], playlist.editors.contains(publicKey) else { throw MusicSourceError.message("The owner must invite you to edit first.") }
            try await relay?.send(.make("edit", edit), logical: "edit:" + edit.id, to: edit.owner)
            message = "Edit sent. It appears when the owner's device accepts it."
        }
    }
    func hostRoom(name: String, tracks: [SharedTrack]) throws -> ListeningRoom {
        try requireConnection()
        guard rooms.activeKey == nil, rooms.requestedKey == nil else { throw MusicSourceError.message("Leave your current Room first.") }
        let room = ListeningRoom(host: publicKey, name: name, queue: Array(tracks.prefix(200)))
        guard room.valid(), !room.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw MusicSourceError.message("Give this Room a name.") }
        rooms.rooms = [room.key: room]; rooms.activeKey = room.key; return room
    }
    func joinRoom(_ link: SocialLink) async throws {
        try requireConnection()
        guard link.type == "room", let id = link.id, link.owner != publicKey, rooms.activeKey == nil else { throw MusicSourceError.message("Leave your current Room before joining another.") }
        let key = link.owner + ":" + id
        rooms.requestedKey = key
        do { try await relay?.send(.make("roomRequest", RoomRequest(roomID: id, host: link.owner, action: "join", name: state.profiles[publicKey]?.name ?? "Guest")), logical: "roomJoin:" + id, to: link.owner, expiresIn: 120_000) }
        catch { rooms.requestedKey = nil; throw error }
    }
    func sendRoom(_ room: ListeningRoom, also recipients: [String] = []) async throws {
        try requireConnection()
        guard room.host == publicKey, room.valid() else { throw MusicSourceError.message("This Room update is invalid.") }
        if (rooms.rooms[room.key]?.revision ?? 0) <= room.revision { rooms.rooms[room.key] = room }
        for person in Set(room.members + recipients) {
            guard let latest = rooms.rooms[room.key] else { return }
            try await relay?.send(.make("room", latest), logical: "room:" + room.id, to: person, expiresIn: 120_000)
        }
    }
    func roomRequest(_ request: RoomRequest) async throws {
        try requireConnection()
        guard request.valid() else { throw MusicSourceError.message("This Room request is invalid.") }
        try await relay?.send(.make("roomRequest", request), logical: "roomRequest:" + request.id, to: request.host, expiresIn: 120_000)
    }

    /// Device packets go out whether or not friend sharing is on.
    func sendDevice(_ packet: SocialPacket, logical: String, to recipient: String?, expiresIn: Int64, extraTags: [[String]] = []) async throws {
        try prepare()
        try await relay?.send(packet, logical: logical, to: recipient, expiresIn: expiresIn, extraTags: extraTags)
    }
    func lookup(_ tag: String, timeout: Duration = .seconds(15)) async {
        guard (try? prepare()) != nil else { return }
        await relay?.lookup(tag, timeout: timeout)
    }
    func lookupFound(_ tag: String, author: String) -> Bool { relay?.lookupFound(tag, author: author) == true }

    private func requireConnection() throws {
        try prepare()
        guard enabled else { throw MusicSourceError.message("Turn on sharing in Friends first.") }
    }
    private func broadcast(_ playlist: SharedPlaylist) async throws {
        guard let current = state.playlists[playlist.key] else { return }
        if current.isPublic { try await relay?.send(.make("playlist", current), logical: "playlist:" + current.id) }
        for person in (state.recipients[playlist.key] ?? []).union(current.editors) {
            guard let latest = state.playlists[playlist.key] else { return }
            try await relay?.send(.make("playlist", latest), logical: "playlist:" + latest.id, to: person)
        }
    }
    private func receive(_ author: String, _ packet: SocialPacket, _ encrypted: Bool) {
        do {
            switch packet.type {
            case "room":
                let room = try packet.decode(ListeningRoom.self)
                if rooms.accept(room, author: author, me: publicKey, encrypted: encrypted) { onRoomUpdate?(room) }
            case "roomRequest":
                guard encrypted else { return }
                let request = try packet.decode(RoomRequest.self)
                if rooms.receive(request, author: author, me: publicKey), let incoming = rooms.requests.last { onRoomRequest?(incoming) }
            case "profileHidden":
                if !encrypted, author != publicKey, state.profiles[author]?.isPublic != false { state.profiles.removeValue(forKey: author); persist() }
            case "profile":
                if state.acceptProfile(try packet.decode(FriendProfile.self), author: author) { persist() }
            case "playlist":
                if state.acceptPlaylist(try packet.decode(SharedPlaylist.self), author: author, me: publicKey, encrypted: encrypted) { persist() }
            case "edit":
                guard encrypted, let updated = state.apply(try packet.decode(SharedEdit.self), author: author, me: publicKey) else { return }
                persist()
                Task { do { try await broadcast(updated) } catch { message = error.localizedDescription } }
            default:
                if packet.type.hasPrefix("device") { onDevicePacket?(author, packet, encrypted) }
                else if packet.type.hasPrefix("sync") { onSyncPacket?(author, packet, encrypted) }
            }
        } catch { /* An invalid share cannot change the library. */ }
    }
    private func persist() { Store.save(state, "socialLibrary") }
}

@MainActor
enum SharedSongMatch {
    private static var pending: [String: Task<Song, Error>] = [:]
    private static var warming = Set<String>()
    static func prepare(_ playlist: SharedPlaylist, app: AppModel) {
        let key = playlist.key + ":" + String(playlist.revision)
        guard warming.insert(key).inserted else { return }
        Task { @MainActor in
            defer { warming.remove(key) }
            for start in stride(from: 0, to: playlist.tracks.count, by: 4) {
                let tracks = Array(playlist.tracks[start..<min(start + 4, playlist.tracks.count)])
                await withTaskGroup(of: Void.self) { group in
                    for track in tracks { group.addTask { @MainActor in _ = try? await resolve(track, app: app) } }
                }
            }
        }
    }
    static func resolve(_ track: SharedTrack, app: AppModel) async throws -> Song {
        do {
            let song = try await resolveCopy(track, app: app)
            PlaylistMatches.shared.found(song, for: track)
            return song
        } catch {
            if !Task.isCancelled { PlaylistMatches.shared.missing(track) }
            throw error
        }
    }
    private static func resolveCopy(_ track: SharedTrack, app: AppModel) async throws -> Song {
        if let chosen = PlaylistMatches.shared.manual(track, app: app) { return chosen }

        func same(_ title: String, _ artist: String, _ duration: Int64) -> Bool {
            SearchMatch.fold(title) == SearchMatch.fold(track.title) && SearchMatch.fold(artist) == SearchMatch.fold(track.artist)
            && (track.durationMs == 0 || abs(duration - track.durationMs) < 5000)
        }
        if let local = app.library.library.songs.first(where: { same($0.title, $0.artist, $0.durationMs) }) { return local }
        if let id = track.sourceID {
            return app.musicStreams.register(OnlineTrack(id: id, title: track.title, artist: track.artist, album: track.album, releaseID: track.releaseID ?? "", durationMs: track.durationMs, trackNumber: 0, discNumber: 1, artwork: track.artwork, playable: true))
        }
        if let cached = app.musicStreams.tracks.values.first(where: { same($0.title, $0.artist, $0.durationMs) }) { return MusicStreams.song(cached) }
        if PlaylistMatches.shared.failed.contains(PlaylistMatches.key(track)) { throw MusicSourceError.message("Choose a local copy or try matching again.") }
        let key = SearchMatch.fold(track.title) + "|" + SearchMatch.fold(track.artist) + "|" + String(track.durationMs)
        if let task = pending[key] { return try await task.value }
        let task = Task { @MainActor in
            let results = (try? await MonochromeClient().search(track.title + " " + track.artist)) ?? []
            if let match = results.first(where: { $0.playable && same($0.title, $0.artist, $0.durationMs) }) { return app.musicStreams.register(match) }
            let seed = OnlineTrack(id: "external-" + SocialRules.hash(Data(PlaylistMatches.key(track).utf8)), title: track.title, artist: track.artist, album: track.album, releaseID: "", durationMs: track.durationMs, trackNumber: 0, discNumber: 1, artwork: track.artwork, playable: false)
            if var alternate = try? await AudioFallback.resolve(seed) { alternate.playable = true; return app.musicStreams.register(alternate) }
            throw MusicSourceError.message("No matching copy of “\(track.title)” was found.")
        }
        pending[key] = task
        defer { pending.removeValue(forKey: key) }
        return try await task.value
    }
}
