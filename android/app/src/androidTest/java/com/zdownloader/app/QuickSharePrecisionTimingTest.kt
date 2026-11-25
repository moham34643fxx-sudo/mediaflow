package com.zdownloader.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.AnalysisTrace
import com.zdownloader.app.ui.QuickShareViewModel
import androidx.lifecycle.ViewModelProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** One controlled share per invocation; unlike AnalysisOptimizationTest, this is not a trial loop. */
class QuickSharePrecisionTimingTest {
    @Test fun capturesSingleShareThroughCompleteCatalogAndOptionalEnrichment() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as ZApplication
        val args=InstrumentationRegistry.getArguments()
        val url=args.getString("analysisUrl")
            ?: "https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd?quick-share-precision=${System.nanoTime()}"
        val runName=args.getString("evidenceRun") ?: "manual"
        val openMore=args.getString("openMore")!="false"
        app.extractor.clearMetadataCache()
        AnalysisTrace.clear()
        val intent=Intent(app,ShareReceiverActivity::class.java)
            .setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT,url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val scenario=ActivityScenario.launch<ShareReceiverActivity>(intent)
        try {
            var model:QuickShareViewModel?=null
            scenario.onActivity {activity->model=ViewModelProvider(activity)[QuickShareViewModel::class.java]}
            val activeModel=requireNotNull(model)
            await(15_000) {traceFor(activeModel.state.value.traceId)?.has("sheet_drawn")==true}
            // Match the user's immediate More Options action while keeping the
            // one-shot measurement independent of Compose test-harness timing.
            if(openMore) scenario.onActivity {activeModel.expand()}
            await(90_000) {
                traceFor(activeModel.state.value.traceId)?.let {row->
                    row.has("intent_received")&&row.has("sheet_drawn")&&row.has("extraction_start")&&
                        row.has("metadata_ready")&&row.has("quick_share_catalog_ready")&&
                        row.has("first_verified_selectable_drawn")&&row.has("complete_catalog_drawn")&&
                        row.has("optional_enrichment_finished")
                }==true
            }
            val trace=traceFor(activeModel.state.value.traceId)
            assertNotNull("Complete Quick Share trace was not recorded: ${AnalysisTrace.snapshot()}",trace)
            val row=requireNotNull(trace)
            val t0=row.getLong("intent_received")
            val stages=listOf("intent_received","sheet_drawn","extraction_start","metadata_ready",
                "quick_share_catalog_ready","first_verified_selectable_drawn","complete_catalog_drawn",
                "optional_enrichment_finished")
            val elapsed=JSONObject()
            stages.forEach {stage->elapsed.put(stage,row.getLong(stage)-t0)}
            val media=activeModel.state.value.media
            val sources=media?.let(::selectableQuickShareVideoSources).orEmpty()
            val groups=media?.let {QuickShareCatalog.selectableGroups(it.qualityGroups(),sources)}.orEmpty()
            val result=JSONObject().put("run",runName).put("clock","elapsedRealtime")
                .put("requested_more",openMore).put("first_selectable_checkpoint","enabled format row draw")
                .put("elapsed_ms_from_T0",elapsed)
                .put("video_source_count",sources.size)
                .put("quality_tiers",JSONArray(groups.map {it.resolution}))
                .put("trace",row)
            val output=File(app.getExternalFilesDir(null),"quick-share-precision/$runName.json")
            output.parentFile?.mkdirs()
            output.writeText(result.toString(2))
            assertTrue("No selectable video/audio/image candidates were discovered",(media?.videoOptions()?.isNotEmpty()==true)||
                (media?.audioOptions()?.isNotEmpty()==true)||(media?.imageOptions()?.isNotEmpty()==true))
        } finally {
            scenario.close()
        }
    }

    private fun await(timeoutMillis:Long,condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+timeoutMillis
        while(!condition()) {
            if(SystemClock.elapsedRealtime()>=deadline) throw AssertionError("Timing checkpoint timeout: ${AnalysisTrace.snapshot()}")
            Thread.sleep(20)
        }
    }

    private fun traceFor(id:String?):JSONObject? {
        if(id.isNullOrBlank()) return null
        val rows=JSONArray(AnalysisTrace.snapshot())
        return (0 until rows.length()).map {rows.getJSONObject(it)}.firstOrNull {it.optString("id")==id}
    }
}
