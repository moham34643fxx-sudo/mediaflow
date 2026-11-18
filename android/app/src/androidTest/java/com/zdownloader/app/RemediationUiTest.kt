package com.zdownloader.app

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.*
import androidx.lifecycle.ViewModelProvider
import com.zdownloader.app.data.Settings
import com.zdownloader.app.domain.*
import com.zdownloader.app.ui.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class RemediationUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun host(language:String,scale:Float=1f,content:@Composable ()->Unit) {
        check(compose.activity.packageName=="com.zdownloader.app.candidate0965")
        compose.runOnUiThread {compose.activity.setContent {
            val config=Configuration(LocalConfiguration.current).apply {setLocale(Locale(language));setLayoutDirection(Locale(language));fontScale=scale}
            val context=LocalContext.current.createConfigurationContext(config)
            CompositionLocalProvider(LocalContext provides context,LocalSpectrumContext provides context,LocalConfiguration provides config,
                androidx.activity.compose.LocalActivityResultRegistryOwner provides compose.activity,
                LocalDensity provides Density(LocalDensity.current.density,scale),
                LocalLayoutDirection provides if(language=="ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                FlowTheme(Spectrum.Dark) {content()}
            }
        }}
        compose.waitForIdle()
    }
    @Test fun actualPreviewAndFormatPickerUseChosenBitrateBeforeSavingSelection() {
        val source=Format.parse(JSONObject().put("format_id","mp3-128").put("url","https://example.com/audio.mp3")
            .put("ext","mp3").put("vcodec","none").put("acodec","mp3").put("abr",128).put("filesize",960000))
        val media=Media("https://example.com/fixture","ui-bitrate","Fixture","Test",null,null,60.0,listOf(source))
        val captured=java.util.concurrent.atomic.AtomicReference<Selection>()
        host("en") {
            val model=ViewModelProvider(compose.activity)[ZViewModel::class.java]
            Preview(media,Settings(language="en",downloadType="audio"),model,{},{}) {selection,_->captured.set(selection)}
        }
        for(rate in listOf(128,320,0)) {
            compose.onNodeWithTag("choose_format").performScrollTo().performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.OnClick) {assertTrue(it())}
            compose.onNodeWithTag("audio_output_mp3").performScrollTo().performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.OnClick) {assertTrue(it())}
            compose.onNodeWithTag("audio_bitrate_$rate").performScrollTo().performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.OnClick) {assertTrue(it())}
            compose.onNodeWithTag("format_confirm").performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.OnClick) {assertTrue(it())}
            compose.onNodeWithTag("start_download").performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.OnClick) {assertTrue(it())}
            val saved=Selection.parse(captured.get()!!.json())
            assertEquals("Chosen bitrate $rate must survive the real Preview callback",rate,saved.bitrate)
            assertEquals(rate==320,saved.convertAudio)
            assertEquals(if(rate==320) ProcessingPlan.TRANSCODE_AUDIO else ProcessingPlan.REMUX,saved.plan)
            assertEquals((if(rate==320) 320L else 128L)*60*125,saved.outputSize!!.bytes)
        }
    }
    @Test fun welcomeUsesBothLanguagesAtEveryStepAndCompletesOnlyOnDone() {
        for(language in listOf("en","ar")) {
            var completed=0
            host(language) {key(language) {WelcomeScreen("0.9.66") {completed++}}}
            val next=if(language=="en") "Next" else "التالي"
            val done=if(language=="en") "Done" else "تم"
            compose.onNodeWithTag("welcome_step1_content").assertExists()
            compose.onNodeWithText(if(language=="en") "Welcome to MediaFlow" else "مرحبًا بك في ميديا فلو").assertExists()
            compose.onNodeWithTag("welcome_next_button").assertTextContains(next).performClick()
            compose.onNodeWithTag("welcome_step2_content").assertExists()
            assertEquals(0,completed)
            compose.onNodeWithTag("welcome_next_button").performClick()
            compose.onNodeWithTag("welcome_step3_content").assertExists()
            compose.onNodeWithTag("welcome_done_button").assertTextContains(done).performClick()
            assertEquals(1,completed)
        }
    }
    @Test fun privacyExplainsThePublicXMetadataFallbackInBothLanguages() {
        for(language in listOf("en","ar")) {
            host(language) {key(language) {
                val model=ViewModelProvider(compose.activity)[ZViewModel::class.java]
                ConsumerSettings(Settings(language=language),model,{},{})
            }}
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("open_privacy_controls"))
            compose.onNodeWithTag("open_privacy_controls").performClick()
            val config=Configuration(compose.activity.resources.configuration).apply {setLocale(Locale.forLanguageTag(language))}
            val expected=compose.activity.createConfigurationContext(config).getString(R.string.about_x_metadata_privacy)
            compose.onNodeWithTag("information_privacy").performScrollToNode(hasTestTag("x_metadata_privacy"))
            compose.onNodeWithTag("x_metadata_privacy").assertIsDisplayed().assertTextEquals(expected)
            assertTrue(expected.contains("FxTwitter"))
        }
    }

    private fun web(view:android.view.View):android.webkit.WebView? {
        if(view is android.webkit.WebView) return view
        if(view is android.view.ViewGroup) for(index in 0 until view.childCount) web(view.getChildAt(index))?.let {return it}
        return null
    }
    private fun javascript(view:android.webkit.WebView,script:String):String {
        val ready=java.util.concurrent.CountDownLatch(1);var answer=""
        compose.runOnUiThread {view.evaluateJavascript(script) {answer=it;ready.countDown()}}
        assertTrue("WebView JavaScript must answer",ready.await(5,java.util.concurrent.TimeUnit.SECONDS))
        return answer
    }
    @Test fun browserRetryKeepsTheGetSessionAndNeverReplaysAFailedPost() {
        host("en") {BrowserScreen(initialUrl="about:blank",onClose={})}
        var browser:android.webkit.WebView?=null
        compose.waitUntil(10000) {compose.runOnUiThread {browser=web(compose.activity.window.decorView)};browser!=null}
        val original=browser!!
        val requests=java.util.concurrent.CopyOnWriteArrayList<String>()
        val finished=java.util.concurrent.CopyOnWriteArrayList<String>()
        val firstFailure=java.util.concurrent.atomic.AtomicBoolean(true)
        val base="https://mf-remediation.invalid"
        compose.runOnUiThread {
            val client=original.webViewClient
            original.webViewClient=object:android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(view:android.webkit.WebView,request:android.webkit.WebResourceRequest)=client.shouldOverrideUrlLoading(view,request)
                override fun onPageFinished(view:android.webkit.WebView,url:String) {
                    client.onPageFinished(view,url)
                    finished+=url
                }
                override fun doUpdateVisitedHistory(view:android.webkit.WebView,url:String,isReload:Boolean)=client.doUpdateVisitedHistory(view,url,isReload)
                override fun onReceivedError(view:android.webkit.WebView,request:android.webkit.WebResourceRequest,error:android.webkit.WebResourceError)=client.onReceivedError(view,request,error)
                override fun onReceivedHttpError(view:android.webkit.WebView,request:android.webkit.WebResourceRequest,response:android.webkit.WebResourceResponse)=client.onReceivedHttpError(view,request,response)
                override fun onFormResubmission(view:android.webkit.WebView,dontResend:android.os.Message,resend:android.os.Message)=client.onFormResubmission(view,dontResend,resend)
                override fun shouldInterceptRequest(view:android.webkit.WebView,request:android.webkit.WebResourceRequest):android.webkit.WebResourceResponse {
                    check(request.url.host=="mf-remediation.invalid") {"Only disposable intercepted pages are allowed"}
                    val path=request.url.path.orEmpty()
                    requests+="${request.method} $path"
                    val failed=path=="/submit"||path=="/failed"&&firstFailure.getAndSet(false)
                    val html="<html><head><title>Disposable browser fixture</title></head><body id='fixture'>$path</body></html>"
                    return android.webkit.WebResourceResponse("text/html","UTF-8",if(failed) 503 else 200,
                        if(failed) "Unavailable" else "OK",emptyMap(),html.byteInputStream())
                }
            }
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("$base/entry")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitUntil(15000) {finished.contains("$base/entry")}
        compose.runOnUiThread {assertTrue(original.isAttachedToWindow);assertSame(original,web(compose.activity.window.decorView))}
        assertEquals("\"fixture\"",javascript(original,"document.body && document.body.id"))
        javascript(original,"sessionStorage.setItem('mf_retry','kept')")
        compose.runOnUiThread {original.loadUrl("$base/failed")}
        compose.waitUntil(15000) {compose.onAllNodesWithTag("browser_retry").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("browser_retry").performClick()
        compose.waitUntil(15000) {requests.count {it=="GET /failed"}==2&&finished.count {it=="$base/failed"}>=2}
        assertEquals("\"/failed\"",javascript(original,"document.body && document.body.textContent"))
        compose.onNodeWithTag("browser_error").assertDoesNotExist()
        assertEquals("\"kept\"",javascript(original,"sessionStorage.getItem('mf_retry')"))
        compose.runOnUiThread {assertSame(original,web(compose.activity.window.decorView));original.postUrl("$base/submit","fixture=value".toByteArray())}
        compose.waitUntil(15000) {compose.onAllNodesWithTag("browser_retry").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText(compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply {
            setLocale(Locale.ENGLISH)}).getString(R.string.browser_form_not_resent)).assertExists()
        compose.onNodeWithTag("browser_retry").performClick()
        compose.waitUntil(15000) {requests.count {it=="GET /failed"}==3&&finished.count {it=="$base/failed"}>=3}
        assertEquals("A failed non-idempotent request must not be replayed",1,requests.count {it=="POST /submit"})
        assertEquals("Retry must use the most recent safe GET, not the initial page",1,requests.count {it=="GET /entry"})
        assertEquals("\"kept\"",javascript(original,"sessionStorage.getItem('mf_retry')"))
        val instrument=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val run=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("evidenceRun","manual")
        val folder=java.io.File(instrument.targetContext.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
        java.io.File(folder,"browser-remediation.json").writeText(JSONObject().put("requests",org.json.JSONArray(requests))
            .put("same_webview",true).put("session_storage_retained",true).put("post_replayed",false)
            .put("fault_boundary","intercepted disposable HTTP 503 responses; no external server").toString(2))
    }
}
