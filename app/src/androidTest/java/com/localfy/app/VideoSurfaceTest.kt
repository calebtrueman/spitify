package com.localfy.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real WebView pixels, with a local silent fixture. No YouTube or browser network is needed. */
@RunWith(AndroidJUnit4::class)
class VideoSurfaceTest {
    @Test fun onlyVideoPixelsFillTheScreenAndPausedMotionCanBeDisabled() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val script = instrumentation.targetContext.assets.open("video-controls.js").bufferedReader().use { it.readText() }
        val videoBytes = instrumentation.context.assets.open("video/letterbox.mp4").use { it.readBytes() }
        val html = """<!doctype html><html><body style="background:lime">
            <div id="movie_player" style="position:absolute;top:180px;width:320px;height:140px;overflow:hidden;transform:translateY(20px)">
              <div class="html5-video-container"><video class="html5-main-video" autoplay muted loop playsinline src="https://www.youtube.com/fixture.mp4"></video></div>
              <div id="fake-title" style="position:fixed;inset:0;background:lime;visibility:visible!important">YouTube title and buttons</div>
            </div></body></html>"""
        lateinit var view: WebView
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    setBackgroundColor(Color.BLACK)
                    WebViewCompat.addDocumentStartJavaScript(this, script, setOf("https://www.youtube.com"))
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                            if (request.url.path == "/fixture.mp4") WebResourceResponse("video/mp4", null, ByteArrayInputStream(videoBytes))
                            else WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                    }
                }
                activity.setContentView(view)
                view.loadUrl("https://www.youtube.com/embed/fixture")
            }
            fun evaluate(code: String): String {
                val done = CountDownLatch(1); var result = ""
                scenario.onActivity { view.evaluateJavascript(code) { result = it; done.countDown() } }
                assertTrue(done.await(5, TimeUnit.SECONDS)); return result
            }
            try {
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && evaluate("Number(getComputedStyle(document.documentElement).getPropertyValue('--spitify-crop')) > 1.2") != "true") Thread.sleep(150)
            assertEquals("Video state: " + evaluate("JSON.stringify({ready:document.querySelector('video').readyState,paused:document.querySelector('video').paused,width:document.querySelector('video').videoWidth,time:document.querySelector('video').currentTime,crop:getComputedStyle(document.documentElement).getPropertyValue('--spitify-crop'),frame:document.documentElement.getAttribute('data-spitify-frame')})"), "true", evaluate("Number(getComputedStyle(document.documentElement).getPropertyValue('--spitify-crop')) > 1.2"))
            assertEquals("\"hidden\"", evaluate("getComputedStyle(document.getElementById('fake-title')).visibility"))
            assertEquals("true", evaluate("document.querySelector('video').getBoundingClientRect().height >= innerHeight"))
            // Dynamic overlays cannot become visible later, even with an inline important rule.
            evaluate("document.getElementById('fake-title').style.setProperty('visibility','visible','important')")
            assertEquals("\"hidden\"", evaluate("getComputedStyle(document.getElementById('fake-title')).visibility"))
            evaluate("document.querySelector('video').pause();window.postMessage({spitifyCanvas:{paused:true,reduceMotion:false}},'*')")
            assertEquals("\"spitify-held-frame\"", evaluate("getComputedStyle(document.querySelector('video')).animationName"))
            evaluate("window.postMessage({spitifyCanvas:{paused:true,reduceMotion:true}},'*')")
            assertEquals("\"none\"", evaluate("getComputedStyle(document.querySelector('video')).animationName"))

            val copied = CountDownLatch(1); var result = -1; lateinit var bitmap: Bitmap
            scenario.onActivity { activity ->
                bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                val position = IntArray(2); view.getLocationInWindow(position)
                PixelCopy.request(activity.window, Rect(position[0], position[1], position[0] + view.width, position[1] + view.height), bitmap, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
            }
            assertTrue(copied.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
            File(instrumentation.targetContext.cacheDir, "video-surface-check.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            for (x in listOf(1, bitmap.width / 2, bitmap.width - 2)) for (y in listOf(1, bitmap.height / 2, bitmap.height - 2)) {
                val pixel = bitmap.getPixel(x, y)
                assertTrue("Video did not cover ($x,$y): ${Integer.toHexString(pixel)}", Color.red(pixel) > 140 && Color.green(pixel) < 80 && Color.blue(pixel) < 80)
            }
            } finally { scenario.onActivity { view.stopLoading(); view.destroy() } }
        }
    }
}
