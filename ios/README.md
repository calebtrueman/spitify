# Spitify for iPhone

A SwiftUI port of Spitify. It has the same on-device recommendation engine (ported from Kotlin, with the same tests), synced lyrics, crossfade, a 10-band EQ, podcasts, LibriVox audiobooks, metadata/artwork fetching and editing, themes, and first-run profile setup. It runs on iOS 17+ (iPhone XS / XR and newer, including the iPhone 13).

## Online music and saved listening

Search for a song or album, then choose **Play**, **Add to Library**, or **Download**. Play starts audio as it arrives. Add to Library saves the song details; Download keeps an offline file. Streaming uses a separate 1 GB cache, with older audio removed first. Clear it in Settings without removing downloads. [More about streaming](../docs/STREAMING.md).

Swipe down over the full player to return to the bottom rail. Lock-screen song details follow the current queue. On supported iOS versions, album artwork can appear expanded; iOS controls that presentation. EQ and crossfade apply to local files and downloads, while live streams use the system player.

<img src="../docs/screenshots/ios-player.png" width="220" alt="iPhone player"> <img src="../docs/screenshots/ios-album.png" width="220" alt="iPhone album page">

## Adding music

- **Files app:** On My iPhone › **Spitify** › `Music` (or `Audiobooks`). Drop files or folders in.
- **AirDrop / Share sheet:** "Open in Spitify".
- **Mac:** Finder › your iPhone › Files › Spitify, and drag folders in.
- **In the app:** *Import music* (from iCloud Drive, USB drives, etc.).
- **Music app library:** Settings › *Include Music app library* plays downloaded, DRM-free songs. Apple Music streaming tracks are DRM-protected and can't be played by other apps.

Formats: FLAC (incl. 24-bit/96 kHz), ALAC, AAC, MP3, WAV, AIFF, CAF. Opus, Vorbis, WMA and APE aren't supported by iOS's decoders and show greyed out.

## Build

```bash
brew install xcodegen
cd ios && xcodegen generate
open Spitify.xcodeproj           # or:
xcodebuild test -project Spitify.xcodeproj -scheme Spitify -destination 'platform=iOS Simulator,name=iPhone 17'
```

## Installing with SideStore (free Apple ID, refreshes itself)

A free Apple ID signature lasts 7 days. SideStore renews it **on the iPhone, in the background**. The app is updated in place, so your library, playlists and listening history stay put, and you never reinstall.

### One-time setup (≈15 min, iPhone plugged into the Mac)

1. On the iPhone, install **LocalDevVPN** from the App Store (search "LocalDevVPN").
2. On the Mac, open **iloader** (Applications), sign in with your Apple ID, select the iPhone, and choose **Install SideStore (Stable)**. iloader also saves the pairing file SideStore needs.
3. On the iPhone:
   - Settings › General › **VPN & Device Management** › trust your Apple ID.
   - Settings › Privacy & Security › **Developer Mode** › On (the phone restarts).
4. Open LocalDevVPN, tap **Connect**, then open SideStore and sign in with the same Apple ID.
5. In SideStore › **Sources** › **+**, add:
   ```
   https://raw.githubusercontent.com/calebtrueman/spitify/main/ios/sidestore-source.json
   ```
   Then **Browse** › Spitify › **Free**. (Or open `Spitify-x.y.z-unsigned.ipa` from Releases and use SideStore's **+** in My Apps.)

### Make it automatic

Shortcuts › **Automation** › **+** › **Time of Day** (e.g. 3:00 am daily) › **Run Immediately**, with these actions:

1. **LocalDevVPN › Connect**
2. **SideStore › Refresh All Apps**
3. *(optional)* **LocalDevVPN › Disconnect**

As long as the phone is on Wi-Fi at least once every 7 days, Spitify (and SideStore itself) never expire. New Spitify releases also show up in SideStore's **Updates** tab, because each GitHub release updates the source above.

Free Apple ID limits: 3 sideloaded apps at once (SideStore counts as one) and 10 new app IDs per week.

### Other options

- **Xcode:** plug in, pick your Apple ID team in *Signing & Capabilities*, press Run. Has to be repeated weekly.
- **Paid Apple Developer account** ($99/yr): signatures last a year, or use TestFlight.

## Releasing a new version

Bump `MARKETING_VERSION` in `project.yml`, then publish a GitHub release tagged `vX.Y.Z`. CI then builds the IPA, attaches it to the release, and adds it to `ios/sidestore-source.json`. SideStore will offer the update.

## Voice and car playback

Siri shortcuts are included in the app. After opening Spitify and loading your library,
try “Play [song, album, artist, playlist, or show] in Spitify,” “Resume Spitify,”
“Pause Spitify,” “Next song in Spitify,” “Previous song in Spitify,”
“Turn shuffle on in Spitify,” or “Set repeat to song in Spitify.”
These actions also appear in Apple's Shortcuts app. Named shortcuts use your library,
followed shows, and current queue. When a name has no local match, **Play in Spitify**
also searches online artists and songs.

If Siri hears “Spotify,” pronounce Spitify as “spit-if-eye” and try
“Resume in Spitify player” or “Play Liked Songs in Spitify player.” The app includes
Apple's supported pronunciation hint without changing its name on your Home Screen.
Speech recognition still depends on Siri and your device.

For a name that sounds completely different, open **Settings › Siri & Shortcuts** in
Spitify. In Apple's Shortcuts app, create a shortcut with the **Resume Spitify** action
and name it **Pocket music**. Then say **“Hey Siri, Pocket music.”** You can do the same
for Pause, Next song, or **Play in Spitify**. For an artist shortcut, choose the
**Play in Spitify** action, search for **Lana Del Rey**, select the artist, and name
the shortcut **Play Lana**. Then say **“Hey Siri, Play Lana.”** This route does not
need the separate Siri signing permission. Apple documents
[running a shortcut by its name](https://support.apple.com/guide/shortcuts/run-shortcuts-with-siri-apd07c25bb38/ios).

The app also handles Siri's built-in music requests directly. That path needs a
Siri-enabled signing profile. Build with
`SPITIFY_APP_ENTITLEMENTS=Spitify/Siri.entitlements` and a matching team/profile.
To enable both Siri and CarPlay, use one entitlement file containing both keys.
The regular SideStore build keeps this optional permission off. App Shortcuts stay
available without it. Siri still chooses where to send a spoken request, so a real
phone voice check is required after signing and installing.
See [Apple's Siri setup](https://developer.apple.com/documentation/xcode/configuring-siri-support).
The pronunciation hint follows [Apple's app-name guidance](https://developer.apple.com/library/archive/qa/qa1950/_index.html).
We do not rely on alternate app-name aliases: Apple's current
[synonym guidance](https://developer.apple.com/documentation/sirikit/specifying-synonyms-for-your-app-name)
requires an Intents extension, which this app does not ship.

“Keep music playing” is on by default. The queue keeps manual picks first, then the
selected album or playlist, then a separate Autoplay section. It refreshes suggestions
as you listen and skips tracks that fail. Repeat, sleep timers, listening rooms, and
explicitly clearing the queue take priority. Podcasts and books do not lead into music.

“Normalize volume” is on by default. Local files are measured from decoded samples;
streams use a decoded-audio tap. Loud songs are lowered, quiet songs are never boosted,
and the app never raises the phone's volume. Audio format detection uses file bytes
rather than a server's label, including Ogg/Opus returned from a FLAC-labeled source.
Native Ogg/Opus playback and normalization were checked on iOS 26.4; older iOS decoder
support may differ.

To repeat the stream checks, start the local fixture server from the `ios` folder:

```sh
python3 scripts/stream_fixture.py --generate
```

`--generate` needs `ffmpeg` on PATH and creates only synthetic 30-second tones under
`work/stream-fixture`. The server sends audio slowly, uses a wrong FLAC label, and can
split a file into byte ranges. Leave it running, then use another terminal:

```sh
TEST_RUNNER_SPITIFY_STREAM_TEST_BASE=http://127.0.0.1:18952 \
TEST_RUNNER_SPITIFY_AUDIO_LIVE_CHECK=1 \
xcodebuild -project Spitify.xcodeproj -scheme Spitify \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  CODE_SIGNING_ALLOWED=NO -only-testing:SpitifyTests/StreamRecoveryTests test
```

Choose an installed simulator name if it differs. These five checks cover a failed
first start, one retry only, cancelling an old retry, paused seeking, and quick Opus
seeks. The live flag also checks the reported Buggles recording with an empty listening
cache. The seek checks count non-silent samples after the normal volume adjustment,
including while the record is being moved. They do not measure a phone's speaker or
Bluetooth output. Omit the live flag to skip the network recording check.

The car's built-in Now Playing screen uses the same audio, artwork, and play/pause/
skip controls as the phone. The separate Spitify CarPlay screens include library
browsing, podcasts, books, and the queue. They share the phone's player and work
when the car launches the app first.

Apple requires an approved CarPlay audio signing profile for that separate app
icon and browsing interface. A free SideStore signature does not provide it.
The regular SideStore build therefore leaves the restricted entitlement off.
After approval, build with `SPITIFY_CARPLAY_ENTITLEMENTS=CarPlay.entitlements`
and your matching team and provisioning profile. This setting also allows a
CarPlay simulator build without changing the normal SideStore release.
See [Apple's CarPlay setup](https://developer.apple.com/documentation/carplay/requesting-carplay-entitlements).

Missing song details and covers are filled automatically when a matching online
result is found. Existing file tags and artwork stay in place. Local files receive
the missing values directly; Music app library files remain read-only. If a match
is uncertain, Spitify leaves the field alone. “Find online” remains available for
choosing a replacement yourself.

## Widgets and automatic backup

The IPA includes Quick play, Your Library and Friends widgets in three sizes. Keep
the `SpitifyWidgets` extension when signing the IPA. An Xcode install needs a profile
for both the app and its widget extension. A build signed only for the app cannot
install those widgets.

Settings and custom covers are included in device backups. The friend identity stays
in Keychain and can be restored from an encrypted device backup. Offloading keeps
app data on the phone. Deleting and reinstalling the app by itself does not restore
all settings; this app has no separate backup account.
