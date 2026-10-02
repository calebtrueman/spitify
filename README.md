<div align="center">

# Spitify

**Music, podcasts and audiobooks for Android and iPhone.**
Play your own files, find online music, stream it, or download it for offline listening. Listen on your phone or through your car’s playback controls. No Spitify account. Recommendations are learned on your device.

Android: Kotlin · Jetpack Compose · Media3 &nbsp; | &nbsp; iPhone: SwiftUI · AVFoundation · iOS 17+

<img src="docs/screenshots/ios-album.png" width="260" alt="Spitify album page on iPhone"> &nbsp; <img src="docs/screenshots/player-cover-screen.jpg" width="260" alt="Spitify player on Android">

</div>

---

## Choose your device

| Platform | What is available | Install and build |
|---|---|---|
| **Android 11+** | Phones, tablets and foldables; Android Auto and car playback controls | [APK releases](../../releases) · [Android build steps](#build) |
| **iPhone, iOS 17+** | Native SwiftUI app; Files import, Siri shortcuts, background audio and lock-screen controls | [IPA releases](../../releases) · [SideStore, AltStore and Xcode setup](ios/README.md) |
| **CarPlay** | System Now Playing controls; a separate Spitify browsing interface is included in the source and needs Apple-approved CarPlay signing | [CarPlay details](#carplay-and-siri) |

## Screenshots

### iPhone

| Full player | Album page | Small player |
|:---:|:---:|:---:|
| <img src="docs/screenshots/ios-player.png" width="220" alt="iPhone full player"> | <img src="docs/screenshots/ios-album.png" width="220" alt="iPhone album page with separate library and download controls"> | <img src="docs/screenshots/ios-mini-player.png" width="220" alt="iPhone song list with the player collapsed to the bottom rail"> |

<sub>Current iPhone simulator captures. Player screens use a generated test song; the album page shows an online search result.</sub>

### Android

| Cover screen | Player | Made for you | daylist |
|:---:|:---:|:---:|:---:|
| <img src="docs/screenshots/home-cover-screen.jpg" width="200"> | <img src="docs/screenshots/player-cover-screen.jpg" width="200"> | <img src="docs/screenshots/made-for-you.jpg" width="200"> | <img src="docs/screenshots/daylist.jpg" width="200"> |

| Dual-screen player with synced lyrics | Flex Mode (half-folded) |
|:---:|:---:|
| <img src="docs/screenshots/dual-screen-lyrics.jpg" width="420"> | <img src="docs/screenshots/flex-mode.jpg" width="320"> |

| 10-band equaliser | Audiobooks | Edit metadata | Profile | First-run setup |
|:---:|:---:|:---:|:---:|:---:|
| <img src="docs/screenshots/equalizer.jpg" width="160"> | <img src="docs/screenshots/audiobook.jpg" width="160"> | <img src="docs/screenshots/metadata-editor.jpg" width="160"> | <img src="docs/screenshots/profile.jpg" width="160"> | <img src="docs/screenshots/onboarding.jpg" width="160"> |

<sub>Screenshots are from the Galaxy Z Fold8 emulator profile included in this repo, using generated test music.</sub>

## Features

### 🎵 Your music, Spotify-style
- Home with quick picks, generated playlists, Jump back in, Recently added and top artists.
- One search list ranks songs, albums, artists, playlists, podcasts, episodes and audiobooks together. Each result shows its type and creator, with an **E** badge when the source marks it explicit.
- Online results are ready to play, even before saving them. **Play** streams online audio as it arrives. **Add to Library** saves the song without downloading it. **Download** keeps an offline copy with song details and cover art.
- Temporary listening audio has a separate 1 GB cache. Older audio is removed first; Settings can clear it without touching downloads. See [streaming and saved music](docs/STREAMING.md).
- Downloads include Wi-Fi-only mode, cancellation, retries, and matched backup sources when the first source fails. Online availability varies. See [music sources and checks](docs/MONOCHROME.md).
- Your Library with filters, sorting, and grid or list view.
- Album, artist and playlist pages with collapsing headers coloured from the artwork.
- Liked Songs, playlists, and a queue you can reorder by dragging.
- Shuffle that reorders the real queue, so "Next up" shows what will actually play.
- Sleep timer with fade-out, playback speed, skip silence, gapless playback, and 0–12 s **crossfade**.
- **Synced lyrics** from file tags, `.lrc` files, or [LRCLIB](https://lrclib.net), fetched automatically when you're online. They follow playback and you can tap a line to jump to it.
- Lock-screen and Bluetooth playback controls. On iPhone, song details follow the current queue and supported iOS versions can show expanded album artwork.
- Swipe down over the full player to collapse it to the small player. Android also supports notification controls and resume-on-reboot; swiping it away from Recents stops playback.

### Friends, playlists and listening together
- Search public Spotify playlists or paste a playlist link. Matching runs in the background when you open or save a playlist, and saved matches are reused. Playback starts with the first available song while the rest prepare. Spitify keeps the playlist name, cover, description and source link, then matches songs to its own files and online sources. Incomplete source lists and songs that cannot be matched are labelled.
- Open **Your Library → Friends** to share a friend code and follow people. Sharing is off until you turn it on. Public profiles and playlists are optional; private shares are encrypted.
- Share a song, album or playlist from its menu. Invite editors to add, remove, rename and reorder songs. Their changes wait for the owner's app to accept them.
- **Shared Mix** takes turns between songs picked by each person and skips repeated recordings. It uses contributions you choose, rather than uploading listening history.
- **Rooms** lets a host approve guests, share a queue, and choose whether guests can control playback. Everyone plays their own matching source. Leaving or ending a Room stops future Room updates from restarting a guest's music.
- Artist pages show songs from their albums and singles, with **Show more songs** to keep browsing beyond the popular picks. Follow artists from their pages, then open **New releases** in Your Library. Checks run when you open the app or refresh the feed. Optional notifications alert you to new releases found during those checks.

Friends and Rooms use public relays, with no Spitify-run server. Delivery and source availability can vary. See [sharing, privacy and limits](docs/SOCIAL.md).

### Music videos
Tap the video icon in the full player to fill the player with a matching music video, behind the title and controls. Tap it again to return to album art. The video stays muted and follows the song's position, pause state and speed. Your existing song audio continues. Closing the view or putting the app in the background stops the visuals.

Videos use YouTube's embedded player. Some songs have no matching video, and some videos block embedding or show provider ads. The song keeps playing if the video cannot load.

### ✨ Made for you, learned on the device
Every listen is logged locally: how much you heard, whether you finished or skipped it, and the time of day. A taste model rebuilds from that log as you listen:
- **What it weighs:** recent listening counts more, finished songs count more than skips, and liked songs count extra.
- **What it learns:** which songs and artists you play together, and what you play at each time of day.

It generates the playlists you'd expect:

| Made for you | Your mixes | Throwbacks |
|---|---|---|
| Discover Weekly · Release Radar · **daylist** ("mellow synthwave thursday evening") | This Is *artist* · *artist* Radio · Chill / Energy / Focus · genre mixes | On Repeat · Repeat Rewind · Your Top Songs *year* · decade mixes |

There's also song radio on any song, artist radio on artist pages, and **Don't recommend** for songs or artists. Each playlist has a generated cover and a line explaining why it was made. The same listening rules run locally in Kotlin on Android and Swift on iPhone. Making these recommendations needs no network calls or AI service.

### Car playback

#### CarPlay and Siri

The iPhone app sends the current song, cover, playback position and play/pause/skip controls to Apple’s Now Playing screen. It keeps using the same queue when you move between the phone, Bluetooth and the car.

- **Siri and Shortcuts:** play a named song, album, artist, playlist or followed show from your library; pause or resume. These actions also appear in Apple’s Shortcuts app.
- **CarPlay browsing:** the included interface has For you, Library, Podcasts and Books, plus Now Playing and the queue. Shuffle and repeat use the phone’s player.
- **Signing limit:** the dedicated Spitify CarPlay icon and browsing screens require Apple’s approved CarPlay audio permission in the signing profile. The normal SideStore build leaves that permission off. The car’s system Now Playing controls do not depend on that separate browsing interface.

See [iPhone voice and car setup](ios/README.md#voice-and-car-playback). Siri’s choice of app and car hardware behaviour still need checking on the actual phone and vehicle.

#### Android Auto

The Android app supports Android Auto, Android Automotive, Assistant and Bluetooth playback controls.

- Browse For you, Library, Podcasts and Books.
- Choose a song to queue its album or playlist from that point.
- Search by voice, with commands such as “Play Daft Punk on Spitify.”
- Use Like and Shuffle for music, or −10 / +30 seconds for spoken word.
- See the current song’s artwork in the car.

### 🎙️ Podcasts & 📚 audiobooks
- **Podcasts:** search Apple's directory or paste any RSS feed. Stream or download episodes. Each one keeps its resume position and played state. Podcasts have their own speed setting and −10 / +30 s skips.
- **Free audiobooks:** 20,000+ public-domain [LibriVox](https://librivox.org) books, streamed or downloaded chapter by chapter.
- **Your own audiobooks:** files in an `Audiobooks` folder or `.m4b` files show up on the Books tab. Each book shows "Chapter X of Y · time left" and continues where you stopped.

### 🏷️ Metadata & artwork
- **Auto-fix:** untagged songs are matched on Deezer or iTunes, and books on Open Library. A match is only accepted when the length matches within 3 s.
- **Missing covers:** fetched automatically.
- **Manual editing:** edit any song, album or book, with online suggestions or your own image.
- Android manual edits stay in the app. On iPhone, automatic fill can write missing tags and artwork into local files; Music app library files stay read-only. Downloads include tags and cover art.

### 🔊 Formats & sound
Both apps have a 10-band equaliser. On iPhone, EQ and crossfade apply to local files and downloads; live music streams use the system player.

| Platform | Audio formats |
|---|---|
| iPhone | FLAC, ALAC, AAC/M4A, MP3, WAV, AIFF and CAF through iOS decoders |
| Android | The formats below, with extra decoders bundled in the app |

The following details describe Android. See [the iPhone guide](ios/README.md) for its format limits.

- **Formats:** MP3, AAC/M4A, **ALAC**, **FLAC** (incl. 24-bit/96 kHz), Opus, Vorbis, WAV, **AIFF/AIFF-C**, AC-3/E-AC-3, DTS, TrueHD, AMR and more. Bundled FFmpeg decoders cover what the phone can't, and Spitify adds its own AIFF reader.
- **Unsupported:** WMA, APE, WavPack and DSD files are shown greyed out.
- **Equaliser:** an in-app **10-band** EQ with a curve you drag directly, 15 presets, bass boost, surround, loudness and a limiter.

### 🎨 Make it yours
- **Theme:** Dark, Light, AMOLED or follow the system.
- **Accent colour:** presets or the current album art on both apps; Android also offers Material You.
- **Look and feel:** typefaces, text sizes, artwork shape, and player style: artwork, spinning vinyl or minimal. Font choices differ by platform.
- **Motion:** reduce motion and haptics toggles.
- **Profile:** your name and photo, set up on first launch.

## iPhone

The iPhone app in [`ios/`](ios/) is built with SwiftUI and runs on iOS 17+. It includes online search, streaming, offline downloads, playlists, on-device mixes, synced lyrics, podcasts, audiobooks and song-detail editing.

- Import audio with the Files app, AirDrop, the share sheet, or Finder file sharing. Spitify’s Music and Audiobooks folders appear under **On My iPhone → Spitify**.
- Optionally include downloaded, DRM-free songs from the Music app library. Apple Music subscription tracks cannot be played through this option.
- Keep listening in the background. Use the lock screen, Bluetooth, Siri shortcuts and car playback controls.
- Swipe down over the full player to return to the bottom rail.
- See album art on the lock screen. Supported iOS versions can show expanded portrait artwork; iOS controls when that view appears.
- Fill missing song details and covers, edit them manually, and use a name and photo for your local profile.

See [the iPhone guide](ios/README.md) for formats, installation, building, Siri and CarPlay signing.

## Android phones, tablets and foldables

The Android app works on regular phones and tablets as well as foldables. Its wider layouts can show the library and player side by side. The repo includes Galaxy Z Fold8 emulator profiles for checking hinge and cover-screen behaviour.

| Device layout | What you get |
|---|---|
| Phone or closed foldable | Full-screen pages and a small player that opens into the full player |
| Wide screen or open foldable | Library beside an always-on player, with Playing, Lyrics and Queue |
| Dual-screen player | Artwork on one half, synced lyrics or the queue on the other |
| Half-folded Flex Mode | Artwork and lyrics above the hinge, controls below it |
| Fold or unfold mid-song | Playback, scroll position and queue carry over |

<img src="docs/screenshots/home-unfolded.jpg" width="720" alt="Android foldable layout with library and player side by side">

## Install

**Android:** download the APK from [Releases](../../releases) and open it on your phone, or install with:

```bash
adb install -r app-release.apk
```

**iPhone:** download the unsigned IPA from [Releases](../../releases) and sign/install it with SideStore or AltStore. You can also add the [Spitify SideStore source](https://raw.githubusercontent.com/calebtrueman/spitify/main/ios/sidestore-source.json) to receive release updates. See the [iPhone setup and build guide](ios/README.md). An unsigned IPA cannot be installed by opening it directly.

On a Fold, set **Settings › Display › Screen continuity** to *Always* for Spitify, so it keeps playing on the cover screen when you close the phone.

## Build

These commands build **Android**. For the iPhone app, use [the Xcode steps](ios/README.md#build).

Requirements: JDK 17, the Android SDK (platform 37), NDK 26.1 and CMake 3.22. Gradle downloads everything else.

```bash
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest      # unit tests (taste engine, lyrics, feeds, library)
./gradlew connectedDebugAndroidTest   # Android Auto browse test (needs a device/emulator)
```

The release build is signed with the debug key so it can be sideloaded. Add your own signing config before distributing.

FFmpeg ships as prebuilt static libraries (an LGPL build, without GPL or non-free parts). To rebuild them from source, run `scripts/build-ffmpeg.sh`.

Toolchain: AGP 9.4 · Gradle 9.6 · Kotlin 2.3 · compileSdk/targetSdk 37 · minSdk 30.

## Galaxy Z Fold8 emulator

Samsung doesn't publish One UI emulator images. Instead, `scripts/setup-emulator.sh` creates Android 17 dual-display foldable AVDs with the exact **Fold8** and **Fold8 Ultra** panel resolutions, a working hinge sensor, and the fold at the correct position.

```bash
scripts/setup-emulator.sh                       # creates both AVDs, boots Galaxy_Z_Fold8
scripts/setup-emulator.sh Galaxy_Z_Fold8_Ultra
scripts/make-test-music.sh                      # optional: tagged test library (needs ffmpeg)

adb emu fold / adb emu unfold                   # cover screen / main screen
adb emu sensor set hinge-angle0 100             # half-open (rotate 90° first for Flex Mode)
```

## Project layout

```
app/src/main/java/com/localfy/app/      (internal package name predates the rename)
├─ data/          MediaStore scanner, Room database, library repository
│  ├─ taste/      listening history → taste model → generated playlists, profile
│  ├─ meta/       metadata overrides, auto-fix, custom artwork
│  ├─ art/        online album art
│  ├─ podcast/    podcast & audiobook feeds, downloads, resume
│  └─ lyrics/     tag reader (ID3/FLAC/Ogg/MP4), LRC parser, LRCLIB
├─ playback/      MediaLibraryService (Android Auto), crossfade, 10-band EQ, AIFF extractor
└─ ui/            adaptive fold-aware shell, player, screens, theme
ios/Spitify/      SwiftUI iPhone app, AVFoundation player, Siri shortcuts and CarPlay screens
ios/SpitifyTests/ iPhone unit and native playback checks
ffmpeg/           Media3 FFmpeg audio decoder module (JNI + prebuilt FFmpeg 6.0)
scripts/          emulator setup, test music, FFmpeg build
```

## Privacy

Your library, listening history and taste profile stay on the phone. Friends sends only the profile and music details you choose to share. Public profiles and playlists are public; direct shares are encrypted. Relays can still see the sending and receiving public keys. Spitify goes online when a feature needs it. Music search sends your query to Monochrome. Playing or downloading online music contacts the audio source; backup matching can send song, artist and album details to a public source. Streaming writes temporary audio to the listening cache. Permanent music downloads start when you request them. Other optional lookups can be switched off in Settings:

| Feature | Service |
|---|---|
| Online music search, streaming and downloads | Monochrome Tracks; matched public backup sources, including Internet Archive |
| Public Spotify playlist search and metadata | wolfXspotify public service; Spotify public embed as a fallback |
| Friends and Rooms, when enabled | Public Nostr relays; editable in Friends |
| Music video search and playback | YouTube |
| Missing album art and song info | Deezer, iTunes Search |
| Missing book info and covers | Open Library |
| Synced lyrics | LRCLIB |
| Podcast search | Apple Podcasts directory + the show's RSS feed |
| Free audiobooks | LibriVox via the Internet Archive |

## License

Spitify's own code is [MIT](LICENSE). Bundled components keep their own licenses (FFmpeg LGPL 2.1, AndroidX Media3 Apache 2.0, fonts OFL 1.1); see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Spitify is a personal project. It is not affiliated with, endorsed by, or connected to Spotify AB or Samsung.
