# Third-party notices

Spitify's own code is MIT-licensed (see `LICENSE`). It bundles or builds on:

| Component | License | Notes |
|---|---|---|
| [FFmpeg](https://ffmpeg.org) 6.0 (`libavcodec`, `libavutil`, `libswresample`) | LGPL 2.1+ — [`licenses/FFmpeg-LGPL-2.1.txt`](licenses/FFmpeg-LGPL-2.1.txt) | Prebuilt static libraries in `ffmpeg/src/main/jni/ffmpeg/android-libs/`, built **without** `--enable-gpl` / `--enable-nonfree`. Rebuild or replace them with [`scripts/build-ffmpeg.sh`](scripts/build-ffmpeg.sh). Source: `https://github.com/FFmpeg/FFmpeg/tree/release/6.0`. |
| [AndroidX Media3](https://github.com/androidx/media) 1.11.1 — incl. the FFmpeg decoder module sources in `ffmpeg/` | Apache 2.0 — [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) | |
| [Jaudiotagger](https://github.com/ijabz/jaudiotagger) 3.0.1 | LGPL 2.1+ — [`licenses/FFmpeg-LGPL-2.1.txt`](licenses/FFmpeg-LGPL-2.1.txt) | Android audio-file tags and covers. Source: Maven Central `net.jthink:jaudiotagger:3.0.1:sources`. |
| [TagLibSwift](https://github.com/jeonghi/TagLibSwift/tree/a36e48f43a4cea1fd41baa0c90acdb6f35444800) | MIT — [`licenses/TagLibSwift-MIT.txt`](licenses/TagLibSwift-MIT.txt) | Pinned source build, wrapping TagLib 2.3.1 (LGPL 2.1+). Rebuilt by Swift Package Manager from `ios/project.yml`. |
| [Nostr SDK](https://github.com/nostrdevkit/nostr-sdk-swift) 0.45.1 | MIT — [`licenses/Nostr-SDK-MIT.txt`](licenses/Nostr-SDK-MIT.txt) | Swift and Kotlin bindings for signed messages and NIP-44 encryption. |
| [JNA](https://github.com/java-native-access/jna) | Apache 2.0 — [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) | Android native-library calls used by the Nostr bindings; offered under Apache 2.0 or LGPL 2.1+. |
| Jetpack Compose, Room, WindowManager, Navigation, Palette and other AndroidX libraries | Apache 2.0 | |
| [Coil](https://github.com/coil-kt/coil) 3 | Apache 2.0 | |
| [Figtree](https://github.com/erikdkennedy/figtree), [Nunito](https://github.com/googlefonts/nunito), [Space Grotesk](https://github.com/floriankarsten/space-grotesk) fonts | SIL Open Font License 1.1 — [`licenses/OFL-1.1.txt`](licenses/OFL-1.1.txt) | `app/src/main/res/font/` |

Online services used (optional, no accounts or keys): Deezer and iTunes Search (artwork & tags),
Open Library (book info), LRCLIB (lyrics), Apple Podcasts search and podcast RSS feeds,
LibriVox / Internet Archive (public-domain audiobooks), wolfXspotify and Spotify public embeds (playlist metadata), YouTube (music video search and embedded visuals), and public Nostr relays (opt-in sharing). Their content belongs to their respective owners.
