import Foundation

/// Serialize edits, work on a copy, and replace only after reading the saved tags back.
actor FileTags {
    static let shared = FileTags()

    func write(_ url: URL, edit: MetadataOverride, artwork: Data? = nil, onlyMissing: Bool = false) throws {
        let fm = FileManager.default
        let temp = url.deletingLastPathComponent().appendingPathComponent(".tags-\(UUID().uuidString).\(url.pathExtension)")
        defer { try? fm.removeItem(at: temp) }
        try fm.copyItem(at: url, to: temp)
        var changes: [String: String] = [:]
        for (key, value) in [("TITLE", edit.title), ("ARTIST", edit.artist), ("ALBUM", edit.album),
                             ("ALBUMARTIST", edit.albumArtist), ("GENRE", edit.genre),
                             ("DATE", edit.year.map(String.init)), ("TRACKNUMBER", edit.track.map(String.init)),
                             ("DISCNUMBER", edit.disc.map(String.init))] {
            if let value { changes[key] = value }
        }
        var jpeg: Data?
        if let artwork {
            guard artwork.count <= 20_000_000, let image = ArtCache.squareJPEG(artwork, side: 1200) else {
                throw failure("The cover image could not be read.")
            }
            jpeg = image
        }
        try FileTagsBridge.write(atPath: temp.path, values: changes, artwork: jpeg, onlyMissing: onlyMissing)
        _ = try fm.replaceItemAt(url, withItemAt: temp)
        try? fm.setAttributes([.modificationDate: Date()], ofItemAtPath: url.path)
    }

    private func failure(_ message: String) -> NSError {
        NSError(domain: "Spitify.FileTags", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }
}
