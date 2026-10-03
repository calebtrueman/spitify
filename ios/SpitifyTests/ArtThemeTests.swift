import XCTest
@testable import Spitify

final class ArtThemeTests: XCTestCase {
    func testExistingAppearanceSurvivesNewThemeFields() throws {
        var original = ThemeSettings()
        original.accent = 0xFF4FA3; original.textScale = .huge; original.reduceMotion = true
        var old = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(original)) as? [String: Any])
        old.removeValue(forKey: "artThemeID"); old.removeValue(forKey: "hideThemeArt")
        let restored = try JSONDecoder().decode(ThemeSettings.self, from: JSONSerialization.data(withJSONObject: old))
        XCTAssertEqual(restored.accent, original.accent)
        XCTAssertEqual(restored.textScale, .huge); XCTAssertTrue(restored.reduceMotion)
        XCTAssertNil(restored.artThemeID)
    }

    func testEveryThemeShipsItsArtworkAndKeepsReadingPreferences() throws {
        XCTAssertEqual(ArtTheme.all.count, 25)
        XCTAssertEqual(Set(ArtTheme.all.map(\.id)).count, 25)
        var current = ThemeSettings(); current.textScale = .huge; current.reduceMotion = true
        for theme in ArtTheme.all {
            let url = try XCTUnwrap(Bundle.main.url(forResource: theme.id, withExtension: "png", subdirectory: "themes/art"))
            XCTAssertGreaterThan(try Data(contentsOf: url).count, 1000)
            let selected = theme.applying(to: current)
            XCTAssertEqual(selected.textScale, .huge); XCTAssertTrue(selected.reduceMotion)
            XCTAssertEqual(try JSONDecoder().decode(ThemeSettings.self, from: JSONEncoder().encode(selected)), selected)
            XCTAssertNil(themePresets[0].applying(to: selected).artThemeID)
        }
    }
}
