# Library sync: the same library on every linked device

Linked devices (see [device-sync.md](device-sync.md)) keep the same library, like Spotify. That
covers likes, saved music, playlists, followed artists and friends, hidden items, podcasts,
listening progress, recently played, play counts, and portable settings.

Audio files never move. A song is identified by what it is, not where it lives, and each device
plays its own file, its own download, or the stream. Downloads stay per device.

The rules live in `core/.../data/sync/LibrarySync.kt` (Android and desktop). The Swift port is
`ios/Spitify/Sync/LibrarySync.swift`, with identical behaviour and JSON. Read the KDoc there first.

## Model

- **Collections:** every collection is a set of items `{k: key, p: present, v: value, t: stampTime,
  d: stampDevice}`. The newest stamp wins: compare `t`, then `d` as a string. Removals are
  tombstones (`p: false`, no `v`) and are kept, so a removed item never comes back from a device
  that hasn't heard about the removal.
- **`history` is grow-only:** items are only ever added, then trimmed after 180 days.
- **`stats:<device>`:** one collection per device, written only by that device (a counter per
  device). A device shows its own counts plus every other device's.
- **What counts as a change:** `meaning(value)`, the value without `track` and without fields whose
  names start with `_`, in canonical form (sorted keys, whole numbers without `.0`). Song details and
  `_…` fields travel but never count as a change. A local file's tags and the catalogue's details
  always differ a little; without this, devices would correct each other forever.

## The platform's two jobs

1. **Report.** Whenever a collection might have changed (observe the data, debounce about 2 s),
   call `report(collection, current)` with what it holds now on this device, as `key → value`.
   - The engine stamps additions and changes, and turns items that disappeared since the last report
     into removals.
   - **Only report a collection once it has loaded.** An empty or half-loaded library would read as
     "remove everything". The engine refuses removals of at least 20 items that are more than half
     the collection, unless `allowMassRemoval` (only pass that for an explicit user action, e.g.
     clearing all likes).
   - For history, call `add(HISTORY, key, value)` for each new local play instead.
2. **Apply.** `receive(doc)` returns `SyncChange`s. For each one, make it true locally, then call
   `applied(change)`.
   - If a song can't be matched yet (offline, or not in the catalogue), skip `applied`. It stays in
     `pending(collection)`; retry when the library changes, when the network returns, and once an
     hour.
   - Removals of things that aren't there count as applied.
   - **Applying must not trigger a report that undoes it.** Call `applied` before the platform's
     observer reports again, or suppress reports while applying.

### Matching songs

Values carry `"track"`: a `SharedTrack` with `id` = the key. To find the local song:

1. A local song (file or download) whose `trackKey(title, artist)` equals the key, preferring the
   closest `durationMs`.
2. Otherwise, a catalogue stream. If `track.sourceID` is set, register that track.
3. Otherwise, the existing shared-track resolver:
   - Android: `PlaylistMatches.resolve(track, app)`
   - desktop: `app.playlistMatches.resolve(track)`
   - iOS: `SharedSongMatch.resolve(track, app:)`

Build outgoing values with `LibrarySync.trackValue(SharedTrack.from(song, onlineTrack))`. Set
`sourceID` whenever the song is a stream or a download. For a plain local file, the platform may
look up the catalogue id in the background later. That changes no key, because keys are titles and
artists.

## Collections

| Collection | Key | Value | Notes |
|---|---|---|---|
| `liked` | `trackKey` | `{track, _addedAt}` | Liked Songs. Order is by `_addedAt`, newest first. |
| `savedTracks` | `trackKey` | `{track, _addedAt}` | Online songs saved to Your Library (`MusicStreams.saved`). |
| `savedAlbums` | `"m:" + catalogue album id` | `{_album: {id, title, artist, artwork, year}, _addedAt}` | If the platform has saved online albums. |
| `followedArtists` | catalogue artist id | `{_artist: {id, name, artwork}}` | Follows from `ArtistFollows`. |
| `hiddenSongs` | `trackKey` | `{track}` | |
| `hiddenArtists` | `SearchMatch.fold(name)` | `{_name}` | |
| `hiddenMixes` | mix key | `{}` | Deleted Made-for-you mixes. |
| `podcasts` | feed URL, or `archive:<id>` for LibriVox | `{_show: {feedUrl, title, author, artwork, kind}}` | Followed shows and books. |
| `progress` | resume key (see Fixed formats) | `{positionMs, durationMs, played, _at}` | Episodes, audiobook chapters and long tracks. Report it rounded down to 5 s, at most once a minute while playing, and on pause, stop and finish. |
| `playlists` | global playlist id (UUID) | `{name, description, imageHash, _image, _createdAt}` | `_image` is a ≤ 24 KB JPEG data URL (300 px) or an https URL; `imageHash` is the first 16 hex characters of its SHA-256. A deleted playlist is a tombstone; then `forget(playlist(id))`. |
| `playlist:<id>` | `entryKeys(tracks)[i]` | `{track, pos}` | The playlist's songs. `pos` is the index; sort by `pos`, then key. |
| `history` | `"<first 8 chars of device>:<playedAt>:<trackKey>"` | `{track, playedAt, listenedMs, durationMs, skipped}` | Grow-only. Every finished or skipped listen; drives Recently played and the taste engine. |
| `stats:<device>` | `trackKey` | `{track, plays, skips, lastPlayed}` | This device's own counts only. |
| `profile` | `"name"`, `"seedArtists"`, `"onboarded"` | `{value}` | |
| `friends` | friend public key | `{_name}` | People you follow. |
| `savedShared` | `"<owner>:<id>"` | `{_name}` | Shared playlists saved to your library. |
| `settings` | a name from `SYNCED_SETTINGS` | `{value}` | Use exactly those names. Map each platform's own setting to the matching name; skip any setting the platform doesn't have. |

### Fixed formats

These must be identical on every platform.

**Progress keys.** Use these, never local ids:
- Podcast episodes: `e:<feedUrl>#<episode guid>`.
- Local audiobook or podcast files, and long tracks: `t:` + `trackKey(title, artist)`.
- A key longer than 380 characters becomes its first 300 characters + `~` + the first 32 hex
  characters of SHA-256(key).

**Progress `_at`:** when the position was recorded, in ms. A receiver keeps its own position if it
recorded it later than the remote `_at`, then reports again so its newer position wins. If `_at` is
missing, apply the remote position.

**Setting values:**

| Setting | Value |
|---|---|
| `themeMode` | `System` \| `Dark` \| `Light` \| `Amoled` |
| `font` | `Figtree` \| `Nunito` \| `SpaceGrotesk` \| `System` \| `Serif` |
| `artShape` | `Rounded` \| `Square` \| `Soft` |
| `playerStyle` | `Artwork` \| `Vinyl` \| `Minimal` |
| `textScale` | the scale as a number: 0.9, 1, 1.12 or 1.25. Apply the nearest. |
| `accent` | the ARGB colour as an integer number |
| `accentFromArt` | boolean |
| `crossfadeMs` | whole number |
| `speedMusic`, `speedPodcast` | decimal number |
| everything else | boolean |

- Match enum names case-insensitively when applying.
- Skip settings this platform doesn't have, and leave values you don't recognise untouched.

**Defaults don't overwrite choices.** Don't report a setting or profile value that is still at its
default unless that name already exists in the synced collection. Otherwise a brand-new device's
defaults would win as the newest change.

**Shapes:**
- A playlist value always has `name` and `description` (`""` when there's none).
- `imageHash` is present only when the playlist has a cover.
- When a remote value means the same as yours, keep reporting the remote's exact value.

**Mass removals.** Explicit user actions that clear a collection report with `allowMassRemoval = true`.
Examples: "Bring back deleted mixes" and "Show hidden again".

### Playlist ids

Each device keeps a map from global id (UUID) to local playlist id in its sync file.
- A playlist created locally gets a new UUID.
- A playlist that arrives from another device is created locally, then mapped.
- On first link, two playlists with the same name and the same songs are not merged; both appear,
  as on Spotify when two accounts merge. They're rare, and the user can delete one.

## Transport

Sync uses each platform's device-sync relay plumbing (`sendDevice`), sending to every linked device.

| Packet type | Body | Logical key | Expires |
|---|---|---|---|
| `syncDoc` | `doc(name)` | `"sync:" + name` | 60 days |
| `syncDigest` | `{docs: digest()}` | `"syncDigest"` | 2 days |

**Sending:**
- After a report or add that changed documents, wait 3 s of quiet, then send each changed document
  to every linked device.
- Send `syncDigest` on start, on linking, when the app comes to the foreground (at most every 10 min),
  and every 6 h.

**Receiving:**
- On a `syncDigest` from device D: for every document name where D's fingerprint differs from yours,
  or that D lacks, send your `doc(name)` to D. D does the same when it gets yours.
- On a `syncDoc`: `receive`, apply, `applied`. Changes from another device are not resent, because
  every device talks to every other directly.
- Accept sync packets only from linked devices (`DeviceSyncState.devices`), encrypted.

**Linking:** right after linking, both sides send a digest. The libraries combine as a union.
Nothing is deleted on link, because there are no tombstones yet.

**Unlinking:**
- A device that leaves keeps its library as it is.
- Its `stats:<id>` collection is forgotten on the others.

**Persistence:**
- Save `LibrarySync.json()` plus the playlist id map to a file in the data directory (iOS: `Store`).
- Write off the main thread, at most once every 2 s.
- Call `trim()` on start.

## Performance

These matter, because Android had serious lag from main-thread work:
- Build snapshots off the main thread.
- Never report on every tick: progress at most once a minute; everything else debounced.
- Matching remote songs can search the catalogue. Do it in the background, at most 4 at a time, and
  never block the UI.

## UI

- Settings › Your devices shows "Library: in sync" or "Library: syncing N items…", with the last
  sync time.
- Songs from another device that couldn't be matched are listed under "Couldn't find on this
  device (N)", with a Retry button.
- No other new UI: synced items just appear in Liked Songs, playlists, Recently played and so on.
