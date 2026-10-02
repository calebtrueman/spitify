# Streaming and saved music

Play starts an online song while audio is still arriving. Album play, shuffle, next, queue, the small player, and the system playback controls use the same queue as local music. A matching download on the device takes priority over a stream.

Add to Library saves the song's details without downloading its audio. These choices survive a restart and can appear in albums, playlists and All Songs. Download keeps an offline file, with song details and cover art written into it as before. Listening alone does not add songs to the library.

## Temporary audio

Both apps keep a separate listening cache with a 1 GB budget. Older audio is removed first. Settings includes Clear listening cache. Clearing it does not remove downloaded files or saved library choices. The current stream can finish using its buffer; active iOS cache files are removed when that stream closes.

Android uses Media3's file cache and reads bytes as the player needs them. Partial cached ranges can be reused. iOS feeds AVPlayer from an ongoing file transfer and keeps only finished files between streams. Skipping cancels unfinished iOS transfers. Its cache files stay outside Documents, so the music scanner cannot mistake them for downloads. Cached replay works without a connection, but only an explicit download promises an offline copy that cache cleanup will keep.

A failed source can switch to a matched backup before playback consumes bytes. Sources never get joined midway through a file. Error pages are rejected before playback. iOS uses the backup catalogue's file size when an audio response omits Content-Length; public Archive ZIP entries need this. An unknown-size source may need to finish before iOS can use it, and seeking beyond the received bytes can buffer. There is no hosted proxy, account, cookie or new hosted database.

On iOS, equalizer effects and crossfade continue to apply to downloaded/local files. Live streams use the standard system player. The player sends a fresh song ID, title, album and queue position to the lock screen on each change and removes the previous cover before loading another. iOS 26 portrait artwork remains enabled where supported; iOS controls its expanded presentation.

## Checks

Native tests use slow audio to check that playback starts before the transfer finishes and that cached replay works with networking unavailable. iOS checks MP3, FLAC, M4A, missing size headers, source failure, cancellation, cache cleanup, library saves, and lock-screen updates. Android checks a failed source, a misleading HTML response, progressive playback, cached replay, and separate library/cache state. Page tests check Add to Library versus Download and a downward swipe over the full player's artwork.

The iOS slow-stream test is opt-in with SPITIFY_STREAM_TEST_BASE pointing to a local HTTP fixture server. It serves 30-second stream.mp3, stream.flac and stream.m4a files in small delayed chunks. HEAD reports their true sizes; the ?chunked=1 GET omits Content-Length. Android StreamingTest generates its own 30-second audio in memory and runs in the isolated monochromeTestApp package.
