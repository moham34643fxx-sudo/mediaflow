package com.zdownloader.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Public Shaka HLS/DASH media through the actual Python downloader, not HTTP mocks. */
class PlaylistSegmentedTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as ZApplication
    private fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText().trim()}
    @Test fun mixedHlsDashBatchUsesTheSharedNativePipelineSequentially()=runBlocking {
        // A prior successful test may have published its last file while its
        // coordinator is still winding down. Wait only for that empty queue.
        try {withTimeout(30000) {while(app.coordinator.running&&app.repository.dao.remaining()==0) delay(50)}}
        catch(error:TimeoutCancellationException) {queueAuditSnapshot("segmented-idle-timeout");throw error}
        assertFalse("Do not interfere with unrelated active work",app.coordinator.running)
        val token=UUID.randomUUID().toString()
        val urls=listOf("https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8?qa-batch=$token","https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd?qa-batch=$token")
        var batch:String?=null
        var receiver:ShareReceiverActivity?=null
        var model:com.zdownloader.app.ui.QuickShareViewModel?=null
        val mainLaunches=java.util.concurrent.atomic.AtomicInteger()
        val callback=androidx.test.runner.lifecycle.ActivityLifecycleCallback {activity,stage->if(activity is MainActivity&&stage==androidx.test.runner.lifecycle.Stage.CREATED) mainLaunches.incrementAndGet()}
        instrumentation.runOnMainSync {androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)}
        val started=android.os.SystemClock.elapsedRealtime()
        val report=JSONObject().put("source","Two public links in the existing playlist UI; finite selection, not external-playlist discovery")
        try {
            shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
            val launch=shell("am start -W -n com.zdownloader.app.test/com.zdownloader.app.ShareOriginActivity -f 0x10008000 --es fixtureUrl ${urls.first()} --ez autoShare true")
            assertFalse(launch,launch.contains("Exception"))
            compose.waitUntil(15000) {compose.onAllNodesWithTag("share_compact").fetchSemanticsNodes().isNotEmpty()}
            instrumentation.runOnMainSync {
                receiver=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<ShareReceiverActivity>().single()
                model=androidx.lifecycle.ViewModelProvider(receiver!!)[com.zdownloader.app.ui.QuickShareViewModel::class.java]
                model!!.cancelAnalysis();model!!.playlists.fromLinks(urls)
            }
            compose.onNodeWithTag("playlist_quality_360").performScrollTo().performClick()
            compose.waitUntil(15000) {runCatching {compose.onNodeWithTag("playlist_download").assertIsEnabled();true}.getOrDefault(false)}
            compose.onNodeWithTag("playlist_download").assertIsEnabled().performClick()
            compose.waitUntil(20000) {model!!.playlists.state.value.queued!=null}
            batch=model!!.playlists.state.value.queued!!
            compose.waitUntil(10000) {receiver!!.isDestroyed||receiver!!.isFinishing}
            shell("am start -W -n com.zdownloader.app.test/com.zdownloader.app.ShareOriginActivity -f 0x34000000 --ez finishFixture true")
            shell("input keyevent KEYCODE_SLEEP")
            withTimeout(480000) {while(app.repository.dao.batchItems(batch!!).any {it.status !in setOf("COMPLETED","FAILED","CANCELLED")}) delay(250)}
            val rows=app.repository.dao.batchItems(batch!!)
            assertEquals(rows.map {it.error}.toString(),2,rows.count {it.status=="COMPLETED"})
            assertEquals(0,mainLaunches.get())
            assertFalse(app.getSystemService(android.os.PowerManager::class.java).isInteractive)
            val records=JSONArray()
            for(row in rows) {
                val events=app.repository.dao.events(row.id)
                val uri=Uri.parse(row.uri)
                val selection=Selection.parse(row.selection)
                assertTrue((qualityLabel(selection.primary) ?: Int.MAX_VALUE)<=360)
                val retriever=android.media.MediaMetadataRetriever()
                try {retriever.setDataSource(app,uri);val frame=retriever.getFrameAtTime(1000000);assertNotNull(frame);frame?.recycle();assertEquals("yes",retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))} finally {retriever.release()}
                records.put(JSONObject().put("position",row.playlistPosition).put("status",row.status).put("transfer_bytes",row.bytes).put("source_protocol",selection.primary.protocol).put("source_quality",qualityLabel(selection.primary)).put("output_bytes",row.outputSize).put("stages",JSONArray(events.map {it.state})))
            }
            val secondStart=app.repository.dao.events(rows[1].id).first {it.state=="ANALYZING"}.time
            assertTrue(secondStart>=rows[0].finishedAt!!)
            report.put("state","passed").put("elapsed_ms",android.os.SystemClock.elapsedRealtime()-started).put("outputs",records)
                .put("screen_off_until_completion",true).put("main_activity_created",mainLaunches.get()).put("confirmed_via_playlist_ui",true)
            shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
            // Open only the exact test URI in the installed OEM single-item viewer.
            // Never browse/capture album grids or navigate to unrelated user media.
            val uri=Uri.parse(rows.first().uri)
            app.grantUriPermission("com.hihonor.photos",uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            require(uri.toString().matches(Regex("content://media/[A-Za-z0-9/_]+")))
            val result=shell("am start -W -n com.hihonor.photos/com.hihonor.gallery.app.SinglePhotoActivity -a android.intent.action.VIEW -d $uri -t video/mp4 --grant-read-uri-permission")
            delay(1500)
            val viewerVisible=instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.hihonor.photos"
            val uid=Regex("uid:(\\d+)").find(shell("pm list packages -U com.hihonor.photos"))?.groupValues?.get(1)
            val audio=uid!=null&&withTimeoutOrNull(15000) {
                while(!shell("dumpsys audio").lineSequence().any {it.contains("AudioPlaybackConfiguration")&&it.contains("u/pid:$uid/")&&it.contains("state:started")}) delay(500)
                true
            }==true
            report.put("exact_uri_gallery",JSONObject().put("launch_result",result).put("viewer_visible",viewerVisible).put("gallery_audio_started",audio).put("scope",if(viewerVisible&&audio) "exact test URI playback started" else "BLOCKED or not confirmed; no album browsing"))
        } finally {
            shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
            val folder=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
            File(folder,"playlist-segmented-regression.json").writeText(report.toString(2))
            batch?.let {key->app.playlists.cancel(key)
            for(row in app.repository.dao.batchItems(key)) {
                app.coordinator.stopAndAwait(row.id)
                val current=app.repository.dao.get(row.id)
                if(current?.status=="COMPLETED") app.storage.deleteFile(current) else app.repository.remove(row.id)
            }
            app.repository.dao.removeEmptyBatch(key)}
            instrumentation.runOnMainSync {receiver?.finish();androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)}
        }
        Unit
    }
}
