package com.localfy.app.data.social

import org.junit.Assert.assertEquals
import org.junit.Test
import org.nostrdevkit.sdk.Keys

/** Proves the bundled native nostr library loads on this OS/arch and NIP-44 works both ways. */
class NostrLoadTest {
    @Test fun nativeLibraryLoadsAndNip44RoundTrips() {
        val a = Keys.generate(); val b = Keys.generate()
        val sealed = a.nip44Encrypt(b.publicKey(), "hello")
        assertEquals("hello", b.nip44Decrypt(a.publicKey(), sealed))
    }
}
