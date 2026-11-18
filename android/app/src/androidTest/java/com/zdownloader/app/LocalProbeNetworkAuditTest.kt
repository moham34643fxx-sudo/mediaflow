package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.MediaException
import com.zdownloader.app.engine.Processor
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Locally downloaded bytes must not let metadata probing open a network URL. */
class LocalProbeNetworkAuditTest {
    @Test fun localValidationDoesNotFetchPlaylistReferencedNetworkResources()=runBlocking<Unit> {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        check(!app.coordinator.running)
        val root=File(app.cacheDir,"local-probe-network-${UUID.randomUUID()}").apply {check(mkdirs())}
        val requests=AtomicInteger()
        val server=ServerSocket(0,4,InetAddress.getByName("127.0.0.1")).apply {soTimeout=250}
        val executor=Executors.newSingleThreadExecutor()
        val acceptor=executor.submit {
            while(!server.isClosed) {
                try {
                    server.accept().use {socket->
                        requests.incrementAndGet()
                        socket.getOutputStream().apply {write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray());flush()}
                    }
                } catch(_:SocketTimeoutException) { /* Bounded accept loop, not a device-job poll. */ }
                catch(error:java.net.SocketException) {if(!server.isClosed) throw error}
            }
        }
        try {
            // HLS demuxers may follow nested URLs even when the caller supplies a local pathname.
            val manifest=File(root,"downloaded.mp4")
            manifest.writeText("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:1.0,\nhttp://127.0.0.1:${server.localPort}/segment.ts\n#EXT-X-ENDLIST\n")
            try {
                Processor().validate(manifest,false,true,expectedContainer="mp4")
                fail("A manifest masquerading as final MP4 must not validate")
            } catch(_:MediaException) { /* Invalid local output is expected to be rejected. */ }
            val report=JSONObject().put("referenced_network_requests",requests.get())
                .put("scope","Actual Processor.validate and bundled FFprobe on a local hostile playlist; only loopback fixture is contacted")
            val run=InstrumentationRegistry.getArguments().getString("evidenceRun","manual")!!
            require(run.matches(Regex("[a-zA-Z0-9-]{1,100}")))
            val evidence=File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
            File(evidence,"local-probe-network.json").writeText(report.toString(2))
            assertEquals("Metadata probing bypassed the application network boundary",0,requests.get())
        } finally {
            server.close();executor.shutdown()
            check(executor.awaitTermination(5,TimeUnit.SECONDS)) {"Loopback probe fixture did not settle"}
            acceptor.get(1,TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }
}
