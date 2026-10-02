# Monochrome music downloads

Search finds saved music and songs and albums from Monochrome in one list. Album and song results open the normal full-page layout. Songs can stream before they are downloaded; compact circles show deliberate download progress. Add to Library saves a song without downloading it. See [Streaming](STREAMING.md) for cache behavior. The download button sits beside the play controls. Saved songs keep their usual menus and queue gestures. Downloaded music joins the normal library, so likes, playlists, recommendations and offline playback work as before. The app does not need a new hosted server.

## Connection

This uses the public Tracks client published by Monochrome, checked on October 1, 2026:

- Source: https://github.com/monochrome-music/monochrome/blob/main/js/tracks-api.js
- Server: `https://tracks.monochrome.st`
- Songs: `GET /search/tracks?q=...&limit=30`
- Albums: `GET /search/releases?q=...&limit=30`
- Album contents: `GET /releases/{releaseId}`
- Audio: `GET /track/{trackId}`

This is the current Tracks route, not the older TIDAL HiFi or browser-verification route. The bounded live check succeeded without a login, token or modified request headers. These are third-party routes and may change. Access failures remain visible; the app does not treat them as an empty search or try to bypass them.

Track and release IDs stay as strings. Durations from this server are milliseconds, including very short tracks. The audio response may use `application/octet-stream`; both clients inspect the FLAC header and stream information. Quality labels come from the downloaded file, not a search result's advertised quality. There is no conversion or quality selector in this version.

## Saving music

The queue allows two transfers at once. Wi-Fi-only is on by default and is saved per new job. Users can cancel a queued or active transfer, and retry failed or cancelled items. Repeated taps and repeated album requests do not create another job for the same source track. Different releases may contain different track IDs; those remain separate deliberately.

The app checks transfer completion, FLAC metadata boundaries and the song's duration before publishing a file. The selected title, song artist, album artist, album and cover are written into the downloaded file before it is published. App overrides keep the same chosen release details. Existing user edits take precedence.

- **iOS:** background URLSession transfers; an atomic queue file under Application Support; staging files outside the scanned music folder; completed files under `Documents/Music/Monochrome/{releaseId}/{trackId}.{flac,m4a,mp3}`. The app reconnects to background tasks on launch. Interrupted jobs with no task are queued again. Force-quitting an app can cancel background work under iOS; reopening allows recovery.
- **Android:** system DownloadManager transfers; Room stores the queue; completion broadcasts schedule a WorkManager import. Files stay private until checked, then move through a pending MediaStore entry into `Music/Spitify/Monochrome/{releaseId}/{trackId}.{flac,m4a,mp3}`. The local database migration from version 5 to 6 only adds `music_downloads`; there is no production server database.

No source URLs are treated as permanent local music locations. Deleting a download outside the app allows it to be fetched again after queue reconciliation. Completed downloads are never removed by Cancel.

## Tests

Normal tests use no network:

```sh
./gradlew :app:testDebugUnitTest
cd ios
xcodegen generate
xcodebuild test -project Spitify.xcodeproj -scheme Spitify \
  -destination 'platform=iOS Simulator,name=YOUR_TEST_SIMULATOR' \
  -only-testing:SpitifyTests
```

The opt-in native checks use Kevin MacLeod's “Carefree”, track `156361611655778304`, and remove the test file afterward. Use disposable test devices.

For iOS, enable `MONOCHROME_LIVE=1` in the scheme's **Test** environment (turn off inheritance from Run) and run `MonochromeLiveTests`. It searches, reads the album, downloads through a background URLSession, scans the file into the library and decodes the whole file with AVAudioFile. Keep normal local simulator signing enabled: an unsigned app cannot reliably reach the system download service. If the repo is in iCloud Documents, place derived data outside it (for example `-derivedDataPath /tmp/spitify-tests`) so Finder attributes do not block signing. The generated Xcode project is ignored; do not commit the opt-in environment setting.

For Android:

```sh
./gradlew -PmonochromeTestApp :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.localfy.app.MonochromeIntegrationTest \
  -Pandroid.testInstrumentationRunnerArguments.monochromeLive=true
```

`monochromeTestApp` uses a separate app ID so the installed app is not replaced. Tests cover the database migration and preservation of existing likes/playlists, duplicate requests, library import, the selected album and native playback of the local file.

The small FLAC header fixtures test parsing and rejection; they are not playable recordings. The live tests provide playback evidence. Background recovery and cancellation tests do not replace testing OS-driven suspension on physical phones.

## Automatic retries and backup audio

Short network and server failures retry automatically. After the first retries, the app looks for a matching copy of the same release, then checks public Internet Archive files, then the existing direct public YouTube path. It keeps the selected song ID, album details and artwork. Permanent failures stay retryable without showing server codes or file paths on album pages.

The Archive lookup uses `https://archive.org/advancedsearch.php` and `/metadata/{identifier}`. Artist and album must match. Song titles must match after removing filename numbering and the artist prefix; known durations must be within three seconds. Private or restricted items are skipped. Individual public ZIP entries can be downloaded through the Archive's own file links; the app never downloads or unpacks a whole ZIP. Album file lists are cached briefly so an album does not cause one search per song.

Only HTTPS Archive download paths for FLAC, M4A or MP3 are accepted. Each file must pass the phone's audio checks and match the requested length before tags and artwork are saved. Failed candidate URLs are remembered so the same bad copy is not selected again. Missing catalogue coverage is still possible; a working backup does not guarantee every recording will be available forever.

The older YouTube path accepts only direct, public audio URLs. Login or access challenges end that attempt. No account, private token, cookie extraction, external helper or new hosted server is used.

On October 2, 2026, iOS native tests forced the first source to fail and downloaded Carmen, Million Dollar Man and the Love version of Because twice each. Android tests verified the same source handoff, saved tags and local playback for those three songs. A separate iOS test downloaded all 28 Love tracks from the backup, wrote tags and artwork, and opened every saved file with native audio. These checks use temporary files and test devices.

The live Archive tests are opt-in: `SPITIFY_ARCHIVE_LIVE_TEST=1` plus the local 404 fixture address in `SPITIFY_RETRY_TEST_BASE` for iOS, or `-e archiveLive true` with `com.localfy.app.ArchiveAudioTest` in the separate Android test app. Regular unit tests leave these network checks off.
