import SwiftUI

struct SearchSubtitle: View {
    var type: String
    var creator: String
    var explicit = false
    var body: some View {
        HStack(spacing: 5) {
            if explicit {
                Text("E").font(.system(size: 9, weight: .bold)).foregroundStyle(Color(.systemBackground))
                    .frame(width: 13, height: 13).background(.secondary, in: RoundedRectangle(cornerRadius: 2))
                    .accessibilityLabel("Explicit")
            }
            Text(type + (creator.isEmpty ? "" : " · " + creator)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
    }
}

private struct MixedResult: Identifiable {
    var id: String
    var title: String
    var creator: String
    var type: String
    var artwork: String?
    var explicit = false
    var score: Int
    var local: Song?
    var track: OnlineTrack?
    var route: Route?
    var podcast: ShowSearchResult?
    var book: BookSearchResult?
    var spotify: SpotifyPlaylistResult?
    var shared: SharedPlaylist?
    var artist: OnlineArtist?
}

struct MixedSearchView: View {
    var query: String
    @Environment(AppModel.self) private var app
    @Environment(Router.self) private var router
    @State private var music = OnlineSearch()
    @State private var podcasts: [ShowSearchResult] = []
    @State private var books: [BookSearchResult] = []
    @State private var playlists: [SpotifyPlaylistResult] = []
    @State private var pending = 0
    @State private var failed: Set<String> = []
    @State private var opening: String?
    @State private var message: String?
    @State private var retry = 0

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 10) {
            if pending > 0 { ProgressView("Searching…") }
            if !failed.isEmpty { Button("Some results couldn't load. Try again") { retry += 1 }.font(.caption) }
            if let message { Text(message).font(.caption).foregroundStyle(.secondary) }
            if results.isEmpty && pending == 0 { Text("No matches yet. Try a title, artist, author or show.").foregroundStyle(.secondary) }
            ForEach(results) { result in
                if let spotify = result.spotify {
                    NavigationLink { SpotifyPlaylistPreview(input: spotify.id) } label: { row(result) }.buttonStyle(.plain)
                } else if let shared = result.shared {
                    NavigationLink { SharedPlaylistView(initial: shared) } label: { row(result) }.buttonStyle(.plain)
                } else if let artist = result.artist {
                    NavigationLink { OnlineArtistView(artist: artist) } label: { row(result) }.buttonStyle(.plain)
                } else {
                    Button { open(result) } label: { row(result) }.buttonStyle(.plain)
                }
            }
        }.padding(16)
        .task(id: "\(query):\(retry)") {
            music = OnlineSearch(); podcasts = []; books = []; playlists = []; failed = []; message = nil
            let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
            guard q.count >= 2 else { pending = 0; return }
            pending = 4
            do { try await Task.sleep(for: .milliseconds(350)) } catch { return }
            await withTaskGroup(of: Void.self) { group in
                group.addTask { @MainActor in
                    do { let result = try await MonochromeClient().searchAll(q); guard !Task.isCancelled else { return }; music = result }
                    catch { if !Task.isCancelled { failed.insert("music") } }
                    if !Task.isCancelled { pending -= 1 }
                }
                group.addTask { @MainActor in
                    let result = await app.shows.searchPodcasts(q); guard !Task.isCancelled else { return }; podcasts = result; pending -= 1
                }
                group.addTask { @MainActor in
                    let result = await app.shows.searchBooks(q); guard !Task.isCancelled else { return }; books = result; pending -= 1
                }
                group.addTask { @MainActor in
                    do {
                        let result = SpotifyPlaylists.playlistID(q).map { [SpotifyPlaylistResult(id: $0, name: "Open Spotify playlist", description: "", image: nil, owner: "Spotify")] }
                        let found: [SpotifyPlaylistResult]
                        if let result { found = result } else { found = try await SpotifyPlaylists().search(q) }
                        guard !Task.isCancelled else { return }; playlists = found
                    } catch { if !Task.isCancelled { failed.insert("playlists") } }
                    if !Task.isCancelled { pending -= 1 }
                }
            }
        }
    }

    private func row(_ result: MixedResult) -> some View {
        HStack(spacing: 12) {
            if let song = result.local { ArtworkView(song).frame(width: 56, height: 56) }
            else { PlaylistCover(url: result.artwork).frame(width: 56, height: 56) }
            VStack(alignment: .leading, spacing: 4) {
                Text(result.title).font(.body).foregroundStyle(.primary).lineLimit(2)
                SearchSubtitle(type: result.type, creator: result.creator, explicit: result.explicit)
            }
            Spacer(minLength: 0)
            if opening == result.id { ProgressView() }
        }.padding(.vertical, 5).contentShape(Rectangle()).accessibilityIdentifier("search:" + result.id)
    }

    private var results: [MixedResult] {
        let library = app.library.library
        var rows: [MixedResult] = []
        func score(_ title: String, _ creator: String, _ album: String = "") -> Int? { SearchMatch.score(query, title: title, artist: creator, album: album) }
        for song in library.songs {
            guard let score = score(song.title, song.artist, song.album) else { continue }
            rows.append(.init(id: "song:" + song.id, title: song.title, creator: song.artist, type: song.isAudiobook ? "Audiobook" : song.isPodcast ? "Episode" : "Song", explicit: song.explicit == true || music.tracks.contains { SearchMatch.sameSong(song, $0) && $0.explicit == true }, score: score, local: song))
        }
        for playlist in app.library.playlists {
            guard let score = score(playlist.name, app.profile.name) else { continue }
            rows.append(.init(id: "localPlaylist:" + playlist.id, title: playlist.name, creator: app.profile.name.isEmpty ? "You" : app.profile.name, type: "Playlist", score: score, local: app.library.songs(of: playlist).first, route: .playlist(playlist.id)))
        }
        for (id, chapters) in Dictionary(grouping: app.library.books, by: \.albumKey) {
            guard let first = chapters.first, let score = score(first.album, first.albumArtist) else { continue }
            rows.append(.init(id: "localBook:" + id, title: first.album, creator: first.albumArtist, type: "Audiobook", score: score, local: first, route: .localBook(id)))
        }
        for show in app.shows.shows {
            if let score = score(show.title, show.author), !podcasts.contains(where: { $0.feedURL == show.feedURL }), !books.contains(where: { $0.id == show.id }) {
                rows.append(.init(id: "savedShow:" + show.id, title: show.title, creator: show.author, type: show.kind == .audiobook ? "Audiobook" : "Podcast", artwork: show.artworkURL, score: score, route: show.kind == .audiobook ? .book(show.id) : .show(show.id)))
            }
            if show.kind == .podcast {
                for episode in show.episodes {
                    guard let score = score(episode.title, show.title) else { continue }
                    rows.append(.init(id: "episode:" + episode.id, title: episode.title, creator: show.title, type: "Episode", artwork: episode.artworkURL ?? show.artworkURL, explicit: episode.explicit == true, score: score, local: app.shows.song(episode, in: show)))
                }
            }
        }
        for track in music.tracks {
            guard !library.songs.contains(where: { SearchMatch.sameSong($0, track) }), let score = score(track.title, track.artist, track.album) else { continue }
            rows.append(.init(id: "track:" + track.id, title: track.title, creator: track.artist, type: "Song", artwork: track.artwork, explicit: track.explicit == true, score: score, track: track))
        }
        for album in library.albums {
            guard let score = score(album.title, album.artist) else { continue }
            let remote = music.albums.first { SearchMatch.fold($0.title) == SearchMatch.fold(album.title) && SearchMatch.fold($0.artist) == SearchMatch.fold(album.artist) }
            rows.append(.init(id: "album:" + album.id, title: album.title, creator: album.artist, type: "Album", explicit: remote?.explicit == true || album.songs.contains { $0.explicit == true }, score: score, local: album.cover, route: remote.map(Route.catalogAlbum) ?? .album(album.id)))
        }
        for album in music.albums {
            guard !library.albums.contains(where: { SearchMatch.fold($0.title) == SearchMatch.fold(album.title) && SearchMatch.fold($0.artist) == SearchMatch.fold(album.artist) }), let score = score(album.title, album.artist) else { continue }
            rows.append(.init(id: "release:" + album.id, title: album.title, creator: album.artist, type: "Album", artwork: album.artwork, explicit: album.explicit == true, score: score, route: .catalogAlbum(album)))
        }
        for artist in music.artists {
            guard let score = score(artist.name, "") else { continue }
            rows.append(.init(id: "artist:" + artist.id, title: artist.name, creator: "", type: "Artist", artwork: artist.artwork, score: score + 20, artist: artist))
        }
        for artist in library.artists where !music.artists.contains(where: { SearchMatch.fold($0.name) == SearchMatch.fold(artist.name) }) {
            guard let score = score(artist.name, "") else { continue }
            rows.append(.init(id: "localArtist:" + artist.name, title: artist.name, creator: "", type: "Artist", score: score + 20, local: artist.cover, route: .artist(artist.name)))
        }
        for show in podcasts {
            guard let score = score(show.title, show.author) else { continue }
            rows.append(.init(id: "podcast:" + show.id, title: show.title, creator: show.author, type: "Podcast", artwork: show.artworkURL, explicit: show.explicit == true, score: score, podcast: show))
        }
        for book in books {
            guard let score = score(book.title, book.author) else { continue }
            rows.append(.init(id: "book:" + book.id, title: book.title, creator: book.author, type: "Audiobook", artwork: book.coverURL, score: score, book: book))
        }
        for playlist in app.social.playlists {
            guard let score = score(playlist.name, playlist.sourceName ?? "") else { continue }
            rows.append(.init(id: "shared:" + playlist.key, title: playlist.name, creator: playlist.sourceName ?? app.social.state.profiles[playlist.owner]?.name ?? "Spitify", type: playlist.kind == "mix" ? "Shared Mix" : playlist.kind.capitalized, artwork: playlist.image, score: score, shared: playlist))
        }
        for playlist in playlists {
            let score = SpotifyPlaylists.playlistID(query) != nil ? 1100 : score(playlist.name, playlist.owner)
            guard let score else { continue }
            rows.append(.init(id: "spotify:" + playlist.id, title: playlist.name, creator: playlist.owner, type: "Playlist", artwork: playlist.image, score: score, spotify: playlist))
        }
        return Array(rows.sorted { a, b in a.score != b.score ? a.score > b.score : a.title.localizedStandardCompare(b.title) == .orderedAscending }.prefix(100))
    }

    private func open(_ result: MixedResult) {
        if let route = result.route { router.go(route); return }
        if let track = result.track { let song = app.musicStreams.register(track); app.player.play([song], shuffle: false, source: "Search: " + query); return }
        if let song = result.local { app.player.play([song], shuffle: false, source: "Search: " + query); return }
        opening = result.id
        Task {
            defer { opening = nil }
            if let podcast = result.podcast {
                if let show = await app.shows.subscribe(feedURL: podcast.feedURL, art: podcast.artworkURL, follow: false) { router.go(.show(show.id)) }
                else { message = "Couldn't open that podcast. Please try again." }
            } else if let book = result.book {
                if let show = await app.shows.addBook(book) { router.go(.book(show.id)) }
                else { message = "Couldn't open that audiobook. Please try again." }
            }
        }
    }
}

struct OnlineArtistView: View {
    var artist: OnlineArtist
    @State private var result = OnlineSearch()
    @State private var message: String?
    @State private var albumCursor = 0
    @State private var loadingMore = false
    @State private var moreRequest = 0
    @Environment(Router.self) private var router
    @Environment(AppModel.self) private var app
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 14) {
                PlaylistCover(url: artist.artwork).frame(width: 200, height: 200).frame(maxWidth: .infinity)
                Button(app.artistFollows.contains(artist.id) ? "Following" : "Follow artist") {
                    if app.artistFollows.contains(artist.id) { app.artistFollows.unfollow(artist.id) }
                    else { app.artistFollows.follow(artist, releases: result.albums) }
                }.buttonStyle(.bordered).disabled(result.albums.isEmpty)
                if let message { Text(message) }
                Text("Songs").font(.headline)
                ForEach(result.tracks) { track in OnlineTrackRow(track: track) }
                if loadingMore { ProgressView("Loading more songs…") }
                else if albumCursor < result.albums.count { Button("Show more songs") { moreRequest += 1 }.buttonStyle(.bordered) }
                Text("Albums & singles").font(.headline)
                ForEach(result.albums) { album in
                    Button { router.go(.catalogAlbum(album)) } label: {
                        HStack { PlaylistCover(url: album.artwork).frame(width: 56, height: 56); VStack(alignment: .leading) { Text(album.title); SearchSubtitle(type: "Album", creator: album.artist, explicit: album.explicit == true) }; Spacer() }
                    }.buttonStyle(.plain)
                }
            }.padding(16)
        }.navigationTitle(artist.name)
        .task(id: artist.id) {
            do {
                result = try await MonochromeClient().artistPage(artist.id)
                albumCursor = 0
                await loadMore()
            } catch { message = error.localizedDescription }
        }
        .task(id: moreRequest) { if moreRequest > 0 { await loadMore() } }
    }
    @MainActor private func loadMore() async {
        guard !loadingMore else { return }
        loadingMore = true; defer { loadingMore = false }
        let end = min(albumCursor + 3, result.albums.count)
        do {
            while albumCursor < end {
                let tracks = try await MonochromeClient().albumTracks(result.albums[albumCursor].id)
                try Task.checkCancellation()
                var seen = Set(result.tracks.map(\.id))
                result.tracks += tracks.filter { seen.insert($0.id).inserted }
                albumCursor += 1
            }
            message = nil
        } catch { if !Task.isCancelled { message = "Couldn't load more songs. Tap Show more songs to retry." } }
    }
}
