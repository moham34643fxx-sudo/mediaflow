package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RecoveryTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
    @Test fun range416NeverPromotesUnknownOrHistoricalSize() = runBlocking {
        val dir=app.storage.temporary("range416")
        try {
            for(total in listOf(-1L,4L,8L)) {
                val output=File(dir,"$total.mp4")
                File(output.path+".part").writeBytes(byteArrayOf(1,2,3,4))
                File(output.path+".resume").writeText(JSONObject().put("validator","tag").put("formatId","fixture").put("total",total).toString())
                val client=OkHttpClient.Builder().addInterceptor {chain->Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(416).message("Range rejected").header("Content-Range","bytes */8").body(ResponseBody.create(null,"")).build()}.build()
                val format=Format("fixture","mp4",null,null,null,null,null,null,"https",JSONObject().put("url","https://example.com/range").toString())
                try {Transfer(app.extractor,client).stream(format,output,"range416",TransferControl()) {_,_->};fail("416 accepted")} catch(e:MediaException) {assertEquals(ErrorCode.DOWNLOAD_INTERRUPTED,e.code)}
                assertFalse(output.exists());assertFalse(File(output.path+".complete").exists());assertFalse(File(output.path+".part").exists())
            }
        } finally {app.storage.removeTemporary("range416")}
    }
    @Test fun ignoredRangeReplacesPartialRatherThanAppending() = runBlocking {
        val dir=app.storage.temporary("range200")
        try {
            val output=File(dir,"full.mp4")
            File(output.path+".part").writeBytes(byteArrayOf(9,9))
            File(output.path+".resume").writeText(JSONObject().put("validator","tag").put("formatId","fixture").put("total",4).toString())
            val expected=byteArrayOf(1,2,3,4)
            val client=OkHttpClient.Builder().addInterceptor {chain->Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Full response").body(ResponseBody.create(null,expected)).build()}.build()
            val format=Format("fixture","mp4",null,null,null,null,null,null,"https",JSONObject().put("url","https://example.com/range").toString())
            Transfer(app.extractor,client).stream(format,output,"range200",TransferControl()) {_,_->}
            assertArrayEquals(expected,output.readBytes())
        } finally {app.storage.removeTemporary("range200")}
    }
    @Test fun resumedRangeRequiresCurrentMatchingLengthAndKeepsValidatedPrefix(): Unit = runBlocking {
        val id = "resume-identity-${java.util.UUID.randomUUID()}"
        val dir = app.storage.temporary(id)
        try {
            for (serverTotal in listOf(8L, 10L)) {
                val output = File(dir, "$serverTotal.mp4")
                File(output.path + ".part").writeBytes(byteArrayOf(1, 2, 3, 4))
                File(output.path + ".resume").writeText(JSONObject().put("validator", "\"current\"").put("formatId", "fixture").put("total", 8).toString())
                var requestedPrefix = false
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    requestedPrefix = chain.request().header("Range")?.startsWith("bytes=4-") == true &&
                        chain.request().header("If-Range") == "\"current\""
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("Partial content")
                        .header("Content-Range", "bytes 4-7/$serverTotal").header("ETag", "\"current\"")
                        .body(ResponseBody.create(null, byteArrayOf(5, 6, 7, 8))).build()
                }.build()
                val format = Format("fixture", "mp4", null, null, null, null, null, null, "https", JSONObject().put("url", "https://example.com/resume").toString())
                if (serverTotal == 8L) {
                    Transfer(app.extractor, client).stream(format, output, id, TransferControl()) { _, _ -> }
                    assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), output.readBytes())
                    assertTrue(File(output.path + ".complete").isFile)
                } else {
                    try { Transfer(app.extractor, client).stream(format, output, id, TransferControl()) { _, _ -> }; fail("Changed representation length accepted") }
                    catch (error: MediaException) { assertEquals(ErrorCode.DOWNLOAD_INTERRUPTED, error.code) }
                    assertFalse(output.exists()); assertFalse(File(output.path + ".complete").exists())
                }
                assertTrue("Resume must preserve the validated prefix and send If-Range", requestedPrefix)
            }
        } finally { app.storage.removeTemporary(id) }
    }
    @Test fun parentCoroutineCancellationCancelsPendingHttpCall() = runBlocking {
        val dir=app.storage.temporary("parent-cancel")
        try {
            val client=OkHttpClient.Builder().addInterceptor { chain -> while(!chain.call().isCanceled()) Thread.sleep(10);throw java.io.IOException("Cancelled controlled request") }.build()
            val format=Format("fixture","mp4",null,null,null,null,null,null,"https",JSONObject().put("url","https://example.com/blocked-fixture").toString())
            val control=TransferControl()
            val start=android.os.SystemClock.elapsedRealtime()
            try { withTimeout(200) { Transfer(app.extractor,client).stream(format,File(dir,"never.mp4"),"parent-cancel",control) { _,_-> } };fail("Timeout did not cancel") } catch(_:TimeoutCancellationException) { }
            assertTrue(android.os.SystemClock.elapsedRealtime()-start<2000)
            assertNull(control.call)
            assertFalse(File(dir,"never.mp4").exists())
        } finally { app.storage.removeTemporary("parent-cancel") }
    }
    @Test fun userStopCancelsBothConcurrentTrackCalls() = runBlocking {
        val dir=app.storage.temporary("dual-track-cancel")
        try {
            val started=CountDownLatch(2)
            val calls=java.util.concurrent.CopyOnWriteArrayList<Call>()
            val client=OkHttpClient.Builder().dispatcher(Dispatcher().apply {maxRequestsPerHost=2}).addInterceptor { chain ->
                calls += chain.call();started.countDown()
                while(!chain.call().isCanceled()) Thread.sleep(10)
                throw java.io.IOException("Cancelled controlled request")
            }.build()
            val format=Format("fixture","mp4",null,null,null,null,null,null,"https",JSONObject().put("url","https://example.com/blocked-fixture").toString())
            val control=TransferControl()
            val jobs=listOf("video","audio").map { name -> async(Dispatchers.IO) { Transfer(app.extractor,client).stream(format,File(dir,"$name.mp4"),name,control) { _,_-> } } }
            assertTrue(started.await(2,TimeUnit.SECONDS))
            control.stop()
            jobs.forEach { job -> try {withTimeout(2000) {job.await()};fail("Stopped track completed")} catch(_:CancellationException) { } }
            assertEquals(2,calls.size)
            assertTrue(calls.all {it.isCanceled()})
            assertNull(control.call)
        } finally {app.storage.removeTemporary("dual-track-cancel")}
    }
    @Test fun controlledHttpErrorsAreClassifiedWithoutFakeProgress() = runBlocking {
        val directory=app.storage.temporary("http-errors")
        try {
            listOf(401 to ErrorCode.AUTHENTICATION_REQUIRED,403 to ErrorCode.HTTP_FORBIDDEN,404 to ErrorCode.VIDEO_NOT_FOUND,410 to ErrorCode.MEDIA_URL_EXPIRED,429 to ErrorCode.RATE_LIMITED,503 to ErrorCode.SERVER_UNAVAILABLE).forEach { (status,code) ->
                val client=OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Controlled failure fixture").header("Retry-After","60").body(ResponseBody.create(null,"" )).build() }.build()
                val format=Format("fixture","mp4",null,null,null,null,null,null,"https",JSONObject().put("url","https://example.com/failure-fixture").toString())
                var progressCalled=false
                try { Transfer(app.extractor,client).stream(format,File(directory,"error-$status.mp4"),"error-$status",TransferControl()) { _,_->progressCalled=true }; fail("HTTP $status accepted") }
                catch(e: MediaException) { assertEquals(code,e.code); if(status==429) assertEquals(60000L,e.retryAfterMillis) }
                assertFalse(progressCalled)
                assertFalse(File(directory,"error-$status.mp4").exists())
            }
        } finally { app.storage.removeTemporary("http-errors") }
    }
    @Test fun cancellationBeforeRegistrationAndProcessingStartsNoWork() = runBlocking {
        val dir=app.storage.temporary("cancel-before")
        try {
            val control=TransferControl().apply { stop() }
            val format=Format("fixture","mp4",null,null,"h264","aac",null,null,"https","{}")
            try { app.transfer.stream(format,File(dir,"never.mp4"),"cancel-before",control) { _,_->fail("Cancelled transfer emitted progress") }; fail("Transfer did not cancel") } catch(_: CancellationException) { }
            try { Processor().process(File(dir,"missing.mp4"),null,File(dir,"output.mp4"),Selection(format),control); fail("Processing did not cancel") } catch(_: CancellationException) { }
            assertFalse(File(dir,"output.mp4").exists())
            val module=app.extractor.python().getModule("zextractor")
            module.callAttr("cancel","python-cancel-before")
            val result=JSONObject(module.callAttr("analyze","https://www.w3schools.com/html/mov_bbb.mp4","python-cancel-before").toString())
            assertEquals("DOWNLOAD_INTERRUPTED",result.getString("error"))
        } finally { app.storage.removeTemporary("cancel-before") }
    }
    @Test fun oldSignedUrlRefreshKeepsFormatAndCompletesRealMedia(): Unit = runBlocking {
        val media=app.extractor.analyze("https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd")
        val original=Selection.video(media,media.formats.filter { it.video && it.height != null }.minBy { it.height!! })
        assertNotNull(original.primary.height)
        val expired=original.copy(primary=original.primary.copy(payload=JSONObject(original.primary.payload).put("url","https://www.w3schools.com/expired-media-fixture.mp4").toString()))
        val id=java.util.UUID.randomUUID().toString()
        app.repository.dao.insert(com.zdownloader.app.data.DownloadEntity(id,media.url,"Refresh test",media.source,null,media.duration,expired.json(),"refresh-test.mp4",null,id,createdAt=System.currentTimeMillis()-11*60_000))
        try {
            withTimeout(120_000) { app.coordinator.run() }
            val row=app.repository.dao.get(id)!!
            assertEquals(row.error,"COMPLETED",row.status)
            val fresh=Selection.parse(row.selection)
            assertEquals(original.primary.id,fresh.primary.id)
            assertEquals(original.primary.height,fresh.primary.height)
            assertEquals(original.output,fresh.output)
            app.storage.deleteFile(row)
        } finally { app.repository.cancel(id); app.repository.remove(id) }
    }
    @Test fun cancellingActiveFfmpegSessionStopsProcessing() = runBlocking {
        val dir=app.storage.temporary("processing-cancel")
        try {
            val input=File(dir,"tone.wav")
            // PCM silence is a valid processing fixture; the min build omits lavfi.
            val dataSize=120*48000*2
            val header=java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray());putInt(dataSize+36);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(48000);putInt(96000);putShort(2);putShort(16);put("data".toByteArray());putInt(dataSize)
            }.array()
            input.outputStream().use { sink -> sink.write(header); val second=ByteArray(96000);repeat(120) { sink.write(second) } }
            val format=Format("tone","wav",null,null,"none","pcm_s16le",input.length(),null,"https","{}")
            val control=TransferControl()
            val result=async(Dispatchers.IO) { try { Processor().process(input,null,File(dir,"output.m4a"),Selection(format,audioOnly=true,output="m4a",convertAudio=true),control); false } catch(_:CancellationException) { true } }
            withTimeout(5000) { while(control.session==null && result.isActive) delay(1) }
            assertNotNull("Conversion finished before cancellation could be tested",control.session)
            control.stop()
            assertTrue(withTimeout(10000) { result.await() })
        } finally { app.storage.removeTemporary("processing-cancel") }
    }
    @Test fun preUpgradeDirectSelectionRefreshPreservesDownload(): Unit = runBlocking {
        val url="https://www.w3schools.com/html/mov_bbb.mp4"
        val old=Media.parse(JSONObject(app.extractor.python().getModule("zextractor").callAttr("analyze",url,"legacy-direct-test").toString()),url)
        val selection=Selection.video(old,old.formats.first { it.video })
        val id=java.util.UUID.randomUUID().toString()
        app.repository.dao.insert(com.zdownloader.app.data.DownloadEntity(id,url,"Pre-upgrade direct request",old.source,null,old.duration,selection.json(),"legacy-direct.mp4",null,id,createdAt=System.currentTimeMillis()-11*60_000))
        try {
            withTimeout(120000) { app.coordinator.run() }
            val row=app.repository.dao.get(id)!!
            assertEquals(row.error,"COMPLETED",row.status)
            assertEquals(selection.primary.id,Selection.parse(row.selection).primary.id)
            app.storage.deleteFile(row)
        } finally { app.repository.cancel(id);app.repository.remove(id) }
    }
}
