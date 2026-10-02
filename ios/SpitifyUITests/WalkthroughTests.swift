import XCTest

/// Walks through first-run setup and the main screens, saving screenshots for review.
final class WalkthroughTests: XCTestCase {
    let out = ProcessInfo.processInfo.environment["SCREENSHOT_DIR"] ?? "/tmp/spitify-shots"

    func shot(_ app: XCUIApplication, _ name: String) {
        try? FileManager.default.createDirectory(atPath: out, withIntermediateDirectories: true)
        let data = XCUIScreen.main.screenshot().pngRepresentation
        try? data.write(to: URL(fileURLWithPath: "\(out)/\(name).png"))
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); a.name = name; a.lifetime = .keepAlways; add(a)
    }

    func testOnboarding() {
        let app = XCUIApplication()
        app.launch()
        shot(app, "01-welcome")
        app.buttons["Get started"].tap()
        let field = app.textFields["Your name"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap(); field.typeText("Sam")
        app.buttons["Next"].tap()
        XCTAssertTrue(app.buttons["Skip for now"].waitForExistence(timeout: 5))
        shot(app, "02-photo")
        app.buttons["Skip for now"].tap()
        app.buttons["Continue"].tap()
        XCTAssertTrue(app.staticTexts["Pick a few artists you love"].waitForExistence(timeout: 10))
        sleep(2)
        if app.buttons["Neon Harbor"].exists { app.buttons["Neon Harbor"].tap() }
        if app.buttons["Daft Punk"].exists { app.buttons["Daft Punk"].tap() }
        shot(app, "03-artists")
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Done' OR label == 'Skip'")).firstMatch.tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'Sam'")).firstMatch.waitForExistence(timeout: 10))
        sleep(3)
        shot(app, "04-home")
    }

    func testTour() {
        let app = XCUIApplication()
        app.launch()
        sleep(4)
        shot(app, "10-home")
        app.swipeUp(); sleep(1); shot(app, "11-home-mixes"); app.swipeDown(); app.swipeDown()

        // Play something and open the full player.
        let shuffle = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Shuffle your library'")).firstMatch
        if shuffle.waitForExistence(timeout: 3) { shuffle.tap() }
        sleep(2)
        shot(app, "12-home-playing")
        app.descendants(matching: .any)["miniPlayer"].firstMatch.tap()
        sleep(2)
        shot(app, "13-player")
        app.swipeUp(); sleep(1); shot(app, "14-player-cards")
        app.swipeDown(); sleep(1)
        app.buttons["Close player"].firstMatch.tap()
        sleep(1)

        app.tabBars.buttons["Search"].tap(); sleep(1); shot(app, "20-search")
        app.tabBars.buttons["Your Library"].tap(); sleep(1); shot(app, "21-library")
        app.tabBars.buttons["Podcasts"].tap(); sleep(1); shot(app, "22-podcasts")
        app.tabBars.buttons["Books"].tap(); sleep(2); shot(app, "23-books")
    }
}

final class FeatureTests: XCTestCase {
    let out = "/tmp/spitify-shots"
    func shot(_ name: String) {
        try? FileManager.default.createDirectory(atPath: out, withIntermediateDirectories: true)
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "\(out)/\(name).png"))
    }

    func search(_ app: XCUIApplication, _ text: String) {
        app.tabBars.buttons["Search"].tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        if let v = field.value as? String, !v.isEmpty, v != field.placeholderValue { field.buttons["Clear text"].firstMatch.tap() }
        field.typeText(text)
        sleep(1)
    }

    func testSyncedLyricsAndFlac() {
        let app = XCUIApplication(); app.launch(); sleep(3)
        search(app, "night drive")
        app.buttons.matching(NSPredicate(format: "label CONTAINS 'Night Drive'")).firstMatch.tap()
        sleep(9)
        app.buttons["miniPlayer"].tap()
        sleep(2)
        app.buttons["Lyrics"].firstMatch.tap()
        sleep(3)
        shot("30-lyrics-synced")
        app.buttons["Close lyrics"].tap(); sleep(2)
        shot("30b-after-close")
        let close = app.buttons["Close player"].firstMatch
        if close.exists && close.isHittable { close.tap(); sleep(1) }

        search(app, "paper lanterns")
        shot("31a-search")
        app.buttons.matching(NSPredicate(format: "label CONTAINS 'Paper Lanterns'")).firstMatch.tap()
        sleep(4)
        shot("31-flac-playing")
    }

    func testEqualizer() {
        let app = XCUIApplication(); app.launch(); sleep(2)
        app.tabBars.buttons["Your Library"].tap()
        app.buttons["gearshape"].firstMatch.tap(); sleep(1)
        app.buttons["Equaliser & sound"].tap(); sleep(1)
        app.buttons["Bass boost"].firstMatch.tap(); sleep(1)
        shot("32-equalizer")
    }

    func testPodcastsAndBooks() {
        let app = XCUIApplication(); app.launch(); sleep(2)
        app.tabBars.buttons["Podcasts"].tap()
        app.buttons["Technology"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS 'Follow'")).firstMatch.waitForExistence(timeout: 20))
        shot("33-podcast-search")
        app.buttons.matching(NSPredicate(format: "label CONTAINS 'Follow'")).element(boundBy: 0).tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS 'Latest episode'")).firstMatch.waitForExistence(timeout: 30))
        sleep(2)
        shot("34-show")
        app.buttons.matching(NSPredicate(format: "label CONTAINS 'Latest episode'")).firstMatch.tap()
        sleep(10)
        shot("35-episode-playing")

        app.tabBars.buttons["Books"].tap()
        app.buttons["Sherlock Holmes"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS 'Add'")).firstMatch.waitForExistence(timeout: 30))
        shot("36-librivox")
        app.buttons.matching(NSPredicate(format: "label CONTAINS 'Add'")).element(boundBy: 0).tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS 'Start listening'")).firstMatch.waitForExistence(timeout: 30))
        shot("37-book")
    }
}

final class LayoutTests: XCTestCase {
    func shot(_ n: String) { try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "/tmp/spitify-shots/\(n).png")) }
    func testPlayerSurvivesSheets() {
        try? FileManager.default.createDirectory(atPath: "/tmp/spitify-shots", withIntermediateDirectories: true)
        let app = XCUIApplication(); app.launch(); sleep(3)
        if !app.buttons["miniPlayer"].exists { app.buttons.matching(NSPredicate(format: "label CONTAINS 'Shuffle your library'")).firstMatch.tap(); sleep(2) }
        app.buttons["miniPlayer"].tap(); sleep(2); shot("L1-open")
        try? app.debugDescription.write(toFile: "/tmp/spitify-shots/tree.txt", atomically: true, encoding: .utf8)
        app.buttons["Queue"].tap(); sleep(2); shot("L2-queue")
        app.buttons["Done"].tap(); sleep(2); shot("L3-after-queue")
        app.buttons["Lyrics"].tap(); sleep(2)
        app.buttons["Close lyrics"].tap(); sleep(3); shot("L4-after-lyrics")
    }
}

final class PlayerRailLayoutTests: XCTestCase {
    func testHomeEndsAbovePlayerRail() {
        let app = XCUIApplication()
        app.launch()
        let shuffle = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Shuffle your library'")).firstMatch
        XCTAssertTrue(shuffle.waitForExistence(timeout: 10))
        shuffle.tap()
        let rail = app.buttons["miniPlayer"]
        XCTAssertTrue(rail.waitForExistence(timeout: 5))
        let page = app.scrollViews.firstMatch
        XCTAssertLessThanOrEqual(page.frame.maxY, rail.frame.minY + 1)
        page.swipeUp()
        page.swipeUp()
        XCTAssertLessThanOrEqual(page.frame.maxY, rail.frame.minY + 1)
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "Home stays above player"; shot.lifetime = .keepAlways; add(shot)
    }
}

final class Release109Tests: XCTestCase {
    func testMenuRecommendationsAndVinylPlayer() {
        let app = XCUIApplication(); app.launch()
        let menu = app.buttons["Open menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 10)); menu.tap()
        app.buttons["Settings"].tap()
        let recommendations = app.switches["Show recommendations"]
        XCTAssertTrue(recommendations.waitForExistence(timeout: 5))
        if recommendations.value as? String == "1" {
            recommendations.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        }
        XCTAssertEqual(recommendations.value as? String, "0")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(menu.waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Your top artists"].exists)
        let allSongs = app.buttons.matching(NSPredicate(format: "label CONTAINS 'All Songs'")).firstMatch
        XCTAssertTrue(allSongs.waitForExistence(timeout: 5)); allSongs.tap()
        let song = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Swipe Test'")).firstMatch
        XCTAssertTrue(song.waitForExistence(timeout: 5)); song.tap()
        let mini = app.buttons["miniPlayer"]
        XCTAssertTrue(mini.waitForExistence(timeout: 5)); mini.tap()
        let record = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH 'Record. Turn'")).firstMatch
        XCTAssertTrue(record.waitForExistence(timeout: 5))
        let start = record.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.15))
        let end = record.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.5))
        start.press(forDuration: 0.1, thenDragTo: end)
        XCTAssertTrue(app.buttons["Close player"].exists)
        let image = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); image.name = "109 vinyl player"; image.lifetime = .keepAlways; add(image)
    }
}

final class Release110LayoutTests: XCTestCase {
    func testProfileBackButtonAndSearchDoNotExposeFolders() {
        let app = XCUIApplication(); app.launch()
        let menu = app.buttons["Open menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 10))
        menu.tap(); app.buttons["Profile"].tap()
        let bar = app.navigationBars["Profile"]
        XCTAssertTrue(bar.waitForExistence(timeout: 5))
        let back = bar.buttons.element(boundBy: 0)
        XCTAssertTrue(back.isHittable)
        XCTAssertGreaterThanOrEqual(back.frame.minY, 47)
        XCTAssertGreaterThan(back.frame.height, 20)
        XCTAssertTrue(app.staticTexts["PROFILE"].isHittable)
        let profileShot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        profileShot.name = "Profile controls below notch"; profileShot.lifetime = .keepAlways; add(profileShot)
        back.tap()
        XCTAssertTrue(menu.waitForExistence(timeout: 5))
        let allSongs = app.buttons.matching(NSPredicate(format: "label CONTAINS 'All Songs'")).firstMatch
        allSongs.tap()
        let song = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Swipe Test'")).firstMatch
        XCTAssertTrue(song.waitForExistence(timeout: 5)); song.tap()
        let mini = app.buttons["miniPlayer"]
        XCTAssertTrue(mini.waitForExistence(timeout: 5))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        menu.tap(); app.buttons["Profile"].tap()
        XCTAssertTrue(mini.waitForExistence(timeout: 5))
        XCTAssertTrue(app.navigationBars["Profile"].buttons.element(boundBy: 0).isHittable)
        mini.tap()
        let close = app.buttons["Close player"].firstMatch
        XCTAssertTrue(close.waitForExistence(timeout: 5))
        XCTAssertGreaterThanOrEqual(close.frame.minY, 47)
        XCTAssertTrue(close.isHittable)
        close.tap()
        app.tabBars.buttons["Search"].tap()
        XCTAssertTrue(app.searchFields.firstMatch.waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Browse folders"].exists)
        XCTAssertFalse(app.staticTexts["123456789"].exists)
        let searchShot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        searchShot.name = "Search without storage folders"; searchShot.lifetime = .keepAlways; add(searchShot)
    }
}

final class IntegratedAlbumTests: XCTestCase {
    func testSearchOpensAlbumPageWithQuietDownloadControls() {
        let app = XCUIApplication(); app.launch()
        app.tabBars.buttons["Search"].tap()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5)); search.tap(); search.typeText("Beatles Love")
        app.buttons["Albums"].tap()
        let album = app.buttons["catalog-album:166082371098722304"]
        XCTAssertTrue(album.waitForExistence(timeout: 25)); album.tap()
        let download = app.buttons["Download album"]
        XCTAssertTrue(download.waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["Play"].firstMatch.isEnabled)
        let addButton = app.buttons["Add to Library"]
        if addButton.exists { addButton.tap() }
        let remove = app.buttons["Remove from Library"]
        XCTAssertTrue(remove.waitForExistence(timeout: 5)); remove.tap()
        XCTAssertTrue(app.buttons["Add to Library"].waitForExistence(timeout: 5))
        XCTAssertTrue(download.exists)
        XCTAssertFalse(app.buttons["Done"].exists)
        XCTAssertFalse(app.staticTexts["Downloads"].exists)
        XCTAssertFalse(app.staticTexts["Checking audio…"].exists)
        let back = app.navigationBars.buttons.element(boundBy: 0)
        XCTAssertTrue(back.isHittable); XCTAssertGreaterThanOrEqual(back.frame.minY, 47)
        let first = app.buttons["Download Get Back"]
        XCTAssertTrue(first.waitForExistence(timeout: 5)); first.tap()
        let cancel = app.buttons["Cancel download"].firstMatch
        XCTAssertTrue(cancel.waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Queued"].exists)
        XCTAssertFalse(app.staticTexts["Retrying automatically"].exists)
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); shot.name = "Integrated album with download circle"; shot.lifetime = .keepAlways; add(shot)
        cancel.tap()
    }
}

final class PartialAlbumTests: XCTestCase {
    func testSavedSongAndMissingSongsShareTheAlbumPage() {
        let app = XCUIApplication(); app.launch()
        app.tabBars.buttons["Search"].tap()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5)); search.tap(); search.typeText("Beatles Love")
        app.buttons["Albums"].tap()
        let album = app.buttons["catalog-album:166082371098722304"]
        XCTAssertTrue(album.waitForExistence(timeout: 30)); album.tap()
        let saved = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Because' AND label CONTAINS 'Downloaded'")).firstMatch
        XCTAssertTrue(saved.waitForExistence(timeout: 20)); XCTAssertTrue(saved.isEnabled)
        XCTAssertTrue(app.buttons["Download Get Back"].exists)
        XCTAssertFalse(app.buttons["Download Because"].exists)
        XCTAssertTrue(app.buttons["Play"].firstMatch.isEnabled)
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); shot.name = "Saved and missing songs together"; shot.lifetime = .keepAlways; add(shot)
    }
}

final class PlayerCollapseTests: XCTestCase {
    func testDownwardSwipeOverArtworkCollapsesAndKeepsPlaying() {
        let app = XCUIApplication(); app.launch()
        let allSongs = app.buttons.matching(NSPredicate(format: "label CONTAINS 'All Songs'")).firstMatch
        XCTAssertTrue(allSongs.waitForExistence(timeout: 10)); allSongs.tap()
        let song = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Stream Gesture Test'")).firstMatch
        XCTAssertTrue(song.waitForExistence(timeout: 8)); song.tap()
        let mini = app.buttons["miniPlayer"]
        XCTAssertTrue(mini.waitForExistence(timeout: 5)); mini.tap()
        let close = app.buttons["Close player"].firstMatch
        XCTAssertTrue(close.waitForExistence(timeout: 5))
        let playerShot = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); playerShot.name = "iPhone full player"; playerShot.lifetime = .keepAlways; add(playerShot)
        // Start well below the old 140-point header-only drag area.
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.34))
        let finish = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.69))
        start.press(forDuration: 0.08, thenDragTo: finish)
        XCTAssertTrue(mini.waitForExistence(timeout: 5))
        XCTAssertFalse(close.exists)
        XCTAssertTrue(mini.isHittable)
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); shot.name = "Player collapsed to rail"; shot.lifetime = .keepAlways; add(shot)
        mini.tap(); XCTAssertTrue(close.waitForExistence(timeout: 5)); close.tap()
        XCTAssertTrue(mini.waitForExistence(timeout: 5))
    }
}
