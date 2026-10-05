import PhotosUI
import SwiftUI

struct OnboardingView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.palette) private var p
    @State private var step = 0
    @State private var name = ""
    @State private var photo: PhotosPickerItem?
    @State private var picked = Set<String>()
    @State private var loading = false

    var body: some View {
        ZStack {
            LinearGradient(colors: [p.accent.mix(.black, 0.55), p.background, p.background], startPoint: .top, endPoint: .bottom).ignoresSafeArea()
            VStack(spacing: 0) {
                HStack(spacing: 6) { ForEach(0..<5) { i in Capsule().fill(i <= step ? p.accent : .white.opacity(0.2)).frame(width: i == step ? 22 : 8, height: 8) } }.padding(.top, 20)
                Group {
                    switch step {
                    case 0: page("Welcome to Spitify", "Your music, podcasts and audiobooks — on your iPhone, no account, no ads. Playlists made for you, learned right here on the device.", "Get started", { step = 1 }) {
                        Image(systemName: "waveform").font(.system(size: 60, weight: .bold)).foregroundStyle(p.onAccent).frame(width: 120, height: 120).background(p.accent, in: Circle()) }
                    case 1: page("What should we call you?", "It's used for things like “Made for you” — it never leaves your phone.", "Next", { app.profile.name = name.trimmingCharacters(in: .whitespaces); step = 2 }, enabled: !name.trimmingCharacters(in: .whitespaces).isEmpty) {
                        TextField("Your name", text: $name).text(.headlineS).multilineTextAlignment(.center).padding(14).background(p.tint, in: RoundedRectangle(cornerRadius: 12)).padding(.horizontal, 30).submitLabel(.next) }
                    case 2: page("Add a profile picture", "Pick a photo, or keep your initial.", app.profile.photoVersion > 0 ? "Looks good" : "Skip for now", { step = 3 }) {
                        PhotosPicker(selection: $photo, matching: .images) { Avatar(size: 150).overlay(alignment: .bottomTrailing) { Image(systemName: "camera.fill").foregroundStyle(.white).frame(width: 38, height: 38).background(.black.opacity(0.6), in: Circle()) } } }
                    case 3: page("Find your music", "Spitify plays files you add to Files › On My iPhone › Spitify, and downloaded songs from your Music library.", "Continue", { step = 4; Task { loading = true; await app.library.scan(); loading = false } }) {
                        VStack(spacing: 14) {
                            Image(systemName: "folder.badge.plus").font(.system(size: 64)).foregroundStyle(p.accent)
                            Toggle("Include my Music app library", isOn: Binding(get: { app.library.includeMusicLibrary }, set: { app.library.includeMusicLibrary = $0 })).padding(.horizontal, 40)
                            ImportButton()
                        } }
                    default: artists
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity), removal: .move(edge: .leading).combined(with: .opacity)))
                .id(step)
            }
            .animation(.spring(duration: 0.4), value: step)
        }
        .foregroundStyle(p.text)
        .onChange(of: photo) { _, item in Task { if let d = try? await item?.loadTransferable(type: Data.self) { saveProfilePhoto(d, app) } } }
    }

    private func page<C: View>(_ title: String, _ body: String, _ cta: String, _ action: @escaping () -> Void, enabled: Bool = true, @ViewBuilder content: () -> C) -> some View {
        VStack(spacing: 18) {
            Spacer()
            content()
            Text(title).text(.display).multilineTextAlignment(.center).padding(.top, 14)
            Text(body).text(.body).foregroundStyle(p.secondary).multilineTextAlignment(.center).padding(.horizontal, 30)
            Button { Haptics.tap(); action() } label: { Text(cta).text(.title).foregroundStyle(p.onAccent).padding(.horizontal, 40).padding(.vertical, 16).background(p.accent.opacity(enabled ? 1 : 0.4), in: Capsule()) }
                .disabled(!enabled).padding(.top, 14)
            Spacer()
        }
    }

    private var artists: some View {
        let list = app.library.library.artists.filter { !$0.name.lowercased().hasPrefix("unknown") }.sorted { $0.songs.count > $1.songs.count }.prefix(30)
        return VStack(spacing: 10) {
            Text("Pick a few artists you love").text(.headline).padding(.top, 24)
            Text("Your first mixes start here — they'll keep learning from everything you play.").text(.bodyS).foregroundStyle(p.secondary).multilineTextAlignment(.center).padding(.horizontal, 24)
            if loading { Spacer(); ProgressView(); Spacer() }
            else if list.isEmpty {
                Spacer()
                Text("No music yet — that's fine. Add some any time from the Files app.").text(.body).foregroundStyle(p.secondary).multilineTextAlignment(.center).padding(30)
                Spacer()
            } else {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 14)], spacing: 16) {
                        ForEach(Array(list)) { a in
                            let on = picked.contains(a.name)
                            Button { Haptics.tap(); if on { picked.remove(a.name) } else { picked.insert(a.name) } } label: {
                                VStack(spacing: 6) {
                                    ArtworkView(a.cover, circle: true).aspectRatio(1, contentMode: .fit)
                                        .overlay(Circle().stroke(p.accent, lineWidth: on ? 3 : 0))
                                        .overlay { if on { Image(systemName: "checkmark").font(.system(size: 18, weight: .bold)).foregroundStyle(p.onAccent).frame(width: 34, height: 34).background(p.accent, in: Circle()) } }
                                    Text(a.name).text(.label).lineLimit(2).multilineTextAlignment(.center)
                                }
                            }.buttonStyle(.pressable)
                        }
                    }.padding(20)
                }
            }
            Button {
                app.profile.seedArtists = picked
                app.profile.onboarded = true
                Haptics.success()
            } label: {
                Text(picked.isEmpty ? "Skip" : "Done (\(picked.count))").text(.title).foregroundStyle(picked.isEmpty ? p.text : p.onAccent)
                    .padding(.horizontal, 40).padding(.vertical, 14).background(picked.isEmpty ? p.tint : p.accent, in: Capsule())
            }.padding(.bottom, 20)
        }
    }
}

struct AddToPlaylistSheet: View {
    @Environment(Router.self) private var router
    var songs: [Song]
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var creating = false
    @State private var name = ""
    var body: some View {
        NavigationStack {
            AppList {
                Button { creating = true } label: { Label("New playlist", systemImage: "plus") }
                ForEach(app.library.playlists) { pl in
                    Button { app.library.add(songs, to: pl.id); Haptics.success(); router.confirm("Added to \(pl.name)"); dismiss() } label: {
                        MediaRowContent(inset: 0) {
                            Group { if let key = app.library.playlistArtworkKey(pl.id) { ArtworkView(key: key, remote: nil, cornerRadius: 4) } else { ArtworkView(app.library.songs(of: pl).first, cornerRadius: 4) } }.frame(width: MediaLayout.rowArt, height: MediaLayout.rowArt)
                        } detail: { MediaRowText(title: pl.name, subtitle: songCount(pl.songIds.count)) } trailing: { EmptyView() }
                    }
                }
            }
            .navigationTitle(songs.count == 1 ? "Add to playlist" : "Add \(songs.count) songs").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .alert("Give your playlist a name", isPresented: $creating) {
                TextField("My playlist", text: $name)
                Button("Create") { let playlist = app.library.createPlaylist(name, songs: songs); router.confirm("Added to \(playlist.name)"); dismiss() }
                Button("Cancel", role: .cancel) {}
            }
        }
        .presentationDetents([.medium, .large])
    }
}

struct SongInfoSheet: View {
    var song: Song
    @Environment(AppModel.self) private var app
    var body: some View {
        NavigationStack {
            AppList {
                row("Title", song.title); row("Artist", song.artist); row("Album", song.album); row("Album artist", song.albumArtist)
                row("Track", [song.disc > 1 ? "Disc \(song.disc)" : nil, song.track > 0 ? "Track \(song.track)" : nil].compactMap { $0 }.joined(separator: ", "))
                row("Year", song.year > 0 ? String(song.year) : "—"); row("Genre", song.genre ?? "—"); row("Length", song.durationMs.formattedDuration)
                row("Format", song.fileExtension.uppercased() + (song.durationMs > 0 && song.sizeBytes > 0 ? " • ~\(song.sizeBytes * 8 / song.durationMs) kbps" : ""))
                if song.sizeBytes > 0 { row("Size", ByteCountFormatter.string(fromByteCount: song.sizeBytes, countStyle: .file)) }
                row("Location", song.kind == .musicLibrary ? "Music library" : song.kind == .remote ? "Streamed" : "/\(song.location)")
                row("Plays", "\(app.library.playCount(song.id))")
            }
            .navigationTitle("Song info").navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
    }
    private func row(_ k: String, _ v: String) -> some View { LabeledContent(k) { Text(v.isEmpty ? "—" : v).multilineTextAlignment(.trailing) } }
}

/// Edit tags and artwork in one song or a whole album/book.
struct MetadataEditor: View {
    var songs: [Song]
    var albumMode: Bool
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var artist = ""
    @State private var album = ""
    @State private var albumArtist = ""
    @State private var genre = ""
    @State private var year = ""
    @State private var track = ""
    @State private var disc = ""
    @State private var newArt: Data?
    @State private var newArtURL: String?
    @State private var photo: PhotosPickerItem?
    @State private var candidates: [MetadataCandidate]?
    @State private var searching = false
    @State private var saving = false
    @State private var saveError: String?

    var body: some View {
        let first = songs[0]
        let book = first.isAudiobook
        NavigationStack {
            AppForm {
                Section {
                    HStack(spacing: 16) {
                        Group {
                            if let d = newArt, let img = UIImage(data: d) { Image(uiImage: img).resizable().scaledToFill() }
                            else if let u = newArtURL { ArtworkView(key: u, remote: u, cornerRadius: 0) }
                            else { ArtworkView(first, cornerRadius: 0) }
                        }.frame(width: 110, height: 110).clipShape(RoundedRectangle(cornerRadius: 10))
                        VStack(alignment: .leading, spacing: 10) {
                            PhotosPicker("Choose image", selection: $photo, matching: .images)
                            if FileManager.default.fileExists(atPath: ArtCache.shared.customURL(first.albumKey).path) {
                                Button("Use file cover", role: .destructive) { ArtCache.shared.removeCustom(first.albumKey); app.library.artVersion += 1 }
                            }
                            Text("Saved into the selected \(songs.count == 1 ? "file" : "files")").text(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                Section {
                    Button { search() } label: { HStack { Label(book ? "Find book info online" : "Find info online", systemImage: "icloud.and.arrow.down"); if searching { Spacer(); ProgressView() } } }
                    if let candidates {
                        if candidates.isEmpty { Text("No matches — try editing the artist/title first.").foregroundStyle(.secondary) }
                        ForEach(candidates.prefix(12)) { c in
                            Button { apply(c) } label: {
                                HStack(spacing: 12) {
                                    ArtworkView(key: c.artURL ?? c.id.uuidString, remote: c.artURL, cornerRadius: 6).frame(width: 50, height: 50)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(c.title).lineLimit(1)
                                        Text([c.artist, c.album].filter { !$0.isEmpty }.joined(separator: " • ")).text(.caption).foregroundStyle(.secondary).lineLimit(1)
                                        let diff = !albumMode && first.durationMs > 0 && c.durationMs > 0 ? abs(c.durationMs - first.durationMs) / 1000 : nil
                                        Text([c.source, c.year.map(String.init), diff.map { $0 <= 3 ? "length matches" : "\($0)s off" }].compactMap { $0 }.joined(separator: " · "))
                                            .text(.labelS).foregroundStyle(diff.map { $0 <= 3 } == true ? Color.green : .secondary)
                                    }
                                    Spacer()
                                    Text("Use").text(.label)
                                }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                            }.buttonStyle(.plain)
                        }
                    }
                }
                Section {
                    if !albumMode { TextField(book ? "Chapter title" : "Title", text: $title) }
                    TextField(book ? "Author" : "Artist", text: $artist)
                    TextField(book ? "Book" : "Album", text: $album)
                    if !book { TextField("Main artist (album artist)", text: $albumArtist)
                        Text("Use the main artist here. Keep featured artists in the Artist credit above.").text(.caption) }
                    TextField("Genre", text: $genre)
                    HStack {
                        TextField("Year", text: $year).keyboardType(.numberPad)
                        if !albumMode { TextField(book ? "Chapter" : "Track", text: $track).keyboardType(.numberPad); if !book { TextField("Disc", text: $disc).keyboardType(.numberPad) } }
                    }
                } footer: { Text(albumMode ? "Applies to \(songs.count) \(book ? "chapters" : "tracks"). Edits are saved into the files." : "Edits are saved into the file.") }
                Section {
                    Button("Reload tags from the file", role: .destructive) { app.library.resetOverrides(songs); ArtCache.shared.removeCustom(first.albumKey); app.library.artVersion += 1; dismiss() }
                }
            }
            .disabled(saving)
            .navigationTitle(albumMode ? (book ? "Edit book" : "Edit album") : "Edit info").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(saving) }
                ToolbarItem(placement: .confirmationAction) { Button(saving ? "Saving…" : "Save") { save() }.bold().disabled(saving) }
            }
            .onAppear {
                title = first.title; artist = first.artist; album = first.album; albumArtist = first.albumArtist; genre = first.genre ?? ""
                year = first.year > 0 ? String(first.year) : ""; track = first.track > 0 ? String(first.track) : ""; disc = first.disc > 1 ? String(first.disc) : ""
            }
            .interactiveDismissDisabled(saving)
            .alert("Could not save all files", isPresented: Binding(get: { saveError != nil }, set: { if !$0 { saveError = nil } })) {
                Button("OK") { saveError = nil }
            } message: { Text(saveError ?? "") }
            .onChange(of: photo) { _, item in Task { newArt = try? await item?.loadTransferable(type: Data.self); newArtURL = nil } }
        }
    }

    private func t(_ s: String) -> String? { let v = s.trimmingCharacters(in: .whitespaces); return v.isEmpty ? nil : v }

    private func apply(_ c: MetadataCandidate) {
        if !albumMode { title = c.title; c.track.map { track = String($0) }; c.disc.map { disc = String($0) } }
        artist = c.artist; album = c.album; albumArtist = c.artist
        c.genre.map { genre = $0 }; c.year.map { year = String($0) }
        if let u = c.artURL { newArtURL = u; newArt = nil }
        candidates = nil
    }

    private func search() {
        searching = true
        let first = songs[0]
        Task {
            if first.isAudiobook {
                candidates = await OpenLibrary.search("\(album) \(artist)").map { MetadataCandidate(title: first.title, artist: $0.author, album: $0.title, year: $0.year, genre: "Audiobook", durationMs: 0, artURL: $0.coverURL, source: "Open Library") }
            } else {
                let q = albumMode ? "\(artist) \(album)" : "\(artist) \(title)"
                candidates = await MusicCatalog.search(q.trimmingCharacters(in: .whitespaces).isEmpty ? MusicCatalog.query(for: first) : q, durationMs: albumMode ? 0 : first.durationMs)
            }
            searching = false
        }
    }

    private func save() {
        let o = MetadataOverride(title: albumMode ? nil : t(title), artist: t(artist), album: t(album), albumArtist: t(albumArtist) ?? t(artist), genre: t(genre),
                                 year: Int(year), track: albumMode ? nil : Int(track), disc: albumMode ? nil : Int(disc), source: "user")
        saving = true
        Task {
            defer { saving = false }
            do {
                var data = newArt
                if let url = newArtURL {
                    guard let downloaded = await HTTP.get(url) else { throw NSError(domain: "Spitify.FileTags", code: 3, userInfo: [NSLocalizedDescriptionKey: "The cover could not be downloaded. Please try again."]) }
                    data = downloaded
                }
                let key = Song.albumKey(album: t(album) ?? songs[0].album, artist: t(albumArtist) ?? t(artist) ?? songs[0].albumArtist)
                if data == nil {
                    data = (try? Data(contentsOf: ArtCache.shared.customURL(songs[0].albumKey)))
                        ?? (try? Data(contentsOf: ArtCache.shared.embeddedURL(songs[0].albumKey)))
                }
                try await app.library.saveFiles(o, for: songs, artwork: data)
                if let data { ArtCache.shared.storeCustom(data, key: key); app.library.artVersion += 1 }
                Haptics.success()
                dismiss()
            } catch { saveError = error.localizedDescription }
        }
    }
}
