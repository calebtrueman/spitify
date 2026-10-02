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

1. On the iPhone, install **[LocalDevVPN](https://apps.apple.com/app/localdevvpn/id6755608044)** from the App Store.
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
