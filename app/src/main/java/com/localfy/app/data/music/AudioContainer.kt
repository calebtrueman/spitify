package com.localfy.app.data.music

/** URLs and response labels are not reliable: use the bytes before picking an extension. */
internal enum class AudioContainer(val extension: String, val mime: String, val label: String) {
    FLAC("flac", "audio/flac", "FLAC"),
    OPUS("opus", "audio/opus", "Opus"),
    OGG("ogg", "audio/ogg", "Ogg"),
    M4A("m4a", "audio/mp4", "M4A"),
    MP3("mp3", "audio/mpeg", "MP3"),
    AAC("aac", "audio/aac", "AAC"),
    WAV("wav", "audio/wav", "WAV"),
    AIFF("aiff", "audio/x-aiff", "AIFF");

    companion object {
        fun detect(bytes: ByteArray): AudioContainer? {
            fun text(start: Int, size: Int) = if (bytes.size >= start + size) String(bytes, start, size, Charsets.ISO_8859_1) else ""
            return when {
                text(0, 4) == "fLaC" -> FLAC
                text(0, 4) == "OggS" -> if (String(bytes, Charsets.ISO_8859_1).contains("OpusHead")) OPUS else OGG
                text(4, 4) == "ftyp" -> M4A
                text(0, 4) == "RIFF" && text(8, 4) == "WAVE" -> WAV
                text(0, 4) == "FORM" && text(8, 4) in listOf("AIFF", "AIFC") -> AIFF
                text(0, 3) == "ID3" -> MP3
                bytes.size >= 2 && bytes[0].toInt() and 255 == 255 && bytes[1].toInt() and 246 == 240 -> AAC
                bytes.size >= 2 && bytes[0].toInt() and 255 == 255 && bytes[1].toInt() and 224 == 224 -> MP3
                else -> null
            }
        }
    }
}
