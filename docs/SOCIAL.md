# Friends, shared music and Rooms

Available on Android and iPhone in 1.0.12. Desktop apps and device linking follow in a separate release.

## Using it

Open **Friends** from the sidebar or Library. Your name and photo come from your normal app profile. While sharing is on, changes publish automatically. **Profile → Public profile** chooses between a public profile and an encrypted profile sent to people you follow. Tap **My code** at the top of Friends to share your picture code, then use **Add friend** to import the other person’s code image or link. Adding a friend turns sharing on. Tapping a friend opens their profile and music you share. Unfollowing has its own menu and confirmation. There is no login or Spitify-run server. Your photo, name, bio, privacy and shared music live together on **Profile**. **Settings → Sharing connection** holds pause, discovery and advanced relay controls.

From a song, album or playlist, choose **Share with friends**. This makes a separate saved share. Choose a private recipient or publish it. A public link works after publishing; a private link works only for recipients who received the private share. Recipients must follow the owner. Links open in Spitify and show a choice to connect; opening a link never silently publishes a profile.

**Create a shared playlist or mix** makes an empty list. Add songs from your library or online search. The owner can invite up to 32 editors. Editors send signed, encrypted requests; only the owner can issue the next saved version. Edits wait while the owner is offline. Removing edit permission keeps that person's read access and removes their contribution from a Shared Mix.

Shared Mix takes turns between each person's chosen songs and removes repeated recordings. Sending a contribution replaces your earlier contribution. It does not send listening history. A mix holds up to 200 songs; a normal shared playlist holds up to 2,000.

## Rooms

Choose **Friends → Rooms** to host from the current music queue. Share the Room link. Guests request entry; the host accepts or declines. Guests can add songs. The host can allow guests to pause, resume, restart, skip and remove songs.

Each device resolves and plays its own copy. No audio passes through the relays. A guest with no matching source may be silent. Playback updates account for message age and correct drift over two seconds. Host changes are checked twice a second, with a five-second keep-alive. Speed follows the host. A guest waits silently if the host has not sent an update for 45 seconds.

A Room supports 32 guests and 200 queued songs, expires after 12 hours, and needs the host's app to remain connected. Leaving clears the local session immediately. An ended or expired Room cannot restart from delayed messages. Sessions do not automatically rejoin after an app restart.

## Artist releases and videos

Follow an artist on their page. **New releases** shows the saved feed. Checks run when opening the app and refreshing the feed. Optional local notifications cover newly found releases dated within the last 14 days. These are app-open checks, not instant background push alerts.

Artist pages load songs from albums and singles beyond the source’s short popular list. Use **Show more songs** to keep going.

The music video button fills the main player behind its title and controls. It looks for an official title and artist match with a similar duration. The YouTube embed starts muted, stays at zero volume and follows the native song. Video playback stops when hidden or closed. Source restrictions can prevent a video from loading without stopping the song.

## Public Spotify playlists

Search uses the public wolfXspotify service. A public Spotify embed is a fallback when loading a link. Names, covers, descriptions and source links stay separate from the matched song tags. The app does not use Spotify audio or Spotify credentials. A list is marked partial when its total cannot be established, the source omits rows, or it exceeds the 2,000-song limit. Matches prepare in the background when a playlist opens or is saved and stay cached for later plays. Saving does not wait for matching. Playback starts with the first available song and adds the remaining songs in order. Unmatched songs move to **Failed matches** below the playable list. A matching local import restores the row to its original place. **Choose copy** lets you pick a file with different tags. Both failures and choices stay saved. Matching also tries the existing alternate audio sources when the main catalogue has no exact copy.

## Privacy and message rules

The signing key is protected by iPhone Keychain or Android Keystore. Encrypted OS backups may carry the identity so restoring a device can keep the same friend code. Android rewraps a restored key with the new device’s Keystore key. Only the public friend code is shared. Public profiles and playlists can be read by relays and other people. Direct shares use NIP-44 encryption, but relay operators can see public sender and recipient keys. Removing a local share does not erase copies that were already published or received.

Messages carry song titles, artists, albums, durations and public catalogue IDs. They never carry local paths, audio URLs, access tokens, audio bytes or listening history. Song artwork and source links must be public HTTPS URLs. New profile photos are saved locally at 1536 × 1536 pixels. Sharing sends a 384–768 pixel copy capped at 18 KB, plus a tiny preview for older versions. Previously saved photos cannot regain detail lost before this update; choose the original photo again for the best result. Public profile photos travel publicly; private profile photos travel inside the encrypted message. Incoming messages must have valid signatures and the expected recipient. Only a playlist owner can replace its saved version; an editor request applies at most once.

Nostr SDK 0.45.1 signs and verifies kind 30078 events. The `d` tag begins `spitify:v1:`. Pending events stay on disk until a relay accepts them. Reconnects retry with a delay. Large messages arrive in bounded parts with a shared hash; a receiver applies only the complete message. Receiving ordinary shared music never starts playback. Rooms require an explicit join and host approval.

## Checks

Focused native tests cover owner and editor checks, revoked permissions, duplicate requests, private recipients, bad signatures, incomplete transfers, offline restart, Room joins, delayed updates after leaving, expiry, clock adjustment and mix order. A 500-song encrypted fixture has been sent from Swift to Kotlin and back. A live check delivered generated metadata between fresh test identities through the default public relays. A separate native iPhone check played the video embed near the requested position with mute on and volume zero.

`PeerRelayTests` exports `swift-wire-fixture.json` under the simulator app's Application Support/Spitify directory. Copy it into the isolated Android test app's files directory, then run `SocialTest`. It writes `android-wire-fixture.json`; copying that back allows the return-direction Swift test. Test keys are public fixtures and are never used for a real user's identity. The return-direction test skips when no fixture is present. Live provider checks are separate from routine tests so an external outage cannot break every build.
