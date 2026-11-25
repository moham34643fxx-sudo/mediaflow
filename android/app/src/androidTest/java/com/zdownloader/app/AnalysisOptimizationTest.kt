package com.zdownloader.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.engine.AnalysisTrace
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Identical instrumentation-only protocol for before/after; no download is submitted. */
class AnalysisOptimizationTest {
    @Test fun shareAnalysisTimings() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as ZApplication
        val args=InstrumentationRegistry.getArguments()
        val url=args.getString("analysisUrl") ?: "https://www.facebook.com/reel/1102243642315985/"
        val rows=JSONArray()
        val evidenceRun=args.getString("evidenceRun") ?: "manual"
        require(evidenceRun.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/$evidenceRun").apply {mkdirs()}
        val report=File(folder,"analysis-optimization.json")
        AnalysisTrace.clear()
        repeat(4) { index ->
            // First 3 trials always extract; fourth explicitly measures the metadata cache.
            if(index<3) app.extractor.clearMetadataCache()
            val start=android.os.SystemClock.elapsedRealtime()
            val intent=Intent(app,ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT,url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<ShareReceiverActivity>(intent).use {scenario->
                var previewRequested=false
                var lastTrace:JSONObject?=null
                var completed=false
                withTimeoutOrNull(100_000) {
                    while(!completed) {
                        val traces=JSONArray(AnalysisTrace.snapshot())
                         val found=(0 until traces.length()).map {traces.getJSONObject(it)}.lastOrNull {it.optLong("activity_create_start")>=start}
                         lastTrace=found
                         if(found!=null&&found.has("preferences_interactive")&&!previewRequested) {
                             val interactiveAt=found.getLong("preferences_interactive")
                             val prematureEngineStages=listOf("python_runtime_start","extraction_start")
                                 .filter {found.has(it)&&found.getLong(it)<interactiveAt}
                             assertTrue("Engine work preceded the interactive preference surface: $prematureEngineStages",prematureEngineStages.isEmpty())
                             scenario.onActivity {androidx.lifecycle.ViewModelProvider(it)[com.zdownloader.app.ui.QuickShareViewModel::class.java].expand()}
                             previewRequested=true
                         }
                        if(found!=null&&found.has("analysis_finished")&&found.has("selectable_drawn")) {
                            rows.put(JSONObject(found.toString()).put("trial",index).put("launch_requested",start)
                                  .put("cache_trial",index==3).put("thumbnail_note","Optional preview explicitly requested only after preferences became interactive; no size probing"))
                            completed=true
                        }
                        if(!completed) delay(20)
                    }
                }
                if(!completed) {
                    val captured=lastTrace ?: JSONObject()
                    val checkpoints=JSONObject()
                    for(stage in listOf("preferences_interactive","sheet_drawn","python_runtime_start",
                        "extraction_start","analysis_finished","selectable_drawn")) {
                        if(captured.has(stage)) checkpoints.put(stage,captured.getLong(stage)-start)
                    }
                    val missing=JSONArray(listOf("analysis_finished","selectable_drawn")
                        .filterNot {captured.has(it)})
                    rows.put(JSONObject().put("trial",index).put("cache_trial",index==3)
                        .put("complete",false).put("missing_checkpoints",missing)
                        .put("elapsed_checkpoints_ms",checkpoints))
                    report.writeText(rows.toString(2))
                    throw AssertionError("Analysis timing timed out; partial evidence saved to ${report.name}")
                }
                val found=lastTrace
                if(args.getString("requireFrameBeforeAnalysis")=="true"&&found!=null&&found.has("python_runtime_start"))
                    assertTrue("Python must not start before the compact draw",found.getLong("sheet_drawn")<=found.getLong("python_runtime_start"))
                instrumentation.waitForIdleSync()
            }
            report.writeText(rows.toString(2))
            delay(300)
        }
        assertEquals(4,rows.length())
    }
}
