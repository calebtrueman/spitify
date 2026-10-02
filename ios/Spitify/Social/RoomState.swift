import Foundation

struct RoomState {
    var rooms: [String: ListeningRoom] = [:]
    var requests: [IncomingRoomRequest] = []
    var handled: [String] = []
    var activeKey: String?
    var requestedKey: String?

    mutating func accept(_ room: ListeningRoom, author: String, me: String, encrypted: Bool) -> Bool {
        guard encrypted, room.valid(), room.host == author, author != me,
              room.key == activeKey || room.key == requestedKey,
              rooms[room.key]?.ended != true,
              rooms[room.key] == nil || rooms[room.key]!.revision < room.revision,
              room.observedAt >= SocialRules.now - 120_000 else { return false }
        rooms[room.key] = room
        if !room.live || !room.members.contains(me) { activeKey = nil; requestedKey = nil }
        else { activeKey = room.key; requestedKey = nil }
        return true
    }
    mutating func receive(_ request: RoomRequest, author: String, me: String) -> Bool {
        let key = me + ":" + request.roomID
        let dedup = author + ":" + request.id
        guard request.valid(), !request.id.isEmpty, request.host == me, SocialRules.key(author), author != me,
              request.createdAt >= SocialRules.now - 120_000, request.createdAt <= SocialRules.now + 30_000,
              !handled.contains(dedup), let room = rooms[key], room.live, activeKey == key,
              request.action == "join" || room.members.contains(author) || request.action == "leave" && requests.contains(where: { $0.sender == author && $0.request.roomID == request.roomID }), requests.count < 100 else { return false }
        handled.append(dedup); if handled.count > 1_000 { handled.removeFirst(handled.count - 1_000) }
        if request.action == "join" { requests.removeAll { $0.sender == author && $0.request.roomID == request.roomID && $0.request.action == "join" } }
        requests.append(IncomingRoomRequest(sender: author, request: request)); return true
    }
    static func expectedPosition(_ room: ListeningRoom, now: Int64 = SocialRules.now) -> Int64 {
        let elapsed = room.playing ? max(0, min(120_000, now - room.observedAt)) : 0
        let duration = room.queue.first { $0.id == room.currentID }?.durationMs ?? 0
        let value = room.positionMs + Int64(Double(elapsed) * Double(room.speed ?? 1))
        return duration > 0 ? min(duration, value) : value
    }
}
