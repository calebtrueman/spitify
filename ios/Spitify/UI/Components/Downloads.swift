import SwiftUI

/// Whether a song can play without a connection.
enum DownloadState: Equatable {
    /// A file on this iPhone (your own music, or a finished download).
    case onDevice
    /// Streamed only; can be downloaded.
    case online
    case downloading(Double?)
    /// Podcasts, books and anything else that doesn't use music downloads.
    case notApplicable
}

extension Song {
    var isStream: Bool { id.hasPrefix("stream:") }
    var streamTrackID: String? { isStream ? String(id.dropFirst(7)) : nil }
}

@MainActor func downloadState(_ song: Song, app: AppModel) -> DownloadState {
    if song.isSpoken { return .notApplicable }
    guard let id = song.streamTrackID else { return song.kind == .file ? .onDevice : .notApplicable }
    // Finished downloads become local songs in the library, so only queued/running jobs matter here.
    guard let job = app.musicDownloads.jobs.first(where: { $0.id == id }), job.state.active else { return .online }
    return .downloading(app.musicDownloads.progress[id])
}

/// Saves streamed songs to the library and queues them for download, confirming the result.
@MainActor func downloadSongs(_ songs: [Song], app: AppModel, router: Router) {
    let tracks = songs.compactMap { $0.streamTrackID.flatMap { app.musicStreams.tracks[$0] } }
    guard !tracks.isEmpty else { return }
    app.musicStreams.save(tracks)
    Task { router.confirm(await app.musicDownloads.enqueue(tracks)) }
}

/// Trailing control on a song row: download, or progress (tap to cancel).
struct SongDownloadButton: View {
    var song: Song
    var state: DownloadState
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @Environment(\.palette) private var p
    var body: some View {
        switch state {
        case .online:
            Button { Haptics.tap(); downloadSongs([song], app: app, router: router) } label: {
                Image(systemName: "arrow.down.circle").font(.system(size: 20)).foregroundStyle(p.secondary).frame(width: 40, height: 44).contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityLabel("Download \(song.title)")
        case .downloading(let progress):
            Button { if let id = song.streamTrackID { app.musicDownloads.cancel(id) } } label: {
                Group {
                    if let progress, progress > 0 { ProgressView(value: progress).progressViewStyle(.circular) } else { ProgressView() }
                }.frame(width: 40, height: 44)
            }.buttonStyle(.plain).accessibilityLabel("Downloading \(song.title). Tap to cancel")
        default: EmptyView()
        }
    }
}

/// Header action for a list of songs: download every streamed one, show progress, or confirm all are offline.
struct DownloadAllButton: View {
    var songs: [Song]
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    var body: some View {
        let music = songs.filter { !$0.isSpoken }
        let streams = music.filter(\.isStream)
        let waiting = streams.filter { if case .downloading = downloadState($0, app: app) { return false } else { return true } }
        if music.isEmpty { EmptyView() }
        else if streams.isEmpty { IconControl(title: "All songs downloaded", symbol: "checkmark.circle.fill", selected: true) {} .disabled(true) }
        else if waiting.isEmpty {
            Button { streams.compactMap(\.streamTrackID).forEach(app.musicDownloads.cancel) } label: { ProgressView().frame(width: 44, height: 44) }
                .accessibilityLabel("Downloading. Tap to cancel")
        } else {
            IconControl(title: waiting.count == streams.count ? "Download all \(waiting.count)" : "Download the other \(waiting.count)", symbol: "arrow.down.circle") {
                downloadSongs(waiting, app: app, router: router)
            }
        }
    }
}
