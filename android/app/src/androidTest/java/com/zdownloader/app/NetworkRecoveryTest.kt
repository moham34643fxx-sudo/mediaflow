package com.zdownloader.app

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Physical connectivity checks restore radio settings even when assertions fail. */
class NetworkRecoveryTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as ZApplication
    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText().trim()}
    private fun online():Boolean {
        val cm=app.getSystemService(ConnectivityManager::class.java)
        // Underlying Wi-Fi validation alone does not mean the app's VPN route is ready.
        val physical=cm.allNetworks.any {network->cm.getNetworkCapabilities(network)?.let {caps->
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)&&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        }==true}
        val current=cm.activeNetwork?.let(cm::getNetworkCapabilities)
        return physical&&current?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true&&current.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    @Test fun inspectCurrentRouteWithoutChangingSettings()=runBlocking {
        val cm=app.getSystemService(ConnectivityManager::class.java)
        fun capabilities(c:NetworkCapabilities?)=JSONObject().put("vpn",c?.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            .put("wifi",c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).put("cellular",c?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
            .put("validated",c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).put("internet",c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            .put("not_vpn",c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        val report=JSONObject().put("guard",NetworkDiagnostics.connectionIssue()?.name ?: "ready").put("active",capabilities(cm.getNetworkCapabilities(cm.activeNetwork)))
            .put("visible_networks",org.json.JSONArray(cm.allNetworks.map {capabilities(cm.getNetworkCapabilities(it))}))
        try {
            withContext(Dispatchers.IO) {
                val client=app.transfer.client.newBuilder().callTimeout(8,java.util.concurrent.TimeUnit.SECONDS).build()
                client.newCall(okhttp3.Request.Builder().url("https://storage.googleapis.com/shaka-demo-assets/angel-one/video_144p_108k_h264.mp4").header("Range","bytes=0-31").build()).execute().use {r->
                    report.put("probe_http_status",r.code).put("probe_prefix_bytes",r.body?.byteStream()?.read(ByteArray(32)))
                }
            }
        } catch(error:Exception) {report.put("probe_exception",error.javaClass.simpleName)}
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
        File(folder,"route-observation.json").writeText(report.toString(2))
    }
    @Test fun partialSurvivesPhysicalNetworkLossAndResumes() = runBlocking {
        val wifi=shell("settings get global wifi_on")!="0"
        val data=shell("settings get global mobile_data")!="0"
        val airplane=shell("settings get global airplane_mode_on")=="1"
        withTimeout(60000) {while(!online()) delay(250)}
        // This controlled test targets Range/resume, not yt-dlp discovery. Use
        // the known public static MP4 as a direct HTTP representation so an
        // extractor registry change cannot prevent the outage scenario from running.
        val source=Format.parse(JSONObject().put("format_id","network-outage-fixture")
            .put("url","https://storage.googleapis.com/shaka-demo-assets/angel-one/video_144p_108k_h264.mp4")
            .put("protocol","https").put("ext","mp4").put("vcodec","h264").put("acodec","none")
            .put("http_headers",JSONObject()))
        val temporaryId="network-outage-${java.util.UUID.randomUUID()}"
        val dir=app.storage.temporary(temporaryId)
        val file=File(dir,"resumed.mp4")
        try {
            val control=TransferControl()
            try {app.transfer.stream(source,file,"outage",control) {bytes,_->if(bytes>=65536) control.stop()};fail("Partial transfer was not paused")} catch(_:CancellationException) {}
            val partial=File(file.path+".part")
            val bytes=partial.length()
            assertTrue(bytes>0)
            shell("svc wifi disable");shell("svc data disable");shell("cmd connectivity airplane-mode enable")
            withTimeout(20000) {while(online()) delay(200)}
            assertNotNull("A VPN without a validated physical bearer is not a ready route",NetworkDiagnostics.connectionIssue())
            assertEquals(bytes,partial.length())
            if(!airplane) shell("cmd connectivity airplane-mode disable")
            if(wifi) shell("svc wifi enable")
            if(data) shell("svc data enable")
            withTimeout(60000) {while(!online()) delay(250)}
            withTimeout(60000) {
                while(true) {
                    while(!online()||NetworkDiagnostics.connectionIssue()!=null) delay(200)
                    try {app.transfer.stream(source,file,"outage-resumed",TransferControl()) {_,_->};break}
                    catch(error:MediaException) {
                        if(error.code !in setOf(ErrorCode.NETWORK_UNAVAILABLE,ErrorCode.NETWORK_UNVALIDATED)) throw error
                    }
                }
            }
            // The fixture's DASH representation is deliberately video-only.
            Processor().validate(file,false,requireAudio=false,expectedContainer="mp4")
            File(app.getExternalFilesDir(null),"obsidian-network-outage.json").writeText(JSONObject().put("partialBytes",bytes).put("finalBytes",file.length()).put("physicalOutageObserved",true).put("restored",true).toString(2))
        } finally {
            shell("cmd connectivity airplane-mode ${if(airplane) "enable" else "disable"}")
            shell("svc wifi ${if(wifi) "enable" else "disable"}")
            shell("svc data ${if(data) "enable" else "disable"}")
            if(!airplane&&(wifi||data)) withTimeout(60000) {while(!online()) delay(250)}
            app.storage.removeTemporary(temporaryId)
        }
    }
    @Test fun nonexistentHostnameIsDnsFailureInBothStacks() = runBlocking {
        val url="https://z-downloader-does-not-exist.invalid/media.mp4"
        val payload=JSONObject().put("url",url).put("ext","mp4").put("protocol","https").put("format_id","dns-test").toString()
        val format=Format("dns-test","mp4",null,null,"h264","aac",null,null,"https",payload)
        val dir=app.storage.temporary("dns-test")
        try {
            try {app.transfer.stream(format,File(dir,"kotlin.mp4"),"dns-test",TransferControl()) {_,_->};fail("Invalid hostname downloaded")}
            catch(e:MediaException) {assertEquals(ErrorCode.DNS_FAILED,e.code)}
            val callback=object:ProgressProxy {override fun progress(downloaded:String,total:String) {}}
            val result=JSONObject(app.extractor.python().getModule("zextractor").callAttr("download_stream",payload,File(dir,"python.mp4").path,"dns-python",callback).toString())
            assertEquals(result.toString(),"DNS_FAILED",result.getString("error"))
        } finally {app.storage.removeTemporary("dns-test")}
    }
}
