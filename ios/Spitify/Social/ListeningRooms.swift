import Foundation
import Observation

@MainActor @Observable
final class ListeningRooms {
    weak var app: AppModel?
    var message: String?
    private var loop: Task<Void, Never>?
    private var applying: Task<Void, Never>?
    private var playedTrack: String?
    private var hostTracks: [String: SharedTrack] = [:]
    var room: ListeningRoom? { guard let app, let key = app.social.rooms.activeKey else { return nil }; return app.social.rooms.rooms[key] }
    var isHost: Bool { room?.host == app?.social.publicKey }
    func start(app: AppModel) {
        self.app = app
        app.social.onRoomUpdate = { [weak self] room in self?.received(room) }
        app.social.onRoomRequest = { [weak self] request in self?.request(request) }
        guard loop == nil else { return }
        loop = Task { [weak self] in
            while !Task.isCancelled { try? await Task.sleep(for: .milliseconds(500)); guard let self else { return }; await self.tick() }
        }
    }
    func host(name: String) throws {
        guard let app else { return }
        hostTracks = [:]
        let tracks = app.player.queue.filter { !$0.isPodcast && !$0.isAudiobook }.prefix(200).map { song -> SharedTrack in let track = SharedTrack.from(song); hostTracks[song.id] = track; return track }
        _ = try app.social.hostRoom(name: name, tracks: tracks)
        Task { await tick() }
    }
    func approve(_ incoming: IncomingRoomRequest, allowed: Bool) async throws {
        guard let app, var room, isHost else { return }
        app.social.rooms.requests.removeAll { $0.id == incoming.id }
        if allowed {
            guard room.members.count < 32 || room.members.contains(incoming.sender) else { throw MusicSourceError.message("This Room already has 32 guests.") }
            if !room.members.contains(incoming.sender) { room.members.append(incoming.sender) }
        }
        room.revision += 1; room.observedAt = SocialRules.now
        try await app.social.sendRoom(room, also: [incoming.sender])
    }
    func setControls(_ allowed: Bool) async throws {
        guard let app, var room, isHost else { return }
        room.allowControls = allowed; room.revision += 1; room.observedAt = SocialRules.now
        try await app.social.sendRoom(room)
    }
    func leave() async {
        guard let app else { return }
        let old = room
        let pending = app.social.rooms.requestedKey
        app.social.rooms.activeKey = nil; app.social.rooms.requestedKey = nil; playedTrack = nil; applying?.cancel()
        if let old {
            if old.host == app.social.publicKey {
                var ended = old; ended.ended = true; ended.playing = false; ended.revision += 1; ended.observedAt = SocialRules.now
                do { try await app.social.sendRoom(ended) } catch { message = error.localizedDescription }
            } else {
                app.player.pause(); app.player.setRoomPlayback(speed: nil)
                try? await app.social.roomRequest(RoomRequest(roomID: old.id, host: old.host, action: "leave"))
            }
        } else if let pending, let split = pending.firstIndex(of: ":") {
            try? await app.social.roomRequest(RoomRequest(roomID: String(pending[pending.index(after: split)...]), host: String(pending[..<split]), action: "leave"))
        }
    }
    func control(playing: Bool? = nil, next: Bool = false, position: Int64? = nil) async throws {
        guard let app, let room else { return }
        if isHost {
            if next { app.player.next() }
            if let playing { if playing { app.player.resume() } else { app.player.pause() } }
            if let position { app.player.seek(Double(position) / 1000) }
            await tick()
        } else {
            guard room.allowControls else { throw MusicSourceError.message("The host has not enabled guest controls.") }
            try await app.social.roomRequest(RoomRequest(roomID: room.id, host: room.host, action: next ? "next" : "control", positionMs: position, playing: playing))
        }
    }
    func add(_ tracks: [SharedTrack]) async throws {
        guard let app, let room else { return }
        if isHost { try await append(tracks); await tick() }
        else { try await app.social.roomRequest(RoomRequest(roomID: room.id, host: room.host, action: "add", tracks: Array(tracks.prefix(100)))) }
    }
    func remove(_ trackID: String) async throws {
        guard let app, let room else { return }
        if isHost {
            guard let index = room.queue.firstIndex(where: { $0.id == trackID }) else { return }
            let entries = app.player.queue.enumerated().filter { !$0.element.isSpoken }
            guard entries.indices.contains(index) else { return }
            app.player.remove(at: entries[index].offset); await tick()
        } else {
            guard room.allowControls else { throw MusicSourceError.message("The host has not enabled guest controls.") }
            try await app.social.roomRequest(RoomRequest(roomID: room.id, host: room.host, action: "remove", trackID: trackID))
        }
    }
    private func append(_ tracks: [SharedTrack]) async throws {
        guard let app, let room, room.queue.count + tracks.count <= 200 else { throw MusicSourceError.message("A Room can hold up to 200 songs.") }
        for track in tracks {
            let song = try await SharedSongMatch.resolve(track, app: app)
            guard self.room?.key == room.key, isHost else { return }
            if app.player.queue.isEmpty { app.player.play([song], source: "Room: " + room.name) }
            else { app.player.appendFromSource([song]) }
        }
    }
    private func tick() async {
        guard let app, var room else { return }
        if !room.live || !app.social.enabled { await leave(); return }
        if !isHost {
            if SocialRules.now - room.observedAt > 45_000 { if app.player.isPlaying { app.player.pause() }; message = "Waiting for the host to reconnect." }
            return
        }
        let entries = Array(app.player.queue.enumerated().filter { !$0.element.isPodcast && !$0.element.isAudiobook }.prefix(200))
        let songs = entries.map(\.element)
        var tracks: [SharedTrack] = []
        // Queue entries can repeat the same recording, so every position has its own ID.
        for (index, song) in songs.enumerated() {
            let key = "\(index):\(song.id)"
            let track = hostTracks[key] ?? SharedTrack.from(song); hostTracks[key] = track; tracks.append(track)
        }
        hostTracks = hostTracks.filter { key, _ in songs.enumerated().contains { "\($0.offset):\($0.element.id)" == key } }
        let previous = room
        room.queue = tracks
        let index = entries.firstIndex { $0.offset == app.player.index }
        room.currentID = index.map { tracks[$0].id }
        room.speed = app.player.speed
        room.positionMs = Int64(app.player.position * 1000); room.playing = app.player.isPlaying && room.currentID != nil
        let changed = previous.queue != room.queue || previous.currentID != room.currentID || previous.playing != room.playing || previous.speed != room.speed || abs(RoomState.expectedPosition(previous) - room.positionMs) > 2_000
        guard changed || SocialRules.now - previous.observedAt >= 5_000 else { return }
        room.observedAt = SocialRules.now; room.revision += 1
        do { try await app.social.sendRoom(room) } catch { message = error.localizedDescription }
    }
    private func received(_ room: ListeningRoom) {
        guard let app else { return }
        applying?.cancel()
        guard room.live, room.members.contains(app.social.publicKey), app.social.rooms.activeKey == room.key else {
            app.player.pause(); app.player.setRoomPlayback(speed: nil); playedTrack = nil; message = room.ended ? "The host ended this Room." : "You are no longer in this Room."; return
        }
        message = nil
        guard let track = room.queue.first(where: { $0.id == room.currentID }) else { app.player.pause(); return }
        applying = Task { [weak self] in
            guard let self else { return }
            do {
                if playedTrack != track.id || app.player.source != "Room: " + room.name {
                    let song = try await SharedSongMatch.resolve(track, app: app)
                    try Task.checkCancellation()
                    guard app.social.rooms.activeKey == room.key, app.social.rooms.rooms[room.key]?.revision == room.revision else { return }
                    app.player.setRoomPlayback(speed: room.speed ?? 1)
                    app.player.play([song], shuffle: false, source: "Room: " + room.name); playedTrack = track.id
                }
                app.player.setRoomPlayback(speed: room.speed ?? 1)
                let position = Double(RoomState.expectedPosition(room)) / 1000
                if abs(app.player.position - position) > 2 { app.player.seek(position) }
                if room.playing { if !app.player.isPlaying { app.player.resume() } } else { app.player.pause() }
            } catch { if !Task.isCancelled { message = error.localizedDescription; app.player.pause() } }
        }
    }
    private func request(_ incoming: IncomingRoomRequest) {
        guard let app, let room, isHost, incoming.request.roomID == room.id else { return }
        if incoming.request.action == "join" { return }
        app.social.rooms.requests.removeAll { $0.id == incoming.id || incoming.request.action == "leave" && $0.sender == incoming.sender && $0.request.roomID == incoming.request.roomID }
        Task {
            do {
                switch incoming.request.action {
                case "leave":
                    guard var current = self.room else { return }; current.members.removeAll { $0 == incoming.sender }; current.revision += 1; current.observedAt = SocialRules.now
                    try await app.social.sendRoom(current, also: [incoming.sender])
                case "add": try await append(incoming.request.tracks); await tick()
                case "remove": if self.room?.allowControls == true, let id = incoming.request.trackID { try await remove(id) }
                case "next": if self.room?.allowControls == true { try await control(next: true) }
                case "control": if self.room?.allowControls == true { try await control(playing: incoming.request.playing, position: incoming.request.positionMs) }
                default: break
                }
            } catch { message = error.localizedDescription }
        }
    }
}
