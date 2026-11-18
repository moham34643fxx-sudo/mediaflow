package com.zdownloader.app

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.Format
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ProtocolPerformanceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication

    private fun download(format: Format, protocol: String, concurrency: Int, iteration: Int, dir: File): JSONObject {
        val output=File(dir,"$protocol-$concurrency-$iteration.${format.ext}")
        var firstByteMs:Long?=null
        var peakBps=0L
        var lastBytes=0L
        var lastSample=SystemClock.elapsedRealtime()
        val started=lastSample
        val cpuStarted=Process.getElapsedCpuTime()
        val callback=object:ProgressProxy {
            override fun progress(downloaded:String,total:String) {
                val bytes=downloaded.toLongOrNull() ?: return
                val now=SystemClock.elapsedRealtime()
                if(bytes>0&&firstByteMs==null) firstByteMs=now-started
                val elapsed=now-lastSample
                if(elapsed>=100) {
                    peakBps=maxOf(peakBps,(bytes-lastBytes).coerceAtLeast(0)*1000/maxOf(1,elapsed))
                    lastBytes=bytes;lastSample=now
                }
            }
        }
        val result=JSONObject(app.extractor.python().getModule("zextractor").callAttr("download_stream",format.payload,output.path,"perf-${UUID.randomUUID()}",callback,concurrency).toString())
        assertFalse(result.toString(),result.has("error"))
        val elapsed=SystemClock.elapsedRealtime()-started
        val cpuMs=Process.getElapsedCpuTime()-cpuStarted
        val pssKib=Debug.getPss()
        val bytes=output.length()
        assertTrue(bytes>0)
        val digest=MessageDigest.getInstance("SHA-256")
        output.inputStream().buffered().use { stream ->
            val buffer=ByteArray(8192)
            var read:Int
            while(stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer,0,read)
            }
        }
        val sha256=digest.digest().joinToString("") { "%02x".format(it) }
        output.delete();File(output.path+".part").delete();File(output.path+".ytdl").delete()
        return JSONObject().put("protocol",protocol).put("fragment_concurrency",concurrency).put("iteration",iteration)
            .put("bytes",bytes).put("sha256",sha256).put("elapsed_ms",elapsed).put("ttfb_ms",firstByteMs ?: JSONObject.NULL)
            .put("average_bps",bytes*1000/maxOf(1,elapsed)).put("peak_window_bps",peakBps)
            .put("cpu_ms",cpuMs).put("pss_kib",pssKib)
    }

    @Test fun fragmentConcurrencyMatrixOnSameDeviceAndSources()=runBlocking {
        val sources=listOf(
            "hls" to "https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8",
            "dash" to "https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd")
        val run=InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "manual"
        val evidence=File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
        var completed=false
        val rows=JSONArray()
        val dir=File(app.cacheDir,"protocol-performance-${UUID.randomUUID()}").apply {mkdirs()}
        try {
            for((protocol,url) in sources) {
                val media=app.extractor.analyze(url,forceRefresh=true)
                val format=media.formats.filter {it.video}.minBy {it.height ?: Int.MAX_VALUE}
                val protocolRows=mutableListOf<JSONObject>()
                repeat(3) {iteration ->
                    val order=when(iteration) {0->listOf(1,2,4,8,16,32);1->listOf(32,16,8,4,2,1);else->listOf(2,1,8,4,32,16)}
                    order.forEach {concurrency ->
                        val row=download(format,protocol,concurrency,iteration,dir)
                        rows.put(row)
                        protocolRows.add(row)
                    }
                }
                assertEquals(18,protocolRows.size)
                val expectedBytes=protocolRows.first().getLong("bytes")
                val expectedSha256=protocolRows.first().getString("sha256")
                protocolRows.forEach {row ->
                    assertEquals(expectedBytes,row.getLong("bytes"))
                    assertEquals(expectedSha256,row.getString("sha256"))
                }
            }
            completed=true
        } finally {
            try {
                File(evidence,"protocol-performance.json").writeText(
                    JSONObject().put("same_device_network_sources",true).put("completed",completed).put("runs",rows).toString(2))
            } finally {
                dir.deleteRecursively()
            }
        }
    }
}
