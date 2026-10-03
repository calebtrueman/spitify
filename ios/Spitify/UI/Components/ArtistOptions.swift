import SwiftUI
import PhotosUI
import Observation

@MainActor @Observable final class ArtistChoices {
    static let shared = ArtistChoices()
    var hidden = Set(UserDefaults.standard.stringArray(forKey: "hiddenLibraryArtists") ?? [])
    static func key(_ name: String) -> String { "artist:" + SearchMatch.fold(name) }
    func contains(_ name: String) -> Bool { hidden.contains(name) }
    func hide(_ name: String) { hidden.insert(name); save() }
    func show(_ name: String) { hidden.remove(name); save() }
    private func save() { UserDefaults.standard.set(Array(hidden).sorted(), forKey: "hiddenLibraryArtists") }
}

struct ArtistOptions: View {
    var name: String
    @Environment(AppModel.self) private var app
    @State private var photo: PhotosPickerItem?
    var body: some View {
        Menu {
            PhotosPicker(selection: $photo, matching: .images) { Label("Change artist cover", systemImage: "photo") }
            if FileManager.default.fileExists(atPath: ArtCache.shared.customURL(ArtistChoices.key(name)).path) {
                Button("Use original cover") { ArtCache.shared.removeCustom(ArtistChoices.key(name)); app.library.artVersion += 1 }
            }
            if ArtistChoices.shared.contains(name) { Button("Show artist in Library") { ArtistChoices.shared.show(name) } }
            else { Button("Hide artist from Library") { ArtistChoices.shared.hide(name) } }
        } label: { IconControlLabel(symbol: "ellipsis") }.accessibilityLabel("Artist options")
            .task(id: photo) { guard let photo, let data = try? await photo.loadTransferable(type: Data.self) else { return }; ArtCache.shared.storeCustom(data, key: ArtistChoices.key(name)); app.library.artVersion += 1 }
    }
}

struct ArtistPicture: View {
    var name: String
    var fallback: Song? = nil
    var remote: String? = nil
    @Environment(AppModel.self) private var app
    var body: some View {
        let _ = app.library.artVersion
        if FileManager.default.fileExists(atPath: ArtCache.shared.customURL(ArtistChoices.key(name)).path) {
            ArtworkView(key: ArtistChoices.key(name), remote: nil, circle: true)
        } else if let remote { ArtworkView(key: remote, remote: remote, circle: true) }
        else { ArtworkView(fallback, circle: true) }
    }
}

struct HiddenArtistsView: View {
    var body: some View {
        AppList {
            Section { Text("Hiding an artist only changes your Library list. Their songs stay saved and can still be searched and played.") }
            ForEach(ArtistChoices.shared.hidden.sorted(), id: \.self) { name in HStack { Text(name); Spacer(); Button("Show") { ArtistChoices.shared.show(name) } } }
            if ArtistChoices.shared.hidden.isEmpty { Text("No hidden artists.") }
        }.navigationTitle("Hidden artists")
    }
}
