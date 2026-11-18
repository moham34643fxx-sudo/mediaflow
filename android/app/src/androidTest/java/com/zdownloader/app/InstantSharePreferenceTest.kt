package com.zdownloader.app

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.zdownloader.app.domain.*
import com.zdownloader.app.ui.QuickShareViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** A real discovered source row remains selectable and completes after the share UI closes. */
class InstantSharePreferenceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as ZApplication
    private val args get()=InstrumentationRegistry.getArguments()

    private fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText().trim()}

    private fun receiver():ShareReceiverActivity? {
        var activity:ShareReceiverActivity?=null
        instrumentation.runOnMainSync {
            activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ShareReceiverActivity>().firstOrNull()
        }
        return activity
    }

    private fun eventually(timeout:Long=15_000,predicate:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(!predicate()) {
            if(SystemClock.elapsedRealtime()>end) error("Expected Quick Share/download state did not appear")
            Thread.sleep(60)
        }
    }

    private fun wakeAndUnlock() {
        shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
        fun locked()=Regex("\\(current\\):.*deviceLocked=1").containsMatchIn(shell("dumpsys trust"))
        if(locked()) {
            val pin=args.getString("devicePin")?.takeIf {it.matches(Regex("[0-9]{4,12}"))}
                ?: error("Device is PIN-locked; provide the approved devicePin instrumentation argument")
            shell("input text $pin");shell("input keyevent KEYCODE_ENTER")
            eventually(10_000) {!locked()}
        }
    }

    @Test fun realHlsQualityIsPersistedAndCompletesAfterShareCloses()=runBlocking {
        val url="https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8?real-quality=${UUID.randomUUID()}"
        val evidenceRun=args.getString("evidenceRun","manual")
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/$evidenceRun").apply {mkdirs()}
        val report=JSONObject().put("source",url).put("selected_row","share_preference_360")
        var id:String?=null
        try {
            wakeAndUnlock()
            shell("am start -W -n com.zdownloader.app.test/com.zdownloader.app.ShareOriginActivity -f 0x34000000 --ez finishFixture true")
            val quoted="'"+url.replace("'","'\\''")+"'"
            val launch=shell("am start -W -n com.zdownloader.app.test/com.zdownloader.app.ShareOriginActivity -f 0x10008000 --es fixtureUrl $quoted --ez autoShare true")
            assertFalse("External caller launch failed: $launch",launch.contains("Exception")||launch.contains("Error:"))
            eventually {receiver()!=null}
            val activity=requireNotNull(receiver())
            var model:QuickShareViewModel?=null
            instrumentation.runOnMainSync {model=ViewModelProvider(activity)[QuickShareViewModel::class.java]}
            compose.waitUntil(15_000) {compose.onAllNodesWithTag("share_compact").fetchSemanticsNodes().isNotEmpty()}
            compose.waitUntil(90_000) {
                val media=model!!.state.value.media
                media!=null&&media.qualityGroups().any {it.resolution==360}&&
                    compose.onAllNodesWithTag("share_preference_360").fetchSemanticsNodes().isNotEmpty()
            }
            val media=requireNotNull(model!!.state.value.media)
            val preview=requireNotNull(VideoPreferenceSelection.ready(media,360))
            assertEquals("Selectable row must be a verified source edge",360,verifiedQualityEdge(preview.primary))
            compose.onNodeWithTag("share_preference_360").performScrollTo().assertIsEnabled().performClick()
            assertEquals(360,model!!.state.value.qualityPreference)
            assertEquals(preview.primary.id,requireNotNull(VideoPreferenceSelection.ready(media,360)).primary.id)
            compose.onNodeWithTag("share_download").assertIsEnabled().performClick()
            withTimeout(20_000) {while(model!!.state.value.queuedId==null) {assertNull(model!!.state.value.error);delay(50)}}
            id=model!!.state.value.queuedId!!
            val submitted=requireNotNull(app.repository.dao.get(id!!))
            assertTrue("Verified source selection is durably accepted",submitted.selection.isNotBlank())
            assertEquals(preview.primary.id,Selection.parse(submitted.selection).primary.id)
            assertNull("Fresh real preview should not degrade into a fabricated preference request",submitted.request)
            compose.waitUntil(20_000) {activity.isFinishing||activity.isDestroyed}
            shell("am start -W -n com.zdownloader.app.test/com.zdownloader.app.ShareOriginActivity -f 0x34000000 --ez finishFixture true")
            shell("input keyevent KEYCODE_SLEEP")
            withTimeout(240_000) {
                while(app.repository.dao.get(id!!)!!.status !in setOf("COMPLETED","FAILED")) delay(150)
            }
            val row=requireNotNull(app.repository.dao.get(id!!))
            assertEquals(row.error,"COMPLETED",row.status)
            val selected=Selection.parse(row.selection)
            assertEquals(preview.primary.id,selected.primary.id)
            assertEquals(360,verifiedQualityEdge(selected.primary))
            assertTrue(requireNotNull(row.uri).startsWith("content://"))
            assertTrue((row.outputSize ?: 0L)>0L)
            assertFalse("Download should finish after the screen turns off",app.getSystemService(android.os.PowerManager::class.java).isInteractive)
            val events=app.repository.dao.events(id!!)
            assertTrue(events.any {it.state.startsWith("DOWNLOADING")})
            assertTrue(events.any {it.state=="VALIDATING"}&&events.any {it.state=="SAVING"})
            report.put("operation",id).put("status",row.status).put("uri",row.uri).put("bytes",row.outputSize)
                .put("actual_width",selected.primary.width).put("actual_height",selected.primary.height)
                .put("screen_interactive_at_completion",false)
                .put("events",JSONArray(events.map {JSONObject().put("state",it.state).put("time",it.time)}))
                .put("manual_resume",false).put("validated",true)
            wakeAndUnlock()
        } catch(error:Throwable) {
            report.put("state","failed").put("error",error.toString())
            throw error
        } finally {
            wakeAndUnlock()
            shell("am start -W -n com.zdownloader.app.test/com.zdownloader.app.ShareOriginActivity -f 0x34000000 --ez finishFixture true")
            id?.let {key->
                app.coordinator.stopAndAwait(key)
                val row=app.repository.dao.get(key)
                if(row?.status=="COMPLETED") app.storage.deleteFile(row)
                else {app.repository.cancel(key);app.repository.remove(key)}
            }
            receiver()?.let {activity->instrumentation.runOnMainSync {activity.finish()}}
            File(folder,"instant-real-quality.json").writeText(report.toString(2))
        }
    }
}
