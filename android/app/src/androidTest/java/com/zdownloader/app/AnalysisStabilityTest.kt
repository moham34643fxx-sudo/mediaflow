package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.engine.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AnalysisStabilityTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
    private val folder get()=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
    @Test fun controlledPythonCancellationReleasesTheWorkerAndSubsequentAnalysisSucceeds()=runBlocking {
        val extractor=Extractor(app)
        val wasStarted=com.chaquo.python.Python.isStarted()
        val python=extractor.python()
        val fixture=python.getModule("types").callAttr("ModuleType","stability_fixture")
        val script="""
import threading, zextractor as z
started = threading.Event()
released = threading.Event()
original = z.AnalysisYoutubeDL.extract_info
def controlled(self, url, *args, **kwargs):
    if 'mediaflow-controlled-stall' not in url:
        return original(self, url, *args, **kwargs)
    started.set()
    try:
        while True:
            z.check(z._current.event)
            z._current.event.wait(0.05)
    finally:
        released.set()
z.AnalysisYoutubeDL.extract_info = controlled
""".trimIndent()
        python.builtins.callAttr("exec",script,fixture.get("__dict__"))
        try {
            repeat(3) {index->
                fixture.get("started")!!.callAttr("clear");fixture.get("released")!!.callAttr("clear")
                val task=async {extractor.analyze("https://example.com/mediaflow-controlled-stall-$index",forceExtractor=true,enrichOptional=false)}
                withTimeout(15000) {while(!fixture.get("started")!!.callAttr("is_set").toBoolean()) delay(50)}
                val start=android.os.SystemClock.elapsedRealtime()
                task.cancelAndJoin()
                withTimeout(5000) {while(!fixture.get("released")!!.callAttr("is_set").toBoolean()) delay(50)}
                assertTrue(android.os.SystemClock.elapsedRealtime()-start<5000)
            }
        } finally {
            python.builtins.callAttr("exec","z.AnalysisYoutubeDL.extract_info = original",fixture.get("__dict__"))
            // This test owns its isolated Extractor, never the application's singleton.
            extractor.javaClass.declaredFields.forEach {field->
                field.isAccessible=true
                val value=field.get(extractor)
                if(value is java.util.concurrent.ThreadPoolExecutor) value.shutdownNow()
                if(value is CoroutineScope) value.cancel()
            }
            File(folder,"analysis-cancellation-traces.json").writeText(AnalysisTrace.snapshot())
        }
        File(folder,"analysis-cancellation.json").writeText(JSONObject().put("cycles",3).put("cooperative_python_worker_released",true).put("python_was_started",wasStarted).put("scope","Controlled production Python entrypoint with a cooperative blocked extractor; no network/server claim").toString(2))
    }
    @Test fun repeatedOriginalUrlCategoryCompletesWarmAndUncachedWithoutLoadingLeaks()=runBlocking {
        val samples=JSONArray()
        repeat(4) {index->
            val id="stability-direct-$index-${java.util.UUID.randomUUID()}"
            val start=android.os.SystemClock.elapsedRealtime()
            val media=app.extractor.analyze("https://www.w3schools.com/html/mov_bbb.mp4?mediaflow-sequential-regression=${java.util.UUID.randomUUID()}",id,forceRefresh=true,enrichOptional=false)
            assertTrue(media.formats.isNotEmpty())
            samples.put(JSONObject().put("id",id).put("ms",android.os.SystemClock.elapsedRealtime()-start))
        }
        File(folder,"analysis-original-category.json").writeText(JSONObject().put("samples",samples).put("trace",JSONArray(AnalysisTrace.snapshot())).put("original_411_root_cause_proven",false).toString(2))
    }
}
