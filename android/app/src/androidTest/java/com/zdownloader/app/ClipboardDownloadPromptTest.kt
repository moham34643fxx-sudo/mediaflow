@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.zdownloader.app.domain.ClipboardLinkPrompt
import com.zdownloader.app.domain.UrlPolicy
import com.zdownloader.app.ui.ClipboardPromptViewModel
import com.zdownloader.app.ui.ZViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ClipboardDownloadPromptTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
    private val clipboard get()=app.getSystemService(ClipboardManager::class.java)
    private val preferences get()=app.getSharedPreferences("mediaflow_clipboard_prompt",Context.MODE_PRIVATE)
    private lateinit var scenario:ActivityScenario<MainActivity>
    private var originalClip:ClipData?=null
    private var clipboardCaptured=false
    private var preferenceExisted=false
    private var previousFingerprint:String?=null
    private var originalWasHandled=false
    private var openingShare=false
    private var openedShare:ShareReceiverActivity?=null
    private var listenerRegistered=false
    private val shareLifecycle=ActivityLifecycleCallback {activity,stage->
        if(openingShare&&activity is ShareReceiverActivity&&stage==Stage.CREATED) openedShare=activity
    }

    @Before fun preserveUserClipboardOnlyInMemory() {
        check(android.os.Build.VERSION.SDK_INT>=29)
        preferenceExisted=preferences.contains("last_handled_fingerprint")
        previousFingerprint=preferences.getString("last_handled_fingerprint",null)
        scenario=ActivityScenario.launch(Intent(app,MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        scenario.onActivity {ViewModelProvider(it)[ClipboardPromptViewModel::class.java].consume()}
        waitForForegroundWindow()
        scenario.onActivity {activity->
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val description=clipboard.primaryClipDescription
            check(description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)!=true) {"Refuse to replace a sensitive user clipboard during UI testing"}
            originalClip=clipboard.primaryClip
            clipboardCaptured=true
            originalWasHandled=previousFingerprint!=null&&fingerprint(originalClip)==previousFingerprint
            ViewModelProvider(activity)[ClipboardPromptViewModel::class.java].consume()
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(shareLifecycle)
            listenerRegistered=true
        }
        compose.waitForIdle()
    }

    @Test fun copiedLinkOffersOnceAndConfirmationOpensExistingOptionsWithoutEnqueuing()=runBlocking<Unit> {
        val before=app.repository.dao.observe().first().map {it.id}.toSet()
        val link="https://www.instagram.com/p/De4f1OAou7a/"
        copyOutsideThenEnter(ClipData.newPlainText("MediaFlow QA",link))
        compose.waitUntil(15_000) {compose.onAllNodesWithTag("clipboard_download_prompt").fetchSemanticsNodes().isNotEmpty()}
        scenario.onActivity {assertFalse(ViewModelProvider(it)[ZViewModel::class.java].ui.value.analyzing)}
        compose.onNodeWithTag("clipboard_ignore").performClick()
        scenario.recreate();compose.waitForIdle()
        compose.onNodeWithTag("clipboard_download_prompt").assertDoesNotExist()
        copyOutsideThenEnter(ClipData.newPlainText("MediaFlow QA",link))
        compose.waitUntil(15_000) {compose.onAllNodesWithTag("clipboard_download_prompt").fetchSemanticsNodes().isNotEmpty()}
        openingShare=true
        compose.onNodeWithTag("clipboard_download").performClick()
        compose.waitUntil(15_000) {compose.onAllNodesWithTag("share_compact").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("share_close").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("clipboard_download_prompt").assertDoesNotExist()
        assertEquals("Showing clipboard options must not itself enqueue media",before,app.repository.dao.observe().first().map {it.id}.toSet())
    }

    @Test fun sensitiveAmbiguousNonTextAndOrdinaryClipboardDoNotOpenADownloadPrompt() {
        val sensitive=ClipData.newPlainText("MediaFlow QA","https://example.com/sensitive").apply {
            description.extras=PersistableBundle().apply {putBoolean(ClipDescription.EXTRA_IS_SENSITIVE,true)}
        }
        val multiple=ClipData.newPlainText("MediaFlow QA","https://example.com/one").apply {addItem(ClipData.Item("https://example.com/two"))}
        for(clip in listOf(sensitive,multiple,ClipData.newPlainText("MediaFlow QA","ordinary notes"),ClipData.newIntent("MediaFlow QA",Intent(Intent.ACTION_VIEW)))) {
            copyOutsideThenEnter(clip)
            compose.onNodeWithTag("clipboard_download_prompt").assertDoesNotExist()
            scenario.onActivity {assertFalse(ViewModelProvider(it)[ZViewModel::class.java].ui.value.analyzing)}
        }
    }

    @Test fun livePagePreviewLoadsAfterTheCardWithoutEnqueuingMedia()=runBlocking<Unit> {
        val before=app.repository.dao.observe().first().map {it.id}.toSet()
        val started=android.os.SystemClock.elapsedRealtime()
        copyOutsideThenEnter(ClipData.newPlainText("MediaFlow QA","https://www.youtube.com/watch?v=aqz-KE-bpKQ"))
        compose.onNodeWithTag("clipboard_download_prompt").assertIsDisplayed()
        compose.onNodeWithTag("clipboard_download").assertIsEnabled()
        val cardMs=android.os.SystemClock.elapsedRealtime()-started
        try {
            compose.waitUntil(20_000) {compose.onAllNodesWithTag("clipboard_thumbnail_loaded").fetchSemanticsNodes().isNotEmpty()}
        } catch(error:androidx.compose.ui.test.ComposeTimeoutException) {
            var detail=""
            scenario.onActivity {val preview=ViewModelProvider(it)[ClipboardPromptViewModel::class.java].preview.value
                detail="titlePresent=${!preview.title.isNullOrBlank()}, imagePresent=${preview.imageUrl!=null}, artworkBytes=${preview.artwork?.size ?: 0}"}
            throw AssertionError("Live preview unavailable: $detail",error)
        }
        val folder=java.io.File(app.getExternalFilesDir(null),"premium-motion").apply {mkdirs()}
        // Capture the app, not Android's transient copied-text editor over it.
        Thread.sleep(3_500)
        val screen=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(folder,"live-youtube-preview.png").outputStream().use {screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        screen.recycle()
        java.io.File(folder,"live-preview.json").writeText(org.json.JSONObject().put("card_test_latency_ms",cardMs)
            .put("artwork_loaded",true).put("timing_qualification","Debug instrumentation, includes background/resume and test synchronization; not first-draw latency").toString(2))
        assertEquals(before,app.repository.dao.observe().first().map {it.id}.toSet())
        compose.onNodeWithTag("clipboard_ignore").performClick()
    }

    private fun copyOutsideThenEnter(clip:ClipData) {
        scenario.moveToState(Lifecycle.State.CREATED)
        clipboard.setPrimaryClip(clip)
        scenario.onActivity {assertNull("Background clipboard changes must not trigger a prompt",ViewModelProvider(it)[ClipboardPromptViewModel::class.java].prompt.value)}
        scenario.moveToState(Lifecycle.State.RESUMED)
        waitForForegroundWindow()
        compose.waitForIdle()
    }

    private fun waitForForegroundWindow() {
        compose.waitUntil(10_000) {
            var focused=false
            scenario.onActivity {activity->
                focused=activity.hasWindowFocus()||ViewModelProvider(activity)[ClipboardPromptViewModel::class.java].prompt.value!=null
            }
            focused
        }
    }

    private fun fingerprint(clip:ClipData?,copiedAt:Long?=null):String? {
        if(clip==null||clip.itemCount!=1) return null
        val text=clip.getItemAt(0).text?.takeIf {it.length<=UrlPolicy.MAX_INPUT_LENGTH}?.toString() ?: return null
        val policy=ClipboardLinkPrompt();policy.offer(text,copiedAt ?: clip.description.timestamp)
        return policy.pending?.fingerprint
    }

    @After fun restoreClipboardAndRepeatSuppression() {
        var restoredFingerprint=previousFingerprint
        var clipboardRestored=false
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {openedShare?.finish()}
            if(::scenario.isInitialized) scenario.moveToState(Lifecycle.State.RESUMED)
            if(::scenario.isInitialized&&clipboardCaptured) scenario.onActivity {activity->
                val previous=originalClip
                if(previous==null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(previous)
                clipboardRestored=true
                if(originalWasHandled&&activity.hasWindowFocus()) restoredFingerprint=fingerprint(previous,clipboard.primaryClipDescription?.timestamp)
                activity.finish()
            }
        } finally {
            if(clipboardCaptured&&!clipboardRestored) {
                val previous=originalClip
                if(previous==null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(previous)
            }
            if(::scenario.isInitialized) scenario.close()
            if(listenerRegistered) InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(shareLifecycle)
            }
            val edit=preferences.edit()
            if(preferenceExisted) edit.putString("last_handled_fingerprint",restoredFingerprint) else edit.remove("last_handled_fingerprint")
            assertTrue("Unable to restore clipboard suppression preference",edit.commit())
        }
    }
}
