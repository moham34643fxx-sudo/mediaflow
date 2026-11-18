@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.Format
import com.zdownloader.app.engine.SlideshowTransfer
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.*
import okhttp3.*
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class SlideshowCancellationAuditTest {
    @Test fun parentCancellationClosesAnImageBodyWithoutWaitingForSocketTimeout()=runBlocking<Unit> {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"slideshow-cancel-${UUID.randomUUID()}").apply {check(mkdirs())}
        val reading=AtomicBoolean();val bodyClosed=AtomicBoolean()
        var observed:Call?=null
        val client=OkHttpClient.Builder().addInterceptor {chain->
            observed=chain.call()
            val body=object:ResponseBody() {
                private val buffered=object:Source {
                    override fun timeout()=Timeout.NONE
                    override fun read(sink:Buffer,byteCount:Long):Long {
                        reading.set(true)
                        val deadline=android.os.SystemClock.elapsedRealtime()+3000
                        while(!chain.call().isCanceled()&&android.os.SystemClock.elapsedRealtime()<deadline) Thread.sleep(5)
                        throw IOException("Controlled blocked image response")
                    }
                    override fun close() {bodyClosed.set(true)}
                }.buffer()
                override fun contentType():MediaType?=null
                override fun contentLength()=-1L
                override fun source()=buffered
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Blocked image").body(body).build()
        }.build()
        val plan=JSONObject().put("version",1).put("timing","equal_generated").put("width",24).put("height",32)
            .put("duration",3).put("post_id","123").put("images",JSONArray().put(JSONObject()
                .put("url","https://slideshow-audit.example/image.jpg").put("width",24).put("height",32)))
        val format=Format.parse(JSONObject().put("format_id","mediaflow-slideshow").put("ext","mp4")
            .put("url","https://slideshow-audit.example/image.jpg").put("protocol","mediaflow_slideshow")
            .put("vcodec","h264").put("acodec","none").put("width",24).put("height",32).put("mediaflow_slideshow",plan))
        val control=TransferControl()
        try {
            val started=android.os.SystemClock.elapsedRealtime()
            try {
                withTimeout(300) {withContext(Dispatchers.IO) {
                    SlideshowTransfer.download(format,File(root,"output.mp4"),client,control,{_,_->}) {_,_->fail("Cancelled image reached rendering")}
                }}
                fail("Parent cancellation did not propagate")
            } catch(_:TimeoutCancellationException) {}
            assertTrue("The test must cancel a body read, not only the HTTP headers",reading.get())
            assertTrue("Cancellation waited for a blocked body",android.os.SystemClock.elapsedRealtime()-started<1500)
            assertTrue(observed?.isCanceled()==true)
            assertTrue(bodyClosed.get());assertNull(control.call)
            assertFalse(File(root,"output.mp4").exists())
        } finally {control.stop();root.deleteRecursively()}
    }
}
