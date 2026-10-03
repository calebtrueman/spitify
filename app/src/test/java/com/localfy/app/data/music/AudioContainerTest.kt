package com.localfy.app.data.music

import org.junit.Assert.*
import org.junit.Test

class AudioContainerTest {
    @Test fun acceptsOpusAndVorbisFromUrlsThatClaimToBeFlac() {
        assertEquals(AudioContainer.OPUS, AudioContainer.detect("OggS${"\u0000".repeat(24)}OpusHead".toByteArray()))
        assertEquals(AudioContainer.OGG, AudioContainer.detect("OggS${"\u0000".repeat(24)}\u0001vorbis".toByteArray()))
        assertEquals(AudioContainer.FLAC, AudioContainer.detect("fLaC\u0000\u0000".toByteArray()))
    }
    @Test fun rejectsWebPagesAndRiffVideoInsteadOfPassingThemToTheAudioDecoder() {
        assertNull(AudioContainer.detect("<html>temporarily unavailable</html>".toByteArray()))
        assertNull(AudioContainer.detect("RIFF1234AVI ".toByteArray()))
        assertNull(AudioContainer.detect(byteArrayOf()))
        assertEquals(AudioContainer.WAV, AudioContainer.detect("RIFF1234WAVE".toByteArray()))
    }
}
