package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.engine.NativeWork
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Bounded network measurement only: never resumes or deletes saved user jobs. */
class AffectedSourceTransferProbeTest {
    @Test fun measureWhetherAffectedAudioAdvancesBeyondTheInitialBytes():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val directory=app.storage.temporary("source-probe-${UUID.randomUUID()}")
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${args.getString("evidenceRun")}").apply {mkdirs()}
        val report=JSONObject().put("scope","At most 2 MiB or 150 seconds; network only, not publication or full-file verification")
        val samples=JSONArray();report.put("samples",samples)
        val control=TransferControl();val began=android.os.SystemClock.elapsedRealtime()
        val cpu=app.getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"MediaFlow:bounded-network-probe")
        cpu.acquire(240_000)
        var received=0L;var sampledAt=0L
        try {
            val media=app.extractor.analyze(checkNotNull(args.getString("probeSource")),forceExtractor=true,enrichOptional=false)
            val selected=Selection.audio(media,"mp3")
            report.put("protocol",selected.primary.protocol).put("duration_s",media.duration)
            try {
                withTimeout(150_000) {
                    app.transfer.stream(selected.primary,File(directory,"source.${selected.primary.ext}"),"bounded-source-probe",control) {bytes,_->
                        received=maxOf(received,bytes)
                        val now=android.os.SystemClock.elapsedRealtime()
                        if(now-sampledAt>=1000) {
                            sampledAt=now;samples.put(JSONObject().put("elapsed_ms",now-began).put("bytes",bytes))
                            File(folder,"source-transfer-probe.json").writeText(report.toString(2))
                        }
                        if(received>=2*1024*1024) control.stop()
                    }
                }
            } catch(error:CancellationException) {
                if(received<2*1024*1024) report.put("interrupted",error.javaClass.simpleName)
            }
            assertTrue("No 2 MiB forward progress within bounded observation; inspect raw samples",received>=2*1024*1024)
        } finally {
            control.stop()
            report.put("bytes",received).put("elapsed_ms",android.os.SystemClock.elapsedRealtime()-began)
                .put("advanced_beyond_initial_bytes",received>=2*1024*1024)
            File(folder,"source-transfer-probe.json").writeText(report.toString(2))
            if(withTimeoutOrNull(30_000) {NativeWork.awaitIdle(directory);true}==true) directory.deleteRecursively()
            else NativeWork.afterIdle(directory) {directory.deleteRecursively()}
            if(cpu.isHeld) cpu.release()
        }
    }
}
