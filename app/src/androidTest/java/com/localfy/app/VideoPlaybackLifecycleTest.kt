package com.localfy.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.localfy.app.data.Song
import com.localfy.app.data.music.MusicVideo
import com.localfy.app.data.music.MusicVideoLookup
import com.localfy.app.data.music.SearchMatch
import com.localfy.app.playback.PlayerUiState
import com.localfy.app.ui.AppActions
import com.localfy.app.ui.LocalApp
import com.localfy.app.ui.player.MusicVideoBackdrop
import com.localfy.app.ui.player.VideoSurfaceCache
import com.localfy.app.ui.player.VideoWebCache
import com.localfy.app.ui.theme.LocalfyTheme
import com.localfy.app.ui.theme.ThemeSettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Exercise the real Compose screen and shared video scripts with an offline moving video. */
@RunWith(AndroidJUnit4::class)
class VideoPlaybackLifecycleTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val app get() = ins.targetContext.applicationContext as LocalfyApp
    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as MutableStateFlow<T>

    @Test fun pauseResumeBackgroundAndPausedSongChangeKeepRealFramesMoving() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
        val songs = listOf("First motion", "Second motion").mapIndexed { index, title ->
            Song(8_800_001L + index, title, "Video test", "Video test", 8_800_000, "Video test", 30_000,
                index + 1, 1, 2026, null, "", 0, 0, "video/mp4")
        }
        val ids = listOf("fixture0001", "fixture0002")
        @Suppress("UNCHECKED_CAST")
        val lookup = MusicVideoLookup.javaClass.getDeclaredField("cache").apply { isAccessible = true }.get(MusicVideoLookup) as MutableMap<String, Pair<Long, List<MusicVideo>>>
        @Suppress("UNCHECKED_CAST")
        val cache = VideoWebCache.javaClass.getDeclaredField("cache").apply { isAccessible = true }.get(VideoWebCache) as VideoSurfaceCache<WebView>
        val keys = songs.map { "${SearchMatch.fold(it.title)}|${SearchMatch.fold(it.artist)}" }
        val oldSongs = app.library.rawSongs.value
        val oldState = app.player.state.value
        val report = File(app.getExternalFilesDir(null), "video-lifecycle-result.txt").apply { writeText("") }
        val reduceMotion = mutableStateOf(true)
        var state = PlayerUiState(connected = true, queue = songs.map { it.id }, currentIndex = 0, durationMs = 30_000, isPlaying = true)
        ins.runOnMainSync {
            app.profiles.completeOnboarding()
            keys.forEachIndexed { index, key -> lookup[key] = System.currentTimeMillis() to listOf(MusicVideo(ids[index], songs[index].title, 30)) }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var first: FixtureWebView
            lateinit var second: FixtureWebView
            scenario.onActivity { activity ->
                app.player.disconnect()
                flow<List<Song>>(app.library, "_raw").value = songs
                flow<PlayerUiState>(app.player, "_state").value = state
                flow<Long>(app.player, "_position").value = 0
                first = FixtureWebView(activity, ids[0])
                cache.prepare(ids[0]) { first }
                activity.setContent {
                    LocalfyTheme(ThemeSettings(reduceMotion = reduceMotion.value)) {
                        val nav = rememberNavController()
                        val actions = remember(nav) { AppActions(app.getSharedPreferences("video-lifecycle-test", 0), app.library,
                            app.player, app.lyrics, app.podcasts, app.taste, app.profiles, nav, { _, _ -> }, {}, { _, _ -> }, {}) }
                        CompositionLocalProvider(LocalApp provides actions) { Box(Modifier.fillMaxSize()) { MusicVideoBackdrop() } }
                    }
                }
            }
            fun evaluate(view: WebView, code: String): String {
                val done = CountDownLatch(1); var result = ""
                ins.runOnMainSync { view.evaluateJavascript(code) { result = it; done.countDown() } }
                assertTrue(done.await(5, TimeUnit.SECONDS)); return result
            }
            fun await(view: WebView, message: String, code: String) {
                val end = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < end) { if (evaluate(view, code) == "true") return; Thread.sleep(100) }
                fail("$message: " + evaluate(view, "JSON.stringify({ready:typeof ready==='undefined'?null:ready,frame:typeof frameReady==='undefined'?null:frameReady,state:typeof player==='undefined'?null:player.getPlayerState(),time:typeof player==='undefined'?null:player.getCurrentTime()})"))
            }
            fun time(view: WebView) = evaluate(view, "player.getCurrentTime()").toDouble()
            fun setPlaying(playing: Boolean) { state = state.copy(isPlaying = playing); ins.runOnMainSync { flow<PlayerUiState>(app.player, "_state").value = state } }
            fun pixels(): IntArray {
                ins.waitForIdleSync()
                val image = ins.uiAutomation.takeScreenshot()
                val result = mutableListOf<Int>()
                for (y in image.height / 3 until image.height * 2 / 3 step 16) for (x in image.width / 4 until image.width * 3 / 4 step 16) result.add(image.getPixel(x, y))
                image.recycle(); return result.toIntArray()
            }
            fun assertAdvances(view: WebView, label: String) {
                await(view, "$label did not start", "typeof player !== 'undefined' && ready && frameReady && player.getPlayerState()===1")
                val start = time(view); val before = pixels(); Thread.sleep(1100)
                assertTrue("$label stayed on the same video time", abs(time(view) - start) > .45)
                val after = pixels()
                val changed = before.indices.count { abs(Color.red(before[it]) - Color.red(after[it])) + abs(Color.green(before[it]) - Color.green(after[it])) + abs(Color.blue(before[it]) - Color.blue(after[it])) > 45 }
                val diagnostic = evaluate(view, "JSON.stringify({wrapper:spitifyVideoState(),child:window.fixtureSurfaceDebug})")
                report.appendText("$label pixels=$changed / ${before.size}; $diagnostic\n")
                assertTrue("$label did not change its visible video pixels ($changed): $diagnostic", changed > before.size / 30)
                assertEquals("true", evaluate(view, "player.isMuted() && player.getVolume()===0"))
            }
            try {
                assertAdvances(first, "Initial play")
                repeat(3) { attempt ->
                    setPlaying(false)
                    await(first, "Pause $attempt did not settle", "player.getPlayerState()===2 && frameReady")
                    val held = time(first); Thread.sleep(550)
                    assertTrue("Pause $attempt kept advancing", abs(time(first) - held) < .2)
                    if (attempt == 0) {
                        ins.runOnMainSync { reduceMotion.value = false }
                        await(first, "Paused drift never started", "getComputedStyle(document.getElementById('stage')).animationName==='spitify-held-frame' && getComputedStyle(document.getElementById('stage')).animationPlayState==='running'")
                        ins.runOnMainSync { reduceMotion.value = true }
                        await(first, "Reduce Motion did not stop the drift", "getComputedStyle(document.getElementById('stage')).animationName==='none'")
                    }
                    setPlaying(true); assertAdvances(first, "Resume $attempt")
                }
                // Hold the fixture's seek long enough to press Pause while a new passage loads.
                evaluate(first, "window.fixtureSeekDelay=500;startPassage()")
                await(first, "Passage did not begin seeking", "spitifyVideoState().transition?.phase==='seeking'")
                setPlaying(false)
                await(first, "Pause during the seek did not settle", "player.getPlayerState()===2")
                Thread.sleep(650)
                val duringSeek = time(first); Thread.sleep(400)
                assertTrue("Paused seek kept playing", abs(time(first) - duringSeek) < .2)
                evaluate(first, "window.fixtureSeekDelay=0")
                setPlaying(true); assertAdvances(first, "Resume after a paused seek")
                await(first, "Passage never finished after resume", "spitifyVideoState().transition===null")

                ins.runOnMainSync { reduceMotion.value = false }
                Thread.sleep(850)
                evaluate(first, "startPassage()")
                await(first, "The dissolve never started", "spitifyVideoState().transition?.phase==='revealing'")
                setPlaying(false)
                await(first, "Pause during dissolve did not settle", "player.getPlayerState()===2")
                Thread.sleep(900)
                assertEquals("\"revealing\"", evaluate(first, "spitifyVideoState().transition?.phase"))
                setPlaying(true); assertAdvances(first, "Resume during a dissolve")
                await(first, "Dissolve did not finish after resume", "spitifyVideoState().transition===null")

                // An ad can replace the picture during a dissolve. Both sides must
                // drop that cut, then allow the next passage to complete normally.
                await(first, "Next cut stayed blocked", "Date.now() >= retryAfter")
                evaluate(first, "startPassage()")
                await(first, "Interrupted cut never began dissolving", "spitifyVideoState().transition?.phase==='revealing'")
                evaluate(first, "fixtureSetAd(true)")
                await(first, "Ad left the parent or child waiting on the old cut", "spitifyVideoState().transition===null && !frameReady && window.fixtureSurfaceDebug?.cut===null")
                evaluate(first, "fixtureSetAd(false)")
                assertAdvances(first, "Return after an ad interrupted the cut")
                await(first, "Cancelled cut never allowed another passage", "Date.now() >= retryAfter")
                evaluate(first, "startPassage()")
                await(first, "Next passage did not dissolve after the ad", "spitifyVideoState().transition?.phase==='revealing'")
                await(first, "Next passage did not finish after the ad", "spitifyVideoState().transition===null")
                assertAdvances(first, "Next passage after the interrupted cut")

                ins.runOnMainSync { reduceMotion.value = true }
                scenario.moveToState(Lifecycle.State.STARTED)
                await(first, "Background video did not stop", "player.getPlayerState()===2")
                val background = time(first); Thread.sleep(550)
                assertTrue("Hidden video kept advancing", abs(time(first) - background) < .2)
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertAdvances(first, "Return to screen")

                setPlaying(false)
                await(first, "Pause before changing song did not settle", "player.getPlayerState()===2")
                scenario.onActivity { activity ->
                    second = FixtureWebView(activity, ids[1]); cache.prepare(ids[1]) { second }
                    state = state.copy(currentIndex = 1)
                    flow<PlayerUiState>(app.player, "_state").value = state
                }
                await(second, "New paused song never painted a frame", "typeof player !== 'undefined' && ready && frameReady && player.getPlayerState()===2")
                val held = time(second); Thread.sleep(550)
                assertTrue("New paused song kept advancing", abs(time(second) - held) < .2)
                setPlaying(true); assertAdvances(second, "New song resume")
            } finally {
                scenario.onActivity { activity ->
                    activity.setContent { }
                    keys.forEach(lookup::remove)
                    flow<List<Song>>(app.library, "_raw").value = oldSongs
                    flow<PlayerUiState>(app.player, "_state").value = oldState
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private inner class FixtureWebView(context: Context, id: String) : WebView(context) {
        private var screenClient: WebViewClient? = null
        override fun setWebViewClient(client: WebViewClient) { screenClient = client }
        init {
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.domStorageEnabled = true
            val helper = context.assets.open("video-controls.js").bufferedReader().use { it.readText() }
            val diagnosticHelper = helper.substringBeforeLast("})();") + """
                window.fixtureSurfaceDebug=()=>({cut,holding,displayed,frames,lastPresented,initialTarget,requested,
                  time:current?.currentTime,paused:current?.paused,seeking:current?.seeking,ready:current?.readyState,
                  holdOpacity:hold?.style.opacity,holdVisibility:hold?getComputedStyle(hold).visibility:null});
                })();
            """.trimIndent()
            WebViewCompat.addDocumentStartJavaScript(this, diagnosticHelper, setOf("https://www.youtube.com"))
            super.setWebViewClient(object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { screenClient?.onPageFinished(view, url) }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = screenClient?.shouldOverrideUrlLoading(view, request) ?: false
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val (file, mime) = when (request.url.path) {
                        "/iframe_api" -> "iframe-api.js" to "application/javascript"
                        "/motion.mp4" -> "motion.mp4" to "video/mp4"
                        else -> "embed.html" to "text/html"
                    }
                    val bytes = ins.context.assets.open("video/$file").use { it.readBytes() }
                    if (mime == "video/mp4") {
                        val range = request.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value
                        val parts = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
                        val start = parts?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, bytes.lastIndex) ?: 0
                        val end = parts?.groupValues?.get(2)?.toIntOrNull()?.coerceIn(start, bytes.lastIndex) ?: bytes.lastIndex
                        val headers = mutableMapOf("Accept-Ranges" to "bytes", "Content-Length" to (end - start + 1).toString())
                        if (parts != null) headers["Content-Range"] = "bytes $start-$end/${bytes.size}"
                        return WebResourceResponse(mime, null, if (parts != null) 206 else 200,
                            if (parts != null) "Partial Content" else "OK", headers, ByteArrayInputStream(bytes, start, end - start + 1))
                    }
                    return WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(bytes))
                }
            })
            val html = context.assets.open("music-video.html").bufferedReader().use { it.readText() }.replace("__VIDEO_ID__", id)
            loadDataWithBaseURL("https://${context.packageName.lowercase()}", html, "text/html", "UTF-8", null)
        }
    }
}
