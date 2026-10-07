package com.localfy.app.data.sync

import com.localfy.app.data.social.JdkRelaySockets
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Library sync between two computers over the real public relays. Runs only with SPITIFY_LIVE=1. */
class LiveLibrarySyncCheck {
    @Test fun likesPlaylistsAndListensOverRealRelays() {
        assumeTrue(System.getenv("SPITIFY_LIVE") == "1")
        LibrarySyncRepositoryTest().apply { sockets = JdkRelaySockets() }.live()
    }
}
