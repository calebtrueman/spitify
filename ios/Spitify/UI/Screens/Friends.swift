import SwiftUI

struct FriendsView: View {
    @Environment(AppModel.self) private var app
    @State private var friendCode = ""
    @State private var name = ""
    @State private var about = ""
    @State private var relays = ""
    @State private var message: String?
    var body: some View {
        Form {
            Section("Sharing") {
                Toggle("Connect with friends", isOn: Binding(get: { app.social.enabled }, set: { app.social.configure(enabled: $0, discovery: app.social.discovery) }))
                Text("Friends connect through public relays. Public profiles and public playlists can be read by anyone. Direct shares are encrypted. Your audio files stay on your device.")
                if app.social.enabled { Text("\(app.social.connected) relays connected · \(app.social.pending) messages waiting") }
                if !app.social.publicKey.isEmpty {
                    ShareLink("Share your friend code", item: SocialLink(type: "person", owner: app.social.publicKey).url)
                }
            }
            Section { NavigationLink("Rooms — listen together") { RoomsView() } }
            Section { NavigationLink("Create a shared playlist or mix") { CreateSharedPlaylistView() } }
            Section("Connection services") {
                TextField("Relay addresses, one per line", text: $relays, axis: .vertical).textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Save relay addresses") {
                    let addresses = relays.split(whereSeparator: \.isWhitespace).map(String.init)
                    if !addresses.isEmpty && addresses.count <= 4 && addresses.allSatisfy({ URLComponents(string: $0)?.scheme == "wss" }) { app.social.configure(enabled: app.social.enabled, discovery: app.social.discovery, relays: addresses); message = "Connection services saved." }
                    else { message = "Enter one to four secure wss:// relay addresses." }
                }
            }
            Section("Follow a friend") {
                TextField("Friend code or Spitify link", text: $friendCode).textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Follow") { run { try app.social.follow(friendCode); friendCode = "" } }.disabled(friendCode.isEmpty)
            }
            Section("Your public profile") {
                TextField("Name", text: $name)
                TextField("About you", text: $about, axis: .vertical)
                Button("Publish profile") { Task { do { try await app.social.publishProfile(name: name, about: about); message = "Profile published." } catch { message = error.localizedDescription } } }.disabled(!app.social.enabled)
                Toggle("Discover public profiles", isOn: Binding(get: { app.social.discovery }, set: { app.social.configure(enabled: app.social.enabled, discovery: $0) }))
            }
            Section("Following") {
                ForEach(app.social.state.following.sorted(), id: \.self) { id in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(app.social.state.profiles[id]?.name ?? "Friend " + id.prefix(8))
                            if let about = app.social.state.profiles[id]?.about, !about.isEmpty { Text(about).font(.caption) }
                        }
                        Spacer()
                        Button("Unfollow", role: .destructive) { app.social.unfollow(id) }
                    }
                }
            }
            if app.social.discovery {
                Section("Public profiles") {
                    ForEach(app.social.state.profiles.values.filter { $0.id != app.social.publicKey && !app.social.state.following.contains($0.id) }.sorted { $0.name < $1.name }) { profile in
                        HStack { Text(profile.name); Spacer(); Button("Follow") { run { try app.social.follow(profile.id) } } }
                    }
                }
            }
            if let message { Section { Text(message) } }
            if let message = app.social.message { Section { Text(message) } }
        }.navigationTitle("Friends")
        .task {
            run { try app.social.prepare() }
            relays = app.social.relayAddresses.joined(separator: "\n")
            name = app.social.state.profiles[app.social.publicKey]?.name ?? ""
            about = app.social.state.profiles[app.social.publicKey]?.about ?? ""
        }
    }
    private func run(_ action: () throws -> Void) { do { try action() } catch { message = error.localizedDescription } }
}

struct PlaylistSharingView: View {
    var playlist: SharedPlaylist
    @Environment(AppModel.self) private var app
    @State private var message: String?
    var body: some View {
        Form {
            Section("Public link") {
                Text("Publishing makes the playlist name, description and song list readable by anyone.")
                Button("Publish playlist") { perform { try await app.social.share(playlist) } }
                ShareLink("Share link", item: SocialLink(type: "playlist", owner: playlist.owner, id: playlist.id).url)
            }
            Section("Send to a friend") {
                Text("Your friend must follow your code to receive your shares.")
                ForEach(app.social.state.following.sorted(), id: \.self) { person in
                    VStack(alignment: .leading) {
                        Text(app.social.state.profiles[person]?.name ?? "Friend " + person.prefix(8))
                        Button("Send privately") { perform { try await app.social.share(playlist, with: person) } }
                        Toggle("Allow edits", isOn: Binding(get: { (app.social.state.playlists[playlist.key] ?? playlist).editors.contains(person) }, set: { allowed in
                            perform { try await app.social.setEditor(person, playlist: playlist, allowed: allowed) }
                        }))
                    }
                }
                if app.social.state.following.isEmpty { Text("Follow someone in Friends to send a private share.") }
            }
            if let message { Section { Text(message) } }
        }.navigationTitle("Share playlist")
    }
    private func perform(_ action: @escaping () async throws -> Void) {
        Task { do { try await action(); message = "Queued for sharing. Delivery waits for a connected relay." } catch { message = error.localizedDescription } }
    }
}

struct IncomingShareView: View {
    var link: SocialLink
    @Environment(AppModel.self) private var app
    @State private var message: String?
    var body: some View {
        Group {
            if link.type == "room" {
                VStack {
                    if app.social.rooms.activeKey == nil && app.social.rooms.requestedKey == nil {
                        Button("Connect and ask to join") { Task { do { app.social.configure(enabled: true, discovery: app.social.discovery); try await app.social.joinRoom(link) } catch { message = error.localizedDescription } } }
                        if let message { Text(message) }
                    }
                    RoomsView()
                }
            } else if link.type == "playlist", let id = link.id, let playlist = app.social.state.playlists[link.owner + ":" + id] {
                SharedPlaylistView(initial: playlist)
            } else {
                Form {
                    Text(app.social.state.profiles[link.owner]?.name ?? "Friend " + link.owner.prefix(8))
                    Text("Following connects to public relays to receive this person's shared music. Private playlists must be sent to your friend code first.")
                    Button("Connect and follow") {
                        do { try app.social.follow(link.url.absoluteString); app.social.configure(enabled: true, discovery: app.social.discovery); message = link.type == "person" ? "Following." : "Waiting for the playlist. Ask its owner to send it privately if it isn't public." }
                        catch { message = error.localizedDescription }
                    }
                    if let message { Text(message) }
                }
            }
        }.navigationTitle("Shared with you")
    }
}
