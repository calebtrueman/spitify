import SwiftUI

struct OnlineMusicView: View {
    let query: String
    var body: some View { MixedSearchView(query: query) }
}

struct DownloadMark: View {
    var complete = false
    var active = false
    var progress: Double = 0
    var body: some View {
        ZStack {
            if active {
                if progress > 0 {
                    Circle().stroke(.secondary.opacity(0.25), lineWidth: 2)
                    Circle().trim(from: 0, to: min(1, progress)).stroke(.green, style: StrokeStyle(lineWidth: 2, lineCap: .round)).rotationEffect(.degrees(-90))
                } else { ProgressView().controlSize(.small) }
                if progress > 0 { Image(systemName: "arrow.down").font(.system(size: 10, weight: .bold)) }
            } else {
                Image(systemName: complete ? "arrow.down.circle.fill" : "arrow.down.circle").font(.system(size: 23))
                    .foregroundStyle(complete ? Color.green : Color.secondary)
            }
        }.frame(width: 24, height: 24)
    }
}

@MainActor func savedSong(_ track: OnlineTrack, app: AppModel) -> Song? {
    if let job = app.musicDownloads.jobs.first(where: { $0.id == track.id && $0.state == .complete }),
       let song = app.library.library.songs.first(where: { $0.location == job.relativePath }) { return song }
    return app.library.library.songs.first { $0.kind != .remote && SearchMatch.sameSong($0, track) && (track.album.isEmpty || AudioFallback.sameRelease($0.album, track.album)) }
}

struct OnlineTrackRow: View {
    let track: OnlineTrack
    var trackNumber: Int? = nil
    var onPlay: ((Song) -> Void)? = nil
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @State private var preparing = false

    var body: some View {
        let job = app.musicDownloads.jobs.first { $0.id == track.id }
        let song = savedSong(track, app: app)
        if let onPlay {
            let playable = song ?? MusicStreams.song(track)
            HStack(spacing: 0) {
                SongRow(song: playable, trackNumber: trackNumber, subtitle: track.artist, downloaded: song != nil, onTap: {
                    app.musicStreams.register(track); onPlay(playable)
                })
                if song == nil {
                    Button {
                        if job?.state.active == true { app.musicDownloads.cancel(track.id) }
                        else { app.musicStreams.save([track]); Task { _ = await app.musicDownloads.enqueue([track]) } }
                    } label: { DownloadMark(active: job?.state.active == true, progress: app.musicDownloads.progress[track.id] ?? 0).frame(width: 44, height: 44) }
                        .buttonStyle(.plain).padding(.trailing, 8).accessibilityLabel(job?.state.active == true ? "Cancel download" : "Download \(track.title)")
                }
            }.onAppear { app.musicStreams.register(track) }
        } else {
        HStack(spacing: 12) {
            Button {
                if let onPlay { if let song { onPlay(song) } }
                else { router.go(.catalogSong(track)) }
            } label: {
                HStack(spacing: 12) {
                    if let trackNumber { Text(String(trackNumber)).font(.callout).frame(width: 26) }
                    else { SearchCover(id: "track:" + track.id, album: track.album.isEmpty ? track.title : track.album, artist: track.artist, artwork: track.artwork) }
                    VStack(alignment: .leading, spacing: 3) {
                        Text(track.title).lineLimit(2).font(.body)
                        Text(track.artist).font(.caption)
                    }
                    Spacer(minLength: 0)
                }.foregroundStyle(Color.primary).contentShape(Rectangle())
            }.buttonStyle(.plain).disabled(onPlay != nil && song == nil)
            Button {
                if job?.state.active == true { app.musicDownloads.cancel(track.id) }
                else {
                    preparing = true
                    Task {
                        var enriched = track
                        if track.album.isEmpty, !track.releaseID.isEmpty,
                           let album = try? await MonochromeClient().albumTracks(track.releaseID),
                           let match = album.first(where: { $0.id == track.id }) { enriched = match }
                        app.musicStreams.save([enriched]); _ = await app.musicDownloads.enqueue([enriched]); preparing = false
                    }
                }
            } label: {
                DownloadMark(complete: song != nil, active: preparing || job?.state.active == true, progress: app.musicDownloads.progress[track.id] ?? 0)
                    .frame(width: 44, height: 44)
            }.buttonStyle(.plain).disabled(song != nil || preparing)
                .accessibilityLabel(song != nil ? "Downloaded" : job?.state.active == true ? "Cancel download" : "Download \(track.title)")
        }.padding(.vertical, 6).padding(.horizontal, onPlay == nil ? 0 : 16)
        }
    }
}

struct OnlineAlbumView: View {
    let album: OnlineAlbum
    var single: OnlineTrack? = nil
    @Environment(AppModel.self) private var app
    @State private var tracks: [OnlineTrack] = []
    @State private var failed = false
    @State private var loading = true
    @State private var adding = false
    @State private var reload = 0
    var body: some View {
        let songs = tracks.map { savedSong($0, app: app) ?? MusicStreams.song($0) }
        CollectionView(title: single?.title ?? album.title, kind: single == nil ? "Album" : "Song", subtitle: album.artist,
            art: songs.first, songs: songs, trackNumbers: single == nil,
            toolbarExtra: AnyView(HStack(spacing: 4) { libraryButton; downloadButton }),
            catalogTracks: tracks, remoteArt: single?.artwork ?? album.artwork) {
                if loading { ProgressView().frame(maxWidth: .infinity).padding() }
                if failed { Button("Couldn't load songs. Try again") { reload += 1 }.padding() }
            }
            .toolbar { ToolbarItem(placement: .topBarTrailing) {
                if songs.contains(where: { $0.kind != .remote }) { Button { appRouterEdit(songs.filter { $0.kind != .remote }) } label: { Image(systemName: "pencil") }.accessibilityLabel("Edit song details") }
            } }
            .task(id: reload) {
                loading = true; failed = false
                app.musicDownloads.refreshMissingFiles()
                if let single { tracks = [single] }
                else { tracks = app.musicDownloads.jobs.map(\.track).filter { $0.releaseID == album.id }.sorted { ($0.discNumber, $0.trackNumber) < ($1.discNumber, $1.trackNumber) } }
                do {
                    let loaded = try await MonochromeClient().albumTracks(album.id)
                    tracks = single.map { chosen in loaded.filter { $0.id == chosen.id } } ?? loaded
                    if tracks.isEmpty, let single { tracks = [single] }
                } catch { failed = tracks.isEmpty }
                tracks = tracks.map { track in
                    var track = track
                    if track.album.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { track.album = album.title }
                    if track.releaseID.isEmpty { track.releaseID = album.id }
                    if track.albumArtist == nil { track.albumArtist = album.artist }
                    if track.artwork == nil { track.artwork = album.artwork }
                    return track
                }
                tracks.forEach { app.musicStreams.register($0) }
                loading = false
            }
    }
    private var libraryButton: some View {
        let saved = !tracks.isEmpty && tracks.allSatisfy { app.musicStreams.savedIDs.contains($0.id) }
        return Button {
            if saved { app.musicStreams.remove(tracks) } else { app.musicStreams.save(tracks) }
        } label: { Image(systemName: saved ? "checkmark.circle.fill" : "plus.circle").font(.system(size: 24)).frame(width: 44, height: 44) }
            .disabled(tracks.isEmpty).accessibilityLabel(saved ? "Remove from Library" : "Add to Library")
    }
    private var downloadButton: some View {
        let songs = tracks.compactMap { savedSong($0, app: app) }
        let ids = Set(tracks.map(\.id))
        let jobs = app.musicDownloads.jobs.filter { ids.contains($0.id) }
        let active = jobs.contains { $0.state.active }
        let complete = !tracks.isEmpty && songs.count == tracks.count
        let transferred = jobs.filter { $0.state.active }.reduce(0.0) { sum, job in sum + (app.musicDownloads.progress[job.id] ?? 0) }
        let progress = tracks.isEmpty ? 0 : (Double(songs.count) + transferred) / Double(tracks.count)
        let label = complete ? "Downloaded" : active ? "Cancel downloads" : single == nil ? "Download album" : "Download song"
        return Button {
            if active { for job in jobs where job.state.active { app.musicDownloads.cancel(job.id) } }
            else { app.musicStreams.save(tracks); adding = true; Task { _ = await app.musicDownloads.enqueue(tracks); adding = false } }
        } label: {
            DownloadMark(complete: complete, active: active || adding, progress: progress).frame(width: 44, height: 44)
        }.disabled(complete || adding || tracks.isEmpty).accessibilityLabel(label)
    }
    @Environment(Router.self) private var router
    private func appRouterEdit(_ songs: [Song]) { router.editing = (songs, single == nil) }
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
