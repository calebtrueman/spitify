package com.localfy.app.data.social

import com.localfy.app.data.Song
import com.localfy.app.data.music.OnlineTrack
import com.localfy.app.desktop.AppPaths
import com.localfy.app.desktop.JsonStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.imageio.ImageIO

/** Key/value settings in one JSON file (the desktop stand-in for SharedPreferences). */
internal class SocialPrefs(file: File) {
    private val store = JsonStore(file)
    private val values: JSONObject = store.readObject() ?: JSONObject()
    @Synchronized fun boolean(key: String, default: Boolean) = values.optBoolean(key, default)
    @Synchronized fun long(key: String, default: Long) = values.optLong(key, default)
    @Synchronized fun string(key: String): String? = if (values.has(key) && !values.isNull(key)) values.optString(key) else null
    @Synchronized fun strings(key: String): List<String>? = values.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }
    @Synchronized fun put(key: String, value: Any?) { if (value == null) values.remove(key) else values.put(key, if (value is Collection<*>) JSONArray(value) else value); val text = values.toString(); store.save { text } }
    @Synchronized fun putLong(key: String, value: Long) { values.put(key, value); val text = values.toString(); store.save { text } }
    fun flush() = store.flush()
}

/**
 * Friends, shared playlists and Listening Rooms (desktop port of the Android repository, same
 * public API). Call it from one thread — normally the UI thread, which should also be [scope]'s
 * dispatcher; relay traffic, signing and disk writes all happen elsewhere.
 *
 * The phone apps read the profile name and photo from their profile store; here they come from
 * [profileName] / [saveProfileName] / [profilePhoto] (by default the name is kept in this
 * repository's own settings and there is no photo).
 */
class SocialRepository(
    private val scope: CoroutineScope,
    private val dir: File = AppPaths.dataDir,
    relay: PeerRelay? = null,
    private val profileName: (() -> String)? = null,
    private val saveProfileName: ((String) -> Unit)? = null,
    private val profilePhoto: () -> File? = { null },
) {
    private val prefs = SocialPrefs(File(dir, "prefs-social_library.json"))
    private val stateStore = JsonStore(File(dir, "social_library.json"))
    val state = runCatching { SocialState.parse(stateStore.readObject() ?: JSONObject()) }.getOrElse { SocialState() }
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private val relay = relay ?: PeerRelay(PeerIdentity.load(File(dir, "peer_identity.key")), scope, outboxFile = File(dir, "peer_outbox.json"))
    val publicKey get() = this.relay.publicKey
    var publicProfile = prefs.boolean("publicProfile", true); private set
    var enabled = prefs.boolean("enabled", false); private set
    var discovery = prefs.boolean("discovery", false); private set
    var relayAddresses = prefs.strings("relays") ?: PeerRelay.DEFAULTS; private set
    var connected = 0; private set
    var pending = this.relay.pendingCount; private set
    val rooms = RoomState()
    var onRoomUpdate: ((ListeningRoom) -> Unit)? = null
    var onRoomRequest: ((IncomingRoomRequest) -> Unit)? = null
    /** Your-devices packets ("deviceList", "devicePlayback"…), handled by DeviceSyncRepository. */
    var onDevicePacket: ((String, SocialPacket, Boolean) -> Unit)? = null
    /** Library sync packets ("syncDoc", "syncDigest"), handled by LibrarySyncRepository. */
    var onSyncPacket: ((String, SocialPacket, Boolean) -> Unit)? = null
    /** True while devices are linked or being paired: the relay then runs even with sharing off (inbox only). */
    var devicesNeedRelay = false
        set(value) { if (field != value) { field = value; refresh() } }
    fun roomChanged() = changed()
    var message: String? = null; private set
    val playlists get() = state.playlists.values.sortedByDescending { it.updatedAt }
    /** The name shown to friends. */
    val name: String get() = (profileName?.invoke() ?: prefs.string("name")).orEmpty()
    /** The "about" line shown to friends. */
    val about: String get() = prefs.string("about") ?: state.profiles[publicKey]?.about.orEmpty()
    init {
        this.relay.onStatus = { count, waiting -> connected = count; pending = waiting; changed() }
        this.relay.onPacket = { author, packet, encrypted -> receive(author, packet, encrypted) }
        refresh()
    }
    private fun changed() { changes.value += 1 }
    private fun persist() {
        // Snapshot on this thread (cheap copies of immutable values); build the JSON on the writer thread.
        val following = state.following.toList(); val profiles = state.profiles.values.toList(); val playlists = state.playlists.values.toList()
        val recipients = state.recipients.mapValues { it.value.toList() }; val handled = state.handledEdits.toList()
        val contributions = state.contributions.mapValues { it.value.toMap() }
        stateStore.save {
            SocialState().apply {
                this.following += following; profiles.forEach { this.profiles[it.id] = it }; playlists.forEach { this.playlists[it.key] = it }
                recipients.forEach { (k, v) -> this.recipients[k] = v.toMutableSet() }; handledEdits += handled
                contributions.forEach { (k, v) -> this.contributions[k] = v.toMutableMap() }
            }.json().toString()
        }
        changed()
    }
    private fun refresh() {
        when {
            enabled -> relay.start(relayAddresses, state.following, discovery)
            devicesNeedRelay -> relay.start(relayAddresses, emptySet(), inboxOnly = true)
            else -> relay.stop()
        }
    }
    /** Sends a your-devices packet; works whether or not friend sharing is on. */
    suspend fun sendDevicePacket(packet: SocialPacket, logical: String, recipient: String?, expiresIn: Long, extraTags: List<List<String>> = emptyList()) =
        relay.send(packet, logical, recipient, expiresIn, extraTags)
    /** One-off relay lookup of a pairing code's tag (see [PeerRelay.lookup]). */
    fun lookupDeviceCode(tag: String) = relay.lookup(tag)

    fun configure(enabled: Boolean, discovery: Boolean = this.discovery, relays: List<String>? = null) {
        if (relays != null) { relayAddresses = relays; prefs.put("relays", relays) }
        this.enabled = enabled; this.discovery = discovery
        prefs.put("enabled", enabled); prefs.put("discovery", discovery); refresh(); changed(); if (enabled) scope.launch { syncProfile(true) }
    }
    fun follow(input: String) {
        val link = SocialLink.parse(input) ?: error("Paste a friend code or Spitify playlist link.")
        require(link.type != "room" && link.owner != publicKey) { "Paste another person's friend code." }
        check(state.following.size < 128 || link.owner in state.following) { "You can follow up to 128 people." }
        state.following += link.owner; persist(); relay.requestPlaylist(link); configure(true)
    }
    fun unfollow(id: String) { state.following -= id; persist(); refresh() }
    /**
     * Follows or unfollows [id] because you did on another device (library sync). Unlike [follow] it
     * doesn't turn sharing on: that stays this device's choice.
     */
    fun syncFollowing(id: String, follow: Boolean) {
        if (!SocialRules.key(id) || id == publicKey || (id in state.following) == follow) return
        if (follow && state.following.size >= 128) return
        if (follow) state.following += id else state.following -= id
        persist(); refresh()
    }
    fun save(playlist: SharedPlaylist) {
        require(playlist.owner == publicKey && playlist.valid()) { "This playlist could not be saved." }
        check(state.playlists.size < 500 || playlist.key in state.playlists) { "Your shared playlist library is full." }
        state.playlists[playlist.key] = playlist; persist()
    }
    /** [track] gives the catalogue track behind a streamed song (Android passes its MusicStreams). */
    fun create(name: String, songs: List<Song>, track: (Song) -> OnlineTrack? = { null }, kind: String = "playlist"): SharedPlaylist {
        val playlist = SharedPlaylist(owner = publicKey, name = name, image = songs.firstOrNull()?.artUrl?.takeIf(SocialRules::publicURL), kind = kind, tracks = songs.map { SharedTrack.from(it, track(it)) })
        save(playlist); return playlist
    }
    fun copy(playlist: SharedPlaylist): SharedPlaylist {
        val own = playlist.copy(id = java.util.UUID.randomUUID().toString(), owner = publicKey, editors = emptyList(), isPublic = false, revision = 1, updatedAt = SocialRules.now)
        save(own); return own
    }
    fun remove(playlist: SharedPlaylist) { state.playlists.remove(playlist.key); persist() }
    suspend fun publishProfile(name: String, about: String) {
        val trimmed = name.take(80)
        saveProfileName?.invoke(trimmed) ?: prefs.put("name", trimmed)
        prefs.put("about", about.take(500))
        syncProfile()
    }
    fun setPublicProfile(value: Boolean) {
        publicProfile = value; prefs.put("publicProfile", value); changed()
        scope.launch { syncProfile(true) }
    }
    suspend fun syncProfile(force: Boolean = false) {
        try {
            val name = this.name.trim().take(80)
            if (name.isEmpty()) return
            val photos = withContext(Dispatchers.IO) {
                profilePhoto()?.takeIf { it.isFile }?.let { file -> runCatching { ImageIO.read(file) }.getOrNull() }?.let { original ->
                    ProfilePhotos.preview(original) to ProfilePhotos.shared(original)
                }
            }
            val old = state.profiles[publicKey]
            val profile = FriendProfile(publicKey, name, (prefs.string("about") ?: old?.about.orEmpty()).take(500), photo = photos?.first, isPublic = publicProfile, photoHD = photos?.second)
            val different = old?.name != name || old.about != profile.about || old.photo != profile.photo || old.photoHD != profile.photoHD || old.isPublic != publicProfile
            if (different) { state.profiles[publicKey] = profile; persist() }
            if (!enabled || !different && !force && prefs.long("profileSent", 0) > SocialRules.now - 86400000) return
            if (publicProfile) relay.send(SocialPacket("profile", profile.json()), "profile")
            else {
                relay.send(SocialPacket("profileHidden", JSONObject().put("id", publicKey)), "profile")
                state.following.toList().forEach { relay.send(SocialPacket("profile", profile.json()), "profile", it) }
            }
            prefs.putLong("profileSent", SocialRules.now); message = null; changed()
        } catch (e: Exception) { if (e is CancellationException) throw e; message = "Profile saved on this computer. Sharing will retry when connected. ${e.message}"; changed() }
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
    /** Writes pending changes and disconnects (app exit). */
    fun close() { stateStore.flush(); prefs.flush(); relay.close() }

    private fun requireConnection() { check(enabled) { "Turn on sharing in Friends first." } }
    private suspend fun broadcast(playlist: SharedPlaylist) {
        val current = state.playlists[playlist.key] ?: return
        if (current.isPublic) relay.send(SocialPacket("playlist", current.json()), "playlist:${current.id}")
        (state.recipients[playlist.key].orEmpty() + current.editors).forEach { person -> val latest = state.playlists[playlist.key] ?: return; relay.send(SocialPacket("playlist", latest.json()), "playlist:${latest.id}", person) }
    }
    private fun receive(author: String, packet: SocialPacket, encrypted: Boolean) {
        runCatching {
            when (packet.type) {
                "deviceCode", "deviceLinkRequest", "deviceList", "deviceUnlink", "devicePlayback", "deviceCommand" -> onDevicePacket?.invoke(author, packet, encrypted)
                "syncDoc", "syncDigest" -> onSyncPacket?.invoke(author, packet, encrypted)
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
