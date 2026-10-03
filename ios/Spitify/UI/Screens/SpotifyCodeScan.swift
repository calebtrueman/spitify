import SwiftUI
import PhotosUI
import AVFoundation
import ImageIO

struct SpotifyCodeScanView: View {
    @Environment(\.palette) private var p
    @State private var photo: PhotosPickerItem?
    @State private var camera = false
    @State private var busy = false
    @State private var message: String?
    @State private var target: SpotifyCodeTarget?
    @State private var work: Task<Void, Never>?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Image(systemName: "barcode.viewfinder").font(.system(size: 52)).foregroundStyle(p.accent).accessibilityHidden(true)
                Text("Scan a Spotify code").text(.headlineL)
                Text("Use a photo or take a picture. Keep the whole row of bars visible and level. We read the picture on your phone, then ask Spotify which item it belongs to.").text(.body).foregroundStyle(p.secondary)
                PhotosPicker(selection: $photo, matching: .images) { Label("Choose a photo", systemImage: "photo")
                    .frame(maxWidth: .infinity).padding(.vertical, 8)
                }.buttonStyle(.borderedProminent).disabled(busy)
                if UIImagePickerController.isSourceTypeAvailable(.camera) {
                    Button { Task { await openCamera() } } label: { Label("Take a picture", systemImage: "camera").frame(maxWidth: .infinity).padding(.vertical, 8) }
                        .buttonStyle(.bordered).disabled(busy)
                }
                if busy { ProgressView("Reading your code…").frame(maxWidth: .infinity) }
                if let message { Text(message).text(.bodyS).foregroundStyle(p.secondary).accessibilityIdentifier("spotify-code-message") }
            }.padding(20).frame(maxWidth: 560, alignment: .leading).frame(maxWidth: .infinity)
        }.background(p.background).foregroundStyle(p.text).tint(p.accent)
        .navigationTitle("Spotify code").navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $camera) { SpotifyCodeCamera { image in camera = false; if let image { scan(image) } } }
        .onChange(of: photo) { _, item in
            guard let item else { return }
            work?.cancel(); busy = true; message = nil
            work = Task {
                do {
                    guard let data = try await item.loadTransferable(type: Data.self), data.count <= 50_000_000,
                          let source = CGImageSourceCreateWithData(data as CFData, nil),
                          let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 1200] as CFDictionary) else { throw MusicSourceError.message("This photo could not be opened. Choose another image.") }
                    try Task.checkCancellation()
                    await read(UIImage(cgImage: cg))
                } catch { if !Task.isCancelled { message = error.localizedDescription; busy = false } }
                photo = nil
            }
        }
        .navigationDestination(isPresented: Binding(get: { target != nil }, set: { if !$0 { target = nil } })) {
            if let target {
                if target.kind == "playlist" { SpotifyPlaylistPreview(input: target.id) }
                else { SpotifyScannedItemView(target: target) }
            }
        }
        .onDisappear { work?.cancel(); busy = false }
    }
    private func scan(_ image: UIImage) {
        work?.cancel(); busy = true; message = nil
        work = Task { await read(image) }
    }
    private func read(_ image: UIImage) async {
        do {
            let reference = await Task.detached(priority: .userInitiated) { SpotifyCodeImageReader.read(image) }.value
            try Task.checkCancellation()
            guard let reference else { throw MusicSourceError.message("No clear Spotify code found. Keep all the bars level and in view, avoid glare, and try a closer picture.") }
            let result = try await SpotifyCodeLookup.shared.resolve(reference)
            try Task.checkCancellation(); target = result; busy = false
        } catch { if !Task.isCancelled { message = error.localizedDescription; busy = false } }
    }
    private func openCamera() async {
        let allowed = await AVCaptureDevice.requestAccess(for: .video)
        if allowed { camera = true }
        else { message = "Camera access is off. You can choose a photo, or allow camera access in iPhone Settings." }
    }
}

private struct SpotifyCodeCamera: UIViewControllerRepresentable {
    var done: (UIImage?) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(done: done) }
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController(); picker.sourceType = .camera; picker.delegate = context.coordinator; return picker
    }
    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}
    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let done: (UIImage?) -> Void
        init(done: @escaping (UIImage?) -> Void) { self.done = done }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { done(nil) }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) { done(info[.originalImage] as? UIImage) }
    }
}

struct SpotifyScannedItemView: View {
    let target: SpotifyCodeTarget
    @Environment(\.palette) private var p
    @State private var title = ""
    @State private var loading = true
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(title.isEmpty ? "Spotify \(target.kind)" : title).text(.headlineL)
                Link("Open in Spotify", destination: target.url).text(.label)
                if loading { ProgressView("Finding this item…") }
                else if !title.isEmpty && target.kind != "user" {
                    Text("Find in Spitify").text(.title)
                    Text("Choose the matching result below. Availability can differ from Spotify.").text(.bodyS).foregroundStyle(p.secondary)
                    MixedSearchView(query: title)
                } else { Text("This code was read successfully. Open the item in Spotify using the link above.").text(.body).foregroundStyle(p.secondary) }
            }.padding(16)
        }.background(p.background).navigationTitle("Scanned item").navigationBarTitleDisplayMode(.inline)
        .task {
            defer { loading = false }
            var url = URLComponents(string: "https://open.spotify.com/oembed")!; url.queryItems = [.init(name: "url", value: target.url.absoluteString)]
            if let (data, response) = try? await URLSession.shared.data(for: URLRequest(url: url.url!, timeoutInterval: 15)), (response as? HTTPURLResponse)?.statusCode == 200, data.count < 1_000_000,
               let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any], let name = object["title"] as? String { title = String(name.prefix(200)) }
        }
    }
}
