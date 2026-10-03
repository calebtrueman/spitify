package com.localfy.app.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackSafetyTest {
    @Test fun mutedGainStaysMutedThroughFadesAndSleepTimerChanges() {
        for (sleep in listOf(0f, .3f, 1f)) for (fade in listOf(0f, .5f, 1f)) {
            assertEquals(0f, combinedGain(0f, sleep, fade), 0f)
            assertTrue(combinedGain(.2f, sleep, fade) <= .2f)
        }
        assertEquals(.2f, combinedGain(.2f, 1f, 1f), .00001f)
        assertEquals(0f, combinedGain(Float.NaN, 1f, 1f), 0f)
    }
    @Test fun normalizerTurnsLoudSongsDownWithoutBoostingQuietOnes() {
        val loud = NormalizationLevel().measure(.5 * 48000, 48000)
        val quiet = NormalizationLevel().measure(.001 * 48000, 48000)
        assertTrue(loud < .2)
        assertEquals(1.0, quiet, 0.0)
        assertEquals(.125892541, kotlin.math.sqrt(.5) * loud, .00001)
    }
    @Test fun suddenNoiseAfterSilenceCannotHideInsideTheAverage() {
        val level = NormalizationLevel()
        level.measure(0.0, 48000 * 30)
        val gain = level.measure(.5 * 4800, 4800, 1.0)
        assertTrue(kotlin.math.sqrt(.5) * gain <= .251189)
        assertTrue(level.measure(0.0, 4800) <= gain)
    }
    @Test fun normalizationRejectsInvalidInputAndResetsForNextRecording() {
        val level = NormalizationLevel()
        assertEquals(0.0, level.measure(Double.NaN, 10), 0.0)
        level.measure(.5 * 48000, 48000)
        level.reset()
        assertEquals(1.0, level.measure(.0001 * 100, 100), 0.0)
    }
    @Test fun radioPrefersNewSongsAndRevisitsOldestOnlyWhenNeeded() {
        assertEquals(listOf(4L, 5L, 1L, 2L), continuationIds(listOf(1L, 2L, 3L, 4L, 5L, 4L), listOf(1L, 2L, 3L), setOf(3L), 4))
        assertEquals(listOf(1L, 2L), continuationIds(listOf(2L, 1L, 3L), listOf(1L, 2L, 3L), setOf(3L), 5))
        assertTrue(continuationIds(listOf(1L, 2L), emptyList(), setOf(1L, 2L), 5).isEmpty())
    }
}
