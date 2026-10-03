import SwiftUI

struct ArtistReleasesView: View {
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    var body: some View {
        AppList {
            Section {
                Toggle("Release notifications", isOn: Binding(get: { app.artistFollows.notifications }, set: { enabled in Task { await app.artistFollows.setNotifications(enabled) } }))
                Text("Spitify checks followed artists when you open the app. New releases appear here; alerts need notification permission.").text(.caption).foregroundStyle(.secondary)
            }
            if app.artistFollows.artists.isEmpty { Text("Follow an artist from search to see their releases here.") }
            Section("Following") {
                ForEach(app.artistFollows.artists) { artist in NavigationLink(artist.name) { OnlineArtistView(artist: artist) } }
            }
            Section("Latest releases") {
                ForEach(app.artistFollows.releases) { notice in
                    Button { router.go(.catalogAlbum(notice.album)) } label: {
                        HStack(spacing: 12) {
                            PlaylistCover(url: notice.album.artwork).frame(width: MediaLayout.rowArt, height: MediaLayout.rowArt)
                            MediaRowText(title: notice.album.title, subtitle: "Album · " + notice.artist.name, explicit: notice.album.explicit == true)
                            Spacer(minLength: 0)
                        }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, MediaLayout.rowPadding).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }
            }
            if let message = app.artistFollows.message { Text(message) }
        }.navigationTitle("New releases")
        .task { await app.artistFollows.refresh() }
        .refreshable { await app.artistFollows.refresh() }
    }
}

struct ArtistLandingView: View {
    var name: String
    @Environment(AppModel.self) private var app
    @State private var artist: OnlineArtist?
    @State private var loading = true
    @State private var failed = false
    @State private var retry = 0
    var body: some View {
        Group {
            if let artist { OnlineArtistView(artist: artist) }
            else if app.library.library.artistByName[name] != nil { ArtistView(name: name) }
            else if loading { ProgressView("Finding artist…").frame(maxWidth: .infinity, maxHeight: .infinity) }
            else {
                VStack {
                    EmptyState(title: failed ? "Couldn't load artist" : "No artist page found", message: failed ? "Check your connection and try again." : "Try searching for the artist by name.", icon: "person")
                    Button("Try again") { retry += 1 }.buttonStyle(.bordered)
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task(id: "\(name):\(retry)") {
            artist = nil; loading = true; failed = false
            do {
                let found = try await MonochromeClient().searchAll(name)
                guard !Task.isCancelled else { return }
                artist = found.artists.first { SearchMatch.fold($0.name) == SearchMatch.fold(name) }
            } catch { guard !Task.isCancelled else { return }; failed = true }
            loading = false
        }
    }
}
