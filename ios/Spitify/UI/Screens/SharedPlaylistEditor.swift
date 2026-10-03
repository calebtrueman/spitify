import SwiftUI

struct SharedPlaylistEditor: View {
    var initial: SharedPlaylist
    @Environment(AppModel.self) private var app
    @State private var name = ""
    @State private var description = ""
    @State private var query = ""
    @State private var results: [OnlineTrack] = []
    @State private var selected: [String: SharedTrack] = [:]
    @State private var message: String?
    @State private var busy = false
    private var playlist: SharedPlaylist { app.social.state.playlists[initial.key] ?? initial }
    var body: some View {
        List {
            Section("Name and description") {
                TextField("Name", text: $name)
                TextField("Description", text: $description, axis: .vertical)
                Button("Save details") { send(SharedEdit(playlistID: playlist.id, owner: playlist.owner, action: "rename", name: name, description: description)) }
            }
            Section(playlist.kind == "mix" ? "Your contribution" : "Add songs") {
                TextField("Find a song", text: $query)
                if playlist.kind == "mix" { Text("Pick up to 100 songs. Sending replaces your previous contribution. The mix takes turns between each person's songs and skips repeats.").font(.caption) }
                ForEach(results) { track in
                    Button {
                        let key = "remote:" + track.id
                        if selected[key] != nil { selected.removeValue(forKey: key) }
                        else if selected.count < 100 { selected[key] = SharedTrack(title: track.title, artist: track.artist, album: track.album, durationMs: track.durationMs, sourceID: track.id, releaseID: track.releaseID, artwork: track.artwork) }
                    } label: {
                        SharedTrackLabel(track: SharedTrack(title: track.title, artist: track.artist, artwork: track.artwork), matched: MusicStreams.song(track), selected: selected["remote:" + track.id] != nil)
                    }
                }
                Text("\(selected.count) selected").font(.caption)
                if !selected.isEmpty { Button("Clear selection") { selected = [:] } }
                ForEach(Array(app.library.library.songs.filter { query.isEmpty || SearchMatch.score(query, title: $0.title, artist: $0.artist, album: $0.album) != nil }.prefix(30))) { song in
                    Button {
                        let key = "local:" + song.id
                        if selected[key] != nil { selected.removeValue(forKey: key) }
                        else if selected.count < 100 { selected[key] = SharedTrack.from(song) }
                    } label: {
                        SharedTrackLabel(track: SharedTrack.from(song), matched: song, selected: selected["local:" + song.id] != nil)
                    }
                }
                Button(playlist.kind == "mix" ? "Send my contribution" : "Add selected songs") {
                    let tracks = selected.keys.sorted().compactMap { selected[$0] }
                    send(SharedEdit(playlistID: playlist.id, owner: playlist.owner, action: playlist.kind == "mix" ? "mix" : "add", tracks: tracks))
                }.disabled(selected.isEmpty)
            }
            if playlist.kind != "mix" {
                Section("Songs — use Edit to move or remove") {
                    ForEach(playlist.tracks) { track in SharedTrackLabel(track: track) }
                        .onDelete { offsets in send(SharedEdit(playlistID: playlist.id, owner: playlist.owner, action: "remove", trackIDs: offsets.map { playlist.tracks[$0].id })) }
                        .onMove { offsets, destination in var tracks = playlist.tracks; tracks.move(fromOffsets: offsets, toOffset: destination); send(SharedEdit(playlistID: playlist.id, owner: playlist.owner, action: "reorder", trackIDs: tracks.map(\.id))) }
                }
            }
            if playlist.owner != app.social.publicKey { Text("Your changes appear after the owner's app accepts them.").font(.caption) }
            if let message { Text(message) }
        }.disabled(busy).navigationTitle(playlist.kind == "mix" ? "Edit Shared Mix" : "Edit playlist").toolbar { EditButton() }
        .onAppear { name = playlist.name; description = playlist.description }
        .task(id: query) {
            results = []
            guard query.trimmingCharacters(in: .whitespacesAndNewlines).count >= 2 else { return }
            do { try await Task.sleep(for: .milliseconds(350)); let found = try await MonochromeClient().search(query); try Task.checkCancellation(); results = found }
            catch { if !Task.isCancelled { message = error.localizedDescription } }
        }
    }
    private func send(_ edit: SharedEdit) {
        busy = true
        Task { defer { busy = false }; do { try await app.social.edit(edit); selected = [:]; message = playlist.owner == app.social.publicKey ? "Saved." : "Sent to the owner." } catch { message = error.localizedDescription } }
    }
}

struct CreateSharedPlaylistView: View {
    @Environment(AppModel.self) private var app
    @State private var name = ""
    @State private var mix = false
    @State private var created: SharedPlaylist?
    @State private var message: String?
    var body: some View {
        Form {
            TextField("Name", text: $name)
            Toggle("Shared Mix", isOn: $mix)
            Text(mix ? "Invite friends from Share with friends. Each person picks songs; the mix balances their contributions." : "Create a playlist, add songs, then choose who can read or edit it.")
            Button("Create") { do { created = try app.social.create(name: name.trimmingCharacters(in: .whitespacesAndNewlines), songs: [], kind: mix ? "mix" : "playlist") } catch { message = error.localizedDescription } }.disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            if let message { Text(message) }
        }.navigationTitle("New shared playlist")
        .navigationDestination(item: $created) { SharedPlaylistView(initial: $0) }
    }
}

struct SharedTrackLabel: View {
    var track: SharedTrack
    var matched: Song? = nil
    var selected: Bool? = nil
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    @State private var resolved: Song?
    var body: some View {
        HStack(spacing: 12) {
            SharedTrackCover(track: track, matched: matched ?? resolved).frame(width: 48, height: 48)
            VStack(alignment: .leading, spacing: 3) {
                Text(track.title).foregroundStyle(p.text).lineLimit(2)
                Text(track.artist).font(.caption).foregroundStyle(p.secondary).lineLimit(2)
            }.multilineTextAlignment(.leading)
            Spacer(minLength: 0)
            if let selected { Image(systemName: selected ? "checkmark.circle.fill" : "circle").foregroundStyle(selected ? p.accent : p.secondary) }
        }.frame(maxWidth: .infinity, minHeight: 56, alignment: .leading).contentShape(Rectangle())
        .task(id: track) { if matched == nil { resolved = try? await SharedSongMatch.resolve(track, app: app) } }
    }
}
