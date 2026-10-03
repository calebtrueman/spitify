import Foundation

/// A generated playlist (daylist, artist mixes, radio...).
struct Mix: Identifiable, Hashable {
    enum Section: String, CaseIterable { case madeForYou = "Made for you", discover = "Discover", yourMixes = "Your mixes", throwbacks = "Throwbacks & favourites" }
    enum CoverStyle { case collage, bold }
    var id: String
    var title: String
    var description: String
    var songs: [Song]
    var section: Section = .madeForYou
    var style: CoverStyle = .collage
    var accent: UInt32 = 0x1ED760
    var why: String? = nil
    var refresh: String? = nil
    var cover: Song { songs[0] }
}

struct TasteInput {
    var songs: [Song]
    var listens: [Listen]
    var liked: Set<String>
    var seedArtists: Set<String> = []
    var hiddenSongs: Set<String> = []
    var hiddenArtists: Set<String> = []
    var userName: String? = nil
    var now: Date = Date()
}

enum Daypart: Int, CaseIterable {
    case morning, afternoon, evening, night
    var label: String { ["morning", "afternoon", "evening", "late night"][rawValue] }
    var mood: String { ["fresh", "upbeat", "mellow", "dreamy"][rawValue] }
    static func of(hour: Int) -> Daypart {
        switch hour { case 5...11: .morning; case 12...16: .afternoon; case 17...21: .evening; default: .night }
    }
}

extension Song {
    var genreKey: String? {
        guard let g = genre?.trimmingCharacters(in: .whitespaces).lowercased(), !g.isEmpty, g != "podcast", g != "audiobook" else { return nil }
        return g
    }
    var decade: Int? { (1900...2100).contains(year) ? year / 10 * 10 : nil }
}

/// What the app has learned about the listener. Rebuilt from raw listens so it keeps adapting:
/// recent listening counts more (30-day half-life), finishing beats half-listening, skips count
/// against, likes count for, and songs heard in the same session pull together (co-listening).
final class TasteModel {
    let input: TasteInput
    let byId: [String: Song]
    private(set) var songScore: [String: Double] = [:]
    private(set) var recentScore: [String: Double] = [:]
    private(set) var artistScore: [String: Double] = [:]
    private(set) var genreScore: [String: Double] = [:]
    private(set) var decadeScore: [Int: Double] = [:]
    private(set) var daypartGenre: [Daypart: [String: Double]] = [:]
    private(set) var daypartSong: [Daypart: [String: Double]] = [:]
    private(set) var co: [String: [String: Double]] = [:]
    private(set) var artistCo: [String: [String: Double]] = [:]
    private(set) var lastPlayed: [String: Date] = [:]
    private(set) var completedPlays: [String: Int] = [:]
    private var artistSongCount: [String: Int] = [:]
    private var artistGenres: [String: Set<String>] = [:]

    init(_ input: TasteInput) {
        self.input = input
        byId = Dictionary(input.songs.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        for s in input.songs {
            artistSongCount[s.primaryArtist, default: 0] += 1
            if let g = s.genreKey { artistGenres[s.primaryArtist, default: []].insert(g) }
        }
        build()
    }

    private func build() {
        let cal = Calendar.current
        for l in input.listens {
            guard let s = byId[l.songId] else { continue }
            let ageDays = max(0, input.now.timeIntervalSince(l.at)) / 86_400
            let decay = pow(0.5, ageDays / 30)
            let fraction = l.durationMs > 0 ? min(1, max(0, Double(l.listenedMs) / Double(l.durationMs))) : 0.5
            let w = l.skipped ? -0.6 : (l.completed ? 1.0 : 0.2 + 0.8 * fraction)
            songScore[s.id, default: 0] += w * decay
            if ageDays <= 30 { recentScore[s.id, default: 0] += w }
            if !l.skipped {
                lastPlayed[s.id] = max(lastPlayed[s.id] ?? .distantPast, l.at)
                if l.completed { completedPlays[s.id, default: 0] += 1 }
                let part = Daypart.of(hour: cal.component(.hour, from: l.at))
                if let g = s.genreKey { daypartGenre[part, default: [:]][g, default: 0] += w * decay }
                daypartSong[part, default: [:]][s.id, default: 0] += w * decay
            }
        }
        if !input.seedArtists.isEmpty { for s in input.songs where s.creditedArtists.contains(where: input.seedArtists.contains) { songScore[s.id, default: 0] += 0.8 } }

        for (id, sc) in songScore {
            guard let s = byId[id] else { continue }
            artistScore[s.primaryArtist, default: 0] += sc
            if let g = s.genreKey { genreScore[g, default: 0] += sc }
            if let d = s.decade { decadeScore[d, default: 0] += sc }
        }
        for (a, v) in artistScore { artistScore[a] = v / max(1, sqrt(Double(artistSongCount[a] ?? 1))) * 1.5 }

        let positive = input.listens.filter { !$0.skipped && byId[$0.songId] != nil }.sorted { $0.at < $1.at }
        var session: [Listen] = []
        func flush() {
            for i in session.indices {
                for j in (i + 1)..<min(session.count, i + 6) {
                    let a = session[i].songId, b = session[j].songId
                    if a == b { continue }
                    let w = 1.0 / Double(j - i)
                    co[a, default: [:]][b, default: 0] += w
                    co[b, default: [:]][a, default: 0] += w
                    let aa = byId[a]!.primaryArtist, ba = byId[b]!.primaryArtist
                    if aa != ba {
                        artistCo[aa, default: [:]][ba, default: 0] += w
                        artistCo[ba, default: [:]][aa, default: 0] += w
                    }
                }
            }
            session = []
        }
        for l in positive {
            if let last = session.last, l.at.timeIntervalSince(last.at) > 30 * 60 { flush() }
            session.append(l)
        }
        flush()
    }

    private lazy var maxSong = max(1e-6, songScore.values.max() ?? 1)
    private lazy var maxArtist = max(1e-6, artistScore.values.max() ?? 1)
    private lazy var maxGenre = max(1e-6, genreScore.values.max() ?? 1)
    private lazy var maxDecade = max(1e-6, decadeScore.values.max() ?? 1)

    func normSong(_ id: String) -> Double { min(1, max(-1, (songScore[id] ?? 0) / maxSong)) }
    func normArtist(_ a: String) -> Double { min(1, max(-1, (artistScore[a] ?? 0) / maxArtist)) }
    func normGenre(_ g: String?) -> Double { g.map { min(1, max(-1, (genreScore[$0] ?? 0) / maxGenre)) } ?? 0 }
    func normDecade(_ d: Int?) -> Double { d.map { min(1, max(-1, (decadeScore[$0] ?? 0) / maxDecade)) } ?? 0 }

    func similarity(_ a: Song, _ b: Song) -> Double {
        var s = 0.0
        if a.primaryArtist == b.primaryArtist { s += 0.45 }
        if a.albumKey == b.albumKey { s += 0.2 }
        if let ga = a.genreKey, let gb = b.genreKey {
            if ga == gb { s += 0.25 } else if ga.split(separator: " ").contains(where: { $0.count > 2 && gb.contains($0) }) { s += 0.12 }
        }
        if a.decade != nil, a.decade == b.decade { s += 0.08 }
        let c = co[a.id]?[b.id] ?? 0
        s += 0.6 * c / (c + 1)
        let ac = artistCo[a.primaryArtist]?[b.primaryArtist] ?? 0
        s += 0.3 * ac / (ac + 1)
        return s
    }

    func artistSimilarity(_ a: String, _ b: String) -> Double {
        if a == b { return 1 }
        let ga = artistGenres[a] ?? [], gb = artistGenres[b] ?? []
        let jaccard = ga.isEmpty || gb.isEmpty ? 0 : Double(ga.intersection(gb).count) / Double(ga.union(gb).count)
        let c = artistCo[a]?[b] ?? 0
        return 0.6 * jaccard + 0.6 * c / (c + 1)
    }

    private lazy var favourites: [Song] = songScore.filter { $0.value > 0 }.sorted { $0.value > $1.value }.prefix(25).compactMap { byId[$0.key] }

    func predicted(_ s: Song) -> Double {
        let resemblance = favourites.map { similarity(s, $0) * max(0, normSong($0.id)) }.max() ?? 0
        return 0.5 * normArtist(s.primaryArtist) + 0.3 * normGenre(s.genreKey) + 0.1 * normDecade(s.decade) + 0.6 * resemblance
    }

    func hidden(_ s: Song) -> Bool { input.hiddenSongs.contains(s.id) || input.hiddenArtists.contains(s.artist) || s.creditedArtists.contains(where: input.hiddenArtists.contains) }

    lazy var topArtists: [String] = {
        let ranked = artistScore.filter { $0.value > 0 && !input.hiddenArtists.contains($0.key) }.sorted { $0.value > $1.value }.map(\.key)
        let rankedSet = Set(ranked)
        let rest = artistSongCount.sorted { $0.value > $1.value }.map(\.key).filter { !rankedSet.contains($0) && !input.hiddenArtists.contains($0) }
        return (ranked + rest).filter { !$0.lowercased().hasPrefix("unknown") }
    }()
}

/// Seeded RNG so a day's/week's playlists are stable until they're due to refresh.
struct SeededRandom: RandomNumberGenerator {
    var state: UInt64
    init(_ seed: Int) { state = UInt64(bitPattern: Int64(seed)) &+ 0x9E3779B97F4A7C15 }
    mutating func next() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
    mutating func double() -> Double { Double(next() >> 11) / Double(1 << 53) }
}

enum PlaylistGenerator {
    static let moods: [(String, [String])] = [
        ("Chill", ["chill", "lofi", "lo-fi", "ambient", "acoustic", "folk", "jazz", "soul", "downtempo", "bossa", "classical"]),
        ("Energy", ["rock", "metal", "punk", "edm", "electronic", "dance", "house", "techno", "drum", "hip-hop", "hip hop", "rap", "trap", "synthwave", "pop"]),
        ("Focus", ["classical", "ambient", "instrumental", "soundtrack", "score", "piano", "post-rock", "lofi", "minimal"]),
    ]
    static let palette: [UInt32] = [0x1ED760, 0xE8115B, 0x509BF5, 0xF59B23, 0xAF2896, 0x27856A]

    static func generate(_ m: TasteModel) -> [Mix] {
        let input = m.input
        let songs = input.songs.filter { $0.playable && !$0.isSpoken && !m.hidden($0) }
        guard !songs.isEmpty else { return [] }
        let cal = Calendar.current
        let daySeed = cal.component(.year, from: input.now) * 1000 + (cal.ordinality(of: .day, in: .year, for: input.now) ?? 0)
        let part = Daypart.of(hour: cal.component(.hour, from: input.now))
        var out: [Mix] = []

        // daylist
        let partGenreScores = (m.daypartGenre[part]?.isEmpty == false ? m.daypartGenre[part]! : m.genreScore)
        let partGenres = Array(partGenreScores.sorted { $0.value > $1.value }.map(\.key).prefix(2))
        let weekday = { let f = DateFormatter(); f.dateFormat = "EEEE"; return f.string(from: input.now).lowercased() }()
        let partSongs = m.daypartSong[part] ?? [:]
        var rd = SeededRandom(daySeed * 10 + part.rawValue)
        let daylist = Array(diversify(songs.filter { partGenres.isEmpty || $0.genreKey.map(partGenres.contains) == true }
            .map { ($0, (partSongs[$0.id] ?? 0) * 0.8 + m.predicted($0) * 0.5 + rd.double() * 0.3) }.sorted { $0.1 > $1.1 }.map(\.0), maxPerArtist: 4).prefix(40))
        if daylist.count >= 5 {
            let name = "daylist • \(part.mood) \(partGenres.joined(separator: " ")) \(weekday) \(part.label)".replacingOccurrences(of: "  ", with: " ")
            out.append(Mix(id: "daylist", title: name, description: "Your day in music, updated through the day based on what you play at this hour.",
                           songs: daylist, section: .madeForYou, style: .bold, accent: [0xFFB86B, 0xFF6B9D, 0x7A5CFF, 0x2B2D6E][part.rawValue],
                           why: "You tend to play \(partGenres.isEmpty ? "this" : partGenres.joined(separator: " & ")) around \(part.label)s.", refresh: "Changes through the day"))
        }

        // On Repeat / Repeat Rewind
        let onRepeat = Array(m.recentScore.filter { $0.value > 0.5 }.sorted { $0.value > $1.value }.compactMap { m.byId[$0.key] }.filter { !m.hidden($0) }.prefix(30))
        if onRepeat.count >= 5 { out.append(Mix(id: "onrepeat", title: "On Repeat", description: "Songs you can't stop playing right now.", songs: onRepeat, section: .throwbacks, style: .bold, accent: 0xE91429, refresh: "Updated daily")) }
        let sixty = input.now.addingTimeInterval(-60 * 86_400)
        let rewind = Array(m.songScore.filter { $0.value > 1 && (m.lastPlayed[$0.key] ?? .distantPast) < sixty }.sorted { $0.value > $1.value }.compactMap { m.byId[$0.key] }.filter { !m.hidden($0) }.prefix(30))
        if rewind.count >= 5 { out.append(Mix(id: "rewind", title: "Repeat Rewind", description: "Past favourites you haven't played in a while.", songs: rewind, section: .throwbacks, style: .bold, accent: 0x148A08)) }

        // This Is / Radio
        for (i, artist) in m.topArtists.prefix(3).enumerated() {
            let theirs = songs.filter { $0.creditedArtists.contains(artist) }
            if theirs.count >= 5 {
                out.append(Mix(id: "thisis:\(artist)", title: "This Is \(artist)", description: "The essential tracks, ranked by how much you play them.",
                               songs: theirs.sorted { m.normSong($0.id) * 2 + m.predicted($0) > m.normSong($1.id) * 2 + m.predicted($1) }, section: .yourMixes, style: .collage, accent: palette[(i + 2) % palette.count]))
            }
            if i < 2 {
                let radio = artistRadio(m, artist, songs)
                if radio.count >= 8 { out.append(Mix(id: "radio:\(artist)", title: "\(artist) Radio", description: "\(artist) and the artists you play alongside them.", songs: radio, section: .yourMixes, style: .collage, accent: palette[(i + 4) % palette.count], why: "Artists you listen to in the same sessions as \(artist), plus similar sounds.")) }
            }
        }

        // Mood mixes
        for (i, (name, keys)) in moods.enumerated() {
            var rnd = SeededRandom(daySeed + i * 7)
            let hits = songs.filter { s in s.genreKey.map { g in keys.contains { g.contains($0) } } == true }
            if hits.count >= 8 {
                out.append(Mix(id: "mood:\(name)", title: "\(name) Mix", description: name == "Chill" ? "Kick back to soft, easy sounds." : name == "Energy" ? "Turn it up." : "Music to get things done.",
                               songs: Array(diversify(hits.map { ($0, m.predicted($0) + rnd.double() * 0.4) }.sorted { $0.1 > $1.1 }.map(\.0), maxPerArtist: 4).prefix(40)),
                               section: .yourMixes, style: .bold, accent: name == "Chill" ? 0x477D95 : name == "Energy" ? 0xDC148C : 0x537AA1, refresh: "Updated daily"))
            }
        }

        // Genre mixes
        var genreOrder = m.genreScore.sorted { $0.value > $1.value }.map(\.key)
        if genreOrder.isEmpty { genreOrder = Dictionary(grouping: songs.compactMap(\.genreKey), by: { $0 }).sorted { $0.value.count > $1.value.count }.map(\.key) }
        for (i, g) in genreOrder.prefix(4).enumerated() {
            var rnd = SeededRandom(daySeed + i)
            let hits = songs.filter { $0.genreKey == g }
            if hits.count >= 5 {
                let pretty = g.prefix(1).uppercased() + g.dropFirst()
                out.append(Mix(id: "genre:\(g)", title: "\(pretty) Mix", description: "The best \(pretty) in your library.",
                               songs: Array(diversify(hits.map { ($0, m.predicted($0) + rnd.double() * 0.3) }.sorted { $0.1 > $1.1 }.map(\.0), maxPerArtist: 4).prefix(50)),
                               section: .yourMixes, style: .collage, accent: palette[(i + 1) % palette.count]))
            }
        }

        // Decades
        let decades = Dictionary(grouping: songs.compactMap(\.decade), by: { $0 }).filter { $0.value.count >= 10 }
        if decades.count >= 2 {
            for d in decades.keys.sorted(by: >) {
                out.append(Mix(id: "decade:\(d)", title: "Your \(d)s", description: "The songs from the \(d)s you love most.",
                               songs: Array(songs.filter { $0.decade == d }.sorted { m.normSong($0.id) + m.predicted($0) * 0.5 > m.normSong($1.id) + m.predicted($1) * 0.5 }.prefix(50)),
                               section: .throwbacks, style: .bold, accent: 0xBA5D07))
            }
        }

        // Your Top Songs <year>
        let year = cal.component(.year, from: input.now)
        let yearStart = cal.date(from: DateComponents(year: year, month: 1, day: 1)) ?? input.now
        let counts = Dictionary(grouping: input.listens.filter { $0.at >= yearStart && !$0.skipped }, by: \.songId).mapValues(\.count)
        let top = Array(counts.sorted { $0.value > $1.value }.compactMap { m.byId[$0.key] }.filter { !m.hidden($0) }.prefix(50))
        if top.count >= 10 { out.append(Mix(id: "top:\(year)", title: "Your Top Songs \(year)", description: "The songs you loved most this year, all wrapped up.", songs: top, section: .throwbacks, style: .bold, accent: 0x1E3264)) }
        return out
    }

    static func songRadio(_ m: TasteModel, seed: Song, size: Int = 50) -> [Song] {
        var rnd = SeededRandom(seed.id.hashValue)
        let pool = m.input.songs.filter { $0.playable && !$0.isSpoken && $0.id != seed.id && !m.hidden($0) }
        let ranked = pool.map { ($0, m.similarity(seed, $0) + 0.2 * m.predicted($0) + rnd.double() * 0.05) }.sorted { $0.1 > $1.1 }.map(\.0)
        return [seed] + diversify(ranked, maxPerArtist: 6).prefix(size - 1)
    }

    static func artistRadio(_ m: TasteModel, _ artist: String, _ songs: [Song]? = nil) -> [Song] {
        let all = (songs ?? m.input.songs).filter { $0.playable && !$0.isSpoken && !m.hidden($0) }
        let related = Array(m.topArtists.filter { $0 != artist }.sorted { m.artistSimilarity(artist, $0) > m.artistSimilarity(artist, $1) }.prefix(6))
        let pool = all.filter { !$0.creditedArtists.contains(artist) && related.contains($0.primaryArtist) }
        return interleave(
            Array(all.filter { $0.creditedArtists.contains(artist) }.sorted { m.normSong($0.id) + m.predicted($0) > m.normSong($1.id) + m.predicted($1) }.prefix(20)),
            Array(pool.sorted { m.predicted($0) + m.artistSimilarity(artist, $0.primaryArtist) > m.predicted($1) + m.artistSimilarity(artist, $1.primaryArtist) }.prefix(30)))
    }

    static func interleave(_ a: [Song], _ b: [Song]) -> [Song] {
        var out: [Song] = []; var i = 0, j = 0
        while i < a.count || j < b.count {
            for _ in 0..<2 where i < a.count { out.append(a[i]); i += 1 }
            if j < b.count { out.append(b[j]); j += 1 }
        }
        var seen = Set<String>()
        return out.filter { seen.insert($0.id).inserted }
    }

    static func diversify(_ list: [Song], maxPerArtist: Int = 3, maxPerAlbum: Int = .max) -> [Song] {
        var perArtist: [String: Int] = [:], perAlbum: [String: Int] = [:]
        var kept = list.filter { s in
            perArtist[s.primaryArtist, default: 0] += 1; perAlbum[s.albumKey, default: 0] += 1
            return perArtist[s.primaryArtist]! <= maxPerArtist && perAlbum[s.albumKey]! <= maxPerAlbum
        }
        if kept.count > 2 {
            for i in 1..<kept.count where kept[i].primaryArtist == kept[i - 1].primaryArtist {
                if let swap = ((i + 1)..<kept.count).first(where: { kept[$0].primaryArtist != kept[i - 1].primaryArtist }) { kept.swapAt(i, swap) }
            }
        }
        return kept
    }
}
