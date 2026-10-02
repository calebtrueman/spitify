import CarPlay
import Observation
import UIKit

/// The car and phone share one library and player, including when the car launches first.
@MainActor
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate, @preconcurrency CPNowPlayingTemplateObserver {
    private let app = AppModel.shared
    private weak var controller: CPInterfaceController?
    private var roots: [CPListTemplate] = []
    private var lists: [(CPListTemplate, () -> [CPListSection])] = []
    private var connected = false

    func templateApplicationScene(_ scene: CPTemplateApplicationScene, didConnect interfaceController: CPInterfaceController) {
        controller = interfaceController
        connected = true
        let now = CPNowPlayingTemplate.shared
        now.add(self)
        now.isUpNextButtonEnabled = true
        now.upNextTitle = "Queue"
        now.updateNowPlayingButtons([
            CPNowPlayingShuffleButton { [weak self] _ in self?.app.player.toggleShuffle() },
            CPNowPlayingRepeatButton { [weak self] _ in self?.app.player.cycleRepeat() },
        ])
        installRoot()
        observeChanges()
        Task { [weak self] in
            guard let self, self.app.profile.onboarded else { return }
            await self.app.start()
            if self.connected { self.refresh() }
        }
    }

    func templateApplicationScene(_ scene: CPTemplateApplicationScene, didDisconnectInterfaceController interfaceController: CPInterfaceController) {
        connected = false
        CPNowPlayingTemplate.shared.remove(self)
        controller = nil
        roots.removeAll()
        lists.removeAll()
        // Disconnecting the car must not create a new player or stop the phone's audio.
    }

    private func installRoot() {
        guard let controller else { return }
        if !app.profile.onboarded {
            let message = CPListItem(text: "Finish setup on your iPhone", detailText: "Open Spitify on your phone while parked.")
            message.isEnabled = false
            controller.setRootTemplate(CPListTemplate(title: "Spitify", sections: [CPListSection(items: [message])]), animated: false, completion: nil)
            return
        }
        roots = [makeList("For you", icon: "house.fill", content: { [weak self] in self?.home() ?? [] }),
                 makeList("Library", icon: "music.note.list", content: { [weak self] in self?.library() ?? [] }),
                 makeList("Podcasts", icon: "dot.radiowaves.left.and.right", content: { [weak self] in self?.shows(books: false) ?? [] }),
                 makeList("Books", icon: "book.fill", content: { [weak self] in self?.shows(books: true) ?? [] })]
        controller.setRootTemplate(CPTabBarTemplate(templates: roots), animated: false, completion: nil)
    }

    private func observeChanges() {
        guard connected else { return }
        withObservationTracking {
            _ = app.profile.onboarded
            _ = app.library.library
            _ = app.library.books
            _ = app.library.playlists
            _ = app.library.liked
            _ = app.library.artVersion
            _ = app.mixes
            _ = app.shows.shows
            _ = app.player.queue
            _ = app.player.index
            _ = app.player.manualQueueIndices
            _ = app.player.isPlaying
        } onChange: { [weak self] in
            Task { @MainActor [weak self] in
                guard let self, self.connected else { return }
                self.refresh()
                self.observeChanges()
            }
        }
    }

    private func refresh() {
        if roots.isEmpty && app.profile.onboarded {
            installRoot()
            Task { await app.start() }
        }
        guard let controller else { return }
        let visible = controller.templates
        lists.removeAll { list, _ in !roots.contains(where: { $0 === list }) && !visible.contains(where: { $0 === list }) }
        for (list, build) in lists { list.updateSections(build()) }
    }

    private func makeList(_ title: String, icon: String? = nil, content: @escaping () -> [CPListSection]) -> CPListTemplate {
        let list = CPListTemplate(title: title, sections: content())
        if let icon { list.tabImage = UIImage(systemName: icon) }
        list.emptyViewTitleVariants = ["Nothing here yet"]
        list.emptyViewSubtitleVariants = ["Add music or follow shows in Spitify on your iPhone."]
        lists.append((list, content))
        return list
    }

    private func push(_ title: String, content: @escaping () -> [CPListSection]) {
        guard let controller else { return }
        controller.pushTemplate(makeList(title, content: content), animated: true, completion: nil)
    }

    private func item(_ title: String, detail: String? = nil, image: UIImage? = nil, action: @escaping () -> Void) -> CPListItem {
        let row = CPListItem(text: title, detailText: detail, image: image)
        row.handler = { _, finish in action(); finish() }
        return row
    }

    private func section(_ items: [CPListItem], title: String? = nil) -> [CPListSection] {
        items.isEmpty ? [] : [CPListSection(items: Array(items.prefix(CPListTemplate.maximumItemCount)), header: title, sectionIndexTitle: nil)]
    }

    private func home() -> [CPListSection] {
        var rows: [CPListItem] = []
        if app.player.hasMedia {
            rows.append(item("Now playing", detail: app.player.current?.title) { [weak self] in self?.nowPlaying() })
        }
        rows.append(item("All songs", image: UIImage(systemName: "music.note.list")) { [weak self] in
            self?.openSongs("All songs") { [weak self] in
                guard let self else { return [] }
                return self.app.library.library.songs
            }
        })
        for mix in app.mixes.prefix(20) {
            rows.append(item(mix.title, detail: mix.description) { [weak self] in self?.openSongs(mix.title) { mix.songs } })
        }
        return section(rows)
    }

    private func library() -> [CPListSection] {
        section([
            item("All songs", image: UIImage(systemName: "music.note")) { [weak self] in
                self?.openSongs("All songs") { [weak self] in self?.app.library.library.songs ?? [] }
            },
            item("Albums", image: UIImage(systemName: "square.stack")) { [weak self] in
                self?.push("Albums") { [weak self] in
                    guard let self else { return [] }
                    return self.section(self.app.library.library.albums.prefix(100).map { album in
                        self.item(album.title, detail: album.artist, image: ArtCache.shared.image(for: album.id)) { [weak self] in
                            self?.openSongs(album.title) { [weak self] in self?.app.library.library.albumById[album.id]?.songs ?? [] }
                        }
                    })
                }
            },
            item("Playlists", image: UIImage(systemName: "music.note.list")) { [weak self] in
                self?.push("Playlists") { [weak self] in
                    guard let self else { return [] }
                    return self.section(self.app.library.playlists.prefix(100).map { playlist in
                        self.item(playlist.name, detail: "\(playlist.songIds.count) songs") { [weak self] in
                            self?.openSongs(playlist.name) { [weak self] in
                                guard let self else { return [] }
                                return (self.app.library.playlists.first { $0.id == playlist.id }?.songIds ?? []).compactMap(self.app.lookup)
                            }
                        }
                    })
                }
            },
        ])
    }

    private func shows(books: Bool) -> [CPListSection] {
        let shows = books ? app.shows.books : app.shows.podcasts
        var rows = shows.prefix(100).map { show in
            item(show.title, detail: show.author) { [weak self] in
                self?.openSongs(show.title, spoken: true) { [weak self] in
                    guard let self, let current = self.app.shows.shows.first(where: { $0.id == show.id }) else { return [] }
                    return self.app.shows.songs(current)
                }
            }
        }
        if books {
            for (key, chapters) in Dictionary(grouping: app.library.books, by: \.albumKey).sorted(by: { $0.value[0].album < $1.value[0].album }).prefix(100) {
                rows.append(item(chapters[0].album, detail: chapters[0].artist) { [weak self] in
                    self?.openSongs(chapters[0].album, spoken: true) { [weak self] in
                        (self?.app.library.books.filter { $0.albumKey == key } ?? []).sorted { ($0.disc, $0.track, $0.fileName) < ($1.disc, $1.track, $1.fileName) }
                    }
                })
            }
        }
        return section(rows)
    }

    private func openSongs(_ title: String, spoken: Bool = false, offset: Int = 0, songs: @escaping () -> [Song]) {
        push(title) { [weak self] in
            guard let self else { return [] }
            let all = songs().filter(\.playable)
            let pageSize = min(100, CPListTemplate.maximumItemCount - 1)
            var rows = all.dropFirst(offset).prefix(pageSize).enumerated().map { i, song in
                let row = self.item(song.title, detail: song.artist, image: ArtCache.shared.image(for: song.albumKey, remote: song.artURL)) { [weak self] in
                    guard let self else { return }
                    if song.isAudiobook { self.app.player.playBook(all, from: offset + i, title: title) }
                    else if spoken || song.isPodcast { self.app.player.playEpisode(song) }
                    else { self.app.player.play(all, from: offset + i, source: title) }
                    self.nowPlaying()
                }
                row.isPlaying = self.app.player.current?.id == song.id && self.app.player.isPlaying
                return row
            }
            if all.count > offset + pageSize {
                rows.append(self.item("More songs") { [weak self] in self?.openSongs(title, spoken: spoken, offset: offset + pageSize, songs: songs) })
            }
            return self.section(rows)
        }
    }

    private func nowPlaying() {
        guard app.player.hasMedia, let controller, controller.topTemplate !== CPNowPlayingTemplate.shared else { return }
        if controller.templates.contains(where: { $0 === CPNowPlayingTemplate.shared }) {
            controller.pop(to: CPNowPlayingTemplate.shared, animated: true, completion: nil)
        } else { controller.pushTemplate(CPNowPlayingTemplate.shared, animated: true, completion: nil) }
    }

    func nowPlayingTemplateUpNextButtonTapped(_ nowPlayingTemplate: CPNowPlayingTemplate) {
        push("Queue") { [weak self] in
            guard let self else { return [] }
            @MainActor func rows(_ songs: [(Int, Song)]) -> [CPListItem] {
                songs.prefix(100).map { index, song in
                    self.item(song.title, detail: song.artist) { [weak self] in self?.app.player.skip(to: index); self?.nowPlaying() }
                }
            }
            return self.section(rows(self.app.player.manuallyQueued), title: "Added by you") +
                self.section(rows(self.app.player.nextFromSource), title: self.app.player.source.map { "Next from: \($0)" } ?? "From your playback list")
        }
    }
}
