package com.localfy.app.data.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Real network end-to-end check; runs only with SPITIFY_LIVE=1 in the environment. */
class LiveOnlineCheck {
    @Test fun searchStreamAndDownload() = runBlocking {
        assumeTrue(System.getenv("SPITIFY_LIVE") == "1")
        val root = Files.createTempDirectory("spitify-live").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val results = Monochrome.search("daft punk one more time")
            val track = results.first { it.title == "One More Time" && it.artist.contains("Daft Punk") }
            println("LIVE search: ${results.size} results; first match ${track.id} '${track.title}' by ${track.artist} (${track.durationMs} ms, release ${track.releaseId})")
            val streams = MusicStreams(File(root, "data"))
            val song = streams.register(track)
            val url = streams.streamUrl(song)
            assertNotNull(url)
            println("LIVE stream URL: $url")

            val imported = mutableListOf<File>()
            val downloads = MusicDownloads(scope, onImported = { imported += it }, downloadsDir = File(root, "Music"),
                workDir = File(root, "work"), storeFile = File(root, "queue.json"))
            println("LIVE enqueue: " + downloads.enqueue(listOf(track)))
            val job = withTimeout(600_000) { downloads.jobsById.first { it[track.id]?.state in setOf("complete", "failed") } }[track.id]!!
            println("LIVE job: state=${job.state} quality=${job.quality} error=${job.error} file=${job.localUri}")
            assertEquals("complete", job.state)
            val file = File(job.localUri!!)
            val info = DesktopAac.probe(file)!!
            println("LIVE file: ${file.name} ${file.length()} bytes codec=${info.codec} rate=${info.sampleRate} channels=${info.channels} bitrate=${info.bitRate} duration=${info.durationMs} ms")
            val tag = org.jaudiotagger.audio.AudioFileIO.read(file).tag
            println("LIVE tags: title=${tag.getFirst(org.jaudiotagger.tag.FieldKey.TITLE)} artist=${tag.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST)} album=${tag.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM)} cover=${tag.firstArtwork?.binaryData?.size} bytes")
            println("LIVE imported callback: $imported")
            assertEquals("aac", info.codec)
        } finally { scope.cancel(); root.deleteRecursively() }
    }
}
