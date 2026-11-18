package com.zdownloader.app

import android.content.Context
import android.content.Intent
import android.os.Parcelable
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.engine.Processor
import com.zdownloader.app.status.StatusImportActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class SecurityBoundaryTest {
    @Test fun malformedParcelableShareDoesNotCrashExportedReceiver() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("status_saver_sources",Context.MODE_PRIVATE)
        val old=prefs.all.toMap()
        try {
            prefs.edit().clear().putString("whatsapp","content://com.zdownloader.app.test.statusdocs/tree/denied-fixture").commit()
            ActivityScenario.launch<StatusImportActivity>(Intent(context,StatusImportActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM,android.os.Bundle())).use {scenario->
                scenario.onActivity {activity->
                    val launchIntent=activity.intent
                    try {
                    for(action in listOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE)) {
                        val intent=Intent(context,StatusImportActivity::class.java).setAction(action).setType("image/png")
                        if(action==Intent.ACTION_SEND) intent.putExtra(Intent.EXTRA_STREAM,Intent())
                        else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM,arrayListOf<Parcelable>(Intent(),android.os.Bundle()))
                        InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity,intent)
                    }
                    } finally {
                        // ActivityScenario filters lifecycle callbacks by the
                        // launching Intent. Restore its action after exercising
                        // onNewIntent, otherwise real destroy events are ignored.
                        activity.intent=launchIntent
                    }
                }
            }
        } finally {
            val edit=prefs.edit().clear();old.forEach {(k,v)->if(v is String) edit.putString(k,v)};edit.commit()
        }
    }
    @Test fun craftedLocalMediaMustNotReachLoopbackThroughNativeProbing()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"audit-native-${java.util.UUID.randomUUID()}").apply {mkdirs()}
        val requests=AtomicInteger()
        val server=ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))
        val thread=Thread {
            try {while(!server.isClosed) server.accept().use {socket->
                requests.incrementAndGet();socket.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }} catch(_:Exception) {}
        }.apply {isDaemon=true;start()}
        try {
            val file=File(root,"not-really-mp4.mp4").apply {writeText("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nhttp://127.0.0.1:${server.localPort}/fixture.ts\n#EXT-X-ENDLIST\n")}
            runCatching {Processor().validate(file,false,false)}
            val out=File(context.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
            File(out,"native-loopback-boundary.json").writeText(JSONObject().put("scope","Synthetic local playlist and own loopback listener only")
                .put("received_requests",requests.get()).toString(2))
            assertEquals("Media inspection must not bypass the network URL policy",0,requests.get())
        } finally {server.close();thread.join(1000);root.deleteRecursively()}
        Unit
    }
}
