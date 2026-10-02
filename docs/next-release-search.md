# Next release: one music search

Keep this out of 1.0.8 so the current fixes can ship.

- Search music on the device and online together when a connection is available.
- Rank both sources by how well they match the same search. Being on the device must not automatically put a weaker match above a better online match.
- Show Songs and Albums choices. Do not show the Monochrome name in the search interface.
- Fetch artwork automatically and show it to the left of each song or album result.
- Keep local search working offline.
- Combine duplicate local and online matches where they refer to the same recording or album, while keeping the available local copy easy to play.

# Next release: All Songs as the main library entry

Replace the prominent Liked Songs entry with All Songs on Android and iPhone.
It should open every song in the local library. Downloading a song already adds
it to the library, so liking must not be needed to include it in that main list.
Remove the separate likes/favourites feature and its buttons. Saving to the
library or a playlist is the way to keep a song; no separate liked list is needed.
