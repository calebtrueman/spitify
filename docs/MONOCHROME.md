# Monochrome music downloads

Search → Online finds songs and albums on Monochrome. Downloaded music joins the normal library, so likes, playlists, recommendations and offline playback work as before. The app does not need a new hosted server.

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

The app checks transfer completion, FLAC metadata boundaries and the song's duration before publishing a file. Original tags and embedded covers remain in the file. Since the service sometimes reuses a recording tagged with another album, the selected song/album details are also saved through the app's existing metadata overrides. User edits take precedence. These overrides do not rewrite downloaded tags.

- **iOS:** background URLSession transfers; an atomic queue file under Application Support; staging files outside the scanned music folder; completed files under `Documents/Music/Monochrome/{releaseId}/{trackId}.flac`. The app reconnects to background tasks on launch. Interrupted jobs with no task are queued again. Force-quitting an app can cancel background work under iOS; reopening allows recovery.
- **Android:** system DownloadManager transfers; Room stores the queue; completion broadcasts schedule a WorkManager import. Files stay private until checked, then move through a pending MediaStore entry into `Music/Spitify/Monochrome/{releaseId}/{trackId}.flac`. The local database migration from version 5 to 6 only adds `music_downloads`; there is no production server database.

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
