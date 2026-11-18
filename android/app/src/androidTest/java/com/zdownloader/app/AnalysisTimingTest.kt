package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class AnalysisTimingTest {
    @Test fun repeatedPublicSourceAnalysis() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        // Installation/upgrade may coincide with a VPN default-route transition.
        val cm=app.getSystemService(android.net.ConnectivityManager::class.java)
        withTimeout(60000) {while(cm.activeNetwork?.let(cm::getNetworkCapabilities)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)!=true) delay(250)}
        val rows = JSONArray()
        for(url in listOf("https://www.w3schools.com/html/mov_bbb.mp4", "https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8", "https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd")) {
            val pythonBefore=com.chaquo.python.Python.isStarted()
            val samples = JSONArray()
            val readySamples = JSONArray()
            repeat(3) {
                val start = android.os.SystemClock.elapsedRealtime()
                var ready=0L
                val media = app.extractor.analyze(url,forceRefresh=true,onMetadata={ ready=android.os.SystemClock.elapsedRealtime()-start })
                samples.put(android.os.SystemClock.elapsedRealtime()-start)
                readySamples.put(ready)
                assertTrue(media.formats.isNotEmpty())
                android.util.Log.i("ZAnalysis", "$url formats=${media.formats.map { f -> "${f.height}:${f.videoCodec}:${f.audioCodec}:${f.bytes}" }}")
            }
            val cached=JSONArray()
            repeat(3) { val start=android.os.SystemClock.elapsedRealtime();app.extractor.analyze(url);cached.put(android.os.SystemClock.elapsedRealtime()-start) }
            rows.put(JSONObject().put("url",url).put("samples_ms",samples).put("preview_ready_samples_ms",readySamples).put("cache_samples_ms",cached).put("python_started_before",pythonBefore).put("python_started_after",com.chaquo.python.Python.isStarted()))
        }
        val encoders = FFmpegKit.executeWithArguments(arrayOf("-hide_banner","-encoders"))
        File(app.getExternalFilesDir(null),"analysis-timing.json").writeText(rows.toString(2))
        File(app.getExternalFilesDir(null),"ffmpeg-encoders.txt").writeText(encoders.allLogsAsString)
        android.util.Log.i("ZAnalysis",rows.toString())
        Unit
    }
}
