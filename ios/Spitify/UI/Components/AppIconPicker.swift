import SwiftUI
import Observation

struct AppIconChoice: Decodable, Identifiable {
    let id: String
    let name: String
    let group: String
    var alternateName: String? { id == "not_for_rent" ? nil : "Vinyl_" + id }
    var previewName: String { "IconPreview_" + id }
    static let all: [AppIconChoice] = {
        guard let url = Bundle.main.url(forResource: "icon-catalog", withExtension: "json"),
              let data = try? Data(contentsOf: url), let rows = try? JSONDecoder().decode([AppIconChoice].self, from: data) else { return [] }
        return rows
    }()
}

/// The system owns the current icon. Only show a new selection after reading it back.
@MainActor @Observable
final class AppIconSelection {
    static let shared = AppIconSelection()
    private(set) var alternateName: String?
    private(set) var changing = false
    var message: String?
    private let read: () -> String?
    private let change: (String?) async throws -> Void

    init(read: @escaping () -> String? = { UIApplication.shared.alternateIconName },
         change: @escaping (String?) async throws -> Void = { name in
             try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                 UIApplication.shared.setAlternateIconName(name) { error in
                     if let error { continuation.resume(throwing: error) } else { continuation.resume() }
                 }
             }
         }) {
        self.read = read; self.change = change; alternateName = read()
    }

    var selected: AppIconChoice? { AppIconChoice.all.first { $0.alternateName == alternateName } }
    func refresh() { alternateName = read() }
    func select(_ icon: AppIconChoice) async {
        guard !changing else { return }
        refresh()
        guard alternateName != icon.alternateName else { return }
        let previous = alternateName
        changing = true; message = nil
        defer { refresh(); changing = false }
        do {
            try await change(icon.alternateName)
            guard read() == icon.alternateName else { throw IconChangeError.notApplied }
        } catch {
            if read() != previous { try? await change(previous) }
            message = "Couldn't change the icon. Please try again."
        }
    }
    private enum IconChangeError: Error { case notApplied }
}

struct AppIconSettingsLink: View {
    @State private var selection = AppIconSelection.shared
    var body: some View {
        NavigationLink { AppIconPickerView() } label: {
            HStack(spacing: 14) {
                if let icon = selection.selected { Image(icon.previewName).resizable().scaledToFit().frame(width: 48, height: 48).clipShape(RoundedRectangle(cornerRadius: 11)).accessibilityHidden(true) }
                VStack(alignment: .leading, spacing: 3) {
                    Text("App icon")
                    Text(selection.selected?.name ?? "Choose your look").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
            }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
        }.onAppear { selection.refresh() }
    }
}

struct AppIconPickerView: View {
    @State private var selection = AppIconSelection.shared
    @Environment(\.palette) private var palette
    @Environment(\.scenePhase) private var scenePhase
    private let columns = [GridItem(.adaptive(minimum: 98, maximum: 160), spacing: 12, alignment: .top)]
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                Text("A new look for your Home screen.").text(.body).foregroundStyle(palette.secondary)
                if selection.changing { ProgressView("Changing app icon…").frame(maxWidth: .infinity) }
                if let message = selection.message { Text(message).foregroundStyle(palette.text).accessibilityAddTraits(.updatesFrequently) }
                if !UIApplication.shared.supportsAlternateIcons { Text("Changing icons isn't available on this device.").foregroundStyle(palette.secondary) }
                if AppIconChoice.all.isEmpty { Text("Icons couldn't load. Close this screen and try again.").foregroundStyle(palette.secondary) }
                ForEach(["Attitude", "Artwork", "Kids"], id: \.self) { group in
                    Text(group).text(.title).foregroundStyle(palette.text).accessibilityAddTraits(.isHeader)
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 12) {
                        ForEach(AppIconChoice.all.filter { $0.group == group }) { icon in
                            let selected = icon.alternateName == selection.alternateName
                            Button { Task { await selection.select(icon) } } label: {
                                VStack(spacing: 9) {
                                    Image(icon.previewName).resizable().scaledToFit().frame(width: 78, height: 78)
                                        .clipShape(RoundedRectangle(cornerRadius: 18))
                                        .overlay(alignment: .bottomTrailing) {
                                            if selected { Image(systemName: "checkmark.circle.fill").font(.system(size: 21)).symbolRenderingMode(.palette).foregroundStyle(palette.accent, palette.background) }
                                        }
                                    Text(icon.name).text(.label).foregroundStyle(palette.text).multilineTextAlignment(.center).lineLimit(3).frame(maxWidth: .infinity)
                                }
                                .padding(.horizontal, 8).padding(.vertical, 12).frame(maxWidth: .infinity, minHeight: 132, alignment: .top)
                                .background(palette.tint, in: RoundedRectangle(cornerRadius: 16))
                                .overlay(RoundedRectangle(cornerRadius: 16).stroke(selected ? palette.accent : .clear, lineWidth: 2))
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain).disabled(selection.changing || !UIApplication.shared.supportsAlternateIcons)
                            .accessibilityLabel(icon.name).accessibilityAddTraits(selected ? .isSelected : [])
                            .accessibilityHint("Use this icon on your Home screen")
                        }
                    }
                }
            }.padding(16)
        }
        .background(palette.background).navigationTitle("App icon").navigationBarTitleDisplayMode(.inline)
        .onAppear { selection.refresh() }
        .onChange(of: scenePhase) { _, phase in if phase == .active { selection.refresh() } }
    }
}
