package com.zdownloader.app.adblock

import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zdownloader.app.MainActivity
import com.zdownloader.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BrowserAdblockIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun bundledRulesRespectPersistedGlobalAndSiteSettings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("mediaflow_browser_adblock", 0)
        val originalEnabled = preferences.getBoolean("enabled", true)
        val originalSiteException = preferences.getStringSet("excluded_hosts", emptySet())
            .orEmpty()
            .contains(TEST_HOST)
        val originalYoutubeException = preferences.getStringSet("excluded_hosts", emptySet())
            .orEmpty()
            .contains(YOUTUBE_HOST)

        try {
            BrowserAdblock.initialize(context)
            withTimeout(30_000) {
                while (!BrowserAdblock.state.value.engineReady) delay(25)
            }

            BrowserAdblock.setEnabled(context, true)
            BrowserAdblock.setHostExcluded(context, TEST_HOST, false)
            BrowserAdblock.setHostExcluded(context, YOUTUBE_HOST, false)
            assertTrue(BrowserAdblock.shouldBlock(PAGE_URL, AD_URL, PAGE_URL, "script", "GET"))
            assertTrue(
                "The bundled YouTube rule must match an explicitly classified ad-break fixture",
                BrowserAdblock.shouldBlock(
                    YOUTUBE_PAGE_URL,
                    YOUTUBE_AD_BREAK_URL,
                    YOUTUBE_PAGE_URL,
                    "xmlhttprequest",
                    "POST"
                )
            )
            assertFalse(
                "YouTube content video segments must remain allowed",
                BrowserAdblock.shouldBlock(
                    YOUTUBE_PAGE_URL,
                    YOUTUBE_CONTENT_VIDEO_URL,
                    YOUTUBE_PAGE_URL,
                    "media",
                    "GET"
                )
            )

            BrowserAdblock.setEnabled(context, false)
            assertEquals(false, preferences.getBoolean("enabled", true))
            assertFalse(BrowserAdblock.shouldBlock(PAGE_URL, AD_URL, PAGE_URL, "script", "GET"))

            BrowserAdblock.setEnabled(context, true)
            BrowserAdblock.setHostExcluded(context, TEST_HOST, true)
            assertTrue(
                preferences.getStringSet("excluded_hosts", emptySet())
                    .orEmpty()
                    .contains(TEST_HOST)
            )
            assertFalse(BrowserAdblock.shouldBlock(PAGE_URL, AD_URL, PAGE_URL, "script", "GET"))

            BrowserAdblock.setHostExcluded(context, TEST_HOST, false)
            assertTrue(BrowserAdblock.shouldBlock(PAGE_URL, AD_URL, PAGE_URL, "script", "GET"))
        } finally {
            BrowserAdblock.setHostExcluded(context, TEST_HOST, originalSiteException)
            BrowserAdblock.setHostExcluded(context, YOUTUBE_HOST, originalYoutubeException)
            BrowserAdblock.setEnabled(context, originalEnabled)
        }
    }

    @Test
    fun browserUiBlocksRequestsAndPopupsShowsCountersAndPersistsControls(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val preferences = context.getSharedPreferences("mediaflow_browser_adblock", 0)
        val originalEnabled = preferences.getBoolean("enabled", true)
        val originalSiteException = preferences.getStringSet("excluded_hosts", emptySet())
            .orEmpty()
            .contains(TEST_HOST)
        val originalYoutubeException = preferences.getStringSet("excluded_hosts", emptySet())
            .orEmpty()
            .contains(YOUTUBE_HOST)

        try {
            BrowserAdblock.resetSessionCounts()
            BrowserAdblock.setEnabled(context, true)
            BrowserAdblock.setHostExcluded(context, TEST_HOST, false)
            BrowserAdblock.setHostExcluded(context, YOUTUBE_HOST, false)
            withTimeout(30_000) {
                while (!BrowserAdblock.state.value.engineReady) delay(25)
            }

            awaitBrowserEntry()
            compose.onNodeWithTag("home_list")
                .performScrollToNode(hasTestTag("home_browser"))
            compose.onNodeWithTag("home_browser").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag("browser_screen").fetchSemanticsNodes().isNotEmpty()
            }

                var webView: WebView? = null
                instrumentation.runOnMainSync {
                    webView = findWebView(compose.activity.window.decorView)
                }
                val view = requireNotNull(webView)
                val html = """
                    <!doctype html>
                    <html><head>
                      <meta name="viewport" content="width=device-width, initial-scale=1">
                      <style>
                        body { margin: 0; }
                        #popup { position: fixed; top: 24px; left: 20px; width: 230px; height: 72px; z-index: 9999; }
                      </style>
                    </head><body>
                      <button id="popup" onclick="window.open('https://publisher.example/popup','_blank')">Open popup</button>
                    </body></html>
                """.trimIndent()
                instrumentation.runOnMainSync {
                    view.stopLoading()
                    view.loadDataWithBaseURL(PAGE_URL, html, "text/html", "UTF-8", PAGE_URL)
                }

                compose.waitUntil(15_000) {
                    runCatching { evaluate(view, "document.readyState") == "\"complete\"" }
                        .getOrDefault(false)
                }
                BrowserAdblock.resetSessionCounts()
                evaluate(
                    view,
                    "(()=>{const s=document.createElement('script');s.src='https://td.doubleclick.net/mediaflow-probe.js';document.head.appendChild(s);return 'requested'})()"
                )
                compose.waitUntil(15_000) { BrowserAdblock.blockedRequestCount() > 0L }
                assertTrue("The bundled network rule must block the probe request", BrowserAdblock.blockedRequestCount() > 0L)

                val youtubeHtml = """
                    <!doctype html>
                    <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
                    <body>
                      <div id="movie_player" class="html5-video-player">
                        <video id="ad-video"></video>
                        <button id="skip-ad" class="ytp-ad-skip-button" style="display:none"
                          onclick="window.__mediaFlowSkipClicked=true;document.getElementById('movie_player').classList.remove('ad-showing')">
                          Skip ad
                        </button>
                      </div>
                    </body></html>
                """.trimIndent()
                instrumentation.runOnMainSync {
                    view.stopLoading()
                    view.loadDataWithBaseURL(YOUTUBE_PAGE_URL, youtubeHtml, "text/html", "UTF-8", YOUTUBE_PAGE_URL)
                }
                compose.waitUntil(15_000) {
                    runCatching { evaluate(view, "document.readyState") == "\"complete\"" }
                        .getOrDefault(false)
                }
                // Exercise the callback's classified-request contract without sending a probe to YouTube.
                // This does not assert that a real WebView supplies Sec-Fetch-Dest.
                for (method in listOf("GET", "POST")) {
                    val adBreak = FixtureWebResourceRequest(
                        Uri.parse(YOUTUBE_AD_BREAK_URL),
                        requestMethod = method
                    )
                    val response = BrowserRequestFilter.intercept(adBreak, YOUTUBE_PAGE_URL)
                    assertEquals("Classified $method fixture should receive 204", 204, response?.statusCode)
                    assertEquals("Classified $method fixture should have no response body", -1, response?.data?.use { it.read() })
                }

                BrowserAdblock.resetSessionCounts()
                evaluate(
                    view,
                    "(()=>{const video=document.createElement('video');" +
                        "video.preload='auto';" +
                        "video.src='https://ads4media.online/mediaflow-ad-probe.mp4';" +
                        "document.body.appendChild(video);video.load();return 'requested'})()"
                )
                compose.waitUntil(15_000) { BrowserAdblock.blockedRequestCount() > 0L }
                assertTrue(
                    "A real WebView media request matching a bundled ad rule must be intercepted",
                    BrowserAdblock.blockedRequestCount() > 0L
                )

                val googleVideoRequest = FixtureWebResourceRequest(
                    Uri.parse(YOUTUBE_CONTENT_VIDEO_URL),
                    destination = "video"
                )
                BrowserAdblock.resetSessionCounts()
                evaluate(
                    view,
                    "document.getElementById('movie_player').classList.add('ad-showing');" +
                        "window.__mfYoutubeAdGuardSync();'active'"
                )
                val googleVideoResponse = BrowserRequestFilter.intercept(googleVideoRequest, YOUTUBE_PAGE_URL)
                assertEquals(
                    "An active ad state alone is not evidence that a Googlevideo stream is ad media",
                    null,
                    googleVideoResponse
                )
                assertEquals(
                    "Ambiguous Googlevideo media must not increment the blocked count",
                    0L,
                    BrowserAdblock.blockedRequestCount()
                )

                evaluate(
                    view,
                    "document.getElementById('movie_player').classList.remove('ad-showing');" +
                        "window.__mfYoutubeAdGuardSync();'content'"
                )
                assertEquals(
                    "A YouTube content stream must remain allowed after the ad state clears",
                    null,
                    BrowserRequestFilter.intercept(googleVideoRequest, YOUTUBE_PAGE_URL)
                )

                evaluate(
                    view,
                    "window.__mediaFlowSkipClicked=false;" +
                        "window.__mediaFlowSeekAttempted=false;" +
                        "window.__mediaFlowSkipAdCalled=false;" +
                        "const p=document.getElementById('movie_player');" +
                        "const v=document.getElementById('ad-video');" +
                        "p.skipAd=()=>{window.__mediaFlowSkipAdCalled=true};" +
                        "Object.defineProperty(v,'duration',{configurable:true,value:10});" +
                        "Object.defineProperty(v,'currentTime',{configurable:true," +
                        "set(){window.__mediaFlowSeekAttempted=true}});" +
                        "p.classList.add('ad-showing');" +
                        "window.__mfYoutubeAdGuardSync();'hidden-skip'"
                )
                assertEquals(
                    "A hidden zero-size Skip button must not be clicked",
                    "false",
                    evaluate(view, "window.__mediaFlowSkipClicked===true")
                )
                assertEquals(
                    "No player API skip or seek fallback is allowed",
                    "false",
                    evaluate(view, "window.__mediaFlowSkipAdCalled===true || window.__mediaFlowSeekAttempted===true")
                )
                evaluate(
                    view,
                    "document.getElementById('skip-ad').style.display='block';" +
                        "window.__mfYoutubeAdGuardSync();'show-skip'"
                )
                compose.waitUntil(3_000) {
                    runCatching {
                        evaluate(view, "window.__mediaFlowSkipClicked===true") == "true"
                    }.getOrDefault(false)
                }
                assertEquals(
                    "A visible, hit-testable YouTube Skip control must be clicked for an active ad",
                    "true",
                    evaluate(view, "window.__mediaFlowSkipClicked===true")
                )
                assertEquals(
                    "Skip recovery must never seek or call the hidden player skip API",
                    "false",
                    evaluate(view, "window.__mediaFlowSkipAdCalled===true || window.__mediaFlowSeekAttempted===true")
                )
                evaluate(view, "window.__mfYoutubeAdGuardSync();'clear'")
                assertEquals(
                    "The ad-media guard must stop blocking after the skip control clears ad state",
                    null,
                    BrowserRequestFilter.intercept(googleVideoRequest, YOUTUBE_PAGE_URL)
                )

                BrowserAdblock.setHostExcluded(context, YOUTUBE_HOST, true)
                evaluate(
                    view,
                    "document.getElementById('movie_player').classList.add('ad-showing');" +
                        "window.__mfYoutubeAdGuardSync();'excluded'"
                )
                assertEquals(
                    "A site exclusion must disable YouTube's dynamic ad-media guard",
                    null,
                    BrowserRequestFilter.intercept(googleVideoRequest, YOUTUBE_PAGE_URL)
                )
                BrowserAdblock.setHostExcluded(context, YOUTUBE_HOST, false)

                instrumentation.runOnMainSync {
                    view.stopLoading()
                    view.loadDataWithBaseURL(PAGE_URL, html, "text/html", "UTF-8", PAGE_URL)
                }
                compose.waitUntil(15_000) {
                    runCatching { evaluate(view, "document.readyState") == "\"complete\"" }
                        .getOrDefault(false)
                }
                BrowserAdblock.resetSessionCounts()
                tapElement(view, "popup")
                compose.waitUntil(10_000) { BrowserAdblock.blockedPopupCount() > 0L }
                assertEquals("The attempted popup must be denied once", 1L, BrowserAdblock.blockedPopupCount())
                instrumentation.runOnMainSync {
                    assertEquals(
                        "A denied popup must not create another WebView",
                        1,
                        findWebViews(compose.activity.window.decorView).size
                    )
                }

                val resourceCount = BrowserAdblock.blockedRequestCount().toInt()
                val popupCount = BrowserAdblock.blockedPopupCount().toInt()
                compose.onNodeWithTag("browser_adblock_settings").performClick()
                compose.onNodeWithTag("browser_adblock_dialog").assertIsDisplayed()
                val expectedResources = context.resources.getQuantityString(
                    R.plurals.adblock_blocked_resources,
                    resourceCount,
                    resourceCount
                )
                val expectedPopups = context.resources.getQuantityString(
                    R.plurals.adblock_blocked_popups,
                    popupCount,
                    popupCount
                )
                compose.waitUntil(10_000) {
                    runCatching {
                        compose.onNodeWithTag("browser_adblock_blocked_resources").assertTextEquals(expectedResources)
                        compose.onNodeWithTag("browser_adblock_blocked_popups").assertTextEquals(expectedPopups)
                    }.isSuccess
                }

                compose.onNodeWithTag("browser_adblock_global_switch").assertIsOn().performClick()
                compose.waitUntil(3_000) { !preferences.getBoolean("enabled", true) }
                assertFalse(BrowserAdblock.shouldBlock(PAGE_URL, AD_URL, PAGE_URL, "script", "GET"))
                compose.onNodeWithTag("browser_adblock_global_switch").assertIsOff()

                compose.onNodeWithTag("browser_adblock_global_switch").performClick()
                compose.waitUntil(3_000) { preferences.getBoolean("enabled", false) }
                compose.onNodeWithTag("browser_adblock_site_switch").assertIsOff().performClick()
                compose.waitUntil(3_000) {
                    preferences.getStringSet("excluded_hosts", emptySet()).orEmpty().contains(TEST_HOST)
                }
                assertFalse(BrowserAdblock.isEnabledForPage(PAGE_URL))
                assertFalse(BrowserAdblock.shouldBlock(PAGE_URL, AD_URL, PAGE_URL, "script", "GET"))
                compose.onNodeWithTag("browser_adblock_site_switch").assertIsOn()

                compose.onNodeWithTag("browser_adblock_close").performClick()
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithTag("browser_adblock_dialog").fetchSemanticsNodes().isEmpty()
                }
                compose.onNodeWithTag("browser_adblock_settings").performClick()
                compose.onNodeWithTag("browser_adblock_global_switch").assertIsOn()
                compose.onNodeWithTag("browser_adblock_site_switch").assertIsOn()
        } finally {
            BrowserAdblock.setYoutubeAdActive(YOUTUBE_PAGE_URL, false)
            BrowserAdblock.setHostExcluded(context, TEST_HOST, originalSiteException)
            BrowserAdblock.setEnabled(context, originalEnabled)
            BrowserAdblock.setHostExcluded(context, YOUTUBE_HOST, originalYoutubeException)
        }
    }

    private fun evaluate(view: WebView, script: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val latch = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync {
            view.evaluateJavascript(script) {
                result = it.orEmpty()
                latch.countDown()
            }
        }
        assertTrue("WebView JavaScript callback", latch.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun tapElement(view: WebView, elementId: String) {
        val x = evaluate(view, "document.getElementById('$elementId').getBoundingClientRect().left + document.getElementById('$elementId').getBoundingClientRect().width / 2")
            .toFloat()
        val y = evaluate(view, "document.getElementById('$elementId').getBoundingClientRect().top + document.getElementById('$elementId').getBoundingClientRect().height / 2")
            .toFloat()
        val density = evaluate(view, "window.devicePixelRatio").toFloat()
        val location = IntArray(2)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.getLocationOnScreen(location)
        }
        val screenX = location[0] + x * density
        val screenY = location[1] + y * density
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, screenX, screenY, 0)
        val up = MotionEvent.obtain(downTime, downTime + 60, MotionEvent.ACTION_UP, screenX, screenY, 0)
        try {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            assertTrue("Touch down must reach the browser WebView", automation.injectInputEvent(down, true))
            assertTrue("Touch up must reach the browser WebView", automation.injectInputEvent(up, true))
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun awaitBrowserEntry() {
        fun isPresent(tag: String) = compose.onAllNodesWithTag(tag)
            .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        compose.waitUntil(20_000) { isPresent("home_list") || isPresent("welcome_next_button") }
        if (!isPresent("home_list")) {
            repeat(2) { compose.onNodeWithTag("welcome_next_button").performClick() }
            compose.onNodeWithTag("welcome_done_button").performClick()
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun notificationAllowButton(): android.view.accessibility.AccessibilityNodeInfo? {
            val root = automation.rootInActiveWindow ?: return null
            val controller = root.packageName?.toString()
            if (controller !in setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")) return null
            return root.findAccessibilityNodeInfosByViewId("$controller:id/permission_allow_button").singleOrNull()
        }
        fun homeFocused() = isPresent("home_list") && compose.runOnUiThread { compose.activity.hasWindowFocus() }
        // API 33+ can present the native notification consent window after Welcome.
        compose.waitUntil(20_000) { notificationAllowButton() != null || homeFocused() }
        notificationAllowButton()?.let {
            assertTrue(it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
        compose.waitUntil(20_000) { homeFocused() }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            repeat(view.childCount) { index ->
                findWebView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun findWebViews(view: View): List<WebView> = buildList {
        if (view is WebView) add(view)
        if (view is ViewGroup) {
            repeat(view.childCount) { index ->
                addAll(findWebViews(view.getChildAt(index)))
            }
        }
    }

    private class FixtureWebResourceRequest(
        private val requestUri: Uri,
        private val destination: String = "empty",
        private val requestMethod: String = "GET"
    ) : WebResourceRequest {
        override fun getUrl(): Uri = requestUri
        override fun isForMainFrame(): Boolean = false
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = requestMethod
        override fun getRequestHeaders(): Map<String, String> =
            mapOf("Sec-Fetch-Dest" to destination)
    }

    private companion object {
        const val TEST_HOST = "publisher.example"
        const val PAGE_URL = "https://publisher.example/article"
        const val AD_URL = "https://td.doubleclick.net/ad.js"
        const val YOUTUBE_HOST = "m.youtube.com"
        const val YOUTUBE_PAGE_URL = "https://m.youtube.com/watch?v=mediaflow-adblock-probe"
        const val YOUTUBE_AD_BREAK_URL = "https://m.youtube.com/youtubei/v1/player/ad_break"
        const val YOUTUBE_CONTENT_VIDEO_URL = "https://rr.googlevideo.com/videoplayback?id=content"
    }
}
