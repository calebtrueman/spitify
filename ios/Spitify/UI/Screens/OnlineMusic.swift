import SwiftUI

struct OnlineMusicView: View {
    let query: String
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @State private var tracks: [OnlineTrack] = []
    @State private var albums: [OnlineAlbum] = []
    @State private var error: String?
    @State private var loading = false
    @State private var albumMode = false
    @State private var selectedAlbum: OnlineAlbum?
    @State private var showDownloads = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text("Results").font(.headline)
                Spacer()
                Button("Downloads", systemImage: "arrow.down.circle") { showDownloads = true }
            }
            Picker("Search for", selection: $albumMode) {
                Text("Songs").tag(false)
                Text("Albums").tag(true)
            }.pickerStyle(.segmented)
            if loading { ProgressView("Searching…") }
            if error != nil { Text("Could not add online matches. Your library results are still available.").foregroundStyle(.secondary) }
            if query.trimmingCharacters(in: .whitespacesAndNewlines).count < 2 {
                Text("Search for a song or album to download to your library.").foregroundStyle(.secondary)
            }
            if albumMode {
                if albumResults.isEmpty && !loading { Text("No albums found.").foregroundStyle(.secondary) }
                ForEach(albumResults) { result in
                    Button {
                        if let local = result.local { router.go(.album(local.id)) } else { selectedAlbum = result.remote }
                    } label: {
                        HStack(spacing: 12) {
                            if let local = result.local { ArtworkView(local.cover).frame(width: 52, height: 52) }
                            else { SearchCover(id: result.id, album: result.title, artist: result.artist, artwork: result.remote?.artwork) }
                            VStack(alignment: .leading) { Text(result.title); Text(result.artist).font(.caption).foregroundStyle(.secondary) }
                            Spacer(); Image(systemName: "chevron.right")
                        }.padding(.vertical, 6).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }
            } else {
                if songResults.isEmpty && !loading { Text("No songs found.").foregroundStyle(.secondary) }
                ForEach(songResults) { result in
                    if let song = result.local {
                        SongRow(song: song) {
                            let songs = songResults.compactMap(\.local)
                            app.player.play(songs, from: songs.firstIndex(where: { $0.id == song.id }) ?? 0, shuffle: false, source: "Search: \(query)")
                        }
                    } else if let track = result.remote { OnlineTrackRow(track: track) }
                }
            }
        }
        .padding(16)
        .onAppear { app.musicDownloads.refreshMissingFiles() }
        .task(id: "\(albumMode):\(query)") {
            tracks = []; albums = []; error = nil; loading = false
            let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
            guard q.count >= 2 else { return }
            loading = true
            do {
                try await Task.sleep(for: .milliseconds(400))
                if albumMode {
                    let result = try await MonochromeClient().searchAlbums(q)
                    try Task.checkCancellation(); albums = result
                } else {
                    let result = try await MonochromeClient().search(q)
                    try Task.checkCancellation(); tracks = result
                }
                loading = false
            } catch {
                guard !Task.isCancelled else { return }
                self.error = error.localizedDescription; loading = false
            }
        }
        .sheet(isPresented: $showDownloads) { MusicDownloadsView() }
        .sheet(item: $selectedAlbum) { album in OnlineAlbumView(album: album) }
    }
    private var songResults: [SongSearchResult] {
        let local = app.library.library.songs.compactMap { song -> SongSearchResult? in
            guard let score = SearchMatch.score(query, title: song.title, artist: song.artist, album: song.album) else { return nil }
            return SongSearchResult(id: "local:" + song.id, title: song.title, artist: song.artist, score: score, local: song)
        }
        let remote = tracks.filter { track in track.playable && !app.library.library.songs.contains { SearchMatch.sameSong($0, track) } }.compactMap { track -> SongSearchResult? in
            guard let score = SearchMatch.score(query, title: track.title, artist: track.artist, album: track.album) else { return nil }
            return SongSearchResult(id: "online:" + track.id, title: track.title, artist: track.artist, score: score, remote: track)
        }
        return Array((local + remote).sorted { $0.score != $1.score ? $0.score > $1.score : SearchMatch.fold($0.title + " " + $0.artist) < SearchMatch.fold($1.title + " " + $1.artist) }.prefix(60))
    }
    private var albumResults: [AlbumSearchResult] {
        let local = app.library.library.albums.compactMap { album -> AlbumSearchResult? in
            guard let score = SearchMatch.score(query, title: album.title, artist: album.artist) else { return nil }
            return AlbumSearchResult(id: "local:" + album.id, title: album.title, artist: album.artist, score: score, local: album)
        }
        let remote = albums.filter { album in !local.contains { SearchMatch.fold($0.title) == SearchMatch.fold(album.title) && SearchMatch.fold($0.artist) == SearchMatch.fold(album.artist) } }.compactMap { album -> AlbumSearchResult? in
            guard let score = SearchMatch.score(query, title: album.title, artist: album.artist) else { return nil }
            return AlbumSearchResult(id: "online:" + album.id, title: album.title, artist: album.artist, score: score, remote: album)
        }
        return Array((local + remote).sorted { $0.score != $1.score ? $0.score > $1.score : SearchMatch.fold($0.title + " " + $0.artist) < SearchMatch.fold($1.title + " " + $1.artist) }.prefix(60))
    }

}

struct OnlineTrackRow: View {
    let track: OnlineTrack
    @Environment(AppModel.self) private var app
    @State private var preparing = false
    @State private var error: String?

    var body: some View {
        let job = app.musicDownloads.jobs.first { $0.id == track.id }
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 12) {
                SearchCover(id: "track:" + track.id, album: track.album.isEmpty ? track.title : track.album, artist: track.artist, artwork: track.artwork)
                VStack(alignment: .leading, spacing: 3) {
                    Text(track.title).lineLimit(2)
                    Text(track.artist).font(.caption).foregroundStyle(.secondary)
                    if let quality = job?.quality { Text(quality).font(.caption2).foregroundStyle(.secondary) }
                }
                Spacer()
                if job?.state == .complete {
                    Label("Downloaded", systemImage: "checkmark.circle").font(.caption)
                } else if job?.state.active == true {
                    Button("Cancel", role: .cancel) { app.musicDownloads.cancel(track.id) }.font(.caption)
                } else {
                    Button(preparing ? "Preparing…" : "Add to library", systemImage: "arrow.down.circle") {
                        preparing = true; error = nil
                        Task {
                            do {
                                var enriched = track
                                if track.album.isEmpty && !track.releaseID.isEmpty {
                                    let album = try await MonochromeClient().albumTracks(track.releaseID)
                                    guard let match = album.first(where: { $0.id == track.id }) else { throw MusicSourceError.message("This song is no longer in that album.") }
                                    enriched = match
                                }
                                self.error = await app.musicDownloads.enqueue([enriched])
                            } catch { self.error = error.localizedDescription }
                            preparing = false
                        }
                    }.disabled(preparing).font(.caption)
                }
            }
            if job?.state == .downloading { ProgressView(value: app.musicDownloads.progress[track.id] ?? 0) }
            if job?.state == .queued { Text("Queued").font(.caption).foregroundStyle(.secondary) }
            if job?.state == .checking { Text("Checking audio…").font(.caption).foregroundStyle(.secondary) }
            if let text = error ?? job?.error { Text(text).font(.caption).foregroundStyle(.secondary) }
        }.padding(.vertical, 6)
    }
}

struct OnlineAlbumView: View {
    let album: OnlineAlbum
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var tracks: [OnlineTrack] = []
    @State private var error: String?
    @State private var notice: String?
    @State private var addingAlbum = false
    var body: some View {
        NavigationStack {
            List {
                if let error { Text(error) }
                if tracks.isEmpty && error == nil { ProgressView("Loading songs…") }
                if !tracks.isEmpty {
                    Button { addingAlbum = true; notice = "Adding album…"; Task { notice = await app.musicDownloads.enqueue(tracks); addingAlbum = false } } label: { Image(systemName: "arrow.down.circle.fill").font(.system(size: 32)) }
                        .accessibilityLabel("Download album").disabled(addingAlbum)
                }
                if let notice { Text(notice).font(.caption) }
                ForEach(tracks) { OnlineTrackRow(track: $0) }
            }
            .navigationTitle(album.title)
            .toolbar { Button("Done") { dismiss() } }
            .task {
                do { tracks = try await MonochromeClient().albumTracks(album.id) }
                catch { self.error = error.localizedDescription }
            }
        }
    }
}

struct MusicDownloadsView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        @Bindable var downloads = app.musicDownloads
        NavigationStack {
            List {
                Section {
                    Toggle("Wi-Fi only for new downloads", isOn: $downloads.wifiOnly)
                    Text("Completed songs stay in your library and play offline.").font(.caption).foregroundStyle(.secondary)
                }
                if let message = downloads.message { Text(message) }
                if downloads.jobs.isEmpty { Text("No downloads yet.") }
                ForEach(downloads.jobs.reversed()) { job in OnlineTrackRow(track: job.track) }
            }
            .navigationTitle("Downloads")
            .toolbar { Button("Done") { dismiss() } }
        }
    }
}

private struct SongSearchResult: Identifiable {
    let id: String; let title: String; let artist: String; let score: Int
    var local: Song? = nil; var remote: OnlineTrack? = nil
}
private struct AlbumSearchResult: Identifiable {
    let id: String; let title: String; let artist: String; let score: Int
    var local: Album? = nil; var remote: OnlineAlbum? = nil
}
private struct SearchCover: View {
    let id: String; let album: String; let artist: String; let artwork: String?
    @State private var resolved: String?
    var body: some View {
        ArtworkView(key: "search:" + id, remote: artwork ?? resolved).frame(width: 52, height: 52)
            .task(id: id) { if artwork == nil { resolved = await MusicCatalog.albumArt(artist: artist, album: album) } }
    }
}
