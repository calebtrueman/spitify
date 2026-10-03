import Foundation

/// Tiny JSON persistence in Application Support, written off the main thread.
enum Store {
    static let directory: URL = {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Spitify", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var backedUp = dir; var values = URLResourceValues(); values.isExcludedFromBackup = false
        try? backedUp.setResourceValues(values)
        return dir
    }()

    static let caches: URL = {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("Spitify", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    static var documents: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0] }

    private static let queue = DispatchQueue(label: "spitify.store", qos: .utility)
    private static let encoder: JSONEncoder = { let e = JSONEncoder(); e.dateEncodingStrategy = .millisecondsSince1970; return e }()
    private static let decoder: JSONDecoder = { let d = JSONDecoder(); d.dateDecodingStrategy = .millisecondsSince1970; return d }()

    static func load<T: Decodable>(_ type: T.Type, _ name: String) -> T? {
        guard let data = try? Data(contentsOf: directory.appendingPathComponent("\(name).json")) else { return nil }
        return try? decoder.decode(type, from: data)
    }

    static func flush() async {
        await withCheckedContinuation { continuation in queue.async { continuation.resume() } }
    }

    static func save<T: Encodable>(_ value: T, _ name: String) {
        guard let data = try? encoder.encode(value) else { return }
        queue.async { try? data.write(to: directory.appendingPathComponent("\(name).json"), options: .atomic) }
    }
}

/// Stable short id from any string (FNV-1a 64).
func stableId(_ s: String) -> String {
    var hash: UInt64 = 0xcbf29ce484222325
    for b in s.utf8 { hash ^= UInt64(b); hash = hash &* 0x100000001b3 }
    return String(hash, radix: 36)
}

func foldForSearch(_ s: String) -> String {
    s.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: .current)
}
