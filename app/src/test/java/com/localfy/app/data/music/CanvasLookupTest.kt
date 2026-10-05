package com.localfy.app.data.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasLookupTest {
    @Test fun matchesTheSameSongByTheSameArtist() {
        assertTrue(CanvasLookup.matches("Houdini", "Dua Lipa", "Houdini", "Dua Lipa"))
        assertTrue(CanvasLookup.matches("BIRDS OF A FEATHER", "Billie Eilish", "Birds of a Feather", "Billie Eilish"))
        assertTrue(CanvasLookup.matches("One More Time (Radio Edit)", "Daft Punk", "One More Time", "Daft Punk"))
        assertTrue(CanvasLookup.matches("Blinding Lights", "The Weeknd", "Blinding Lights (Official Video)", "The Weeknd"))
        assertTrue(CanvasLookup.matches("Señorita", "Shawn Mendes; Camila Cabello", "Señorita", "Shawn Mendes & Camila Cabello"))
    }

    @Test fun rejectsOtherArtistsAndOtherRecordings() {
        assertFalse(CanvasLookup.matches("One More Time", "Daft Punk", "ONE MORE TIME", "blink-182"))
        assertFalse(CanvasLookup.matches("Houdini", "Dua Lipa", "Houdini (London Sessions)", "Dua Lipa"))
        assertFalse(CanvasLookup.matches("Houdini", "Dua Lipa", "Houdini (Live)", "Dua Lipa"))
        assertFalse(CanvasLookup.matches("Houdini", "Dua Lipa", "Houdini (Lyric Video)", "Dua Lipa"))
        assertFalse(CanvasLookup.matches("Houdini", "Dua Lipa", "Training Season", "Dua Lipa"))
    }

    @Test fun keepsALiveSongsLiveVideo() {
        assertTrue(CanvasLookup.matches("Houdini (Live)", "Dua Lipa", "Houdini (Live)", "Dua Lipa"))
    }
}
