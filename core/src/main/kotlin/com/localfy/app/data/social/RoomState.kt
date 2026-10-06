package com.localfy.app.data.social

class RoomState {
    val rooms = mutableMapOf<String, ListeningRoom>()
    val requests = mutableListOf<IncomingRoomRequest>()
    private val handled = mutableListOf<String>()
    var activeKey: String? = null
    var requestedKey: String? = null
    fun accept(room: ListeningRoom, author: String, me: String, encrypted: Boolean): Boolean {
        if (!encrypted || !room.valid() || room.host != author || author == me || (room.key != activeKey && room.key != requestedKey) || rooms[room.key]?.ended == true || (rooms[room.key]?.revision ?: 0) >= room.revision || room.observedAt < SocialRules.now - 120_000) return false
        rooms[room.key] = room
        if (!room.live || me !in room.members) { activeKey = null; requestedKey = null }
        else { activeKey = room.key; requestedKey = null }
        return true
    }
    fun receive(request: RoomRequest, author: String, me: String): Boolean {
        val key = "$me:${request.roomID}"; val dedup = "$author:${request.id}"
        val room = rooms[key] ?: return false
        if (!request.valid() || request.id.isEmpty() || request.host != me || !SocialRules.key(author) || author == me || request.createdAt < SocialRules.now - 120_000 || request.createdAt > SocialRules.now + 30_000 || dedup in handled || !room.live || activeKey != key || (request.action != "join" && author !in room.members && !(request.action == "leave" && requests.any { it.sender == author && it.request.roomID == request.roomID })) || requests.size >= 100) return false
        handled += dedup; if (handled.size > 1000) handled.removeAt(0)
        if (request.action == "join") requests.removeAll { it.sender == author && it.request.roomID == request.roomID && it.request.action == "join" }
        requests += IncomingRoomRequest(author, request); return true
    }
    companion object {
        fun expectedPosition(room: ListeningRoom, now: Long = SocialRules.now): Long {
            val elapsed = if (room.playing) (now - room.observedAt).coerceIn(0, 120_000) else 0
            val duration = room.queue.firstOrNull { it.id == room.currentID }?.durationMs ?: 0
            val value = room.positionMs + (elapsed * room.speed).toLong()
            return if (duration > 0) value.coerceAtMost(duration) else value
        }
    }
}
