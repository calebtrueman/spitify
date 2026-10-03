import SwiftUI

private func relative(_ d: Date?) -> String {
    guard let d else { return "" }
    if Date().timeIntervalSince(d) < 7 * 86_400 { return RelativeDateTimeFormatter().localizedString(for: d, relativeTo: Date()) }
    let sameYear = Calendar.current.isDate(d, equalTo: Date(), toGranularity: .year)
    return sameYear ? d.formatted(.dateTime.month(.abbreviated).day()) : d.formatted(.dateTime.month(.abbreviated).day().year())
}

struct PodcastsView: View {
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @Environment(\.palette) private var p
    @State private var query = ""
    @State private var results: [ShowSearchResult]?
    @State private var searching = false
    @State private var searchRequest = UUID()
    @State private var searchFailed = false
    @State private var addingRSS = false
    @State private var rss = ""
    @State private var rssBusy = false
    @State private var rssFailed = false
    @State private var openingShows: Set<String> = []
    @State private var failedShows: Set<String> = []
    private let popular = ["News", "Comedy", "True crime", "Technology", "History", "Science", "Business", "Sports"]

    var body: some View {
        let shows = app.shows.podcasts
        let all = shows.flatMap { s in app.shows.songs(s).map { (s, $0) } }
        let inProgress = all.filter { app.shows.resume[$0.1.resumeKey].map { !$0.played && $0.positionMs > 0 } == true }
            .sorted { (app.shows.resume[$0.1.resumeKey]?.updated ?? .distantPast) > (app.shows.resume[$1.1.resumeKey]?.updated ?? .distantPast) }.prefix(10)
        let fresh = all.filter { app.shows.resume[$0.1.resumeKey]?.played != true }.sorted { $0.1.dateAdded > $1.1.dateAdded }.prefix(30)
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if rssBusy { ProgressView("Adding show…").frame(maxWidth: .infinity).padding() }
                if rssFailed {
                    SpokenSearchFailure(message: "Couldn't follow this show. Check the RSS link and try again.", retryTitle: "Edit RSS link") { addingRSS = true }
                }
                if searching { ProgressView().frame(maxWidth: .infinity).padding() }
                if searchFailed {
                    SpokenSearchFailure { search(query) }
                } else if let results {
                    SectionHeader(title: "Results for “\(query)”")
                    if results.isEmpty && !searching { EmptyState(title: "No shows found", message: "Try another name, or add the show's RSS link.", icon: "magnifyingglass") }
                    ForEach(results) { r in resultRow(r, following: shows.contains { $0.feedURL == r.feedURL }) }
                } else {
                    if shows.isEmpty { EmptyState(title: "Find your next show", message: "Search millions of podcasts, follow the ones you like, and download episodes for offline listening.", icon: "dot.radiowaves.left.and.right") }
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) { ForEach(popular, id: \.self) { t in Pill(title: t, selected: false) { search(t) } } }.padding(.horizontal, 16)
                    }.padding(.vertical, 8)
                    if !inProgress.isEmpty { SectionHeader(title: "Continue listening"); ForEach(Array(inProgress), id: \.1.id) { s, song in EpisodeRow(show: s, song: song, showArt: true) } }
                    if !shows.isEmpty {
                        TileShelf(title: "Your shows", tiles: shows.map { s in Tile(id: s.id, title: s.title, subtitle: s.author, song: nil, remoteArt: s.artworkURL) { router.go(.show(s.id)) } }, width: 136)
                        SectionHeader(title: "New episodes")
                        ForEach(Array(fresh), id: \.1.id) { s, song in EpisodeRow(show: s, song: song, showArt: true) }
                    }
                }
            }.padding(.bottom, 24)
        }
        .background(p.background)
        .navigationTitle("Podcasts")
        .searchable(text: $query, prompt: "Search all podcasts")
        .onSubmit(of: .search) { search(query) }
        .onChange(of: query) { _, q in if q.isEmpty { searchRequest = UUID(); results = nil; searching = false; searchFailed = false } }
        .refreshable { await app.shows.refreshAll() }
        .toolbar { ToolbarItem(placement: .topBarTrailing) { Button { addingRSS = true } label: { Image(systemName: "dot.radiowaves.up.forward") }.accessibilityLabel("Add a show by RSS") } }
        .alert("Add a show by RSS", isPresented: $addingRSS) {
            TextField("https://example.com/feed.xml", text: $rss).textInputAutocapitalization(.never).keyboardType(.URL)
            Button("Follow") {
                guard !rssBusy else { return }
                let link = rss.trimmingCharacters(in: .whitespacesAndNewlines)
                rssBusy = true; rssFailed = false
                Task {
                    if let s = await app.shows.subscribe(feedURL: link) { rss = ""; router.go(.show(s.id)) }
                    else { rssFailed = true }
                    rssBusy = false
                }
            }.disabled(rssBusy || rss.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            Button("Cancel", role: .cancel) {}
        }
    }

    private func search(_ t: String) {
        let term = t.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !term.isEmpty else { return }
        let request = UUID(); searchRequest = request
        query = term; searching = true; searchFailed = false; results = nil
        Task {
            do {
                let found = try await app.shows.searchPodcasts(term)
                guard searchRequest == request else { return }
                results = found; searching = false
            } catch {
                guard searchRequest == request else { return }
                searching = false; searchFailed = true
            }
        }
    }

    private func resultRow(_ r: ShowSearchResult, following: Bool) -> some View {
        VStack(alignment: .leading, spacing: 0) {
        HStack(spacing: 12) {
            Button { openShow(r, follow: false) } label: {
                HStack(spacing: 12) {
                    ArtworkView(key: r.feedURL, remote: r.artworkURL, cornerRadius: 8).frame(width: MediaLayout.rowArt, height: MediaLayout.rowArt)
                    MediaRowText(title: r.title, subtitle: [r.author, r.genre].compactMap { $0 }.joined(separator: " • "))
                    Spacer()
                }.contentShape(Rectangle())
            }.buttonStyle(.pressable(0.98))
            if openingShows.contains(r.id) { ProgressView().accessibilityLabel("Opening show") }
            Button { openShow(r, follow: true) } label: {
                IconControlLabel(symbol: following ? "checkmark.circle.fill" : "plus.circle", selected: following)
            }.buttonStyle(.plain).accessibilityLabel(following ? "Following" : "Follow show")
        }.disabled(openingShows.contains(r.id)).padding(.horizontal, MediaLayout.inset).padding(.vertical, MediaLayout.rowPadding)
            if failedShows.contains(r.id) { Text("Couldn't open this show. Tap to try again.").text(.caption).foregroundStyle(p.secondary).padding(.horizontal, 16).padding(.bottom, 8) }
        }
    }

    private func openShow(_ result: ShowSearchResult, follow: Bool) {
        guard openingShows.insert(result.id).inserted else { return }
        failedShows.remove(result.id)
        Task {
            if let show = await app.shows.subscribe(feedURL: result.feedURL, art: result.artworkURL, follow: follow) { router.go(.show(show.id)) }
            else { failedShows.insert(result.id) }
            openingShows.remove(result.id)
        }
    }
}

private struct SpokenSearchFailure: View {
    var message = "Couldn't search. Check your connection and try again."
    var retryTitle = "Try again"
    var retry: () -> Void
    var body: some View {
        VStack(spacing: 8) {
            Text(message).multilineTextAlignment(.center).foregroundStyle(.secondary)
            Button(retryTitle, action: retry).frame(minHeight: 44)
        }.frame(maxWidth: .infinity).padding(16)
    }
}

/// One episode/chapter: date & length, title, notes preview, progress, then download / played / play.
struct EpisodeRow: View {
    var show: Show?
    var song: Song
    var showArt: Bool
    var onPlay: (() -> Void)? = nil
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p

    var body: some View {
        let r = app.shows.resume[song.resumeKey]
        let episode = show?.episodes.first { song.episodeId?.hasSuffix("/\($0.id)") == true }
        let isCurrent = app.player.current?.id == song.id
        let dur = song.durationMs > 0 ? song.durationMs : (r?.durationMs ?? 0)
        let meta = [relative(episode?.published), r?.played == true ? "Played" : (r.map { $0.positionMs > 0 && dur > 0 } == true ? "\((dur - r!.positionMs).formattedLong) left" : (dur > 0 ? dur.formattedLong : ""))].filter { !$0.isEmpty }.joined(separator: " • ")
        let play = onPlay ?? { app.player.playEpisode(song) }
        VStack(alignment: .leading, spacing: 8) {
            Button(action: play) {
                VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top, spacing: 12) {
                    if showArt { ArtworkView(song, cornerRadius: 8).frame(width: MediaLayout.rowArt, height: MediaLayout.rowArt) }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(song.title).text(.body).fontWeight(.semibold).foregroundStyle(isCurrent ? p.accent : p.text)
                            .lineLimit(2).fixedSize(horizontal: false, vertical: true).multilineTextAlignment(.leading)
                        if showArt { Text(song.album).text(.bodyS).foregroundStyle(p.secondary).lineLimit(2) }
                    }
                    Spacer(minLength: 0)
                }
                if let sum = episode?.summary, !sum.isEmpty { Text(sum).text(.caption).foregroundStyle(p.secondary).lineLimit(2).multilineTextAlignment(.leading) }
                }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
            }.buttonStyle(.pressable(0.99))
                HStack(spacing: 4) {
                    Text(meta).text(.caption).foregroundStyle(p.secondary).fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                    if let show, let episode {
                        if episode.localFile != nil {
                            Button { app.shows.deleteDownload(episode, in: show) } label: { Image(systemName: "arrow.down.circle.fill").foregroundStyle(p.accent).frame(width: 44, height: 44).contentShape(Rectangle()) }.accessibilityLabel("Remove download")
                        } else if app.shows.downloads[episode.id] != nil {
                            ProgressView().frame(width: 44, height: 44)
                        } else {
                            Button { app.shows.download(episode, in: show) } label: { Image(systemName: "arrow.down.circle").foregroundStyle(p.secondary).frame(width: 44, height: 44).contentShape(Rectangle()) }.accessibilityLabel("Download episode")
                        }
                    }
                    Button { app.shows.setPlayed(song.resumeKey, r?.played != true, dur) } label: {
                        Image(systemName: r?.played == true ? "checkmark.circle.fill" : "circle").foregroundStyle(r?.played == true ? p.accent : p.secondary).frame(width: 44, height: 44).contentShape(Rectangle())
                    }.accessibilityLabel(r?.played == true ? "Mark unplayed" : "Mark played")
                    PlayButton(playing: isCurrent && app.player.isPlaying, size: 44) { if isCurrent { app.player.toggle() } else { play() } }
                        .accessibilityLabel(isCurrent && app.player.isPlaying ? "Pause episode" : "Play episode")
                }.font(.system(size: 21))
                if let r, !r.played, r.positionMs > 0, dur > 0 { ProgressView(value: Double(r.positionMs) / Double(dur)).tint(p.accent) }
        }
        .padding(.horizontal, MediaLayout.inset).padding(.vertical, 12).contentShape(Rectangle())
    }
}

struct ShowView: View {
    var id: String
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    @Environment(\.dismiss) private var dismiss
    @State private var filter = 0
    @State private var expanded = false
    var body: some View {
        if let show = app.shows.shows.first(where: { $0.id == id }) {
            let songs = app.shows.songs(show).filter { s in
                switch filter {
                case 1: return app.shows.resume[s.resumeKey]?.played != true
                case 2: return show.episodes.first { s.episodeId?.hasSuffix("/\($0.id)") == true }?.localFile != nil
                default: return true
                }
            }
            CollectionLayout(title: show.title, subtitle: show.author,
                             metadata: "Podcast • \(show.episodes.count) episodes", artKey: show.id, remoteArt: show.artworkURL) {
                ArtworkView(key: show.id, remote: show.artworkURL, cornerRadius: 8)
            } actions: {
                CollectionActionBar(enabled: !show.episodes.isEmpty, playLabel: "Play latest episode", play: {
                    if let latest = app.shows.songs(show).first { app.player.playEpisode(latest) }
                }) {
                    IconControl(title: show.following ? "Unfollow podcast" : "Follow podcast",
                                symbol: show.following ? "checkmark.circle.fill" : "plus.circle", selected: show.following) {
                        app.shows.setFollowing(show, !show.following)
                    }
                    Text(show.following ? "Following" : "Follow").text(.label).foregroundStyle(p.secondary)
                }
            } content: {
                if !show.summary.isEmpty {
                    Text(show.summary).text(.bodyS).foregroundStyle(p.secondary).lineLimit(expanded ? nil : 3)
                        .padding(.horizontal, MediaLayout.inset).padding(.bottom, 16).onTapGesture { withAnimation { expanded.toggle() } }
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) { ForEach(Array(["All", "Unplayed", "Downloaded"].enumerated()), id: \.offset) { i, title in
                        Pill(title: title, selected: filter == i) { filter = i }
                    } }.padding(.horizontal, MediaLayout.inset)
                }
                SectionHeader(title: "Episodes", eyebrow: "\(songs.count) available")
                LazyVStack(spacing: 0) { ForEach(songs) { song in
                    EpisodeRow(show: show, song: song, showArt: false)
                    Divider().overlay(p.tint).padding(.horizontal, MediaLayout.inset)
                } }
            }
        } else { EmptyState(title: "Show not found", message: "Find this show again in Podcasts.", icon: "dot.radiowaves.left.and.right") }
    }
}

// MARK: - Audiobooks

struct BookItem: Identifiable {
    var id: String
    var title: String
    var author: String
    var chapters: [Song]
    var show: Show?
    var artKey: String
    var artURL: String?
    var route: Route
}

@MainActor func bookItems(_ app: AppModel) -> [BookItem] {
    let online = app.shows.books.map { s in BookItem(id: "lv" + s.id, title: s.title, author: s.author, chapters: app.shows.songs(s), show: s, artKey: s.id, artURL: s.artworkURL, route: .book(s.id)) }
    let mine = Dictionary(grouping: app.library.books, by: \.albumKey).map { key, ch -> BookItem in
        let sorted = ch.sorted { ($0.disc, $0.track, $0.fileName) < ($1.disc, $1.track, $1.fileName) }
        return BookItem(id: "lb" + key, title: sorted[0].album, author: sorted[0].albumArtist, chapters: sorted, show: nil, artKey: key, artURL: nil, route: .localBook(key))
    }.sorted { $0.title < $1.title }
    return online + mine
}

@MainActor func bookProgress(_ app: AppModel, _ chapters: [Song]) -> (index: Int, leftMs: Int64, fraction: Double, started: Bool) {
    let total = max(1, chapters.reduce(Int64(0)) { $0 + $1.durationMs })
    var listened: Int64 = 0
    var last: (Int, Resume)?
    for (i, c) in chapters.enumerated() {
        guard let r = app.shows.resume[c.resumeKey] else { continue }
        listened += r.played ? c.durationMs : r.positionMs
        if last == nil || r.updated > last!.1.updated { last = (i, r) }
    }
    let idx = last.map { $0.1.played ? min(chapters.count - 1, $0.0 + 1) : $0.0 } ?? 0
    return (idx, max(0, total - listened), min(1, Double(listened) / Double(total)), last != nil)
}

struct BooksView: View {
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @Environment(\.palette) private var p
    @State private var query = ""
    @State private var results: [BookSearchResult]?
    @State private var searching = false
    @State private var searchRequest = UUID()
    @State private var searchFailed = false
    private let classics = ["Sherlock Holmes", "Jane Austen", "Mark Twain", "Dickens", "Tolkien", "Shakespeare", "Poe", "Jules Verne", "Dracula"]

    var body: some View {
        let books = bookItems(app)
        let reading = books.filter { let pr = bookProgress(app, $0.chapters); return pr.started && pr.fraction < 0.99 }
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if searching { ProgressView().frame(maxWidth: .infinity).padding() }
                if searchFailed {
                    SpokenSearchFailure { search(query) }
                } else if let results {
                    SectionHeader(title: "LibriVox results", eyebrow: "Free public-domain recordings")
                    if results.isEmpty && !searching { EmptyState(title: "No books found", message: "Try a title or an author's name.", icon: "book") }
                    ForEach(results) { r in BookResultRow(r: r) }
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) { ForEach(classics, id: \.self) { c in Pill(title: c, selected: false) { search(c) } } }.padding(.horizontal, 16)
                    }.padding(.vertical, 8)
                    if !reading.isEmpty { SectionHeader(title: "Continue listening"); ForEach(reading) { b in ContinueBookRow(book: b) } }
                    if books.isEmpty {
                        EmptyState(title: "Your bookshelf is empty", message: "Search LibriVox for free classics, or put your own audiobooks (MP3, M4A, M4B) in Files › On My iPhone › Spitify › Audiobooks.", icon: "book.closed")
                        HStack { Spacer(); ImportButton(audiobooks: true); Spacer() }
                    } else {
                        SectionHeader(title: "Your books")
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 3), spacing: 16) {
                            ForEach(books) { b in
                                Button { router.go(b.route) } label: {
                                    VStack(alignment: .leading, spacing: 4) {
                                        ArtworkView(key: b.artKey, remote: b.artURL, cornerRadius: 6).aspectRatio(0.72, contentMode: .fit).shadow(radius: 8)
                                        Text(b.title).text(.titleS).foregroundStyle(p.text).lineLimit(2).multilineTextAlignment(.leading)
                                        Text(b.author).text(.caption).foregroundStyle(p.secondary).lineLimit(1)
                                    }
                                }.buttonStyle(.pressable)
                            }
                        }.padding(.horizontal, 16)
                    }
                }
            }.padding(.bottom, 24)
        }
        .background(p.background)
        .navigationTitle("Audiobooks")
        .searchable(text: $query, prompt: "Search 20,000+ free books")
        .onSubmit(of: .search) { search(query) }
        .onChange(of: query) { _, q in if q.isEmpty { searchRequest = UUID(); results = nil; searching = false; searchFailed = false } }
    }

    private func search(_ t: String) {
        let term = t.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !term.isEmpty else { return }
        let request = UUID(); searchRequest = request
        query = term; searching = true; searchFailed = false; results = nil
        Task {
            do {
                let found = try await app.shows.searchBooks(term)
                guard searchRequest == request else { return }
                results = found; searching = false
            } catch {
                guard searchRequest == request else { return }
                searching = false; searchFailed = true
            }
        }
    }
}

struct BookResultRow: View {
    var r: BookSearchResult
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @Environment(\.palette) private var p
    @State private var busy = false
    @State private var failed = false
    var body: some View {
        let owned = app.shows.shows.first { $0.feedURL == "archive:\(r.id)" }
        Button {
            if let owned { router.go(.book(owned.id)); return }
            busy = true; failed = false
            Task {
                if let s = await app.shows.addBook(r) { router.go(.book(s.id)) }
                else { failed = true }
                busy = false
            }
        } label: {
            HStack(spacing: 12) {
                ArtworkView(key: r.id, remote: r.coverURL, cornerRadius: 6).frame(width: 56, height: 78)
                VStack(alignment: .leading, spacing: 2) {
                    Text(r.title).text(.body).fontWeight(.semibold).foregroundStyle(p.text).lineLimit(2).fixedSize(horizontal: false, vertical: true).multilineTextAlignment(.leading)
                    Text(r.author).text(.bodyS).foregroundStyle(p.secondary).lineLimit(2)
                    Text([r.seconds > 0 ? (r.seconds * 1000).formattedLong : nil, r.language.isEmpty ? nil : r.language].compactMap { $0 }.joined(separator: " · ")).text(.labelS).foregroundStyle(p.tertiary)
                    if failed { Text("Couldn't add this book. Tap to try again.").text(.caption).foregroundStyle(p.secondary).fixedSize(horizontal: false, vertical: true) }
                }
                Spacer()
                if busy { ProgressView() } else {
                    IconControlLabel(symbol: owned != nil ? "checkmark.circle.fill" : "plus.circle", selected: owned != nil).accessibilityLabel(owned != nil ? "In library" : "Add audiobook")
                }
            }.padding(.horizontal, MediaLayout.inset).padding(.vertical, MediaLayout.rowPadding).contentShape(Rectangle())
        }.buttonStyle(.pressable(0.98)).disabled(busy)
    }
}

struct ContinueBookRow: View {
    var book: BookItem
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @Environment(\.palette) private var p
    var body: some View {
        let pr = bookProgress(app, book.chapters)
        Button { router.go(book.route) } label: {
            HStack(spacing: 12) {
                ArtworkView(key: book.artKey, remote: book.artURL, cornerRadius: 6).frame(width: 56, height: 78)
                VStack(alignment: .leading, spacing: 4) {
                    Text(book.title).text(.body).fontWeight(.semibold).foregroundStyle(p.text).lineLimit(2).fixedSize(horizontal: false, vertical: true)
                    Text("Chapter \(pr.index + 1) of \(book.chapters.count) · \(pr.leftMs.formattedLong) left").text(.caption).foregroundStyle(p.secondary)
                    ProgressView(value: pr.fraction).tint(p.accent)
                }
                PlayButton(playing: false, size: 44) { app.player.playBook(book.chapters, from: pr.index, title: book.title) }
            }.frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.vertical, 8).contentShape(Rectangle())
        }.buttonStyle(.pressable(0.98))
    }
}

struct BookView: View {
    var showId: String? = nil
    var localKey: String? = nil
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @Environment(\.palette) private var p
    @Environment(\.dismiss) private var dismiss
    @State private var expanded = false

    var body: some View {
        if let book = bookItems(app).first(where: { $0.id == (showId.map { "lv" + $0 } ?? "lb" + (localKey ?? "")) }) {
            let pr = bookProgress(app, book.chapters)
            CollectionLayout(title: book.title, subtitle: book.author,
                             metadata: "Audiobook • \(book.chapters.count) chapters · \(book.chapters.reduce(Int64(0)) { $0 + $1.durationMs }.formattedLong)",
                             artKey: book.artKey, remoteArt: book.artURL, portrait: true) {
                ArtworkView(key: book.artKey, remote: book.artURL, cornerRadius: 8)
            } actions: {
                CollectionActionBar(enabled: !book.chapters.isEmpty,
                                    playLabel: pr.started ? "Continue chapter \(pr.index + 1)" : "Start listening", play: {
                    app.player.playBook(book.chapters, from: pr.index, title: book.title)
                }) {
                    if let show = book.show, show.episodes.contains(where: { $0.localFile == nil }) {
                        IconControl(title: "Download whole book", symbol: "arrow.down.circle") {
                            show.episodes.filter { $0.localFile == nil }.forEach { app.shows.download($0, in: show) }
                        }
                    }
                    if book.show == nil {
                        IconControl(title: "Edit book info and cover", symbol: "pencil") { router.editing = (book.chapters, true) }
                    }
                }
            } content: {
                if pr.started {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Chapter \(pr.index + 1) · \(Int(pr.fraction * 100))% complete · \(pr.leftMs.formattedLong) left")
                            .text(.caption).foregroundStyle(p.secondary)
                        ProgressView(value: pr.fraction).tint(p.accent)
                    }.padding(.horizontal, MediaLayout.inset).padding(.bottom, 16)
                }
                if let summary = book.show?.summary, !summary.isEmpty {
                    Text(summary).text(.bodyS).foregroundStyle(p.secondary).lineLimit(expanded ? nil : 4)
                        .padding(.horizontal, MediaLayout.inset).padding(.bottom, 12).onTapGesture { withAnimation { expanded.toggle() } }
                }
                SectionHeader(title: "Chapters")
                LazyVStack(spacing: 0) {
                    ForEach(Array(book.chapters.enumerated()), id: \.element.id) { i, chapter in
                        EpisodeRow(show: book.show, song: chapter, showArt: false) { app.player.playBook(book.chapters, from: i, title: book.title) }
                        Divider().overlay(p.tint).padding(.horizontal, MediaLayout.inset)
                    }
                }
            }
            .toolbar {
                if let show = book.show {
                    Menu { Button("Remove from library", systemImage: "trash", role: .destructive) { dismiss(); app.shows.remove(show) } }
                    label: { IconControlLabel(symbol: "ellipsis") }.accessibilityLabel("Book options")
                }
            }
        } else { EmptyState(title: "Book not found", message: "It may have been removed.", icon: "book") }
    }
}
