package com.zdownloader.app

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.engine.NetworkDiagnostics
import com.zdownloader.app.engine.Transfer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Records actual reachability separately from Android's validation bit.
 * This is a diagnostic probe: its successful execution is NOT website support.
 */
class NetworkEnvironmentProbeTest {
    @Test fun captureActualSystemDnsHttpsAndPythonReachability()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as ZApplication
        val manager=app.getSystemService(ConnectivityManager::class.java)
        fun snapshot():JSONObject {
            val caps=manager.getNetworkCapabilities(manager.activeNetwork)
            return JSONObject().put("available",caps!=null)
                .put("validated",caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true)
                .put("internet",caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true)
                .put("captive",caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)==true)
                .put("vpn",caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true)
                .put("wifi",caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true)
                .put("appPreflightCode",NetworkDiagnostics.connectionIssue()?.name ?: "OK")
        }
        val report=JSONObject().put("run",InstrumentationRegistry.getArguments().getString("evidenceRun"))
            .put("startedAt",System.currentTimeMillis()).put("before",snapshot())
        val sources=JSONArray()
        val client=Transfer.networkClient.newBuilder().callTimeout(25,TimeUnit.SECONDS).build()
        for((host,url) in listOf("www.w3schools.com" to "https://www.w3schools.com/html/mov_bbb.mp4","storage.googleapis.com" to "https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd")) {
            val result=JSONObject().put("host",host)
            try {result.put("systemDnsAddressCount",InetAddress.getAllByName(host).size)}
            catch(e:Exception) {result.put("systemDnsError",NetworkDiagnostics.classify(e,"dns").name)}
            try {
                client.newCall(Request.Builder().url(url).header("Range","bytes=0-65535").build()).execute().use {response->
                    result.put("httpsStatus",response.code)
                    val buffer=ByteArray(4096)
                    result.put("firstReadBytes",response.body?.byteStream()?.read(buffer) ?: -1)
                }
            } catch(e:Exception) {result.put("httpsError",NetworkDiagnostics.classify(e,"body").name).put("exceptionType",e.javaClass.simpleName)}
            val id="environment-${java.util.UUID.randomUUID()}"
            try {
                val python=withTimeout(95000) {app.extractor.pythonCall("analyze",url,id)}
                result.put("pythonError",python.optString("error","OK")).put("pythonStage",python.optString("stage"))
                    .put("pythonFormats",python.optJSONArray("formats")?.length() ?: 0)
            } catch(e:Exception) {app.extractor.cancel(id);result.put("pythonException",e.javaClass.simpleName)}
            sources.put(result)
        }
        report.put("sources",sources).put("after",snapshot()).put("finishedAt",System.currentTimeMillis())
        val file=File(app.getExternalFilesDir(null),"network-environment-probe.json")
        file.writeText(report.toString(2))
        assertTrue(file.isFile)
    }
}
