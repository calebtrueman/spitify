import SwiftUI

struct RoomsView: View {
    @Environment(AppModel.self) private var app
    @State private var name = ""
    @State private var link = ""
    @State private var query = ""
    @State private var results: [OnlineTrack] = []
    @State private var message: String?
    var body: some View {
        AppList {
            if let room = app.rooms.room {
                Section(room.name) {
                    Text(app.rooms.isHost ? "You host this Room" : "Listening with the host")
                    Text("Everyone streams their own copy. An unavailable song may leave one person silent. Rooms need the host's app to stay connected.").text(.caption)
                    ShareLink("Invite someone", item: SocialLink(type: "room", owner: room.host, id: room.id).url)
                    Text("\(room.members.count) guests")
                    if app.rooms.isHost {
                        Toggle("Let guests control playback", isOn: Binding(get: { room.allowControls }, set: { allowed in perform { try await app.rooms.setControls(allowed) } }))
                    }
                    if app.rooms.isHost || room.allowControls {
                        HStack {
                            Button(room.playing ? "Pause" : "Play") { perform { try await app.rooms.control(playing: !room.playing) } }
                            Button("Next") { perform { try await app.rooms.control(next: true) } }
                            Button("Restart") { perform { try await app.rooms.control(position: 0) } }
                        }
                    }
                    Button(app.rooms.isHost ? "End Room" : "Leave Room", role: .destructive) { Task { await app.rooms.leave() } }
                }
                if app.rooms.isHost {
                    Section("Join requests") {
                        ForEach(app.social.rooms.requests.filter { $0.request.action == "join" && $0.request.roomID == room.id }) { incoming in
                            VStack(alignment: .leading) {
                                Text(incoming.request.name.isEmpty ? "Guest " + incoming.sender.prefix(8) : incoming.request.name)
                                HStack { Button("Accept") { perform { try await app.rooms.approve(incoming, allowed: true) } }; Button("Decline") { perform { try await app.rooms.approve(incoming, allowed: false) } } }
                            }
                        }
                    }
                }
                Section("Shared queue") {
                    ForEach(room.queue) { track in
                        MediaRowContent(inset: 0) {
                            SharedTrackCover(track: track, matched: nil).frame(width: MediaLayout.rowArt, height: MediaLayout.rowArt)
                        } detail: { MediaRowText(title: track.title, subtitle: track.artist, highlighted: room.currentID == track.id, badge: room.currentID == track.id ? "speaker.wave.2.fill" : nil) } trailing: { EmptyView() }
                            .swipeActions { if app.rooms.isHost || room.allowControls { Button("Remove", role: .destructive) { perform { try await app.rooms.remove(track.id) } } } }
                    }
                }
                Section("Add a song") {
                    TextField("Find a song", text: $query)
                    ForEach(results) { track in
                        Button { perform { try await app.rooms.add([SharedTrack(title: track.title, artist: track.artist, album: track.album, durationMs: track.durationMs, sourceID: track.id, releaseID: track.releaseID, artwork: track.artwork)]) } } label: {
                            MediaRowContent(inset: 0) {
                                PlaylistCover(url: track.artwork).frame(width: MediaLayout.rowArt, height: MediaLayout.rowArt)
                            } detail: { MediaRowText(title: track.title, subtitle: track.artist) } trailing: { EmptyView() }
                        }
                    }
                }
            } else if app.social.rooms.requestedKey != nil {
                Text("Waiting for the host to accept your request.")
                Button("Cancel join") { Task { await app.rooms.leave() } }
            } else {
                Section("Listen together") {
                    Text("Host a Room from your current music queue or paste an invite. Each guest needs the host's approval.")
                    TextField("Room name", text: $name)
                    Button("Host Room") { do { try app.rooms.host(name: name) } catch { message = error.localizedDescription } }.disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                Section("Join a Room") {
                    TextField("Room link", text: $link).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button("Ask to join") {
                        perform { guard let parsed = SocialLink.parse(link), parsed.type == "room" else { throw MusicSourceError.message("Paste a Spitify Room link.") }; try await app.social.joinRoom(parsed) }
                    }.disabled(link.isEmpty)
                }
                if !app.social.enabled { Text("Turn on Connect with friends in Friends first.") }
            }
            if let message { Text(message) }
            if let message = app.rooms.message { Text(message) }
        }.navigationTitle("Rooms")
        .task(id: query) {
            results = []
            guard query.trimmingCharacters(in: .whitespacesAndNewlines).count >= 2 else { return }
            do { try await Task.sleep(for: .milliseconds(350)); let found = try await MonochromeClient().search(query); try Task.checkCancellation(); results = found }
            catch { if !Task.isCancelled { message = error.localizedDescription } }
        }
    }
    private func perform(_ action: @escaping () async throws -> Void) { Task { do { try await action(); message = nil } catch { message = error.localizedDescription } } }
}
