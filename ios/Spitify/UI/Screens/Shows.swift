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
    @State private var addingRSS = false
    @State private var rss = ""
    private let popular = ["News", "Comedy", "True crime", "Technology", "History", "Science", "Business", "Sports"]

    var body: some View {
        let shows = app.shows.podcasts
        let all = shows.flatMap { s in app.shows.songs(s).map { (s, $0) } }
        let inProgress = all.filter { app.shows.resume[$0.1.resumeKey].map { !$0.played && $0.positionMs > 0 } == true }
            .sorted { (app.shows.resume[$0.1.resumeKey]?.updated ?? .distantPast) > (app.shows.resume[$1.1.resumeKey]?.updated ?? .distantPast) }.prefix(10)
        let fresh = all.filter { app.shows.resume[$0.1.resumeKey]?.played != true }.sorted { $0.1.dateAdded > $1.1.dateAdded }.prefix(30)
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if searching { ProgressView().frame(maxWidth: .infinity).padding() }
                if let results {
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
        .onChange(of: query) { _, q in if q.isEmpty { results = nil } }
        .refreshable { await app.shows.refreshAll() }
        .toolbar { ToolbarItem(placement: .topBarTrailing) { Button { addingRSS = true } label: { Image(systemName: "dot.radiowaves.up.forward") } } }
        .alert("Add a show by RSS", isPresented: $addingRSS) {
            TextField("https://example.com/feed.xml", text: $rss).textInputAutocapitalization(.never).keyboardType(.URL)
            Button("Follow") { Task { if let s = await app.shows.subscribe(feedURL: rss.trimmingCharacters(in: .whitespaces)) { router.go(.show(s.id)) }; rss = "" } }
            Button("Cancel", role: .cancel) {}
        }
    }

    private func search(_ t: String) {
        query = t; searching = true
        Task { results = await app.shows.searchPodcasts(t); searching = false }
    }

    private func resultRow(_ r: ShowSearchResult, following: Bool) -> some View {
        Button {
            Task { if let s = await app.shows.subscribe(feedURL: r.feedURL, art: r.artworkURL) { router.go(.show(s.id)) } }
        } label: {
            HStack(spacing: 12) {
                ArtworkView(key: r.feedURL, remote: r.artworkURL, cornerRadius: 8).frame(width: 64, height: 64)
                VStack(alignment: .leading, spacing: 2) {
                    Text(r.title).text(.titleS).foregroundStyle(p.text).lineLimit(2)
                    Text([r.author, r.genre].compactMap { $0 }.joined(separator: " • ")).text(.caption).foregroundStyle(p.secondary).lineLimit(1)
                }
                Spacer()
                Text(following ? "Following" : "Follow").text(.label).foregroundStyle(following ? p.text : p.onAccent)
                    .padding(.horizontal, 14).padding(.vertical, 7).background(following ? p.tint : p.accent, in: Capsule())
            }.padding(.horizontal, 16).padding(.vertical, 6).contentShape(Rectangle())
        }.buttonStyle(.pressable(0.98))
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
        Button(action: play) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top, spacing: 12) {
                    if showArt { ArtworkView(song, cornerRadius: 8).frame(width: 56, height: 56) }
                    VStack(alignment: .leading, spacing: 2) {
                        if showArt { Text(song.album).text(.labelS).foregroundStyle(p.secondary).lineLimit(1) }
                        Text(song.title).text(.titleS).foregroundStyle(isCurrent ? p.accent : p.text).lineLimit(2).multilineTextAlignment(.leading)
                    }
                }
                if let sum = episode?.summary, !sum.isEmpty { Text(sum).text(.caption).foregroundStyle(p.secondary).lineLimit(2).multilineTextAlignment(.leading) }
                HStack(spacing: 4) {
                    Text(meta).text(.labelS).foregroundStyle(p.secondary)
                    Spacer()
                    if let show, let episode {
                        if episode.localFile != nil {
                            Button { app.shows.deleteDownload(episode, in: show) } label: { Image(systemName: "arrow.down.circle.fill").foregroundStyle(p.accent) }.frame(width: 40, height: 40)
                        } else if app.shows.downloads[episode.id] != nil {
                            ProgressView().frame(width: 40, height: 40)
                        } else {
                            Button { app.shows.download(episode, in: show) } label: { Image(systemName: "arrow.down.circle").foregroundStyle(p.secondary) }.frame(width: 40, height: 40)
                        }
                    }
                    Button { app.shows.setPlayed(song.resumeKey, r?.played != true, dur) } label: {
                        Image(systemName: r?.played == true ? "checkmark.circle.fill" : "circle").foregroundStyle(r?.played == true ? p.accent : p.secondary)
                    }.frame(width: 40, height: 40)
                    Button { if isCurrent { app.player.toggle() } else { play() } } label: {
                        Image(systemName: isCurrent && app.player.isPlaying ? "pause.fill" : "play.fill").foregroundStyle(p.background).frame(width: 38, height: 38).background(p.text, in: Circle())
                    }
                }.font(.system(size: 21))
                if let r, !r.played, r.positionMs > 0, dur > 0 { ProgressView(value: Double(r.positionMs) / Double(dur)).tint(p.accent) }
            }
            .padding(.horizontal, 16).padding(.vertical, 10).contentShape(Rectangle())
        }.buttonStyle(.pressable(0.99))
    }
}

struct ShowView: View {
    var id: String
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    @Environment(\.dismiss) private var dismiss
    @State private var color = Color(hex: 0x2A2A2E)
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
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(alignment: .bottom, spacing: 16) {
                        ArtworkView(key: show.id, remote: show.artworkURL, cornerRadius: 12).frame(width: 140, height: 140).shadow(radius: 16)
                        VStack(alignment: .leading, spacing: 4) {
                            Text("PODCAST").text(.labelS).foregroundStyle(p.secondary)
                            Text(show.title).text(.headlineS).foregroundStyle(p.text).lineLimit(3)
                            Text(show.author).text(.bodyS).foregroundStyle(p.secondary).lineLimit(1)
                        }
                    }.padding(16)
                    HStack {
                        Button { dismiss(); app.shows.remove(show) } label: { Text("Following").text(.label).foregroundStyle(p.text).padding(.horizontal, 16).padding(.vertical, 8).background(p.tint, in: Capsule()) }
                        Spacer()
                        if let latest = app.shows.songs(show).first {
                            Button { app.player.playEpisode(latest) } label: { Label("Latest episode", systemImage: "play.fill").text(.label).foregroundStyle(p.onAccent).padding(.horizontal, 16).padding(.vertical, 10).background(p.accent, in: Capsule()) }
                        }
                    }.padding(.horizontal, 16)
                    if !show.summary.isEmpty {
                        Text(show.summary).text(.bodyS).foregroundStyle(p.secondary).lineLimit(expanded ? nil : 3).padding(16).onTapGesture { withAnimation { expanded.toggle() } }
                    }
                    HStack(spacing: 8) { ForEach(Array(["All", "Unplayed", "Downloaded"].enumerated()), id: \.offset) { i, t in Pill(title: t, selected: filter == i) { filter = i } } }.padding(.horizontal, 16)
                    Text("\(songs.count) episodes").text(.labelS).foregroundStyle(p.secondary).padding(16)
                    LazyVStack(spacing: 0) { ForEach(songs) { s in EpisodeRow(show: show, song: s, showArt: false); Divider().opacity(0.3) } }
                }.padding(.bottom, 24)
            }
            .background(LinearGradient(colors: [p.isDark ? color : color.mix(.white, 0.55), p.background], startPoint: .top, endPoint: .center).ignoresSafeArea())
            .artColor(key: show.id, remote: show.artworkURL, into: $color)
            .navigationBarTitleDisplayMode(.inline)
        }
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
    private let classics = ["Sherlock Holmes", "Jane Austen", "Mark Twain", "Dickens", "Tolkien", "Shakespeare", "Poe", "Jules Verne", "Dracula"]

    var body: some View {
        let books = bookItems(app)
        let reading = books.filter { let pr = bookProgress(app, $0.chapters); return pr.started && pr.fraction < 0.99 }
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if searching { ProgressView().frame(maxWidth: .infinity).padding() }
                if let results {
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
        .onChange(of: query) { _, q in if q.isEmpty { results = nil } }
    }

    private func search(_ t: String) { query = t; searching = true; Task { results = await app.shows.searchBooks(t); searching = false } }
}

struct BookResultRow: View {
    var r: BookSearchResult
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @Environment(\.palette) private var p
    @State private var busy = false
    var body: some View {
        let owned = app.shows.shows.first { $0.feedURL == "archive:\(r.id)" }
        Button {
            if let owned { router.go(.book(owned.id)); return }
            busy = true
            Task { if let s = await app.shows.addBook(r) { router.go(.book(s.id)) }; busy = false }
        } label: {
            HStack(spacing: 12) {
                ArtworkView(key: r.id, remote: r.coverURL, cornerRadius: 6).frame(width: 56, height: 78)
                VStack(alignment: .leading, spacing: 2) {
                    Text(r.title).text(.titleS).foregroundStyle(p.text).lineLimit(2).multilineTextAlignment(.leading)
                    Text(r.author).text(.caption).foregroundStyle(p.secondary).lineLimit(1)
                    Text([r.seconds > 0 ? (r.seconds * 1000).formattedLong : nil, r.language.isEmpty ? nil : r.language].compactMap { $0 }.joined(separator: " · ")).text(.labelS).foregroundStyle(p.tertiary)
                }
                Spacer()
                if busy { ProgressView() } else {
                    Text(owned != nil ? "In library" : "Add").text(.label).foregroundStyle(owned != nil ? p.text : p.onAccent)
                        .padding(.horizontal, 14).padding(.vertical, 7).background(owned != nil ? p.tint : p.accent, in: Capsule())
                }
            }.padding(.horizontal, 16).padding(.vertical, 6).contentShape(Rectangle())
        }.buttonStyle(.pressable(0.98))
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
                    Text(book.title).text(.titleS).foregroundStyle(p.text).lineLimit(1)
                    Text("Chapter \(pr.index + 1) of \(book.chapters.count) · \(pr.leftMs.formattedLong) left").text(.caption).foregroundStyle(p.secondary)
                    ProgressView(value: pr.fraction).tint(p.accent)
                }
                PlayButton(playing: false, size: 44) { app.player.playBook(book.chapters, from: pr.index, title: book.title) }
            }.padding(.horizontal, 16).padding(.vertical, 8)
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
    @State private var color = Color(hex: 0x2A2A2E)
    @State private var expanded = false

    var body: some View {
        if let book = bookItems(app).first(where: { $0.id == (showId.map { "lv" + $0 } ?? "lb" + (localKey ?? "")) }) {
            let pr = bookProgress(app, book.chapters)
            ScrollView {
                VStack(spacing: 10) {
                    ArtworkView(key: book.artKey, remote: book.artURL, cornerRadius: 8).frame(width: 180, height: 250).shadow(color: .black.opacity(0.5), radius: 24, y: 12).padding(.top, 10)
                    Text(book.title).text(.headlineS).foregroundStyle(p.text).multilineTextAlignment(.center).padding(.horizontal, 24)
                    Text(book.author).text(.body).foregroundStyle(p.secondary)
                    Text("\(book.chapters.count) chapters · \(book.chapters.reduce(Int64(0)) { $0 + $1.durationMs }.formattedLong)").text(.caption).foregroundStyle(p.tertiary)
                    if pr.started {
                        ProgressView(value: pr.fraction).tint(p.accent).frame(width: 220)
                        Text("\(Int(pr.fraction * 100))% · \(pr.leftMs.formattedLong) left").text(.labelS).foregroundStyle(p.secondary)
                    }
                    HStack(spacing: 14) {
                        Button { app.player.playBook(book.chapters, from: pr.index, title: book.title) } label: {
                            Label(pr.started ? "Continue · Ch. \(pr.index + 1)" : "Start listening", systemImage: "play.fill").text(.label).foregroundStyle(p.onAccent)
                                .padding(.horizontal, 24).padding(.vertical, 12).background(p.accent, in: Capsule())
                        }.buttonStyle(.pressable)
                        if let show = book.show, show.episodes.contains(where: { $0.localFile == nil }) {
                            Button { show.episodes.filter { $0.localFile == nil }.forEach { app.shows.download($0, in: show) } } label: { Image(systemName: "arrow.down.circle").font(.system(size: 26)).foregroundStyle(p.text) }
                        }
                        if book.show == nil { Button { router.editing = (book.chapters, true) } label: { Image(systemName: "pencil.circle").font(.system(size: 26)).foregroundStyle(p.text) } }
                    }
                    if let s = book.show?.summary, !s.isEmpty {
                        Text(s).text(.bodyS).foregroundStyle(p.secondary).lineLimit(expanded ? nil : 4).padding(20).onTapGesture { withAnimation { expanded.toggle() } }
                    }
                    if let show = book.show { Button("Remove from library") { dismiss(); app.shows.remove(show) }.text(.label).foregroundStyle(p.secondary) }
                    SectionHeader(title: "Chapters")
                    LazyVStack(spacing: 0) {
                        ForEach(Array(book.chapters.enumerated()), id: \.element.id) { i, c in
                            EpisodeRow(show: book.show, song: c, showArt: false) { app.player.playBook(book.chapters, from: i, title: book.title) }
                            Divider().opacity(0.3)
                        }
                    }
                }.padding(.bottom, 24)
            }
            .background(LinearGradient(colors: [p.isDark ? color : color.mix(.white, 0.55), p.background], startPoint: .top, endPoint: .center).ignoresSafeArea())
            .artColor(key: book.artKey, remote: book.artURL, into: $color)
            .navigationBarTitleDisplayMode(.inline)
        } else { EmptyState(title: "Book not found", message: "It may have been removed.", icon: "book") }
    }
}
