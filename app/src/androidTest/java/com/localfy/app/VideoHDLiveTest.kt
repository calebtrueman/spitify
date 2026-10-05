package com.localfy.app

import android.util.Log
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localfy.app.ui.player.VideoWebCache
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in network check: pass -e liveVideoId an HD YouTube video ID. */
@RunWith(AndroidJUnit4::class)
class VideoHDLiveTest {
    @Test fun realPlayerDecodesHDAndRecoversAfterPause() {
        val id = InstrumentationRegistry.getArguments().getString("liveVideoId")
        assumeTrue("Live network checks are opt-in", !id.isNullOrBlank())
        lateinit var view: WebView
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = VideoWebCache.take(activity, id!!)
                view.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        Log.i("SpitifyHDCheck", "Console: ${message.message()}"); return true
                    }
                }
                view.webViewClient = object : WebViewClient() {
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        Log.i("SpitifyHDCheck", "Load error ${request.url.host}${request.url.path}: ${error.description}")
                    }
                }
                activity.setContentView(view)
            }
            fun evaluate(code: String): String {
                val done = CountDownLatch(1); var value = ""
                scenario.onActivity { view.evaluateJavascript(code) { value = it; done.countDown() } }
                assertTrue("WebView callback timed out", done.await(20, TimeUnit.SECONDS)); return value
            }
            fun awaitHD(minimumShot: Int = 0): JSONObject {
                val deadline = System.currentTimeMillis() + 90_000
                var state = JSONObject()
                while (System.currentTimeMillis() < deadline) {
                    evaluate("if(window.spitifySetVisible){spitifySetVisible(true);spitifySync(0,true,1,false)}")
                    val raw = evaluate("window.spitifyVideoState ? spitifyVideoState() : {}")
                    state = JSONObject(raw)
                    Log.i("SpitifyHDCheck", state.toString())
                    val surface = state.optJSONObject("surface")
                    if (surface != null && surface.optInt("height") >= 720 && surface.optInt("frames") > 10 && !surface.optBoolean("paused", true) && state.optInt("shot") >= minimumShot && state.isNull("transition")) return state
                    Thread.sleep(1000)
                }
                fail("No decoded HD frame: $state")
                return state
            }
            try {
                val first = awaitHD()
                evaluate("spitifySync(0,false,1,false)")
                Thread.sleep(1000)
                assertTrue(JSONObject(evaluate("spitifyVideoState()")).getJSONObject("surface").getBoolean("paused"))
                val resumed = awaitHD()
                assertTrue(resumed.getJSONObject("surface").getInt("frames") > first.getJSONObject("surface").getInt("frames"))
                awaitHD(minimumShot = 1)
            } finally { scenario.onActivity { view.stopLoading(); view.destroy() } }
        }
    }
}
