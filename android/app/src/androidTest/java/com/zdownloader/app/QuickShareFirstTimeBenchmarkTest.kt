package com.zdownloader.app

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.chaquo.python.Python
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.AnalysisTrace
import com.zdownloader.app.ui.QuickShareViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Measures real share-window and on-screen quality availability without enqueueing downloads. */
class QuickShareFirstTimeBenchmarkTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as ZApplication
    private val automation get()=instrumentation.uiAutomation

    private fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)).bufferedReader().use {it.readText().trim()}

    private fun receiver():ShareReceiverActivity? {
        var result:ShareReceiverActivity?=null
        instrumentation.runOnMainSync {
            result=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ShareReceiverActivity>().firstOrNull()
        }
        return result
    }

    @Test fun newUrlsAndRepeatedUrlReportRealOnScreenTimings()=runBlocking<Unit> {
        val args=InstrumentationRegistry.getArguments()
        check(app.packageName=="com.zdownloader.app.candidate0965") {
            "This performance run is restricted to the isolated candidate package."
        }
        val run=requireNotNull(args.getString("evidenceRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val folder=File(app.getExternalFilesDir(null),"discovery-benchmarks/$run")
        check(folder.mkdirs()) {"Refusing evidence-directory collision"}
        automation.serviceInfo=automation.serviceInfo.apply {
            flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")

        val token=UUID.randomUUID().toString()
        val urls=listOf(
            "new-hls" to "https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8?mf-discovery=$token-hls",
            "warm-new-dash" to "https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd?mf-discovery=$token-dash",
            "warm-new-direct" to "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4?mf-discovery=$token-direct",
            "repeat-cached-hls" to "https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8?mf-discovery=$token-hls"
        )
        val trials=JSONArray()
        val report=JSONObject().put("device",android.os.Build.MODEL).put("sdk",android.os.Build.VERSION.SDK_INT)
            .put("scope","Quick Share real Activity, Compose sheet, and selectable quality row; no download enqueued")
            .put("trials",trials)

        var activeScenario:ActivityScenario<ShareReceiverActivity>?=null
        try {
            for((index,entry) in urls.withIndex()) {
                val (label,url)=entry
                val repeated=index==urls.lastIndex
                if(index==0) app.extractor.clearMetadataCache()
                AnalysisTrace.clear()
                val pythonWasStarted=Python.isStarted()
                val sample=JSONObject().put("label",label).put("url",url).put("repeated_cached_url",repeated)
                    .put("python_started_before",pythonWasStarted)
                    .put("extraction_class",if(repeated) "repeated_url_control" else if(pythonWasStarted) "warm_new_url" else "cold_python_new_url")
                val begin=SystemClock.elapsedRealtime()
                val sendIntent=Intent(app,ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(Intent.EXTRA_TEXT,url)
                activeScenario=ActivityScenario.launch<ShareReceiverActivity>(sendIntent)
                sample.put("share_intent_dispatch_complete_ms",SystemClock.elapsedRealtime()-begin)

                compose.waitUntil(15_000) {receiver()!=null}
                val activity=requireNotNull(receiver())
                val model=ViewModelProvider(activity)[QuickShareViewModel::class.java]
                compose.waitUntil(15_000) {compose.onAllNodesWithTag("share_compact").fetchSemanticsNodes().isNotEmpty()}
                sample.put("share_window_ms",SystemClock.elapsedRealtime()-begin)
                compose.onNodeWithTag("share_preference_mp3").assertIsDisplayed().assertIsEnabled()
                sample.put("preferences_on_screen_ms",SystemClock.elapsedRealtime()-begin)
                    .put("preferences_before_metadata",model.state.value.media==null)
                val traceId=model.state.value.traceId

                var qualityTag:String?=null
                var firstQualityOnScreenAt:Long?=null
                var extractionStartedAt:Long?=null
                var expandedForQuality=false
                var expandedSourceTag:String?=null
                var scrolledToExpandedSource=false
                val qualityDeadline=SystemClock.elapsedRealtime()+90_000
                while(qualityTag==null&&SystemClock.elapsedRealtime()<qualityDeadline) {
                    val media=model.state.value.media
                    if(extractionStartedAt==null&&model.state.value.analyzing)
                        extractionStartedAt=SystemClock.elapsedRealtime()
                    if(media!=null) {
                        val compact=QuickShareCatalog.verifiedGroups(media)
                            .filter {it.resolution in QuickShareCatalog.basicQualities}
                            .mapNotNull {it.resolution?.let {height->"share_video_$height"}}
                        qualityTag=compact.firstOrNull {tag->compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
                        if(qualityTag!=null) firstQualityOnScreenAt=SystemClock.elapsedRealtime()
                        if(qualityTag==null&&!expandedForQuality&&media.videoOptions().isNotEmpty()&&
                            compose.onAllNodesWithTag("share_more").fetchSemanticsNodes().isNotEmpty()) {
                             compose.onNodeWithTag("share_more").performScrollTo().performClick()
                            expandedForQuality=true
                            compose.waitUntil(10_000) {compose.onAllNodesWithTag("share_expanded").fetchSemanticsNodes().isNotEmpty()}
                        }
                        if(qualityTag==null&&expandedForQuality) {
                            val source=QuickShareCatalog.variantEntries(media).firstOrNull()?.format
                            if(source!=null) expandedSourceTag="share_video_quality_${qualityLabel(source) ?: "unknown"}"
                            val tag=expandedSourceTag
                            if(!scrolledToExpandedSource&&tag!=null) {
                                compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag(tag))
                                scrolledToExpandedSource=true
                            }
                            expandedSourceTag?.takeIf {compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty()}
                                ?.let {qualityTag=it;firstQualityOnScreenAt=SystemClock.elapsedRealtime()}
                        }
                    }
                    if(qualityTag==null) delay(40)
                }
                assertNotNull("No real selectable video quality reached the share UI for $label",qualityTag)
                sample.put("first_quality_on_screen_ms",requireNotNull(firstQualityOnScreenAt)-begin)
                    .put("quality_after_window_ms",SystemClock.elapsedRealtime()-begin-sample.getLong("share_window_ms"))
                    .put("extraction_started_observed_ms",extractionStartedAt?.minus(begin) ?: JSONObject.NULL)
                    .put("quality_row_tag",qualityTag)
                val media=requireNotNull(model.state.value.media)
                sample.put("source",media.source).put("format_count",media.formats.size)
                    .put("video_format_count",media.videoOptions().size)
                    .put("catalog",JSONArray(media.formats.map {format->JSONObject().put("id",format.id)
                        .put("width",format.width).put("height",format.height).put("image",format.image)
                        .put("video",format.video).put("audio",format.audio)}))

                compose.waitUntil(90_000) {!model.state.value.analyzing}
                sample.put("analysis_terminal_ms",SystemClock.elapsedRealtime()-begin)
                    .put("error",model.state.value.error?.name)
                val traces=JSONArray(AnalysisTrace.snapshot())
                val trace=(0 until traces.length()).map {traces.getJSONObject(it)}.firstOrNull {it.optString("id")==traceId}
                if(trace!=null) {
                    sample.put("trace",trace)
                    for(stage in listOf("activity_create_start","intent_received","url_normalized","preferences_interactive",
                        "quality_catalog_ready","quick_share_catalog_ready","analysis_finished","share_analysis_terminal")) {
                        if(trace.has(stage)) sample.put("${stage}_ms",trace.getLong(stage)-begin)
                    }
                    if(trace.has("activity_create_start")&&trace.has("preferences_interactive"))
                        sample.put("activity_to_first_draw_ms",trace.getLong("preferences_interactive")-trace.getLong("activity_create_start"))
                    sample.put("cache_hit",trace.optBoolean("cache_hit",false))
                    assertEquals("Unexpected result-cache state for $label",repeated,trace.optBoolean("cache_hit",false))
                } else fail("Missing analysis trace for $label")
                trials.put(sample)
                File(folder,"report.json").writeText(report.toString(2))

                compose.onNodeWithTag("share_close").performClick()
                compose.waitUntil(10_000) {activity.isFinishing||activity.isDestroyed}
                activeScenario?.close()
                activeScenario=null
            }
        } finally {
            activeScenario?.close()
            receiver()?.let {activity->instrumentation.runOnMainSync {activity.finish()}}
            File(folder,"report.json").writeText(report.toString(2))
        }
        assertEquals(urls.size,trials.length())
    }
}
