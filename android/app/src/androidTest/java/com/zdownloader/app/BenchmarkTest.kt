package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import android.os.Debug
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.engine.TransferControl
import com.zdownloader.app.engine.Processor
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BenchmarkTest {
    @Test fun measuresActualEngineAndTransferWithoutClaimingSpeedup() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val report=JSONObject().put("python_already_started",com.chaquo.python.Python.isStarted())
        val init=SystemClock.elapsedRealtime();app.extractor.python();report.put("python_start_ms",SystemClock.elapsedRealtime()-init)
        val start=SystemClock.elapsedRealtime();val cpu=android.os.Process.getElapsedCpuTime()
        val extraction=SystemClock.elapsedRealtime()
        val media=app.extractor.analyze("https://www.w3schools.com/html/mov_bbb.mp4")
        report.put("analysis_ms",SystemClock.elapsedRealtime()-extraction)
        val dir=app.storage.temporary("benchmark")
        try {
            val source=File(dir,"source.mp4")
            val transfer=SystemClock.elapsedRealtime()
            app.transfer.stream(media.formats.first { it.video },source,"benchmark",TransferControl()) { _,_-> }
            val elapsed=SystemClock.elapsedRealtime()-transfer
            report.put("transfer_ms",elapsed).put("transferred_bytes",source.length()).put("bytes_per_second",source.length()*1000/maxOf(1,elapsed))
            val processing=SystemClock.elapsedRealtime()
            Processor().process(source,null,File(dir,"output.mp4"),Selection.video(media,media.formats.first { it.video }),TransferControl())
            report.put("processing_ms",SystemClock.elapsedRealtime()-processing)
            val memory=Debug.MemoryInfo();Debug.getMemoryInfo(memory)
            report.put("pss_kib",memory.totalPss).put("elapsed_ms",SystemClock.elapsedRealtime()-start).put("process_cpu_ms",android.os.Process.getElapsedCpuTime()-cpu)
            android.util.Log.i("ZBenchmark",report.toString())
            File(app.getExternalFilesDir(null),"engine-benchmark.json").writeText(report.toString(2))
            assertTrue(source.length()>0)
        } finally { app.storage.removeTemporary("benchmark") }
    }
}
