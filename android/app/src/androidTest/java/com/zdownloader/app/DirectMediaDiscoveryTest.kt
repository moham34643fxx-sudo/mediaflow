package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.engine.*
import com.zdownloader.app.domain.ErrorCode
import com.zdownloader.app.domain.MediaException
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real valid MP4 bytes, controlled HTTP latency; not a WAN performance benchmark. */
class DirectMediaDiscoveryTest {
    @Test fun delayedTailMoovStaysVerifiedWithoutPythonOrARedundantRange():Unit=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        val directory=File(app.cacheDir,"direct-discovery-${UUID.randomUUID()}").apply {mkdirs()}
        val source=File(directory,"original.mp4");val tail=File(directory,"tail.mp4")
        var tested:Extractor?=null
        try {
            instrument.context.assets.open("status-original.mp4").use {input->source.outputStream().use {input.copyTo(it)}}
            assertTrue(ReturnCode.isSuccess(FFmpegKit.executeWithArguments(arrayOf("-y","-v","error","-i",source.path,"-map","0","-c","copy",tail.path)).returnCode))
            val info=FFprobeKit.getMediaInformation(tail.path).mediaInformation
            val video=info.streams.first {it.type=="video"}
            val bytes=tail.readBytes();assertTrue(bytes.size>65536)
            val calls=AtomicInteger()
            val client=OkHttpClient.Builder().addInterceptor {chain->
                assertEquals("https://mediaflow-discovery.example/tail.mp4",chain.request().url.toString())
                calls.incrementAndGet();Thread.sleep(1100)
                val range=Regex("bytes=(\\d+)-(\\d+)").matchEntire(chain.request().header("Range")!!)!!
                val start=range.groupValues[1].toInt();val end=minOf(range.groupValues[2].toInt(),bytes.lastIndex)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("Controlled range")
                    .header("Content-Type","video/mp4").header("Content-Range","bytes $start-$end/${bytes.size}")
                    .body(ResponseBody.create(null,bytes.copyOfRange(start,end+1))).build()
            }.build()
            var connectionIssue:ErrorCode?=ErrorCode.NETWORK_UNAVAILABLE
            tested=Extractor(app,DirectMediaResolver(client)) {connectionIssue}
            try {
                tested!!.analyze("https://mediaflow-discovery.example/tail.mp4",forceRefresh=true,enrichOptional=false)
                fail("An unavailable connection must reject discovery before HTTP")
            } catch(error:MediaException) {assertEquals(ErrorCode.NETWORK_UNAVAILABLE,error.code)}
            assertEquals(0,calls.get())
            connectionIssue=null
            val id=UUID.randomUUID().toString();val start=android.os.SystemClock.elapsedRealtime()
            val media=withTimeout(12000) {tested!!.analyze("https://mediaflow-discovery.example/tail.mp4",id,forceRefresh=true,enrichOptional=false)}
            val elapsed=android.os.SystemClock.elapsedRealtime()-start
            assertTrue("Inspection must outlast the obsolete two-second deadline",elapsed>=2200)
            assertEquals("The moov prefix must eliminate a third request",2,calls.get())
            val format=media.formats.single();assertEquals("direct",format.id)
            assertEquals(video.width?.toInt(),format.width);assertEquals(video.height?.toInt(),format.height)
            assertEquals(bytes.size.toLong(),format.bytes)
            assertEquals(info.streams.any {it.type=="audio"},format.audio)
            val traces=JSONArray(AnalysisTrace.snapshot())
            val trace=(0 until traces.length()).map {traces.getJSONObject(it)}.single {it.getString("id")==id}
            assertFalse("Reliable direct metadata must not fall through to Python",trace.has("python_runtime_start"))
            val folder=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
            File(folder,"delayed-direct-discovery.json").writeText(JSONObject().put("state","passed").put("elapsed_ms",elapsed).put("requests",calls.get())
                .put("width",format.width).put("height",format.height).put("exact_bytes",format.bytes).put("audio_verified",true).put("has_audio",format.audio).put("python_fallback",false)
                .put("scope","Valid remuxed MP4 with tail moov; controlled 1100ms per HTTP range, not a live-network speed claim").toString(2))
        } finally {
            for(owner in listOfNotNull(tested)) owner.javaClass.declaredFields.forEach {field->field.isAccessible=true;val value=field.get(owner)
                if(value is java.util.concurrent.ThreadPoolExecutor) value.shutdownNow()
                if(value is CoroutineScope) value.cancel()}
            directory.deleteRecursively()
        }
    }
}
