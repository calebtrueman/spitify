package com.localfy.app.data.social

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class SocialRepository(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("social_library", Context.MODE_PRIVATE)
    val state = runCatching { SocialState.parse(JSONObject(prefs.getString("state", "{}")!!)) }.getOrElse { SocialState() }
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private val relay = PeerRelay(context, PeerIdentity.load(context), scope)
    val publicKey get() = relay.publicKey
    var publicProfile = prefs.getBoolean("publicProfile", true); private set
    var enabled = prefs.getBoolean("enabled", false); private set
    var discovery = prefs.getBoolean("discovery", false); private set
    var relayAddresses = prefs.getStringSet("relays", PeerRelay.DEFAULTS.toSet())!!.toList(); private set
    var connected = 0; private set
    var pending = 0; private set
    val rooms = RoomState()
    var onRoomUpdate: ((ListeningRoom) -> Unit)? = null
    var onRoomRequest: ((IncomingRoomRequest) -> Unit)? = null
    fun roomChanged() = changed()
    var message: String? = null; private set
    val playlists get() = state.playlists.values.sortedByDescending { it.updatedAt }
    init {
        relay.onStatus = { count, waiting -> connected = count; pending = waiting; changed() }
        relay.onPacket = { author, packet, encrypted -> receive(author, packet, encrypted) }
        refresh()
    }
    private fun changed() { changes.value += 1 }
    private fun persist() { prefs.edit().putString("state", state.json().toString()).apply(); changed() }
    private fun refresh() { if (enabled) relay.start(relayAddresses, state.following, discovery) else relay.stop() }
    fun configure(enabled: Boolean, discovery: Boolean = this.discovery, relays: List<String>? = null) {
        if (relays != null) { relayAddresses = relays; prefs.edit().putStringSet("relays", relays.toSet()).apply() }
        this.enabled = enabled; this.discovery = discovery
        prefs.edit().putBoolean("enabled", enabled).putBoolean("discovery", discovery).apply(); refresh(); changed(); if (enabled) scope.launch { syncProfile(true) }
    }
    fun follow(input: String) {
        val link = SocialLink.parse(input) ?: error("Paste a friend code or Spitify playlist link.")
        require(link.type != "room" && link.owner != publicKey) { "Paste another person's friend code." }
        check(state.following.size < 128 || link.owner in state.following) { "You can follow up to 128 people." }
        state.following += link.owner; persist(); relay.requestPlaylist(link); configure(true)
    }
    fun unfollow(id: String) { state.following -= id; persist(); refresh() }
    fun save(playlist: SharedPlaylist) {
        require(playlist.owner == publicKey && playlist.valid()) { "This playlist could not be saved." }
        check(state.playlists.size < 500 || playlist.key in state.playlists) { "Your shared playlist library is full." }
        state.playlists[playlist.key] = playlist; persist()
    }
    fun create(name: String, songs: List<com.localfy.app.data.Song>, streams: com.localfy.app.data.music.MusicStreams, kind: String = "playlist"): SharedPlaylist {
        val playlist = SharedPlaylist(owner = publicKey, name = name, image = songs.firstOrNull()?.artUrl?.takeIf(SocialRules::publicURL), kind = kind, tracks = songs.map { SharedTrack.from(it, streams.track(it)) })
        save(playlist); return playlist
    }
    fun copy(playlist: SharedPlaylist): SharedPlaylist {
        val own = playlist.copy(id = java.util.UUID.randomUUID().toString(), owner = publicKey, editors = emptyList(), isPublic = false, revision = 1, updatedAt = SocialRules.now)
        save(own); return own
    }
    fun remove(playlist: SharedPlaylist) { state.playlists.remove(playlist.key); persist() }
    suspend fun publishProfile(name: String, about: String) {
        (context.applicationContext as com.localfy.app.LocalfyApp).profiles.setName(name.take(80))
        prefs.edit().putString("about", about.take(500)).apply()
        syncProfile()
    }
    fun setPublicProfile(value: Boolean) {
        publicProfile = value; prefs.edit().putBoolean("publicProfile", value).apply(); changed()
        scope.launch { syncProfile(true) }
    }
    suspend fun syncProfile(force: Boolean = false) {
        try {
            val app = context.applicationContext as com.localfy.app.LocalfyApp
            val name = app.profiles.profile.value.name.trim().take(80)
            if (name.isEmpty()) return
            val photos = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                android.graphics.BitmapFactory.decodeFile(app.profiles.photoFile.path)?.let { original ->
                    try { ProfilePhotos.preview(original) to ProfilePhotos.shared(original) } finally { original.recycle() }
                }
            }
            val old = state.profiles[publicKey]
            val profile = FriendProfile(publicKey, name, prefs.getString("about", old?.about.orEmpty()).orEmpty().take(500), photo = photos?.first, isPublic = publicProfile, photoHD = photos?.second)
            val different = old?.name != name || old.about != profile.about || old.photo != profile.photo || old.photoHD != profile.photoHD || old.isPublic != publicProfile
            if (different) { state.profiles[publicKey] = profile; persist() }
            if (!enabled || !different && !force && prefs.getLong("profileSent", 0) > SocialRules.now - 86400000) return
            if (publicProfile) relay.send(SocialPacket("profile", profile.json()), "profile")
            else {
                relay.send(SocialPacket("profileHidden", JSONObject().put("id", publicKey)), "profile")
                state.following.forEach { relay.send(SocialPacket("profile", profile.json()), "profile", it) }
            }
            prefs.edit().putLong("profileSent", SocialRules.now).apply(); message = null; changed()
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; message = "Profile saved on this phone. Sharing will retry when connected. ${e.message}"; changed() }
    }
    suspend fun share(playlist: SharedPlaylist, person: String? = null) {
        requireConnection(); require(playlist.owner == publicKey) { "Make your own copy before sharing this playlist." }
        var updated = state.playlists[playlist.key] ?: playlist
        if (person != null) {
            require(SocialRules.key(person) && person in state.following) { "Follow this person before sending a private share." }
            state.recipients.getOrPut(playlist.key) { mutableSetOf() } += person
        } else { updated = updated.copy(isPublic = true, revision = updated.revision + 1, updatedAt = SocialRules.now); state.playlists[updated.key] = updated }
        persist(); relay.send(SocialPacket("playlist", updated.json()), "playlist:${updated.id}", person)
    }
    suspend fun setEditor(person: String, playlist: SharedPlaylist, allowed: Boolean) {
        requireConnection(); require(playlist.owner == publicKey && SocialRules.key(person)) { "Only the owner can change who edits this playlist." }
        val current = state.playlists[playlist.key] ?: error("Save this playlist first.")
        var updated = current.copy(editors = (current.editors - person) + if (allowed) listOf(person) else emptyList(), revision = current.revision + 1, updatedAt = SocialRules.now)
        require(updated.valid()) { "A playlist can have up to 32 editors." }
        if (allowed) state.recipients.getOrPut(playlist.key) { mutableSetOf() } += person
        if (updated.kind == "mix" && !allowed) {
            state.contributions[updated.key]?.remove(person)
            val people = state.contributions[updated.key].orEmpty()
            updated = updated.copy(tracks = SocialRules.mix(people.keys.sorted().mapNotNull { people[it] }))
        }
        state.playlists[playlist.key] = updated; persist(); broadcast(updated)
    }
    suspend fun edit(edit: SharedEdit) {
        if (edit.owner == publicKey) {
            val updated = state.apply(edit, publicKey, publicKey) ?: error("That edit could not be applied.")
            persist(); if (enabled) broadcast(updated)
        } else {
            requireConnection()
            require(publicKey in state.playlists["${edit.owner}:${edit.playlistID}"]?.editors.orEmpty()) { "The owner must invite you to edit first." }
            relay.send(SocialPacket("edit", edit.json()), "edit:${edit.id}", edit.owner)
            message = "Edit sent. It appears when the owner's device accepts it."; changed()
        }
    }
    fun hostRoom(name: String, tracks: List<SharedTrack>): ListeningRoom {
        requireConnection(); check(rooms.activeKey == null && rooms.requestedKey == null) { "Leave your current Room first." }
        val room = ListeningRoom(host = publicKey, name = name, queue = tracks.take(200))
        require(room.valid() && room.name.isNotBlank()) { "Give this Room a name." }
        rooms.rooms.clear(); rooms.rooms[room.key] = room; rooms.activeKey = room.key; changed(); return room
    }
    suspend fun joinRoom(link: SocialLink) {
        requireConnection(); val roomId = link.id
        require(link.type == "room" && roomId != null && link.owner != publicKey && rooms.activeKey == null) { "Leave your current Room before joining another." }
        rooms.requestedKey = "${link.owner}:$roomId"; changed()
        try { relay.send(SocialPacket("roomRequest", RoomRequest(roomID = roomId, host = link.owner, action = "join", name = state.profiles[publicKey]?.name ?: "Guest").json()), "roomJoin:${link.id}", link.owner, 120_000) }
        catch (e: Exception) { rooms.requestedKey = null; changed(); throw e }
    }
    suspend fun sendRoom(room: ListeningRoom, also: List<String> = emptyList()) {
        requireConnection(); require(room.host == publicKey && room.valid()) { "This Room update is invalid." }
        if ((rooms.rooms[room.key]?.revision ?: 0) <= room.revision) rooms.rooms[room.key] = room
        changed()
        (room.members + also).toSet().forEach { person -> val latest = rooms.rooms[room.key] ?: return; relay.send(SocialPacket("room", latest.json()), "room:${room.id}", person, 120_000) }
    }
    suspend fun roomRequest(request: RoomRequest) {
        requireConnection(); require(request.valid()) { "This Room request is invalid." }
        relay.send(SocialPacket("roomRequest", request.json()), "roomRequest:${request.id}", request.host, 120_000)
    }

    private fun requireConnection() { check(enabled) { "Turn on sharing in Friends first." } }
    private suspend fun broadcast(playlist: SharedPlaylist) {
        val current = state.playlists[playlist.key] ?: return
        if (current.isPublic) relay.send(SocialPacket("playlist", current.json()), "playlist:${current.id}")
        (state.recipients[playlist.key].orEmpty() + current.editors).forEach { person -> val latest = state.playlists[playlist.key] ?: return; relay.send(SocialPacket("playlist", latest.json()), "playlist:${latest.id}", person) }
    }
    private fun receive(author: String, packet: SocialPacket, encrypted: Boolean) {
        runCatching {
            when (packet.type) {
                "room" -> { val room = ListeningRoom.parse(packet.body); if (rooms.accept(room, author, publicKey, encrypted)) { changed(); onRoomUpdate?.invoke(room) } }
                "roomRequest" -> if (encrypted) { val request = RoomRequest.parse(packet.body); if (rooms.receive(request, author, publicKey)) { changed(); onRoomRequest?.invoke(rooms.requests.last()) } }
                "profileHidden" -> if (!encrypted && author != publicKey && state.profiles[author]?.isPublic != false) { state.profiles.remove(author); persist() }
                "profile" -> if (state.acceptProfile(FriendProfile.parse(packet.body), author)) persist()
                "playlist" -> if (state.acceptPlaylist(SharedPlaylist.parse(packet.body), author, publicKey, encrypted)) persist()
                "edit" -> if (encrypted) state.apply(SharedEdit.parse(packet.body), author, publicKey)?.let { updated ->
                    persist(); scope.launch { runCatching { broadcast(updated) }.onFailure { message = it.message; changed() } }
                }
            }
        }
    }
}
