package com.zdownloader.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Process
import android.system.ErrnoException
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.net.InetAddress

/** Diagnostic evidence only: completion is not a claim that DNS or extraction succeeded. */
class AppConnectivityBoundaryTest {
    private fun causes(error:Throwable):JSONArray {
        val facts=JSONArray()
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable,Boolean>())
        var next:Throwable?=error
        while(next!=null && facts.length()<8 && seen.add(next)) {
            val currentError=next
            facts.put(JSONObject().put("class",currentError.javaClass.simpleName)
                .apply {if(currentError is ErrnoException) put("errno",currentError.errno)})
            next=currentError.cause
        }
        return facts
    }

    @Test fun compareDefaultAndActiveNetworkDnsWithoutChangingRouting() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val active=manager.activeNetwork
        val report=JSONObject().put("uid",Process.myUid()).put("applicationUid",context.applicationInfo.uid)
            .put("internetPermission",context.checkSelfPermission(Manifest.permission.INTERNET)==PackageManager.PERMISSION_GRANTED)
            .put("activeNetworkHandle",active?.networkHandle)
            .put("processBoundNetworkHandle",manager.boundNetworkForProcess?.networkHandle)
            .put("routingChanged",false)
        val rows=JSONArray()
        for(host in listOf("www.tiktok.com","www.facebook.com","www.instagram.com","www.w3schools.com","storage.googleapis.com","api.github.com")) {
            val row=JSONObject().put("host",host)
            var started=android.os.SystemClock.elapsedRealtime()
            try {row.put("defaultAddressCount",InetAddress.getAllByName(host).size)}
            catch(error:Exception) {row.put("defaultError",causes(error))}
            row.put("defaultMs",android.os.SystemClock.elapsedRealtime()-started)
            started=android.os.SystemClock.elapsedRealtime()
            try {row.put("activeAddressCount",active?.getAllByName(host)?.size ?: 0)}
            catch(error:Exception) {row.put("activeError",causes(error))}
            row.put("activeMs",android.os.SystemClock.elapsedRealtime()-started)
            rows.put(row)
        }
        report.put("sources",rows)
        File(context.getExternalFilesDir(null),"urgent-dns-boundary.json").writeText(report.toString(2))
    }

    @Test fun capturePythonResolverAndOfficialUpdateFeedWithoutChangingState() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val report=JSONObject().put("routingChanged",false).put("updateStateChanged",false)
        val file=File(app.getExternalFilesDir(null),"urgent-network-layers.json")
        val python=app.extractor.python()
        val globals=python.getModule("builtins").callAttr("dict")
        python.getModule("builtins").callAttr("exec","""
import socket, json, time
rows = []
for host in ('www.w3schools.com', 'storage.googleapis.com', 'api.github.com'):
    row = {'host': host}
    started = time.monotonic()
    try:
        row['addressCount'] = len(socket.getaddrinfo(host, 443))
    except Exception as error:
        row['errorClass'] = type(error).__name__
        row['errno'] = getattr(error, 'errno', None)
    row['elapsedMs'] = round((time.monotonic() - started) * 1000)
    rows.append(row)
result_json = json.dumps(rows)
""".trimIndent(),globals)
        report.put("pythonDns",JSONArray(globals.callAttr("__getitem__","result_json").toString()))
        file.writeText(report.toString(2))
        val client=com.zdownloader.app.updates.UpdateUrlPolicy.client().newBuilder()
            .callTimeout(30,java.util.concurrent.TimeUnit.SECONDS).build()
        val started=android.os.SystemClock.elapsedRealtime()
        try {
            client.newCall(okhttp3.Request.Builder().url(BuildConfig.UPDATE_FEED_URL)
                .header("Accept","application/vnd.github+json").header("User-Agent","MediaFlow-network-diagnostic").build())
                .execute().use { response ->
                    report.put("feedStatus",response.code)
                    report.put("bodyAvailable",response.body!=null)
                    if(response.isSuccessful) {
                        val source=response.body?.source()
                        if(source!=null && !source.request(1_048_577)) {
                            val releases=JSONArray(source.readUtf8())
                            report.put("releaseCount",releases.length())
                            report.put("stablePublishedCount",(0 until releases.length()).count {
                                val release=releases.getJSONObject(it)
                                !release.optBoolean("draft") && !release.optBoolean("prerelease")
                            })
                        } else report.put("bodyLimitExceeded",true)
                    }
                }
        } catch(error:Exception) {report.put("feedError",causes(error))}
        report.put("feedMs",android.os.SystemClock.elapsedRealtime()-started)
        file.writeText(report.toString(2))
    }
}
