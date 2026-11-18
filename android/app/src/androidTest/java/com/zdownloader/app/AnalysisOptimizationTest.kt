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
        val url=args.getString("analysisUrl") ?: "https://www.facebook.com/share/v/1TBGC8WbKh/"
        val rows=JSONArray()
        AnalysisTrace.clear()
        repeat(4) { index ->
            // First 3 trials always extract; fourth explicitly measures the metadata cache.
            if(index<3) app.extractor.clearMetadataCache()
            val start=android.os.SystemClock.elapsedRealtime()
            val intent=Intent(app,ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT,url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<ShareReceiverActivity>(intent).use {scenario->
                var previewRequested=false
                withTimeout(100_000) {
                    while(true) {
                        val traces=JSONArray(AnalysisTrace.snapshot())
                         val found=(0 until traces.length()).map {traces.getJSONObject(it)}.lastOrNull {it.optLong("activity_create_start")>=start}
                         if(found!=null&&found.has("preferences_interactive")&&!previewRequested) {
                             assertFalse("No engine work before the interactive preference surface",found.has("python_runtime_start")||found.has("extraction_start"))
                             scenario.onActivity {androidx.lifecycle.ViewModelProvider(it)[com.zdownloader.app.ui.QuickShareViewModel::class.java].expand()}
                             previewRequested=true
                         }
                        if(found!=null&&found.has("analysis_finished")&&found.has("selectable_drawn")) {
                            rows.put(JSONObject(found.toString()).put("trial",index).put("launch_requested",start)
                                 .put("cache_trial",index==3).put("thumbnail_note","Optional preview explicitly requested only after preferences became interactive; no size probing"))
                            if(args.getString("requireFrameBeforeAnalysis")=="true"&&found.has("python_runtime_start"))
                                assertTrue("Python must not start before the compact draw",found.getLong("sheet_drawn")<=found.getLong("python_runtime_start"))
                            break
                        }
                        delay(20)
                    }
                }
                instrumentation.waitForIdleSync()
            }
            delay(300)
        }
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${args.getString("evidenceRun","manual")}").apply {mkdirs()}
        File(folder,"analysis-optimization.json").writeText(rows.toString(2))
        assertEquals(4,rows.length())
    }
}
