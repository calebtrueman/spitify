import SwiftUI
import PhotosUI
import CoreImage.CIFilterBuiltins
import Vision

struct FriendsView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    @State private var adding = false
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Text("Music is better together.").text(.headlineL)
                HStack {
                    Button("Add friend", systemImage: "person.badge.plus") { adding = true }.buttonStyle(.borderedProminent)
                    NavigationLink { FriendCodeView() } label: { Label("My code", systemImage: "qrcode") }.buttonStyle(.bordered)
                }
                NavigationLink { RoomsView() } label: { Label("Listen together", systemImage: "headphones") }
                Text("Your friends").text(.title)
                if app.social.state.following.isEmpty { Text("Add a friend's picture code or link to start sharing music.").foregroundStyle(p.secondary) }
                ForEach(app.social.state.following.sorted { displayName($0) < displayName($1) }, id: \.self) { person in
                    NavigationLink { FriendProfileView(person: person) } label: {
                        HStack(spacing: 14) {
                            FriendPortrait(profile: app.social.state.profiles[person]).frame(width: 60, height: 60)
                            VStack(alignment: .leading, spacing: 4) { Text(displayName(person)).text(.title); Text(app.social.state.profiles[person]?.about ?? "Waiting for their profile…").text(.bodyS).lineLimit(2).foregroundStyle(p.secondary) }
                            Spacer(); Image(systemName: "chevron.right")
                        }.padding(12).background(p.surface, in: RoundedRectangle(cornerRadius: 16))
                    }.buttonStyle(.plain)
                }
                NavigationLink("Create a shared playlist or mix") { CreateSharedPlaylistView() }
                if !app.social.enabled { Button("Resume sharing") { app.social.configure(enabled: true, discovery: app.social.discovery) } }
                if let message = app.social.message { Text(message).text(.bodyS) }
            }.padding(20)
        }.navigationTitle("Friends")
            .sheet(isPresented: $adding) { NavigationStack { AddFriendView() } }
            .task { try? app.social.prepare(); await app.social.syncProfile() }
    }
    private func displayName(_ person: String) -> String { app.social.state.profiles[person]?.name ?? "Profile not loaded" }
}

struct FriendPortrait: View {
    var profile: FriendProfile?
    var body: some View {
        Group {
            if let photo = profile?.photoHD ?? profile?.photo, let data = Data(base64Encoded: photo), let image = UIImage(data: data) { Image(uiImage: image).resizable().scaledToFill() }
            else if let url = profile?.image { AsyncImage(url: URL(string: url)) { image in image.resizable().scaledToFill() } placeholder: { placeholder } }
            else { placeholder }
        }.clipShape(Circle())
    }
    private var placeholder: some View { ZStack { Color.gray.opacity(0.2); Image(systemName: "person.fill").resizable().scaledToFit().padding(20).foregroundStyle(.secondary) } }
}

struct FriendProfileView: View {
    var person: String
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var removing = false
    private var own: Bool { person == app.social.publicKey }
    private var profile: FriendProfile? { app.social.state.profiles[person] }
    private var shared: [SharedPlaylist] { app.social.playlists.filter { $0.owner == person || app.social.state.recipients[$0.key]?.contains(person) == true || $0.editors.contains(person) } }
    var body: some View {
        if own { ProfileView() } else {
        CollectionLayout(title: profile?.name ?? "Profile not loaded", subtitle: profile?.about ?? "Their name and photo will appear when their profile arrives.", metadata: own ? "Your profile" : "Friend", artKey: "friend:" + person, remoteArt: profile?.image) {
            FriendPortrait(profile: profile)
        } actions: {
            HStack {
                if app.social.state.following.contains(person) {
                    Menu { Button("Unfollow", role: .destructive) { removing = true } } label: { Label("Following", systemImage: "checkmark") }.buttonStyle(.bordered)
                } else { Button("Follow") { try? app.social.follow(person) }.buttonStyle(.borderedProminent) }
            }.padding(.horizontal, 20)
        } content: {
            VStack(alignment: .leading, spacing: 16) {
                Text(own ? "Your shared music" : "Music you share").text(.title)
                if shared.isEmpty { Text("Shared playlists will appear here. Open one of your playlists and choose Share with friends.").text(.bodyS) }
                ForEach(shared) { list in NavigationLink { SharedPlaylistView(initial: list) } label: {
                    HStack(spacing: 12) { PlaylistCover(url: list.image).frame(width: 64, height: 64); VStack(alignment: .leading) { Text(list.name); Text(list.owner == app.social.publicKey ? "Shared by you" : "Shared by them").text(.caption) }; Spacer(); Image(systemName: "chevron.right") }
                }.buttonStyle(.plain) }
            }.padding(20)
        }.confirmationDialog("Unfollow this friend?", isPresented: $removing, titleVisibility: .visible) { Button("Unfollow", role: .destructive) { app.social.unfollow(person); dismiss() } } message: { Text("Your saved playlists stay on this phone.") }
        }
    }
}

struct FriendsSettingsView: View {
    @Environment(AppModel.self) private var app
    @State private var relays = ""
    @State private var message: String?
    var body: some View {
        AppForm {
            Section("Connection") {
                Toggle("Connect with friends", isOn: Binding(get: { app.social.enabled }, set: { app.social.configure(enabled: $0, discovery: app.social.discovery) }))
                Text(app.social.enabled ? "\(app.social.connected) connections · \(app.social.pending) updates waiting" : "Sharing is paused. Your changes stay saved here.")
                Toggle("Discover public profiles", isOn: Binding(get: { app.social.discovery }, set: { app.social.configure(enabled: app.social.enabled, discovery: $0) }))
            }
            Section { DisclosureGroup("Advanced connection settings") {
                TextField("Relay addresses", text: $relays, axis: .vertical).textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Save connections") {
                    let addresses = relays.split(whereSeparator: \.isWhitespace).map(String.init)
                    if !addresses.isEmpty && addresses.count <= 4 && addresses.allSatisfy({ URLComponents(string: $0)?.scheme == "wss" }) { app.social.configure(enabled: app.social.enabled, discovery: app.social.discovery, relays: addresses); message = "Connections saved." }
                    else { message = "Enter one to four wss:// addresses." }
                }
            } }
            if let message { Text(message) }
            if let message = app.social.message { Text(message) }
        }.navigationTitle("Sharing connection").onAppear { relays = app.social.relayAddresses.joined(separator: "\n") }
    }
}

struct FriendCodeView: View {
    @Environment(AppModel.self) private var app
    var body: some View {
        VStack(spacing: 24) {
            Text(app.profile.name).text(.headlineL)
            if let image = FriendPictureCode.make(SocialLink(type: "person", owner: app.social.publicKey).url.absoluteString) {
                Image(uiImage: image).interpolation(.none).resizable().scaledToFit().frame(maxWidth: 300)
                ShareLink(item: Image(uiImage: image), preview: SharePreview("Spitify friend code", image: Image(uiImage: image))) { Label("Share picture code", systemImage: "square.and.arrow.up") }.buttonStyle(.borderedProminent)
            }
            Text("Your friend can import this image in Friends → Add friend.").multilineTextAlignment(.center)
        }.padding(24).navigationTitle("Your friend code")
    }
}

struct AddFriendView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var code = ""
    @State private var photo: PhotosPickerItem?
    @State private var message: String?
    var body: some View {
        AppForm {
            Section { PhotosPicker(selection: $photo, matching: .images) { Label("Import friend code image", systemImage: "qrcode") } }
            Section("Or paste a link") { TextField("Friend code or Spitify link", text: $code).textInputAutocapitalization(.never).autocorrectionDisabled(); Button("Add friend") { follow() }.disabled(code.isEmpty) }
            if let message { Text(message) }
        }.navigationTitle("Add friend").toolbar { Button("Cancel") { dismiss() } }
            .task(id: photo) { guard let photo else { return }; if let data = try? await photo.loadTransferable(type: Data.self), let value = FriendPictureCode.read(data) { code = value; message = "Code found. Tap Add friend to follow." } else { message = "No Spitify friend code found in this image." } }
    }
    private func follow() { do { try app.social.follow(code); dismiss() } catch { message = error.localizedDescription } }
}

enum FriendPictureCode {
    static func make(_ text: String) -> UIImage? {
        guard SocialLink.parse(text)?.type == "person" else { return nil }
        let filter = CIFilter.qrCodeGenerator(); filter.message = Data(text.utf8); filter.correctionLevel = "M"
        guard let output = filter.outputImage, let cg = CIContext().createCGImage(output.transformed(by: CGAffineTransform(scaleX: 10, y: 10)), from: output.extent.applying(CGAffineTransform(scaleX: 10, y: 10))) else { return nil }
        let size = CGFloat(cg.width + 80)
        return UIGraphicsImageRenderer(size: CGSize(width: size, height: size)).image { ctx in UIColor.white.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: size, height: size)); UIImage(cgImage: cg).draw(in: CGRect(x: 40, y: 40, width: cg.width, height: cg.height)) }
    }
    static func read(_ data: Data) -> String? {
        let request = VNDetectBarcodesRequest(); request.symbologies = [.qr]
        try? VNImageRequestHandler(data: data).perform([request])
        if let value = request.results?.compactMap(\.payloadStringValue).first(where: { SocialLink.parse($0)?.type == "person" }) { return value }
        guard let image = CIImage(data: data), let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: CIContext(), options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]) else { return nil }
        return detector.features(in: image).compactMap { ($0 as? CIQRCodeFeature)?.messageString }.first { SocialLink.parse($0)?.type == "person" }
    }
}

struct PlaylistSharingView: View {
    var playlist: SharedPlaylist
    @Environment(AppModel.self) private var app
    @State private var message: String?
    var body: some View {
        AppForm {
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
                AppForm {
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
