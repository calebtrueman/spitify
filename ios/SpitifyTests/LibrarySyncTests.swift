import XCTest
@testable import Spitify

/// Mirrors core/src/test/.../LibrarySyncTest.kt, plus fixtures for what the Kotlin code produces, so
/// both sides agree on keys, shards, fingerprints and the JSON on the wire.
final class LibrarySyncTests: XCTestCase {
    private let a = String(repeating: "a", count: 64)
    private let b = String(repeating: "b", count: 64)
    private let c = String(repeating: "c", count: 64)
    private var now: Int64 = 1_000

    private func device(_ id: String) -> LibrarySync { LibrarySync(me: id) { [unowned self] in self.now } }
    private func song(_ title: String, _ artist: String = "Band", _ duration: Int64 = 200_000, art: String? = nil) -> SharedTrack {
        SharedTrack(title: title, artist: artist, durationMs: duration, artwork: art)
    }
    private func liked(_ songs: SharedTrack...) -> [String: SyncObject] {
        Dictionary(songs.map { (LibrarySync.trackKey($0), LibrarySync.trackValue($0)) }, uniquingKeysWith: { a, _ in a })
    }
    /// Sends every document `from` has to `to` through JSON text; `to` applies everything it's told.
    @discardableResult
    private func sync(_ from: LibrarySync, _ to: LibrarySync) -> [SyncChange] {
        let changes = from.docNames().sorted().flatMap { to.receive(SyncJSON.parse(from.doc($0).data)!) }
        changes.forEach(to.applied)
        return changes
    }

    // MARK: Ported from LibrarySyncTest.kt

    func testLikesMergeAndRemovalsWin() {
        let mac = device(a), phone = device(b)
        mac.report(LibrarySync.liked, liked(song("One"), song("Two")))
        now += 10; phone.report(LibrarySync.liked, liked(song("Two"), song("Three")))
        // Linking: both libraries combine.
        let toPhone = sync(mac, phone); sync(phone, mac)
        XCTAssertEqual(Set(toPhone.map(\.key)), ["one|band"])
        XCTAssertEqual(Set(mac.present(LibrarySync.liked).keys), ["one|band", "two|band", "three|band"])
        XCTAssertEqual(Set(mac.present(LibrarySync.liked).keys), Set(phone.present(LibrarySync.liked).keys))
        // The phone unlikes One; the Mac removes it too and it stays removed.
        now += 10; phone.report(LibrarySync.liked, liked(song("Two"), song("Three")))
        let removal = sync(phone, mac)
        XCTAssertEqual(removal, [SyncChange(collection: LibrarySync.liked, key: "one|band", present: false, value: nil)])
        now += 10; XCTAssertTrue(mac.report(LibrarySync.liked, liked(song("Two"), song("Three"))).isEmpty, "nothing new to send")
        XCTAssertEqual(mac.digest(), phone.digest())
    }

    func testTagsVersusCatalogueDetailsDontPingPong() {
        let mac = device(a), phone = device(b)
        mac.report(LibrarySync.liked, liked(song("Song (feat. Guest)", "Band, Guest", 200_123)))
        sync(mac, phone)
        // The phone has it as a stream with different details: same song, no new change.
        now += 10
        XCTAssertTrue(phone.report(LibrarySync.liked, liked(song("Song", "Band", 200_000, art: "https://x.example/a.jpg"))).isEmpty)
        XCTAssertTrue(mac.report(LibrarySync.liked, liked(song("Song (feat. Guest)", "Band, Guest", 200_123))).isEmpty)
    }

    func testAnUnloadedLibraryDoesntWipeEverything() {
        let mac = device(a)
        var many: [String: SyncObject] = [:]
        for n in 1...40 { let s = song("S\(n)"); many[LibrarySync.trackKey(s)] = LibrarySync.trackValue(s) }
        mac.report(LibrarySync.liked, many)
        now += 10
        XCTAssertTrue(mac.report(LibrarySync.liked, [:]).isEmpty)
        XCTAssertEqual(mac.present(LibrarySync.liked).count, 40)
        mac.report(LibrarySync.liked, [:], allowMassRemoval: true)
        XCTAssertEqual(mac.present(LibrarySync.liked).count, 0)
    }

    func testUnmatchedSongsStayPendingAndArentRemoved() {
        let mac = device(a), phone = device(b)
        mac.report(LibrarySync.liked, liked(song("Rare"), song("Common")))
        let changes = mac.docNames().flatMap { phone.receive(SyncJSON.parse(mac.doc($0).data)!) }
        // The phone can only find "Common".
        changes.filter { $0.key == "common|band" }.forEach(phone.applied)
        now += 10
        XCTAssertTrue(phone.report(LibrarySync.liked, liked(song("Common"))).isEmpty, "absent but never had it: not a removal")
        XCTAssertEqual(phone.pending(LibrarySync.liked).map(\.key), ["rare|band"])
    }

    func testThreeDevicesConvergeWhateverTheOrder() {
        let x = device(a), y = device(b), z = device(c)
        x.report(LibrarySync.liked, liked(song("A"))); now += 1
        y.report(LibrarySync.liked, liked(song("A"), song("B"))); now += 1
        z.report(LibrarySync.liked, liked(song("C")))
        sync(z, y); sync(y, x); sync(x, z); sync(z, x); sync(x, y); sync(y, z)
        XCTAssertEqual(x.digest(), y.digest()); XCTAssertEqual(y.digest(), z.digest())
        XCTAssertEqual(Set(z.present(LibrarySync.liked).keys), ["a|band", "b|band", "c|band"])
    }

    func testPlaylistEntriesMergeConcurrentAdditions() {
        let mac = device(a), phone = device(b)
        let list = LibrarySync.playlist("p1")
        let base = [song("One"), song("Two")]
        func entries(_ tracks: [SharedTrack]) -> [String: SyncObject] {
            Dictionary(zip(LibrarySync.entryKeys(tracks), tracks).enumerated().map { i, pair in (pair.0, LibrarySync.entryValue(pair.1, pos: i)) }, uniquingKeysWith: { a, _ in a })
        }
        mac.report(list, entries(base)); sync(mac, phone)
        now += 10; mac.report(list, entries(base + [song("Mac add")]))
        now += 1; phone.report(list, entries(base + [song("Phone add")]))
        sync(mac, phone); sync(phone, mac)
        XCTAssertEqual(Set(mac.present(list).keys), ["one|band#1", "two|band#1", "mac add|band#1", "phone add|band#1"])
        XCTAssertEqual(mac.digest(), phone.digest())
        // The same song twice gets distinct entries.
        XCTAssertEqual(LibrarySync.entryKeys([song("X"), song("X")]), ["x|band#1", "x|band#2"])
    }

    func testHistoryIsGrowOnlyAndTrimmedByAge() {
        let mac = device(a), phone = device(b)
        var value = LibrarySync.trackValue(song("One")); value["playedAt"] = .int(now)
        mac.add(LibrarySync.history, "e1", value)
        XCTAssertTrue(mac.add(LibrarySync.history, "e1", [:]).isEmpty, "same event twice")
        sync(mac, phone)
        XCTAssertEqual(Set(phone.present(LibrarySync.history).keys), ["e1"])
        now += Int64(LibrarySync.historyDays + 1) * 24 * 60 * 60_000
        phone.trim()
        XCTAssertTrue(phone.present(LibrarySync.history).isEmpty)
    }

    func testMeaningIgnoresTrackAndKeyOrder() {
        let one: SyncObject = ["pos": .int(3), "track": .object(["title": .string("x")])]
        let two = SyncJSON.parse(Data(#"{"track":{"title":"y"},"pos":3.0}"#.utf8))!.object!
        XCTAssertEqual(LibrarySync.meaning(one), LibrarySync.meaning(two))
        XCTAssertEqual(LibrarySync.meaning(["positionMs": .int(5), "played": .bool(true)]), #"{"played":true,"positionMs":5}"#)
        XCTAssertNotEqual(LibrarySync.meaning(one), LibrarySync.meaning(["pos": .int(4)]))
    }

    func testSavedStateRoundTrips() {
        let mac = device(a)
        mac.report(LibrarySync.liked, liked(song("One")))
        let copy = device(a)
        copy.load(SyncJSON.parse(mac.json().data)!)
        XCTAssertEqual(mac.digest(), copy.digest())
        now += 10
        XCTAssertTrue(copy.report(LibrarySync.liked, liked(song("One"))).isEmpty, "remembers what was reported")
        let stranger = device(b); stranger.load(SyncJSON.parse(mac.json().data)!)
        XCTAssertTrue(stranger.collections().isEmpty, "another device's file isn't loaded")
    }

    func testTrackKeys() {
        XCTAssertEqual(LibrarySync.trackKey("Song (feat. Guest)", "Band feat. Guest"), "song|band")
        XCTAssertEqual(LibrarySync.trackKey("Song [ft. X]", "Band"), "song|band")
        XCTAssertNotEqual(LibrarySync.trackKey("Song", "Band"), LibrarySync.trackKey("Song - Remastered 2011", "Band"))
        XCTAssertEqual(LibrarySync.trackKey("Beyoncé", "BEYONCÉ"), "beyonce|beyonce")
        XCTAssertEqual(LibrarySync.trackKey("Dreams", "Florence + the Machine & Guest"), LibrarySync.trackKey("Dreams", "Florence + the Machine"))
        XCTAssertEqual(LibrarySync.trackKey("X", "Band x Other"), "x|band")
    }

    func testUnderscoreFieldsTravelButDontCountAsChanges() {
        let v1: SyncObject = ["_addedAt": .int(5), "name": .string("Mix")]
        let v2: SyncObject = ["_addedAt": .int(9), "name": .string("Mix")]
        XCTAssertEqual(LibrarySync.meaning(v1), LibrarySync.meaning(v2))
        XCTAssertEqual(LibrarySync.meaning(v1), #"{"name":"Mix"}"#)
    }

    // MARK: Agreement with the Kotlin code

    /// Expected values worked out from LibrarySync.kt (org.json, java.text.Normalizer, java.util.regex)
    /// and SHA-256 computed independently (`shasum -a 256`).
    func testKeysAndFoldingMatchKotlin() {
        let cases: [(String, String, String)] = [
            ("Song (feat. Guest)", "Band feat. Guest", "song|band"),
            ("Beyoncé", "BEYONCÉ", "beyonce|beyonce"),
            ("Thunderstruck", "AC/DC", "thunderstruck|ac"),
            ("The Boxer", "Simon & Garfunkel", "the boxer|simon"),
            ("Primadonna", "Marina and the Diamonds", "primadonna|marina"),
            ("Kickstart My Heart", "Mötley Crüe", "kickstart my heart|motley crue"),
            ("Hoppípolla", "Sigur Rós", "hoppipolla|sigur ros"),
            ("Hello (with Friend)", "Adele; Someone", "hello|adele"),
            ("Hello (With)", "Adele", "hello with|adele"),
            ("Track", "DJ vs. MC", "track|dj"),
            ("Don't Stop — Live!", "Band", "don t stop live|band"),
            ("残酷な天使のテーゼ", "高橋洋子", "残酷な天使のテーセ|高橋洋子"),
            ("  ", "Band", "|band"),
        ]
        for (title, artist, key) in cases { XCTAssertEqual(LibrarySync.trackKey(title, artist), key, "\(title) / \(artist)") }
        XCTAssertEqual(LibrarySync.fold("  Ångström_42  "), "angstrom 42")
    }

    func testShardsAndDocNamesMatchKotlin() {
        // sha256("song|band") = e6b2…, sha256("beyonce|beyonce") = 615e…, sha256("one|band") = 67ff…
        XCTAssertEqual(LibrarySync.shardOf(LibrarySync.liked, "song|band"), 0xE6B2 % 8)
        XCTAssertEqual(LibrarySync.shardOf(LibrarySync.liked, "beyonce|beyonce"), 0x615E % 8)
        XCTAssertEqual(LibrarySync.shardOf(LibrarySync.history, "one|band"), 0x67FF % 16)
        XCTAssertEqual(LibrarySync.shardOf(LibrarySync.playlist("p1"), "song|band"), 0xE6B2 % 4)
        XCTAssertEqual(LibrarySync.shardOf(LibrarySync.podcasts, "song|band"), 0xE6B2 % 2)
        XCTAssertEqual(LibrarySync.shardOf(LibrarySync.profile, "song|band"), 0)
        XCTAssertEqual(LibrarySync.shardOf(LibrarySync.stats(a), "one|band"), 0x67FF % 8)
        XCTAssertEqual(LibrarySync.docName(LibrarySync.liked, "song|band"), "liked#2")
        XCTAssertEqual(LibrarySync.splitDoc("playlist:abc#3").0, "playlist:abc")
        XCTAssertEqual(LibrarySync.splitDoc("playlist:abc#3").1, 3)
        XCTAssertEqual(LibrarySync.splitDoc("liked").1, 0)
        XCTAssertTrue(LibrarySync.validCollection(LibrarySync.stats(a)))
        XCTAssertFalse(LibrarySync.validCollection("stats:nope"))
        XCTAssertFalse(LibrarySync.validCollection("playlist:"))
        XCTAssertTrue(LibrarySync.validCollection("playlist:p"))
        XCTAssertFalse(LibrarySync.validCollection("other"))
        XCTAssertEqual(LibrarySync.syncedSettings.count, 23)
    }

    func testMeaningMatchesOrgJson() {
        // org.json (Android) JSONObject.quote escapes "/" as "\/" and control characters as \u00xx.
        XCTAssertEqual(LibrarySync.meaning(["name": .string("AC/DC \"Live\"\\"), "_x": .int(1)]), #"{"name":"AC\/DC \"Live\"\\"}"#)
        XCTAssertEqual(LibrarySync.meaning(["s": .string("a\u{1}b\tc\nd\u{8}e\u{C}f\rg")]), #"{"s":"a\u0001b\tc\nd\be\ff\rg"}"#)
        XCTAssertEqual(LibrarySync.meaning(["b": .bool(true), "a": .array([.int(1), .double(2.5), .null, .string("x")]), "n": .null]), #"{"a":[1,2.5,null,"x"],"b":true}"#)
        XCTAssertEqual(LibrarySync.meaning(["o": .object(["z": .int(1), "_y": .int(2), "q": .null])]), #"{"o":{"_y":2,"z":1}}"#, "only top-level _ fields are dropped")
        XCTAssertEqual(LibrarySync.meaning(["v": .double(3.0), "w": .double(-0.0), "x": .double(0.00001), "y": .double(12345678.5), "z": .double(1.25)]),
                       #"{"v":3,"w":0,"x":1.0E-5,"y":1.23456785E7,"z":1.25}"#)
        XCTAssertEqual(LibrarySync.meaning(nil), "{}")
        XCTAssertEqual(LibrarySync.meaning(["track": .object([:])]), "{}")
        XCTAssertEqual(LibrarySync.meaning(["é": .int(1), "z": .int(2), "Z": .int(3)]), #"{"Z":3,"z":2,"é":1}"#, "keys in UTF-16 order")
    }

    /// A document exactly as org.json writes it on Android or desktop.
    func testReadsAKotlinDocumentAndAgreesOnItsFingerprint() throws {
        let kotlin = """
        {"collection":"liked","shard":2,"items":[{"k":"song|band","p":true,"t":1000,"d":"\(a)","v":{"track":{"id":"song|band","title":"Song (feat. Guest)","artist":"Band feat. Guest","album":"","durationMs":200000,"sourceID":"123"},"_addedAt":5}}]}
        """
        let phone = device(b)
        let changes = phone.receive(try XCTUnwrap(SyncJSON.parse(Data(kotlin.utf8))))
        XCTAssertEqual(changes.map(\.key), ["song|band"])
        let track = try XCTUnwrap(LibrarySync.track(changes.first?.value))
        XCTAssertEqual(track.title, "Song (feat. Guest)"); XCTAssertEqual(track.sourceID, "123"); XCTAssertEqual(track.durationMs, 200_000)
        XCTAssertEqual(LibrarySync.trackKey(track), track.id)
        // sha256("song|band|true|1000|aaa…")[0..16]
        XCTAssertEqual(phone.digest(), ["liked#2": "966413ae069d5ec8"])

        let profile = """
        {"collection":"profile","shard":0,"items":[{"k":"song|band","p":true,"t":1000,"d":"\(a)","v":{"value":"x"}},{"k":"one|band","p":false,"t":1010,"d":"\(b)"}]}
        """
        _ = phone.receive(try XCTUnwrap(SyncJSON.parse(Data(profile.utf8))))
        // Lines sorted, joined by "\\n": sha256("one|band|false|1010|bbb…\\nsong|band|true|1000|aaa…")[0..16]
        XCTAssertEqual(phone.digest()["profile#0"], "12376d7b59e8f849")
    }

    /// The JSON Swift writes has the same keys and types org.json reads.
    func testWritesTheSameShapeAsKotlin() throws {
        let mac = device(a)
        mac.report(LibrarySync.liked, liked(SharedTrack(title: "Song", artist: "Band", durationMs: 200_000, sourceID: "77")))
        now += 5
        mac.report(LibrarySync.liked, [:])
        let doc = try XCTUnwrap(JSONSerialization.jsonObject(with: mac.doc("liked#2").data) as? [String: Any])
        XCTAssertEqual(doc["collection"] as? String, "liked"); XCTAssertEqual(doc["shard"] as? Int, 2)
        let item = try XCTUnwrap((doc["items"] as? [[String: Any]])?.first)
        XCTAssertEqual(Set(item.keys), ["k", "p", "t", "d"], "a removal has no value")
        XCTAssertEqual(item["p"] as? Bool, false); XCTAssertEqual((item["t"] as? NSNumber)?.int64Value, 1_005); XCTAssertEqual(item["d"] as? String, a)

        let value = LibrarySync.trackValue(SharedTrack(title: "Song", artist: "Band", durationMs: 1, sourceID: "77"))
        let track = try XCTUnwrap(JSONSerialization.jsonObject(with: SyncJSON.object(value).data) as? [String: Any])["track"] as? [String: Any]
        XCTAssertEqual(Set(track?.keys.map { $0 } ?? []), ["id", "title", "artist", "album", "durationMs", "sourceID"], "nil fields are left out, as org.json does")
        XCTAssertEqual(track?["id"] as? String, "song|band")
        XCTAssertEqual(LibrarySync.entryValue(SharedTrack(title: "Song", artist: "Band"), pos: 4)["pos"], .int(4))
        // A grow-only removal from a misbehaving device is ignored.
        let phone = device(b)
        XCTAssertTrue(phone.receive(.object(["collection": .string("history"), "items": .array([.object(["k": .string("x"), "p": .bool(false), "t": .int(1), "d": .string(a)])])])).isEmpty)
        XCTAssertTrue(phone.present(LibrarySync.history).isEmpty)
    }

    /// Outgoing song values: keyed by what the song is, with the catalogue id for streams and downloads.
    @MainActor func testSongsAndListensFromThisDevice() throws {
        let stream = OnlineTrack(id: "123", title: "Song (feat. Guest)", artist: "Band, Guest", album: "LP", releaseID: "9", durationMs: 200_000, trackNumber: 1, discNumber: 1, artwork: "https://x.example/a.jpg", playable: true)
        let shared = try XCTUnwrap(AppLibrarySyncHost.shared(MusicStreams.song(stream), streams: ["123": stream], downloads: [:]))
        XCTAssertEqual(shared.id, "song|band"); XCTAssertEqual(shared.sourceID, "123"); XCTAssertEqual(shared.releaseID, "9")
        var file = MusicStreams.song(stream); file.id = "f1"; file.kind = .file; file.location = "Music/Monochrome/x/123.flac"; file.artURL = nil
        XCTAssertEqual(AppLibrarySyncHost.shared(file, streams: [:], downloads: [file.location: "123"])?.sourceID, "123", "a download keeps its catalogue id")
        file.location = "Music/mine.mp3"
        XCTAssertNil(AppLibrarySyncHost.shared(file, streams: [:], downloads: [:])?.sourceID)
        let listen = Listen(songId: "f1", at: Date(timeIntervalSince1970: 1_759_000_000), listenedMs: 30_000, durationMs: 200_000, completed: false, skipped: true)
        let (key, value) = AppLibrarySyncHost.historyItem(listen, track: shared, me: a)
        XCTAssertEqual(key, "aaaaaaaa:1759000000000:song|band")
        XCTAssertEqual(value["playedAt"], .int(1_759_000_000_000)); XCTAssertEqual(value["skipped"], .bool(true)); XCTAssertEqual(value["listenedMs"], .int(30_000))
        XCTAssertEqual(LibrarySync.track(value)?.id, "song|band")
    }

    func testProgressKeysFollowTheSharedConvention() {
        XCTAssertEqual(LibrarySync.episodeProgressKey(feed: "https://f.example/rss", guid: "abc-1"), "e:https://f.example/rss#abc-1")
        XCTAssertEqual(LibrarySync.trackProgressKey("Chapter 1 (feat. Reader)", "Author & Co"), "t:chapter 1|author")
        let long = "e:" + String(repeating: "x", count: 400)
        // sha256(long) = aef8ca5d…
        XCTAssertEqual(LibrarySync.shortKey(long), "e:" + String(repeating: "x", count: 298) + "~aef8ca5dbfe6ec8d7e121ef6993f48c8")
        XCTAssertEqual(LibrarySync.shortKey("t:short"), "t:short")
    }

    func testDigestOrderIsUTF16LikeKotlin() {
        // By code point U+FFFD sorts before U+1F600, but in UTF-16 (Kotlin's String order) the surrogate D83D comes first.
        XCTAssertTrue(utf16Less("😀", "\u{FFFD}"))
        XCTAssertTrue(utf16Less("Z", "a"))
        let x = device(a)
        x.report(LibrarySync.profile, ["😀": ["value": .int(2)]])
        x.report(LibrarySync.profile, ["\u{FFFD}": ["value": .int(1)], "😀": ["value": .int(2)]])
        // sha256("😀|true|1000|aaa…\n\u{FFFD}|true|1001|aaa…")[0..16]
        XCTAssertEqual(x.digest()["profile#0"], LibrarySyncTests.utf16Digest)
    }
    static let utf16Digest = "cc882da5a38e225c"
}
