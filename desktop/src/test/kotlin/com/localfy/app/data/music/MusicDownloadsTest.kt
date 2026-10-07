package com.localfy.app.data.music

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Collections
import javax.imageio.ImageIO

class MusicDownloadsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val root: File = Files.createTempDirectory("downloads-test").toFile()
    private val flacMs = FlacInfo.read(hiresFlac).durationMs
    private val imported = Collections.synchronizedList(mutableListOf<File>())
    private val states = Collections.synchronizedList(mutableListOf<String>())

    @After fun tearDown() { scope.cancel(); root.deleteRecursively() }

    private fun track(id: String, artwork: String? = null) =
        OnlineTrack(id, "Title $id", "Some Artist", "Some Album", "222", flacMs, 3, 1, artwork, true, albumArtist = "Some Artist")

    /** Fake HTTP: each URL answers from its own script of behaviours, in order (the last one repeats). */
    private class FakeTransport(val scripts: Map<String, List<suspend (File) -> Long>>) : DownloadTransport {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        override suspend fun fetch(url: String, dest: File, onProgress: (Long, Long) -> Unit): Long {
            val n = calls.count { it == url }
            calls += url
            val script = scripts[url] ?: throw TransferFailed(404, "Download response 404")
            return script[minOf(n, script.size - 1)](dest).also { onProgress(it, it) }
        }
    }

    private val serveFlac: suspend (File) -> Long = { dest -> hiresFlac.copyTo(dest, overwrite = true); dest.length() }
    private fun failWith(code: Int): suspend (File) -> Long = { throw TransferFailed(code, "Download response $code") }

    private fun downloads(transport: DownloadTransport, alternate: suspend (OnlineTrack) -> OnlineTrack? = { null }, cover: (String) -> ByteArray = { error("no cover") }) =
        MusicDownloads(scope, onImported = { imported += it }, alternate = alternate, transport = transport,
            downloadsDir = File(root, "Music"), workDir = File(root, "work"), storeFile = File(root, "queue.json"),
            retryDelay = { 20L }, loadCover = cover).also { d ->
            scope.launch { d.jobsById.collect { m -> m.values.forEach { j -> if (states.lastOrNull() != "${j.id}:${j.state}") states += "${j.id}:${j.state}" } } }
        }

    private suspend fun MusicDownloads.awaitState(id: String, vararg wanted: String): MusicDownloadEntity =
        withTimeout(60_000) { jobsById.first { it[id]?.state in wanted } }[id]!!

    private fun png(w: Int, h: Int): ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()

    @Test fun downloadsConvertsTagsAndImports() = runBlocking {
        val d = downloads(FakeTransport(mapOf(Monochrome.audioUrl("111") to listOf(serveFlac))), cover = { png(1600, 1400) })
        assertEquals("1 song queued.", d.enqueue(listOf(track("111", artwork = "https://img.example/cover.png"))))
        val job = d.awaitState("111", "complete", "failed")
        assertEquals(null, job.error)
        assertEquals("complete", job.state)
        assertEquals(DesktopAac.LABEL, job.quality)
        val file = File(job.localUri!!)
        assertEquals(File(root, "Music/222/111.m4a").absolutePath, file.absolutePath)
        val info = DesktopAac.probe(file)!!
        assertEquals("aac", info.codec); assertEquals(48_000, info.sampleRate)
        val tag = AudioFileIO.read(file).tag
        assertEquals("Title 111", tag.getFirst(FieldKey.TITLE))
        assertEquals("Some Artist", tag.getFirst(FieldKey.ARTIST))
        assertEquals("Some Album", tag.getFirst(FieldKey.ALBUM))
        assertEquals("3", tag.getFirst(FieldKey.TRACK))
        val art = ImageIO.read(tag.firstArtwork.binaryData.inputStream())
        assertEquals(1200, art.width); assertEquals(1200, art.height)
        withTimeout(5_000) { while (imported.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(listOf(file.absolutePath), imported.map { it.absolutePath })
        assertTrue(d.progress.value.isEmpty())
        assertEquals("Already saved in your library.", d.enqueue(listOf(track("111"))))
        assertTrue(File(root, "work").listFiles().orEmpty().isEmpty()) // no temp files left behind
    }

    @Test fun temporaryFailureWaitsAndRetries() = runBlocking {
        val url = Monochrome.audioUrl("5")
        val transport = FakeTransport(mapOf(url to listOf(failWith(503), serveFlac)))
        val d = downloads(transport)
        d.enqueue(listOf(track("5")))
        val job = d.awaitState("5", "complete", "failed")
        assertEquals("complete", job.state)
        assertEquals(listOf(url, url), transport.calls)
        assertTrue(states.toString(), "5:waiting" in states)
        assertEquals(1, job.track().retryCount)
    }

    @Test fun missingSongFindsAnotherSource() = runBlocking {
        val archive = "https://archive.org/download/item/05%20Title.flac"
        val transport = FakeTransport(mapOf(Monochrome.audioUrl("6") to listOf(failWith(404)), archive to listOf(serveFlac)))
        val d = downloads(transport, alternate = { it.copy(audioURL = archive, attemptedSources = listOf(archive)) })
        d.enqueue(listOf(track("6")))
        val job = d.awaitState("6", "complete", "failed")
        assertEquals("complete", job.state)
        // "finding" is brief and a state flow may skip it; the saved source shows the fallback ran.
        assertEquals(archive, job.track().audioURL)
    }

    @Test fun missingSongWithoutAlternativeFails() = runBlocking {
        val d = downloads(FakeTransport(emptyMap()))
        d.enqueue(listOf(track("7")))
        val job = d.awaitState("7", "failed")
        assertEquals("This song is not available to download right now.", job.error)
    }

    @Test fun brokenAudioNeverLeavesAJobStuck() = runBlocking {
        val url = Monochrome.audioUrl("8")
        val transport = FakeTransport(mapOf(url to listOf({ dest -> dest.writeText("<html>blocked</html>"); dest.length() })))
        val d = downloads(transport)
        d.enqueue(listOf(track("8")))
        val job = d.awaitState("8", "failed")
        assertEquals("Could not download this song. Please try again later.", job.error)
        assertTrue(transport.calls.size in 4..MusicDownloads.MAX_RETRIES + 1)
        assertFalse(File(root, "Music/222/8.m4a").exists())
    }

    @Test fun cancelStopsTheTransfer() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val url = Monochrome.audioUrl("9")
        val d = downloads(FakeTransport(mapOf(url to listOf({ dest ->
            dest.writeBytes(ByteArray(100)); started.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }))))
        d.enqueue(listOf(track("9")))
        withTimeout(10_000) { started.await() }
        assertEquals("downloading", d.jobsById.value["9"]?.state)
        d.cancel("9").join()
        withTimeout(10_000) { stopped.await() }
        assertEquals("cancelled", d.jobsById.value["9"]?.state)
        assertTrue(File(root, "work").listFiles().orEmpty().none { it.name.startsWith("9-") })
        // Cancelled songs can be queued again.
        assertEquals("1 song queued.", d.enqueue(listOf(track("9"))))
    }

    @Test fun atMostTwoTransfersAtOnceAndQueueSurvivesRestart() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val active = java.util.concurrent.atomic.AtomicInteger(); var peak = 0
        val hold: suspend (File) -> Long = { dest ->
            val now = active.incrementAndGet(); synchronized(this@MusicDownloadsTest) { peak = maxOf(peak, now) }
            try { gate.await(); serveFlac(dest) } finally { active.decrementAndGet() }
        }
        val ids = listOf("21", "22", "23", "24")
        val d = downloads(FakeTransport(ids.associate { Monochrome.audioUrl(it) to listOf(hold) }))
        d.enqueue(ids.map { track(it) })
        withTimeout(10_000) { while (active.get() < 2) kotlinx.coroutines.delay(10) }
        kotlinx.coroutines.delay(200)
        assertEquals(2, d.jobs.value.count { it.state == "downloading" })
        assertEquals(2, d.jobs.value.count { it.state == "queued" })
        d.flush()
        // A fresh instance (app restarted) sees the same queue; interrupted transfers are re-queued.
        val reloaded = MusicDownloads(scope, transport = FakeTransport(emptyMap()), downloadsDir = File(root, "Music2"),
            workDir = File(root, "work2"), storeFile = File(root, "queue.json"))
        assertEquals(ids.toSet(), reloaded.jobs.value.map { it.id }.toSet())
        gate.complete(Unit)
        ids.forEach { d.awaitState(it, "complete") }
        assertEquals(2, peak)
    }
}
