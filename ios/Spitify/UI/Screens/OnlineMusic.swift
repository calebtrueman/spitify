import SwiftUI

struct OnlineMusicView: View {
    let query: String
    @Environment(AppModel.self) private var app
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
                Text("From Monochrome").font(.headline)
                Spacer()
                Button("Downloads", systemImage: "arrow.down.circle") { showDownloads = true }
            }
            Picker("Search for", selection: $albumMode) {
                Text("Songs").tag(false)
                Text("Albums").tag(true)
            }.pickerStyle(.segmented)
            if loading { ProgressView("Searching…") }
            if let error { Text(error).foregroundStyle(.secondary) }
            if query.trimmingCharacters(in: .whitespacesAndNewlines).count < 2 {
                Text("Search for a song or album to download to your library.").foregroundStyle(.secondary)
            } else if !loading && error == nil && (albumMode ? albums.isEmpty : tracks.isEmpty) {
                Text("No matches found.").foregroundStyle(.secondary)
            }
            if albumMode {
                ForEach(albums) { album in
                    Button { selectedAlbum = album } label: {
                        HStack { VStack(alignment: .leading) { Text(album.title); Text(album.artist).font(.caption).foregroundStyle(.secondary) }; Spacer(); Image(systemName: "chevron.right") }
                    }.buttonStyle(.plain)
                }
            } else {
                ForEach(tracks) { track in OnlineTrackRow(track: track) }
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
}

struct OnlineTrackRow: View {
    let track: OnlineTrack
    @Environment(AppModel.self) private var app
    @State private var preparing = false
    @State private var error: String?

    var body: some View {
        let job = app.musicDownloads.jobs.first { $0.id == track.id }
        VStack(alignment: .leading, spacing: 4) {
            HStack {
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
                    Button(preparing ? "Preparing…" : "Download", systemImage: "arrow.down.circle") {
                        preparing = true; error = nil
                        Task {
                            do {
                                var enriched = track
                                if track.album.isEmpty && !track.releaseID.isEmpty {
                                    let album = try await MonochromeClient().albumTracks(track.releaseID)
                                    guard let match = album.first(where: { $0.id == track.id }) else { throw MusicSourceError.message("This song is no longer in that album.") }
                                    enriched = match
                                }
                                app.musicDownloads.enqueue([enriched])
                            } catch { self.error = error.localizedDescription }
                            preparing = false
                        }
                    }.disabled(preparing || !track.playable).font(.caption)
                }
            }
            if job?.state == .downloading { ProgressView(value: app.musicDownloads.progress[track.id] ?? 0) }
            if job?.state == .queued { Text("Queued").font(.caption).foregroundStyle(.secondary) }
            if job?.state == .checking { Text("Checking audio…").font(.caption).foregroundStyle(.secondary) }
            if let text = error ?? job?.error { Text(text).font(.caption).foregroundStyle(.secondary) }
            if !track.playable { Text("Unavailable from this source").font(.caption).foregroundStyle(.secondary) }
        }.padding(.vertical, 6)
    }
}

struct OnlineAlbumView: View {
    let album: OnlineAlbum
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var tracks: [OnlineTrack] = []
    @State private var error: String?
    var body: some View {
        NavigationStack {
            List {
                if let error { Text(error) }
                if tracks.isEmpty && error == nil { ProgressView("Loading songs…") }
                if !tracks.isEmpty { Button("Download album") { app.musicDownloads.enqueue(tracks) } }
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
