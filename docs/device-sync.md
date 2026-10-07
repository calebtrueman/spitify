# Your devices: "Playing on …" and continue where you left off

Spitify has no accounts, so a person's devices are linked once with a short code. Linked devices
then share what they're playing, the way Spotify does. A device can show "Playing on MacBook",
control that device, take playback over ("Listen here"), and continue where you left off.

The shared rules live in `core/.../data/social/DeviceSync.kt` (Android and desktop). The iOS file
`ios/Spitify/Social/DeviceSync.swift` mirrors them with identical JSON. Everything travels over
each platform's existing `PeerRelay`, as kind-30078 events, NIP-44 encrypted to one recipient. The
one exception is the public code offer described below.

## Identity

Each device keeps its own friend key (`PeerIdentity`). The device id is that key's public hex. A
linked device is not a friend: it is never shown in Friends and never receives friend shares.

Device name: the user can edit it in Settings › Your devices. Defaults:

| Platform | Default name |
|---|---|
| Android | `Build.MODEL`, e.g. "Pixel 9" or "SM-F966U" |
| iOS | `UIDevice.current.name`, which is "iPhone" on iOS 16+ |
| Desktop | the host name without `.local`, falling back to "Mac", "Windows PC" or "Linux PC" |

The `platform` value is one of `android`, `ios`, `macos`, `windows`, `linux`.

## Packets

All packets are `SocialPacket(type, body)` with `v = 1`.

| type | Encrypted to | Body | Logical key, for `send(…, logical, recipient)` | Expires |
|---|---|---|---|---|
| `deviceCode` | public, with the extra tag `["t", lookupTag(code)]` | `DeviceCodeOffer` {owner, name, platform, createdAt} | `deviceCode` | 10 min |
| `deviceLinkRequest` | the code's owner | `DeviceLinkRequest` {token, name, platform, createdAt} | `deviceLink` | 10 min |
| `deviceList` | every device in the group | `DeviceList` {devices:[{id,name,platform,linkedAt}], revision} | `deviceList` | 30 days |
| `deviceUnlink` | every device in the group, including the one removed | {id} | `deviceUnlink:<id>` | 30 days |
| `devicePlayback` | every linked device | `DevicePlayback` (see `DeviceSync.kt`) | `devicePlayback` | 14 days |
| `deviceCommand` | the target device | `DeviceCommand` {id, target, action, positionMs, createdAt} | `deviceCommand` | 2 min |

The same logical key sent again replaces the earlier event on the relays, so only the latest state
and the latest command per recipient are kept. The relay's `d` tag is already
`spitify:v1:<logical>:<recipient>:<n>`.

## PeerRelay changes on each platform

1. **`send` gains `extraTags: List<List<String>>`**, appended to the event tags. It's used only by
   `deviceCode`.
2. **New `lookup(tag: String)` with a 15 s timeout.** It sends a one-off
   `["REQ", "spitify-lookup-<n>", {"kinds":[30078], "#t":[tag], "limit": 5}]` on each connected
   socket and delivers matching verified events through the normal `onPacket` path. It closes the
   subscription when done. Normal `receive` validation still applies: `deviceCode` events carry
   `["t","spitify"]` as well as the lookup tag, so they pass.
3. **The relay must be started when any device is linked or a code is being shown/entered,** even
   if friend sharing is turned off. With sharing off, the relay subscribes only to its own
   encrypted inbox (`#p` = me). It does not publish a profile.

## Pairing

1. **Device A, Settings › Your devices › Link a device:**
   - Calls `newCode()` and shows `displayCode(code)` large ("K7QX M2PA") with a 10-minute countdown.
   - Publishes `deviceCode` with the extra tag `lookupTag(code)`.
   - Explains: "On your other device, open Settings › Your devices › Enter code."
2. **Device B, Enter code:** the user types the code; dashes, spaces and case don't matter.
   - B calls `lookup(lookupTag(code))`.
   - For the newest valid `deviceCode` offer, whose `owner` must equal the event author, B remembers
     `pendingOwner = owner`.
   - B then sends `deviceLinkRequest {token: normalizeCode(code), name, platform}` to the owner.
   - B shows "Waiting for <offer.name> to allow this device…".
   - If nothing is found within 15 s, B shows "That code didn't match. Check it on your other device
     — codes last 10 minutes."
3. **Device A** runs `receiveLink(...)`. When it returns true, A shows a dialog: "Link <name>? It
   will see what you play and can control playback." with Allow and Don't allow.
   - **Allow** calls `approve(author)`, then sends `deviceList` (from `list(myName, myPlatform)`)
     to every device in the group, including the new one.
   - **Don't allow** calls `decline(author)`.
4. **Every device** handles a received `deviceList` with
   `acceptList(list, author, encrypted, pendingOwner)`.
   - On B this links the whole group. B clears `pendingOwner` and shows "Linked with <name>".
   - Existing devices learn about B.

**Removing a device:** a remove button on each row sends `deviceUnlink {id}` to every device,
including the one removed, then calls `unlink(id)` locally. A "Leave this group" button sends
`deviceUnlink {id: me}` and clears local state. Receivers use `acceptUnlink`.

## Sharing playback

**When to send `devicePlayback`:**
- After any change: current song, play/pause, seek, queue edits, or the "playing from" source.
- Changes are debounced by `DEBOUNCE` (1.5 s).
- While playing, a heartbeat goes out every `HEARTBEAT` (30 s).
- On pause, one final state is sent, and no heartbeat follows.
- Nothing is sent when no devices are linked.

**Contents:**
- `queue`: the `DevicePlayback.window(...)` of the play order around the current song, each entry
  as `SharedTrack.from(song, onlineTrack)` (Swift: the existing `SharedTrack` builder).
- `revision`: the current time, but always above the last one sent.
- `observedAt` and `positionMs`: when and where the position was read.
- `spoken = true` for podcasts and audiobooks. Other devices show those but don't take them over.

**Don't send during Listening Rooms:** while this device is in a Room as a listener (not the
host), don't send states, so the group doesn't fight over the Room.

**Performance:** on Android the relay outbox write is a `SharedPreferences.commit()`. Call `send`
off the main thread, and never more than once per debounce window per device. Heartbeats must not
wake the CPU when nothing is playing.

## Receiving

- Keep `receivedAt[device] = now` whenever `acceptPlayback` returns true. Persist the
  `DeviceSyncState` JSON with `receivedAt` (Android and desktop: a small JSON file in the data dir;
  iOS: the existing Store), so "continue" survives restarts.
- **`active(receivedAt)`, the "Playing on" bar:**
  - Show it when this device isn't playing and `active` isn't null.
  - Place it right above the mini player, or at the top of the player pane on wide layouts.
  - Desktop shows it above the player bar or in the Now Playing pane.
  - It's a thin bar in the accent colour with a speaker/devices icon: "Playing on MacBook ·
    Song — Artist", plus a play/pause button.
  - Tapping it opens a sheet with that device's song (art from `SharedTrack.artwork`), a progress
    bar driven by `expectedPosition`, Previous / Play-Pause / Next, and **Listen here**.
- **Remote control** sends `deviceCommand` to that device with a fresh random id (16 hex). Seek
  carries `positionMs`.
  - Optimistically update the shown state, e.g. flip `playing`.
  - The real state arrives within about 2 s, because the target sends a state after obeying.

**Receiving a command** (`acceptCommand` returns true):

| action | What the device does |
|---|---|
| `play` | `setPlaying(true)` |
| `pause` | `setPlaying(false)` |
| `next` / `previous` | skip |
| `seek` | `seekTo(positionMs)` |
| `handoff` | pause, then show a brief note: "Now playing on <sender name>" |

**Listen here:**
- Resolve the remote queue to local songs with the existing shared-track matcher: Android
  `PlaylistMatches.resolve(track, app)`, desktop `app.playlistMatches.resolve(track)`, iOS
  `SharedSongMatch.resolve(track, app:)`. It finds a local copy or registers the stream.
- Resolve the current song first and start it at `expectedPosition(...)`. Append the rest in order
  as they resolve, skipping failures.
- Start playing with source `"<remote source>"` (or "From <device name>"), then send `handoff` to
  the remote device.

**Spotify-style single active device:** when the user starts playback on this device (play, a
song tap, or resume; not when obeying a remote `play` command) and `active` names another device,
send that device `handoff`. Two of your devices don't play at once unless one is in a Room.

## Continue where you left off

When the app comes to the foreground or launches, check whether to offer the other device's
playback:

- Conditions:
  - this device isn't playing;
  - `latest(receivedAt)` names another device;
  - its `revision` hasn't been offered before (remember the last offered revision);
  - it's newer than this device's own last playback change (remember that time when sending).
- When they hold, show a card where the mini player is: "Continue from MacBook — Song · Artist",
  with **Play** (which does Listen here) and a dismiss ✕.
- Don't replace the local queue silently.

## Settings › Your devices

Desktop shows this in Settings and also as a "Devices" button (speaker/devices icon) in the player
bar, like Spotify's Connect button. That button opens a popover listing:
- this device;
- linked devices, each with a green "Playing" or a grey "Last active 3 h ago";
- "Link a device".

The Settings screen shows:
- "This device": the name, editable.
- Linked devices: name, platform icon, "Playing: Song" or "Last seen <relative time>", and a remove
  button.
- **Link a device** (show a code) and **Enter code**.
- A one-line privacy note: "Devices you link see what you play and can control playback. Sent
  end-to-end encrypted through the same relays as friend shares."
