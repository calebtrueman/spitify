<div align="center">

# Spitify

**A Spotify-style player for the music, podcasts and audiobooks on your phone.**
Built for the Samsung Galaxy Z Fold8. No account, no streaming service, no ads. Your recommendations are learned on the device.

Kotlin · Jetpack Compose · Media3 · FFmpeg · Room · Jetpack WindowManager · Android Auto

<img src="docs/screenshots/home-unfolded.jpg" width="720" alt="Spitify on the unfolded Galaxy Z Fold8: library on the left half, player on the right">

</div>

---

## Screenshots

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
- Search across songs, artists, albums and playlists, plus browsing by genre and folder.
- Your Library with filters, sorting, and grid or list view.
- Album, artist and playlist pages with collapsing headers coloured from the artwork.
- Liked Songs, playlists, and a queue you can reorder by dragging.
- Shuffle that reorders the real queue, so "Next up" shows what will actually play.
- Sleep timer with fade-out, playback speed, skip silence, gapless playback, and 0–12 s **crossfade**.
- **Synced lyrics** from file tags, `.lrc` files, or [LRCLIB](https://lrclib.net) (optional). They follow playback and you can tap a line to jump to it.
- Lock screen, notification, Bluetooth controls and resume-on-reboot.

### ✨ Made for you, learned on the device
Every listen is logged locally: how much you heard, whether you finished or skipped it, and the time of day. A taste model rebuilds from that log as you listen:
- **What it weighs:** recent listening counts more, finished songs count more than skips, and liked songs count extra.
- **What it learns:** which songs and artists you play together, and what you play at each time of day.

It generates the playlists you'd expect:

| Made for you | Your mixes | Throwbacks |
|---|---|---|
| Daily Mix 1–6 · Discover Weekly · Release Radar · **daylist** ("mellow synthwave thursday evening") | This Is *artist* · *artist* Radio · Chill / Energy / Focus · genre mixes | On Repeat · Repeat Rewind · Your Top Songs *year* · decade mixes |

There's also song radio on any song, artist radio on artist pages, and **Don't recommend** for songs or artists. Each playlist has a generated cover and a line explaining why it was made. All of this is plain Kotlin on the device, with no network calls and no AI service.

### 📱 Built for the Galaxy Z Fold8
| Device state | What you get |
|---|---|
| **Closed** (5.5″ 1248×1972 cover screen) | Phone layout. Drag the mini player up to open the full player. Below it are lyrics, up next, the artist and credits. |
| **Open** (7.6″ 2448×1848 main screen) | The screen splits at the hinge: library on the left, an always-on player with Playing / Lyrics / Queue on the right. |
| **Dual-screen player** | The player on one half, synced lyrics or the queue on the other. |
| **Flex Mode** (half-folded) | Artwork and lyrics on the upright half, controls on the half resting on the desk. |
| **Fold or unfold mid-song** | Playback, scroll position and queue carry over. |

It also adapts to the Fold8 Ultra and to any phone or tablet size.

### 🚗 Android Auto
Spitify works in Android Auto, Android Automotive, Assistant and Bluetooth head units:
- **Tabs:** For you, Library, Podcasts and Books.
- **Tap a song:** its whole album or playlist queues from there.
- **Search and voice:** "Hey Google, play Daft Punk on Spitify".
- **Buttons:** Like and Shuffle, or −10 / +30 s for spoken word.
- **Artwork:** shown in the car too.

### 🎙️ Podcasts & 📚 audiobooks
- **Podcasts:** search Apple's directory or paste any RSS feed. Stream or download episodes. Each one keeps its resume position and played state. Podcasts have their own speed setting and −10 / +30 s skips.
- **Free audiobooks:** 20,000+ public-domain [LibriVox](https://librivox.org) books, streamed or downloaded chapter by chapter.
- **Your own audiobooks:** files in an `Audiobooks` folder or `.m4b` files show up on the Books tab. Each book shows "Chapter X of Y · time left" and continues where you stopped.

### 🏷️ Metadata & artwork
- **Auto-fix:** untagged songs are matched on Deezer or iTunes, and books on Open Library. A match is only accepted when the length matches within 3 s.
- **Missing covers:** fetched automatically.
- **Manual editing:** edit any song, album or book, with online suggestions or your own image.
- **Your files are never modified.** Edits live in the app, so *Reset* always works.

### 🔊 Formats & sound
- **Formats:** MP3, AAC/M4A, **ALAC**, **FLAC** (incl. 24-bit/96 kHz), Opus, Vorbis, WAV, **AIFF/AIFF-C**, AC-3/E-AC-3, DTS, TrueHD, AMR and more. Bundled FFmpeg decoders cover what the phone can't, and Spitify adds its own AIFF reader.
- **Unsupported:** WMA, APE, WavPack and DSD files are shown greyed out.
- **Equaliser:** an in-app **10-band** EQ with a curve you drag directly, 15 presets, bass boost, surround, loudness and a limiter.

### 🎨 Make it yours
- **Theme:** Dark, Light, AMOLED or follow the system.
- **Accent colour:** 10 presets, the current album art, or Material You.
- **Look and feel:** 5 typefaces, 4 text sizes, artwork shape, and Now Playing style (artwork, spinning vinyl or minimal).
- **Motion:** reduce motion and haptics toggles.
- **Profile:** your name and photo, set up on first launch.

## Install

Download the APK from [Releases](../../releases) (or build it yourself, below) and sideload it:

```bash
adb install -r app-release.apk
```

On a Fold, set **Settings › Display › Screen continuity** to *Always* for Spitify, so it keeps playing on the cover screen when you close the phone.

## Build

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
ffmpeg/           Media3 FFmpeg audio decoder module (JNI + prebuilt FFmpeg 6.0)
scripts/          emulator setup, test music, FFmpeg build
```

## Privacy

Everything stays on the phone: your library, listening history, taste profile, name and photo. Spitify only goes online when a feature needs it, and sends only search terms such as an artist and album name. Each of these can be switched off in Settings:

| Feature | Service |
|---|---|
| Missing album art and song info | Deezer, iTunes Search |
| Missing book info and covers | Open Library |
| Synced lyrics (opt-in) | LRCLIB |
| Podcast search | Apple Podcasts directory + the show's RSS feed |
| Free audiobooks | LibriVox via the Internet Archive |

## License

Spitify's own code is [MIT](LICENSE). Bundled components keep their own licenses (FFmpeg LGPL 2.1, AndroidX Media3 Apache 2.0, fonts OFL 1.1); see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Spitify is a personal project. It is not affiliated with, endorsed by, or connected to Spotify AB or Samsung.
