# Spitify for iPhone

A SwiftUI port of Spitify. It has the same on-device recommendation engine (ported from Kotlin, with the same tests), synced lyrics, crossfade, a 10-band EQ, podcasts, LibriVox audiobooks, metadata/artwork fetching and editing, themes, and first-run profile setup. It runs on iOS 17+ (iPhone XS / XR and newer, including the iPhone 13).

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
try “Play [song, album, artist, playlist, or show] in Spitify,” “Resume Spitify,” or
“Pause Spitify.” These actions play items already in your library or followed shows.
They also appear in Apple's Shortcuts app. Siri still decides which app a spoken
request belongs to; the phone needs a real voice check after installation.

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
