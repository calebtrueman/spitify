import XCTest
import UIKit
@testable import Spitify

final class AppIconTests: XCTestCase {
    @MainActor func testFailureRestoresThePreviousSystemIconAndRetryCanSucceed() async {
        struct Rejected: Error {}
        var systemName: String? = "Vinyl_green"
        var calls: [String?] = []
        let selection = AppIconSelection(read: { systemName }, change: { name in
            calls.append(name); systemName = name
            if name == "Vinyl_moon" { throw Rejected() }
        })
        await selection.select(AppIconChoice(id: "moon", name: "Moonlight", group: "Artwork"))
        XCTAssertEqual(calls, ["Vinyl_moon", "Vinyl_green"])
        XCTAssertEqual(systemName, "Vinyl_green")
        XCTAssertEqual(selection.alternateName, "Vinyl_green")
        XCTAssertNotNil(selection.message)
        XCTAssertFalse(selection.changing)
        await selection.select(AppIconChoice(id: "flower", name: "Daisy", group: "Artwork"))
        XCTAssertEqual(selection.alternateName, "Vinyl_flower")
        XCTAssertNil(selection.message)
    }

    @MainActor func testSuccessCallbackWithoutAChangedSystemIconDoesNotShowAFakeSelection() async {
        let selection = AppIconSelection(read: { "Vinyl_green" }, change: { _ in })
        await selection.select(AppIconChoice(id: "moon", name: "Moonlight", group: "Artwork"))
        XCTAssertEqual(selection.alternateName, "Vinyl_green")
        XCTAssertNotNil(selection.message)
    }

    @MainActor func testEveryBundledChoiceHasAPreviewAndDeclaredSystemIcon() throws {
        XCTAssertFalse(AppIconChoice.all.isEmpty, "The picker needs its bundled catalog")
        let icons = try XCTUnwrap(Bundle.main.infoDictionary?["CFBundleIcons"] as? [String: Any])
        let alternates = try XCTUnwrap(icons["CFBundleAlternateIcons"] as? [String: Any])
        for choice in AppIconChoice.all {
            XCTAssertNotNil(UIImage(named: choice.previewName), "Missing preview for " + choice.id)
            if let key = choice.alternateName { XCTAssertNotNil(alternates[key], "Missing system icon for " + choice.id) }
        }
    }
}
