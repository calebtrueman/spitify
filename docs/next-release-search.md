# Search and playback in 1.0.9

Music search now combines local and online matches. Songs and albums use the same match score regardless of source. A matching local copy replaces a duplicate online result. Local search keeps working without a connection. Results include cover art; the search screen does not show the source service name.

All Songs replaces Liked Songs. Adding music to the library or a playlist is how listeners keep it. Existing saved likes remain in old data for compatibility, but no longer appear as a separate feature or affect recommendations.

Settings lives in the Home menu on both platforms. Listening stats lives in Profile. Show recommendations can hide suggested mixes and top artists, and can be turned back on at any time.

Guest credits no longer split albums when the main album artist is known. Song credits stay intact. Commas inside artist names are kept unless another track on that album establishes the main artist.

Turning the vinyl record seeks within the current song. One full turn moves 30 seconds. The Android mini-player drag target stays alive until the finger lifts, so the player cannot be stranded partway open by that transition. The player background continues through the status bar. Both platforms request high refresh rates where the device allows them.

## Lock-screen art

The setting starts on. A short pause keeps artwork. Stop, closing the playback service, or 10 minutes idle clears the temporary art where the operating system allows the app to run.

Android saves a still lock-screen wallpaper in private, non-backed-up app storage before replacing it. The user must grant All files access in Settings because Android requires it to read the existing wallpaper. No change is made without that access. Live wallpapers are left alone. A saved image is restored only while Spitify still owns the current wallpaper; a later user choice wins. A saved journal recovers normal interrupted sessions on the next launch. If a write was interrupted before its result could be saved, the original remains available through Restore saved wallpaper. A force-stop that kills the process cannot run cleanup until the app opens again.

On iOS 26 and later, the app supplies a local portrait video and matching preview through Apple's animated-artwork API. It does not change the user's wallpaper. iOS decides when to expand that art and may stop running the app while paused, so the 10-minute cleanup cannot be guaranteed to run at an exact time in the background. Older iOS versions keep standard media artwork. Full lock-screen presentation and physical-device refresh rates still need device checks.
