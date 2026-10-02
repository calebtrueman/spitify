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
        app.buttons.matching(NSPredicate(format: "label CONTAINS 'NOW PLAYING'")).firstMatch.tap()
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
