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

## Installing without a paid developer account

A free Apple ID can sign apps, but Apple makes free signatures expire after **7 days** (and allows 3 sideloaded apps at once). Nothing on a server can change that, because the 7-day limit is in the provisioning profile Apple issues. What you *can* do is re-sign automatically, so you never have to think about it:

1. **[SideStore](https://sidestore.io)** (recommended, no computer needed after setup). It installs once from your Mac, then re-signs apps **on the iPhone itself** in the background over Wi-Fi. Install `Spitify.ipa` through it and turn on background refresh.
2. **[AltStore](https://altstore.io)** with AltServer running on your Mac. It refreshes automatically whenever the iPhone and Mac are on the same Wi-Fi.
3. **Xcode:** plug in the iPhone, select your Apple ID team in *Signing & Capabilities*, and press Run. Repeat weekly.

Grab `Spitify.ipa` from [Releases](../../releases), or from the *iOS build* workflow artifacts. Every push to `main` builds a fresh unsigned IPA ready for SideStore/AltStore.

With a paid Apple Developer account ($99/yr), signatures last a year, or you can use TestFlight.
