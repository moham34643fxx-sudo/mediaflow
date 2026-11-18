package com.zdownloader.app

import android.content.pm.ApplicationInfo
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.engine.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PerformanceAuditTest {
    @Test fun recordsMeasuredRuntimeAndBoundedWorkWithoutSpeedupClaims()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        assertEquals(0,app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val report=JSONObject().put("scope","Instrumented candidate; warm Activity launches and real authorized public-media analysis/transfer, not cold OS startup or laboratory throughput")
        val start=SystemClock.elapsedRealtime();val cpu=android.os.Process.getElapsedCpuTime()
        val launches=JSONArray()
        repeat(3) {
            val before=SystemClock.elapsedRealtime()
            ActivityScenario.launch(MainActivity::class.java).use {launches.put(SystemClock.elapsedRealtime()-before)}
        }
        report.put("warm_activity_launch_ms",launches)
        val runtime=SystemClock.elapsedRealtime();val wasStarted=com.chaquo.python.Python.isStarted()
        app.extractor.python();report.put("python_was_started",wasStarted).put("python_access_ms",SystemClock.elapsedRealtime()-runtime)
        report.put("quickjs_version",JsBridge.version()).put("lame_version",LameEncoder.version())
        for(name in listOf("getFFmpegVersion","getVersion")) report.put(name,runCatching {com.arthenica.ffmpegkit.FFmpegKitConfig::class.java.getMethod(name).invoke(null).toString()}.getOrDefault("unavailable"))
        val analyses=JSONArray();var media:com.zdownloader.app.domain.Media?=null
        repeat(3) {
            val before=SystemClock.elapsedRealtime()
            media=app.extractor.analyze("https://www.w3schools.com/html/mov_bbb.mp4",forceRefresh=true,enrichOptional=false)
            analyses.put(SystemClock.elapsedRealtime()-before)
        }
        report.put("uncached_direct_analysis_ms",analyses)
        val id="audit-benchmark-${java.util.UUID.randomUUID()}";val dir=app.storage.temporary(id)
        try {
            val file=File(dir,"source.mp4");val before=SystemClock.elapsedRealtime()
            app.transfer.stream(media!!.formats.first {it.video},file,id,TransferControl()) {_,_->}
            val elapsed=SystemClock.elapsedRealtime()-before
            report.put("transfer_ms",elapsed).put("transfer_bytes",file.length()).put("transfer_bytes_per_second",file.length()*1000/maxOf(1,elapsed))
            assertTrue(file.length()>0)
        } finally {app.storage.removeTemporary(id)}
        val memory=Debug.MemoryInfo();Debug.getMemoryInfo(memory)
        report.put("final_total_pss_kib",memory.totalPss).put("final_native_heap_allocated_bytes",Debug.getNativeHeapAllocatedSize())
            .put("observed_thread_count",Thread.getAllStackTraces().size).put("elapsed_ms",SystemClock.elapsedRealtime()-start)
            .put("process_cpu_ms",android.os.Process.getElapsedCpuTime()-cpu)
        val out=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
        File(out,"performance-audit.json").writeText(report.toString(2))
        Unit
    }
}
