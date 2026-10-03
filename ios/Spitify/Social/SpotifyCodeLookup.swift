import Foundation
import CryptoKit

struct SpotifyCodeTarget: Hashable {
    let kind: String
    let id: String
    var url: URL { URL(string: "https://open.spotify.com/\(kind)/\(id)")! }
    static func parse(_ value: String) -> Self? {
        let parts = value.split(separator: ":").map(String.init)
        guard parts.first == "spotify" else { return nil }
        let pair: [String]
        if parts.count == 5, parts[1] == "user", parts[3] == "playlist" { pair = Array(parts.suffix(2)) }
        else if parts.count == 3 { pair = Array(parts.suffix(2)) }
        else { return nil }
        guard ["playlist", "track", "album", "artist", "show", "episode", "audiobook", "user"].contains(pair[0]),
              !pair[1].isEmpty, pair[1].count <= 100,
              pair[1].allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "_" || $0 == "-") }) else { return nil }
        return .init(kind: pair[0], id: pair[1])
    }
}

/// Uses a short-lived anonymous web-player session. No personal account or token is saved.
actor SpotifyCodeLookup {
    static let shared = SpotifyCodeLookup()
    private let session = URLSession(configuration: .ephemeral)
    private var token: String?
    private var expires = Date.distantPast
    // Public web-player handshake, verified October 2026. Spotify may change this format.
    private let version = 61
    private let key = "GM3TMMJTGYZTQNZVGM4DINJZHA4TGOBYGMZTCMRTGEYDSMJRHE4TEOBUG4YTCMRUGQ4DQOJUGQYTAMRRGA2TCMJSHE3TCMBY"
    private func request(_ url: URL, token: String? = nil) async throws -> (Data, HTTPURLResponse) {
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.setValue("Mozilla/5.0", forHTTPHeaderField: "User-Agent")
        if let token { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        let (data, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse, data.count < 1_000_000 else { throw MusicSourceError.message("Spotify's code lookup is unavailable. Try again shortly.") }
        return (data, response)
    }
    private func accessToken() async throws -> String {
        if let token, expires.timeIntervalSinceNow > 60 { return token }
        let (clockData, clockResponse) = try await request(URL(string: "https://open.spotify.com/api/server-time")!)
        let clock = (try? JSONSerialization.jsonObject(with: clockData)) as? [String: Any]
        let now = clockResponse.statusCode == 200 ? (clock?["serverTime"] as? Double ?? Date().timeIntervalSince1970) : Date().timeIntervalSince1970
        let code = Self.totp(base32: key, time: now)
        var url = URLComponents(string: "https://open.spotify.com/api/token")!
        url.queryItems = [.init(name: "reason", value: "init"), .init(name: "productType", value: "web-player"), .init(name: "totp", value: code), .init(name: "totpServer", value: code), .init(name: "totpVer", value: String(version))]
        let (data, response) = try await request(url.url!)
        let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        guard response.statusCode == 200, object?["isAnonymous"] as? Bool == true, let value = object?["accessToken"] as? String else {
            throw MusicSourceError.message("Spotify changed or blocked its code lookup. You can still paste a playlist link.")
        }
        token = value
        expires = Date(timeIntervalSince1970: (object?["accessTokenExpirationTimestampMs"] as? Double ?? (Date().timeIntervalSince1970 + 300) * 1000) / 1000)
        return value
    }
    func resolve(_ reference: UInt64) async throws -> SpotifyCodeTarget {
        guard reference < (1 << 37) else { throw MusicSourceError.message("This code could not be read.") }
        for attempt in 0..<2 {
            let access = try await accessToken()
            let (data, response) = try await request(URL(string: "https://spclient.wg.spotify.com/scannable-id/id/\(reference)?format=json")!, token: access)
            if response.statusCode == 401 && attempt == 0 { token = nil; continue }
            let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            guard response.statusCode == 200, let target = object?["target"] as? String, let result = SpotifyCodeTarget.parse(target) else {
                throw MusicSourceError.message("Spotify couldn't open this code. It may have expired or point to something unavailable.")
            }
            return result
        }
        throw MusicSourceError.message("Spotify's code lookup is unavailable. Try again shortly.")
    }
    static func totp(base32: String, time: TimeInterval) -> String {
        let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")
        var buffer: UInt64 = 0; var count = 0; var bytes: [UInt8] = []
        for c in base32 { guard let value = alphabet.firstIndex(of: c) else { continue }; buffer = (buffer << 5) | UInt64(value); count += 5; if count >= 8 { count -= 8; bytes.append(UInt8((buffer >> count) & 255)) }; buffer &= (1 << count) - 1 }
        var step = UInt64(time / 30).bigEndian
        let data = withUnsafeBytes(of: &step) { Data($0) }
        let hash = Array(HMAC<Insecure.SHA1>.authenticationCode(for: data, using: SymmetricKey(data: bytes)))
        let offset = Int(hash[19] & 15)
        let value = hash[offset..<(offset + 4)].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) } & 0x7fffffff
        return String(format: "%06u", value % 1_000_000)
    }
}
