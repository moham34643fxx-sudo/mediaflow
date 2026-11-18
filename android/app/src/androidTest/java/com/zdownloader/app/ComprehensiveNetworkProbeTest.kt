package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.engine.Transfer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class ComprehensiveNetworkProbeTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ZApplication
    private fun save(name:String,report:JSONObject) {
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
        File(folder,"$name.json").writeText(report.toString(2))
    }
    /** Diagnostic outcome, not an assertion that the external file is available. */
    @Test fun captureFailedPortraitSourceHttpResponse() {
        val url="https://videos.pexels.com/video-files/5995101/5995101-hd_1080_1920_25fps.mp4"
        val report=JSONObject().put("url",url).put("scope","Read-only bounded HTTP probe on the same Android device and production network policy")
        val client=Transfer.networkClient.newBuilder().callTimeout(20,TimeUnit.SECONDS).build()
        try {
            client.newCall(Request.Builder().url(url).header("Range","bytes=0-1023").build()).execute().use {response->
                report.put("status",response.code).put("content_type",response.header("Content-Type"))
                val bytes=ByteArray(1024);val read=response.body?.byteStream()?.read(bytes) ?: -1
                report.put("bytes_read",read)
                if(!response.isSuccessful&&read>0) report.put("error_body_prefix",String(bytes,0,read,Charsets.UTF_8).take(300))
            }
        } catch(error:Exception) {report.put("exception",error.javaClass.simpleName)}
        save("portrait-http-probe",report)
        assertTrue(report.has("status")||report.has("exception"))
    }
    @Test fun existingDatabaseIntegrityAndStateSnapshot():Unit=runBlocking {
        val database=app.database.openHelper.readableDatabase
        val integrity=database.query("PRAGMA integrity_check").use {it.moveToFirst();it.getString(0)}
        val foreignKeys=database.query("PRAGMA foreign_key_check").use {it.count}
        val rows=app.repository.dao.observe().first()
        val states=JSONObject();rows.groupingBy {it.status}.eachCount().forEach {(key,value)->states.put(key,value)}
        val errors=JSONObject();rows.filter {it.status=="FAILED"}.groupingBy {it.error ?: "UNKNOWN"}.eachCount().forEach {(key,value)->errors.put(key,value)}
        save("existing-state-snapshot",JSONObject().put("integrity",integrity).put("foreign_key_violations",foreignKeys)
            .put("download_rows",rows.size).put("states",states).put("historical_failed_errors",errors)
            .put("historical_rows_retried",false).put("coordinator_running",app.coordinator.running)
            .put("sdk",android.os.Build.VERSION.SDK_INT).put("model",android.os.Build.MODEL)
            .put("heap_used_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()))
        assertEquals("ok",integrity);assertEquals(0,foreignKeys)
    }
}
