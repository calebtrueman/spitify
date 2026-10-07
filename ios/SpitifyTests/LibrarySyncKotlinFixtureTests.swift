import XCTest
@testable import Spitify

/// Real documents written by the Kotlin LibrarySync (core SwiftFixtureTest): Swift must merge them to the
/// same digests and agree on song keys, shards and the canonical "meaning" text.
final class LibrarySyncKotlinFixtureTests: XCTestCase {
    private func fixture() throws -> SyncObject {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "library-sync-kotlin", withExtension: "json"))
        return try XCTUnwrap(SyncJSON.parse(Data(contentsOf: url))?.object)
    }

    func testMergingKotlinDocumentsGivesKotlinsDigests() throws {
        let f = try fixture()
        let sync = LibrarySync(me: String(repeating: "f", count: 64))
        for doc in try XCTUnwrap(f["docs"]?.array) { _ = sync.receive(doc) }
        let expected = try XCTUnwrap(f["digest"]?.object).compactMapValues(\.string)
        XCTAssertEqual(sync.digest(), expected)
        XCTAssertFalse(expected.isEmpty)
    }

    func testSongKeysShardsAndMeaningsMatchKotlin() throws {
        let f = try fixture()
        for row in try XCTUnwrap(f["trackKeys"]?.array) {
            let r = try XCTUnwrap(row.array)
            XCTAssertEqual(LibrarySync.trackKey(r[0].string!, r[1].string!), r[2].string, "\(r[0]) / \(r[1])")
        }
        for row in try XCTUnwrap(f["shards"]?.array) {
            let r = try XCTUnwrap(row.array)
            guard case .int(let shard) = r[2] else { return XCTFail("shard") }
            XCTAssertEqual(LibrarySync.shardOf(r[0].string!, r[1].string!), Int(shard), "\(r[0]) \(r[1])")
        }
        for row in try XCTUnwrap(f["meanings"]?.array) {
            let r = try XCTUnwrap(row.array)
            XCTAssertEqual(LibrarySync.meaning(r[0].object), r[1].string)
        }
    }
}
