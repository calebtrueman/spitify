import XCTest
@testable import Spitify

final class TasteTests: XCTestCase {
    let now = Date(timeIntervalSince1970: 1_790_000_000)
    var nextId = 0

    func song(_ artist: String, _ genre: String, year: Int = 2020) -> Song {
        nextId += 1
        return Song(id: "s\(nextId)", title: "Song \(nextId)", artist: artist, album: "\(artist) LP", albumArtist: artist, durationMs: 200_000,
                    track: 1, disc: 1, year: year, genre: genre, location: "Music/\(nextId).mp3", kind: .file,
                    dateAdded: now.addingTimeInterval(-400 * 86_400), sizeBytes: 1, fileExtension: "mp3")
    }

    lazy var synth = (1...8).map { _ in song("Neon Harbor", "Synthwave") } + (1...6).map { _ in song("Mira Sol", "Synthwave") }
    lazy var folk = (1...8).map { _ in song("Juniper Fields", "Folk") } + (1...5).map { _ in song("Cedar & Pine", "Folk") }
    lazy var metal = (1...6).map { _ in song("Iron Tide", "Metal") }
    lazy var all = synth + folk + metal

    func listen(_ s: Song, daysAgo: Double, completed: Bool = true, skipped: Bool = false, minute: Int = 0) -> Listen {
        Listen(songId: s.id, at: now.addingTimeInterval(-daysAgo * 86_400 + Double(minute) * 60), listenedMs: skipped ? 5_000 : 200_000, durationMs: 200_000, completed: completed, skipped: skipped)
    }

    func input(hiddenArtists: Set<String> = []) -> TasteInput {
        var listens: [Listen] = []
        for day in 0..<10 {
            for (i, s) in synth.prefix(5).enumerated() { listens.append(listen(s, daysAgo: Double(day) + 0.1, minute: i * 4)) }
            listens.append(listen(synth[9], daysAgo: Double(day) + 0.1, minute: 22))
        }
        for s in folk.prefix(3) { listens.append(listen(s, daysAgo: 2)) }
        for s in metal.prefix(3) { listens.append(listen(s, daysAgo: 1, completed: false, skipped: true)) }
        return TasteInput(songs: all, listens: listens, liked: [synth[0].id], hiddenArtists: hiddenArtists, userName: "Sam", now: now)
    }

    func testLearnsFavouritesAndPenalisesSkips() {
        let m = TasteModel(input())
        XCTAssertEqual(m.topArtists.first, "Neon Harbor")
        XCTAssertGreaterThan(m.normSong(synth[0].id), m.normSong(folk[0].id))
        XCTAssertLessThan(m.normSong(metal[0].id), 0)
    }

    func testCoListeningMakesSongsSimilar() {
        let m = TasteModel(input())
        XCTAssertGreaterThan(m.similarity(synth[0], synth[9]), m.similarity(synth[0], folk[0]))
        XCTAssertGreaterThan(m.artistSimilarity("Neon Harbor", "Mira Sol"), m.artistSimilarity("Neon Harbor", "Juniper Fields"))
    }

    func testGeneratesSpotifyStylePlaylists() {
        let mixes = PlaylistGenerator.generate(TasteModel(input()))
        let titles = mixes.map(\.title)
        XCTAssertFalse(titles.contains { $0.hasPrefix("Daily Mix") })
        XCTAssertFalse(titles.contains("Discover Weekly"))
        XCTAssertFalse(titles.contains("Release Radar"))
        XCTAssertTrue(titles.contains { $0.hasPrefix("daylist") })
        XCTAssertTrue(titles.contains("This Is Neon Harbor"))

    }

    func testRadioStaysOnVibe() {
        let m = TasteModel(input())
        let radio = PlaylistGenerator.songRadio(m, seed: synth[0])
        XCTAssertEqual(radio.first?.id, synth[0].id)
        XCTAssertGreaterThanOrEqual(radio.prefix(10).filter { $0.genre == "Synthwave" }.count, 6)
    }

    func testHiddenArtistsNeverAppear() {
        let mixes = PlaylistGenerator.generate(TasteModel(input(hiddenArtists: ["Juniper Fields"])))
        XCTAssertFalse(mixes.flatMap(\.songs).contains { $0.artist == "Juniper Fields" })
    }

    func testColdStartUsesPickedArtists() {
        let m = TasteModel(TasteInput(songs: all, listens: [], liked: [], seedArtists: ["Juniper Fields"], now: now))
        XCTAssertEqual(m.topArtists.first, "Juniper Fields")
        XCTAssertTrue(PlaylistGenerator.generate(m).contains { $0.title == "This Is Juniper Fields" })
    }
}
