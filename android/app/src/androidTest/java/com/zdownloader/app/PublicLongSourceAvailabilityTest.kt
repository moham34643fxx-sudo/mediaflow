package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.MediaException
import com.zdownloader.app.domain.videoOptions
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bounded environment preflight; does not bypass the production network policy. */
class PublicLongSourceAvailabilityTest {
    @Test fun probesPublicLongSourcesThroughProductionAnalyzer(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as ZApplication
        val run = requireNotNull(InstrumentationRegistry.getArguments().getString("evidenceRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val directory = File(requireNotNull(app.getExternalFilesDir(null)), "source-availability/$run")
        check(directory.mkdirs()) { "refusing to overwrite source availability evidence" }
        val results = JSONArray()
        var reachable = false
        for (url in listOf("https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd",
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.mpd")) {
            val started = android.os.SystemClock.elapsedRealtime()
            val row = JSONObject().put("source", url)
            try {
                val media = withTimeout(25_000L) { app.extractor.analyze(url, forceRefresh = true, enrichOptional = false) }
                val options = media.videoOptions().filter { it.ext == "mp4" }
                val available = options.isNotEmpty()
                row.put("available", available).put("duration_seconds", media.duration)
                    .put("mp4_heights", JSONArray(options.map { it.height }))
                reachable = reachable || available
            } catch (error: Exception) {
                row.put("available", false).put("failure_type", error.javaClass.simpleName)
                if (error is MediaException) row.put("error_code", error.code.name)
            } finally {
                row.put("analysis_ms", android.os.SystemClock.elapsedRealtime() - started)
                results.put(row)
                File(directory, "result.json").writeText(JSONObject().put("results", results)
                    .put("scope", "production analysis only; not successful full downloads").toString(2))
            }
        }
        assertTrue("no public long source was reachable through the application", reachable)
    }
}
