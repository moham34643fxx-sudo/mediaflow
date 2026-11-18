package com.zdownloader.app

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class QuickShareTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as ZApplication
    private val automation get()=instrumentation.uiAutomation
    private val folder get()=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "manual"}").apply {mkdirs()}
    @After fun closeOnlyTheExternalCallerFixture() {
        // The caller must remain alive for the return-to-origin assertions, then
        // leave no test-package root underneath later grant/bootstrap activities.
        val result=android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("am start -W -n com.zdownloader.app.test/com.zdownloader.app.ShareOriginActivity -f 0x34000000 --ez finishFixture true")).bufferedReader().use {it.readText()}
        assertFalse("Fixture cleanup failed: $result",result.contains("Exception")||result.contains("Error:"))
    }
    private fun eventually(timeout:Long=10000,condition:()->Boolean) {
        val end=android.os.SystemClock.elapsedRealtime()+timeout
        while(!condition()) {if(android.os.SystemClock.elapsedRealtime()>end) fail("Condition timed out; root=${automation.rootInActiveWindow?.packageName}");Thread.sleep(50)}
    }
    private fun source(url:String,chooser:Boolean=false) {
        automation.serviceInfo=automation.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        // Model an explicit user launch of the external caller; a background
        // target-app Context is not permitted to launch arbitrary tasks on API35.
        val quoted="'"+url.replace("'","'\\''")+"'"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cmd statusbar collapse")).use {it.readBytes()}
        val launch=android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("am start -W -n com.zdownloader.app.test/com.zdownloader.app.ShareOriginActivity -f 0x10008000 --es fixtureUrl $quoted --ez autoShare ${!chooser}")).bufferedReader().use {it.readText()}
        assertFalse("External caller launch failed: $launch",launch.contains("Exception")||launch.contains("Error:"))
        if(!chooser) {eventually {shareActivity()!=null};instrumentation.waitForIdleSync();return}
        automation.waitForIdle(200,5000)
        eventually {automation.rootInActiveWindow?.let {it.packageName?.toString()=="com.zdownloader.app.test"&&it.findAccessibilityNodeInfosByText(url).isNotEmpty()}==true}
        automation.waitForIdle(300,5000)
        Thread.sleep(350)
        val title=if(chooser) "Open Android share chooser" else "Share directly to ZD"
        val node=automation.rootInActiveWindow!!.findAccessibilityNodeInfosByText(title).first()
        node.refresh()
        val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")).bufferedReader().use {it.readText()}
    }
    private fun capture(name:String) {
        compose.waitForIdle();Thread.sleep(200)
        val image=automation.takeScreenshot() ?: error("Screenshot unavailable")
        File(folder,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    private fun shareActivity():ShareReceiverActivity? {
        var result:ShareReceiverActivity?=null
        instrumentation.runOnMainSync {result=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<ShareReceiverActivity>().firstOrNull()}
        return result
    }
    private fun originVisible():Boolean=automation.windows.any {window->
        window.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION&&window.root?.packageName?.toString()=="com.zdownloader.app.test"
    }
    private fun waitForPreview() {
        var model:com.zdownloader.app.ui.QuickShareViewModel?=null
        val activity=shareActivity()!!
        instrumentation.runOnMainSync {model=androidx.lifecycle.ViewModelProvider(activity)[com.zdownloader.app.ui.QuickShareViewModel::class.java]}
        compose.waitUntil(90000) {model!!.state.value.error!=null||model!!.state.value.media!=null&&!model!!.state.value.analyzing&&model!!.state.value.mp3Available}
        assertNull("Optional preview failed",model!!.state.value.error)
    }
    private fun openAudioFormats() {
        compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_more_audio"))
        compose.onNodeWithTag("share_more_audio").performClick()
        waitForPreview()
        compose.waitForIdle()
    }
    @Test fun playlistContextAsksForScopeAndMalformedReplacementCannotSubmitOldSelection()=runBlocking {
        val url="https://www.youtube.com/watch?v=fixture&list=fixture-list"
        val before=app.repository.dao.batches().first().map {it.id}.toSet()
        try {
             source(url)
             compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_more"))
             compose.onNodeWithTag("share_more").performClick()
             compose.waitUntil(15000) {runCatching {compose.onAllNodesWithTag("playlist_single").fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
            compose.onNodeWithTag("playlist_single").assertIsDisplayed()
            compose.onNodeWithTag("playlist_entire").assertIsDisplayed()
            assertEquals(before,app.repository.dao.batches().first().map {it.id}.toSet())
            val activity=shareActivity()!!
            instrumentation.runOnMainSync {instrumentation.callActivityOnNewIntent(activity,Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"javascript:alert(1)"))}
            compose.waitUntil(10000) {compose.onAllNodesWithTag("playlist_preview").fetchSemanticsNodes().isEmpty()}
            compose.onNodeWithTag("playlist_download").assertDoesNotExist()
            assertEquals(before,app.repository.dao.batches().first().map {it.id}.toSet())
        } finally {shareActivity()?.let {activity->instrumentation.runOnMainSync {activity.finish()}}}
    }
    @Test fun realQualitySelectionSurvivesOptionalFormatExpansion()=runBlocking {
        val settings=SettingsRepository(app);val old=settings.current()
        val url="https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd?preference-preview=${UUID.randomUUID()}"
        try {
            settings.text("language","en")
            source(url)
            val activity=shareActivity()!!
            var model:com.zdownloader.app.ui.QuickShareViewModel?=null
            instrumentation.runOnMainSync {model=androidx.lifecycle.ViewModelProvider(activity)[com.zdownloader.app.ui.QuickShareViewModel::class.java]}
            compose.waitUntil(90_000) {
                val media=model!!.state.value.media
                media!=null&&media.qualityGroups().any {it.resolution==480}&&
                    compose.onAllNodesWithTag("share_preference_480").fetchSemanticsNodes().isNotEmpty()
            }
            val actualMedia=requireNotNull(model!!.state.value.media)
            val actualSelection=requireNotNull(VideoPreferenceSelection.ready(actualMedia,480))
            assertEquals(480,verifiedQualityEdge(actualSelection.primary))
            compose.onNodeWithTag("share_preference_480").performScrollTo().performClick().assertIsSelected()
            assertEquals(480,model!!.state.value.qualityPreference)
            compose.onNodeWithTag("share_download").assertIsEnabled()
            capture("share-compact-en-selected")
            compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_more"))
            compose.onNodeWithTag("share_more").performClick()
            waitForPreview()
            assertEquals(480,model!!.state.value.qualityPreference)
            assertNull(model!!.state.value.choice)
            assertTrue(model!!.state.value.media!!.qualityGroups().any {it.resolution==480})
            compose.onNodeWithTag("share_download").assertIsEnabled()
            compose.onNodeWithTag("share_back").performClick()
            // Compact mode intentionally retains instant preference controls after
            // discovery; all source audio/video formats share its More entry.
            compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_more"))
            compose.onNodeWithTag("share_more").performClick()
            waitForPreview()
            compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_audio_original"))
            capture("share-confirmed-formats-en")
            compose.onNodeWithTag("share_back").performClick()
            compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_preference_480"))
            compose.onNodeWithTag("share_preference_480").assertIsSelected()
            compose.onNodeWithTag("share_download").assertIsEnabled()
            assertTrue(app.repository.dao.observe().first().none {it.url==url})
        } finally {
            shareActivity()?.let {activity->instrumentation.runOnMainSync {activity.finish()}}
            settings.text("language",old.language)
        }
    }
    @Test fun compactExpandedBackAndDismissPreserveOriginalAppWithoutEnqueue()=runBlocking {
        val settings=SettingsRepository(app);val old=settings.current()
        val url="https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd?share-test=${UUID.randomUUID()}"
        val mainLaunches=AtomicInteger()
        val callback=ActivityLifecycleCallback {activity,stage->if(activity is MainActivity&&stage==Stage.CREATED) mainLaunches.incrementAndGet()}
        instrumentation.runOnMainSync {ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)}
        try {
            settings.text("language","ar")
            source(url)
            compose.waitUntil(15000) {compose.onAllNodesWithTag("share_compact").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("share_compact").assertIsDisplayed()
            capture("share-compact-ar")
            compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_more"));compose.onNodeWithTag("share_more").performClick()
            compose.onNodeWithTag("share_expanded").assertIsDisplayed()
            capture("share-expanded-ar")
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(10000) {compose.onAllNodesWithTag("share_compact").fetchSemanticsNodes().isNotEmpty()}
            val current=shareActivity()!!
            instrumentation.runOnMainSync {current.recreate()}
            compose.waitUntil(15000) {compose.onAllNodesWithTag("share_compact").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("share_close").performClick()
            eventually {automation.rootInActiveWindow?.packageName?.toString()=="com.zdownloader.app.test"}
            assertTrue(app.repository.dao.observe().first().none {it.url==url})
            assertEquals(0,mainLaunches.get())
            File(folder,"share-dismissal.json").writeText(JSONObject().put("origin_returned",true).put("main_activity_created",mainLaunches.get())
                .put("queue_rows",0).put("rotation_preserved",true).put("expanded_back_to_compact",true).toString(2))
        } finally {
            shareActivity()?.let {activity->instrumentation.runOnMainSync {activity.finish()}}
            settings.text("language",old.language)
            instrumentation.runOnMainSync {ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)}
        }
    }
    @Test fun actualMp3QueuePersistsAndContinuesAfterSheetCloses()=runBlocking {
        val settings=SettingsRepository(app);val old=settings.current()
        val url="https://www.w3schools.com/html/mov_bbb.mp4?share-test=${UUID.randomUUID()}"
        var id:String?=null
        try {
            settings.text("language","en")
            source(url)
            openAudioFormats()
            compose.waitUntil(90000) {compose.onAllNodesWithTag("share_audio_mp3").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_audio_mp3"))
            compose.onNodeWithTag("share_audio_mp3").performScrollTo().performClick().assertIsSelected()
            capture("share-selected-mp3-en")
            val receiver=shareActivity()!!
            compose.onNodeWithTag("share_download").assertIsEnabled().performClick()
            // Fresh installations legitimately ask for notification permission.
            // Exercise the actual system prompt instead of assuming an earlier install granted it.
            if(android.os.Build.VERSION.SDK_INT>=33&&app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
                fun allowButton(node:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
                    if(node==null) return null
                    if(node.viewIdResourceName?.endsWith(":id/permission_allow_button")==true) return node
                    for(i in 0 until node.childCount) allowButton(node.getChild(i))?.let {return it}
                    return null
                }
                eventually(10000) {allowButton(automation.rootInActiveWindow)!=null||receiver.isFinishing||receiver.isDestroyed}
                allowButton(automation.rootInActiveWindow)?.let {
                    capture("share-notification-permission")
                    assertTrue(it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                }
            }
            try {
                compose.waitUntil(20000) {receiver.isFinishing||receiver.isDestroyed}
                eventually(10000) {originVisible()}
            } catch(error:Throwable) {
                var debug:com.zdownloader.app.ui.QuickShareState?=null
                instrumentation.runOnMainSync {debug=androidx.lifecycle.ViewModelProvider(receiver)[com.zdownloader.app.ui.QuickShareViewModel::class.java].state.value}
                File(folder,"share-enqueue-failure.json").writeText(JSONObject().put("saving",debug?.saving).put("queued",debug?.queuedId).put("error",debug?.error?.name)
                    .put("choice",debug?.choice?.toString()).put("rows",app.repository.dao.observe().first().count {it.url==url}).toString(2))
                capture("share-enqueue-failure")
                throw error
            }
            val rows=app.repository.dao.observe().first().filter {it.url==url}
            assertEquals(1,rows.size);id=rows.single().id
            val selected=Selection.parse(rows.single().selection)
            assertTrue(selected.audioOnly);assertEquals("mp3",selected.output);assertTrue(selected.convertAudio)
            withTimeout(180000) {while(app.repository.dao.get(id!!)!!.status !in listOf("COMPLETED","FAILED")) delay(250)}
            val completed=app.repository.dao.get(id!!)!!
            assertEquals(completed.error,"COMPLETED",completed.status)
            assertTrue((completed.outputSize ?: 0)>0)
            val retriever=android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(app,android.net.Uri.parse(completed.uri))
                assertEquals("audio/mpeg",retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
            } finally {retriever.release()}
            File(folder,"share-background-mp3.json").writeText(JSONObject().put("origin_returned",true).put("queue_rows",rows.size)
                .put("status",completed.status).put("output","mp3").put("bytes",completed.outputSize).toString(2))
        } finally {
            shareActivity()?.let {activity->instrumentation.runOnMainSync {activity.finish()}}
            val cleanupId=id ?: app.repository.dao.observe().first().singleOrNull {it.url==url}?.id
            cleanupId?.let {key->val row=app.repository.dao.get(key);if(row?.status=="COMPLETED") app.storage.deleteFile(row) else {app.repository.cancel(key);app.coordinator.stopAndAwait(key);app.repository.remove(key)}}
            settings.text("language",old.language)
        }
    }
    @Test fun shareChooserResolvesOnlyToShareReceiverAndInvalidPayloadDoesNotEnqueue()=runBlocking {
        val targets=app.packageManager.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(app.packageName),0)
        assertEquals(listOf(ShareReceiverActivity::class.java.name),targets.map {it.activityInfo.name})
        source("javascript:alert(1)")
        compose.waitUntil(10000) {compose.onAllNodesWithTag("share_error").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("share_download").assertIsNotEnabled()
        compose.onNodeWithTag("share_close").performClick()
        eventually {automation.rootInActiveWindow?.packageName?.toString()=="com.zdownloader.app.test"}
    }
    @Test fun repeatedWarmIntentKeepsSelectionAndInvalidReplacementClearsOldCandidates()=runBlocking {
        val url="https://www.w3schools.com/html/mov_bbb.mp4?share-repeat=${UUID.randomUUID()}"
        try {
            source(url)
            openAudioFormats()
            compose.waitUntil(90000) {compose.onAllNodesWithTag("share_audio_mp3").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_audio_mp3"))
            compose.onNodeWithTag("share_audio_mp3").performScrollTo().performClick()
            val receiver=shareActivity()!!
            instrumentation.runOnMainSync {
                instrumentation.callActivityOnNewIntent(receiver,Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,url))
            }
            compose.onNodeWithTag("share_audio_mp3").assertIsSelected()
            assertTrue(app.repository.dao.observe().first().none {it.url==url})
            instrumentation.runOnMainSync {
                instrumentation.callActivityOnNewIntent(receiver,Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"not a media link"))
            }
            compose.waitUntil(5000) {compose.onAllNodesWithTag("share_error").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("share_audio_mp3").assertDoesNotExist()
            compose.onNodeWithTag("share_download").assertIsNotEnabled()
            compose.onNodeWithTag("share_close").performClick()
            eventually {automation.rootInActiveWindow?.packageName?.toString()=="com.zdownloader.app.test"}
        } finally {shareActivity()?.let {activity->instrumentation.runOnMainSync {activity.finish()}}}
    }
    @Test fun largeFontKeepsCtaVisibleAndOutsideDismissalCreatesNoJob()=runBlocking {
        fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use {it.readText().trim()}
        val scale=shell("settings get system font_scale")
        val settings=SettingsRepository(app);val old=settings.current()
        val url="https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd?share-large=${UUID.randomUUID()}"
        try {
            shell("settings put system font_scale 1.5")
            settings.text("language","en")
            source(url)
            compose.waitUntil(15000) {compose.onAllNodesWithTag("share_compact").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("share_download").assertIsDisplayed()
            capture("share-large-font-compact")
            compose.onNodeWithText("Original").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {get->
                val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                assertTrue(get(layouts));assertTrue(layouts.isNotEmpty())
                File(folder,"large-font-text-layout.json").writeText(org.json.JSONArray(layouts.map {layout->
                    JSONObject().put("text",layout.layoutInput.text.text).put("width",layout.size.width).put("height",layout.size.height)
                        .put("paragraph_width",layout.multiParagraph.width).put("line_width",layout.getLineRight(0)-layout.getLineLeft(0))
                        .put("paragraph_height",layout.multiParagraph.height).put("overflow_width",layout.didOverflowWidth)
                        .put("overflow_height",layout.didOverflowHeight).put("line_count",layout.lineCount)
                }).toString(2))
                layouts.forEach {layout->
                    assertEquals("The complete Original label fits one accessible row",1,layout.lineCount)
                    assertFalse("Large-font label must not be ellipsized",layout.isLineEllipsized(0))
                    // Paragraph width is the available measure space (488 px
                    // here), not the rendered line (163 px). Check the actual
                    // line bounds and allow only integer-pixel rounding.
                    assertTrue("Large-font label width",layout.getLineRight(0)-layout.getLineLeft(0)<=layout.size.width+1f)
                    assertFalse("Large-font label height",layout.didOverflowHeight)
                }
            }
            compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_more"));compose.onNodeWithTag("share_more").performClick()
            compose.onNodeWithTag("share_expanded").assertIsDisplayed()
            compose.onNodeWithTag("share_download").assertIsDisplayed()
            capture("share-large-font-expanded")
            compose.onNodeWithTag("share_back").performClick()
            compose.onNodeWithTag("share_compact").assertIsDisplayed()
            val receiver=shareActivity()!!
            val top=compose.onNodeWithTag("share_compact").fetchSemanticsNode().boundsInWindow.top
            shell("input tap ${app.resources.displayMetrics.widthPixels/2} ${maxOf(100,(top/2).toInt())}")
            compose.waitUntil(10000) {receiver.isFinishing||receiver.isDestroyed}
            eventually {originVisible()}
            assertTrue(app.repository.dao.observe().first().none {it.url==url})
            File(folder,"share-large-font.json").writeText(JSONObject().put("font_scale",1.5).put("cta_visible_compact",true)
                .put("cta_visible_expanded",true).put("outside_dismissal_returned",true).put("queue_rows",0).toString(2))
        } finally {
            shareActivity()?.let {activity->instrumentation.runOnMainSync {activity.finish()}}
            if(scale=="null") shell("settings delete system font_scale") else shell("settings put system font_scale $scale")
            settings.text("language",old.language)
        }
    }
}
