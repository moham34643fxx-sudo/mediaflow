package com.zdownloader.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.domain.*
import com.zdownloader.app.ui.QuickShareViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Direct receiver lifecycle checks, separate from external chooser-driver acceptance. */
class PlaylistShareReceiverTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as ZApplication
    @Test fun ambiguousScopeDoesNotEnqueueAndInvalidReplacementClearsPreview()=runBlocking {
      ActivityScenario.launch<ShareReceiverActivity>(Intent(app,ShareReceiverActivity::class.java)).use {scenario->
        var model:QuickShareViewModel?=null
        scenario.onActivity {model=ViewModelProvider(it)[QuickShareViewModel::class.java]}
        val before=app.repository.dao.batches().first().map {it.id}
         compose.runOnIdle {model!!.accept("https://www.youtube.com/watch?v=fixture&list=fixture-list")}
         compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_more"));compose.onNodeWithTag("share_more").performClick()
        compose.onNodeWithTag("playlist_single").assertIsDisplayed()
        compose.onNodeWithTag("playlist_entire").assertIsDisplayed()
        compose.runOnIdle {model!!.accept("https://www.youtube.com/watch?v=fixture&list=fixture-list")}
        assertTrue(model!!.playlists.state.value.ambiguous)
        compose.runOnIdle {model!!.accept(null)}
        compose.waitUntil(10000) {compose.onAllNodesWithTag("playlist_preview").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithTag("playlist_download").assertDoesNotExist()
        assertEquals(before,app.repository.dao.batches().first().map {it.id})
      }
    }
    @Test fun singleVideoShareStillPublishesAfterReceiverClosesWithoutMainActivity()=runBlocking {
        val settings=SettingsRepository(app);val old=settings.current()
        var model:QuickShareViewModel?=null
        var id:String?=null
        val launches=AtomicInteger()
        val callback=ActivityLifecycleCallback {activity,stage->if(activity is MainActivity&&stage==Stage.CREATED) launches.incrementAndGet()}
        instrumentation.runOnMainSync {ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)}
        try {
            settings.boolean("notifications",false);settings.boolean("wifi",false);settings.text("destination","");settings.text("download_type","video")
            // ActivityScenario matches lifecycle events against its launch Intent.
            // Launch the actual SEND contract instead of replacing MAIN with SEND.
            val share=Intent(app,ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4?mediaflow-sequential-regression=${UUID.randomUUID()}")
            ActivityScenario.launch<ShareReceiverActivity>(share).use {scenario->
            var receiver:ShareReceiverActivity?=null
            scenario.onActivity {model=ViewModelProvider(it)[QuickShareViewModel::class.java];receiver=it;it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            try {
                compose.waitUntil(90_000) {
                    val media=model!!.state.value.media
                    media!=null&&media.qualityGroups().any {it.resolution==720}&&
                        compose.onAllNodesWithTag("share_preference_720").fetchSemanticsNodes().isNotEmpty()
                }
                assertNotNull(model!!.state.value.media)
                val media=requireNotNull(model!!.state.value.media)
                assertEquals(720,verifiedQualityEdge(requireNotNull(VideoPreferenceSelection.ready(media,720)).primary))
                compose.onNodeWithTag("share_preference_720").performScrollTo().assertIsEnabled().performClick()
            } catch(error:Throwable) {
                val current=model!!.state.value
                val directory=java.io.File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
                java.io.File(directory,"share-analysis-failure.json").writeText(org.json.JSONObject().put("analyzing",current.analyzing).put("error",current.error?.name).put("formats",current.media?.formats?.size).put("has_choice",current.choice!=null).put("has_queued_id",current.queuedId!=null).put("trace_id",current.traceId).toString(2))
                throw error
            }
            compose.onNodeWithTag("share_download").assertIsDisplayed().performClick()
            withTimeout(60000) {while(id==null) {id=model!!.state.value.queuedId;delay(50)}}
            compose.waitUntil(20000) {receiver!!.isDestroyed}
            withTimeout(180000) {while(app.repository.dao.get(id!!)!!.status !in setOf("COMPLETED","FAILED")) delay(100)}
            val row=app.repository.dao.get(id!!)!!
            assertEquals(row.error,"COMPLETED",row.status)
            assertNull(row.batchId)
            assertFalse(com.zdownloader.app.domain.Selection.parse(row.selection).audioOnly)
            assertEquals(0,launches.get())
            app.contentResolver.openFileDescriptor(android.net.Uri.parse(row.uri),"r")!!.use {assertTrue(it.statSize>0)}
            val directory=java.io.File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
            java.io.File(directory,"share-single-video-publication.json").writeText(org.json.JSONObject().put("entry_point",Intent.ACTION_SEND).put("receiver_destroyed",receiver!!.isDestroyed).put("main_activity_created",launches.get()).put("status",row.status).put("output_bytes",row.outputSize).put("video_selection",true).toString(2))
            }
        } finally {
            id?.let {key->app.coordinator.stopAndAwait(key);val row=app.repository.dao.get(key);if(row?.status=="COMPLETED") app.storage.deleteFile(row) else {app.repository.cancel(key);app.repository.remove(key)}}
            settings.boolean("notifications",old.notifications);settings.boolean("wifi",old.wifiOnly);settings.text("destination",old.destination.orEmpty());settings.text("download_type",old.downloadType)
            instrumentation.runOnMainSync {ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)}
        }
    }
}
