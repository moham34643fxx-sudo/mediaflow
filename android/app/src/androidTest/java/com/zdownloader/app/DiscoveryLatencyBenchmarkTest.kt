package com.zdownloader.app

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.zdownloader.app.domain.UrlPolicy
import com.zdownloader.app.engine.AnalysisTrace
import com.zdownloader.app.ui.ZViewModel
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Unmodified extraction/network and real home state; no downloads, preference changes or synthetic successes. */
class DiscoveryLatencyBenchmarkTest {
    @Test fun repeatedHomeDiscoveryRecordsConfirmedCatalogAndOptionalTail()=runBlocking<Unit> {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        val args=InstrumentationRegistry.getArguments()
        val url=UrlPolicy.normalize(requireNotNull(args.getString("discoveryUrl")))
        val run=requireNotNull(args.getString("evidenceRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val folder=File(app.getExternalFilesDir(null),"discovery-benchmarks/$run")
        folder.parentFile!!.mkdirs()
        check(folder.mkdir()) {"Refusing evidence-directory collision"}
        val trials=JSONArray()
        val report=JSONObject().put("sdk",android.os.Build.VERSION.SDK_INT).put("pid",Process.myPid())
            .put("source",url).put("trials",trials)
            .put("scope","Real home ViewModel availability; not rendered-frame latency. CPU is process-wide; PSS endpoints are samples, not peaks. Cold means observed uninitialized Python, not cleared OS caches.")
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            lateinit var model:ZViewModel
            scenario.onActivity {model=ViewModelProvider(it)[ZViewModel::class.java]}
            try {
                repeat(4) {trial->
                    if(trial<3) app.extractor.clearMetadataCache()
                    AnalysisTrace.clear()
                    val sample=JSONObject().put("trial",trial).put("cache_trial",trial==3)
                        .put("python_started_before",Python.isStarted()).put("pss_before_kb",Debug.getPss())
                    val cpu=Process.getElapsedCpuTime();val began=SystemClock.elapsedRealtime()
                    scenario.onActivity {model.input("");model.input(url);model.analyze()}
                    val token=model.ui.value.analysisId
                    try {
                        withTimeout(110_000) {
                            while(model.ui.value.analyzing) delay(10)
                        }
                        sample.put("first_selectable_model_ms",SystemClock.elapsedRealtime()-began)
                        val confirmed=model.ui.value.media
                        if(confirmed==null) {
                            sample.put("outcome","failed").put("error",model.ui.value.error?.name ?: "NO_CONFIRMED_CATALOG")
                        } else {
                            assertTrue(confirmed.formats.isNotEmpty())
                            sample.put("outcome","resolved").put("first_catalog",catalog(confirmed))
                        }
                        withTimeout(10_000) {
                            while(trace(token)?.has("home_analysis_terminal")!=true) delay(10)
                        }
                        sample.put("terminal_ms",SystemClock.elapsedRealtime()-began)
                        model.ui.value.media?.let {sample.put("final_catalog",catalog(it))}
                    } catch(error:TimeoutCancellationException) {
                        sample.put("outcome","timeout").put("error",error.javaClass.simpleName)
                        scenario.onActivity {model.cancelAnalysis()}
                    } finally {
                        sample.put("wall_ms",SystemClock.elapsedRealtime()-began).put("cpu_ms",Process.getElapsedCpuTime()-cpu)
                            .put("pss_after_kb",Debug.getPss()).put("traces",JSONArray(AnalysisTrace.snapshot()))
                        trials.put(sample)
                        File(folder,"report.json").writeText(report.toString(2))
                    }
                }
            } finally {scenario.onActivity {model.cancelAnalysis();model.input("")}}
        }
        assertEquals(4,trials.length())
    }

    private fun trace(token:String):JSONObject? {
        val traces=JSONArray(AnalysisTrace.snapshot())
        return (0 until traces.length()).map {traces.getJSONObject(it)}.firstOrNull {it.optString("id")==token}
    }

    private fun catalog(media:com.zdownloader.app.domain.Media)=JSONObject()
        .put("source",media.source).put("format_count",media.formats.size)
        .put("formats",JSONArray(media.formats.map {format->
            JSONObject().put("id",format.id).put("protocol",format.protocol).put("ext",format.ext)
                .put("width",format.width).put("height",format.height).put("fps",format.fps)
                .put("video_codec",format.videoCodec).put("audio_codec",format.audioCodec)
                .put("image",format.image).put("audio_only",format.streamOnly).put("bytes",format.bytes)
                .put("size_estimated",format.sizeEstimated).put("transport_valid",format.transportValid)
        }))
}
