package com.localfy.app.playback
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
class VinylScrubTest {
    @Test fun boundaryCrossingDoesNotJumpToTheOtherEndOfTheSong() {
        assertEquals(0.2, VinylScrub.delta(PI - 0.1, -PI + 0.1), 0.0001)
        assertEquals(-0.2, VinylScrub.delta(-PI + 0.1, PI - 0.1), 0.0001)
        assertEquals(60000L, VinylScrub.position(30000, 2 * PI, 180000))
        assertEquals(0L, VinylScrub.position(10000, -2 * PI, 180000))
        assertEquals(180000L, VinylScrub.position(170000, 2 * PI, 180000))
    }
}
