package com.localfy.app.data.social

import org.json.JSONArray
import org.json.JSONObject

/** Saved music and permissions. Receiving a message never starts audio by itself. */
class SocialState {
    val following = mutableSetOf<String>()
    val profiles = mutableMapOf<String, FriendProfile>()
    val playlists = mutableMapOf<String, SharedPlaylist>()
    val recipients = mutableMapOf<String, MutableSet<String>>()
    val handledEdits = mutableListOf<String>()
    val contributions = mutableMapOf<String, MutableMap<String, List<SharedTrack>>>()

    fun acceptProfile(profile: FriendProfile, author: String): Boolean {
        if (!profile.valid() || profile.id != author || profile.updatedAt > SocialRules.now + 300_000 ||
            (profiles[author]?.updatedAt ?: -1) >= profile.updatedAt || profiles.size >= 500 && author !in profiles) return false
        profiles[author] = profile; return true
    }
    fun acceptPlaylist(playlist: SharedPlaylist, author: String, me: String, encrypted: Boolean): Boolean {
        if (!playlist.valid() || playlist.owner != author || author == me || playlist.updatedAt > SocialRules.now + 300_000 ||
            !encrypted && !playlist.isPublic || author !in following && playlist.key !in playlists ||
            (playlists[playlist.key]?.revision ?: 0) >= playlist.revision || playlists.size >= 500 && playlist.key !in playlists) return false
        playlists[playlist.key] = playlist; return true
    }
    fun apply(edit: SharedEdit, author: String, me: String): SharedPlaylist? {
        val key = "$me:${edit.playlistID}"; val requestKey = "$author:${edit.id}"
        var playlist = playlists[key] ?: return null
        if (!edit.valid() || edit.owner != me || edit.id.isEmpty() || edit.playlistID.isEmpty() || edit.createdAt > SocialRules.now + 300_000 ||
            edit.createdAt < SocialRules.now - 30L * 24 * 60 * 60 * 1000 || requestKey in handledEdits || playlist.owner != me || author != me && author !in playlist.editors) return null
        playlist = when (edit.action) {
            "add" -> {
                if (playlist.tracks.size + edit.tracks.size > 2000) return null
                val existing = playlist.tracks.map { it.id }.toSet()
                playlist.copy(tracks = playlist.tracks + edit.tracks.filter { it.id !in existing })
            }
            "remove" -> playlist.copy(tracks = playlist.tracks.filter { it.id !in edit.trackIDs })
            "reorder" -> {
                if (edit.trackIDs.size != playlist.tracks.size || edit.trackIDs.toSet() != playlist.tracks.map { it.id }.toSet()) return null
                val lookup = playlist.tracks.associateBy { it.id }
                playlist.copy(tracks = edit.trackIDs.mapNotNull { lookup[it] })
            }
            "rename" -> playlist.copy(name = edit.name ?: playlist.name, description = edit.description ?: playlist.description)
            "mix" -> {
                if (playlist.kind != "mix") return null
                val people = contributions.getOrPut(key) { mutableMapOf() }
                people[author] = edit.tracks
                people.keys.retainAll((playlist.editors + me).toSet())
                playlist.copy(tracks = SocialRules.mix(people.keys.sorted().mapNotNull { people[it] }))
            }
            else -> return null
        }
        playlist = playlist.copy(revision = playlist.revision + 1, updatedAt = SocialRules.now)
        if (!playlist.valid()) return null
        playlists[key] = playlist; handledEdits += requestKey
        while (handledEdits.size > 4000) handledEdits.removeAt(0)
        return playlist
    }
    fun json() = JSONObject().put("following", JSONArray(following.toList()))
        .put("profiles", JSONArray(profiles.values.map { it.json() })).put("playlists", JSONArray(playlists.values.map { it.json() }))
        .put("recipients", JSONObject().apply { recipients.forEach { (key, value) -> put(key, JSONArray(value.toList())) } })
        .put("handledEdits", JSONArray(handledEdits))
        .put("contributions", JSONObject().apply { contributions.forEach { (key, people) -> put(key, JSONObject().apply { people.forEach { (author, tracks) -> put(author, JSONArray(tracks.map { it.json() })) } }) } })
    companion object {
        fun parse(o: JSONObject): SocialState = SocialState().apply {
            following += o.strings("following").filter(SocialRules::key).take(128)
            o.objects("profiles", FriendProfile::parse).filter { it.valid() }.take(500).forEach { profiles[it.id] = it }
            o.objects("playlists", SharedPlaylist::parse).filter { it.valid() }.take(500).forEach { playlists[it.key] = it }
            o.optJSONObject("recipients")?.let { obj -> obj.keys().forEach { recipients[it] = obj.strings(it).filter(SocialRules::key).toMutableSet() } }
            handledEdits += o.strings("handledEdits").takeLast(4000)
            o.optJSONObject("contributions")?.let { obj -> obj.keys().forEach { key -> val people = obj.getJSONObject(key); contributions[key] = people.keys().asSequence().associateWith { people.objects(it, SharedTrack::parse) }.toMutableMap() } }
        }
    }
}
