@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.Format
import com.zdownloader.app.engine.NativeWork
import com.zdownloader.app.engine.Transfer
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real urllib socket read under the production native-transfer cancellation and ownership path. */
class NativeReadCancellationAuditTest {
    @Test fun cancellingABlockedNativeBodyReleasesItsActualFileLease()=runBlocking<Unit> {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        check(!app.coordinator.running)
        val root=File(app.cacheDir,"native-read-cancel-${UUID.randomUUID()}").apply {check(mkdirs())}
        val python=app.extractor.python()
        val fixture=python.getModule("types").callAttr("ModuleType","native_read_cancellation_audit")
        val control=TransferControl()
        var task:Job?=null
        var installed=false
        var settled=false
        try {
            python.builtins.callAttr("exec","""
import socket, threading, http.client
from urllib.parse import urlsplit
import zextractor as z
from yt_dlp.networking._urllib import UrllibResponseAdapter
original_engine = z.yt_dlp.YoutubeDL
release = threading.Event()
reading = threading.Event()
server_errors = []
client, server = socket.socketpair()
client.settimeout(5)
server.settimeout(5)
def serve():
    try:
        server.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: 4096\r\nConnection: close\r\n\r\nX')
        release.wait(8)
        server.sendall(b'X' * 4095)
    except OSError as error:
        # A reset/broken pipe after client cancellation is the expected server outcome.
        server_errors.append(type(error).__name__)
    finally:
        server.close()
server_thread = threading.Thread(target=serve, daemon=True)
server_thread.start()
class ObservedResponse(UrllibResponseAdapter):
    def read(self, amount=None):
        reading.set()
        return super().read(amount)
class FixtureEngine(original_engine):
    def urlopen(self, request):
        url = request if isinstance(request, str) else request.url
        if urlsplit(url).hostname != 'native-cancel-audit.example':
            raise AssertionError('Unexpected request in local cancellation fixture')
        raw = http.client.HTTPResponse(client)
        raw.url = url
        raw.begin()
        return ObservedResponse(raw)
z.yt_dlp.YoutubeDL = FixtureEngine
""".trimIndent(),fixture.get("__dict__"))
            installed=true
            val url="https://native-cancel-audit.example/fragment"
            val format=Format.parse(JSONObject().put("format_id","native-cancel-audit").put("url",url)
                .put("ext","m4a").put("vcodec","none").put("acodec","aac").put("protocol","http_dash_segments")
                .put("fragments",JSONArray().put(JSONObject().put("url",url))))
            task=launch(Dispatchers.IO) {
                Transfer(app.extractor).stream(format,File(root,"audio.m4a"),"native-cancel-${UUID.randomUUID()}",control) {_,_->}
            }
            withTimeout(10_000) {while(!fixture.get("reading")!!.callAttr("is_set").toBoolean()) delay(10)}
            assertTrue("The native read must own the file before cancellation",NativeWork.busy(root))
            val started=android.os.SystemClock.elapsedRealtime()
            task.cancelAndJoin()
            settled=withTimeoutOrNull(1000) {NativeWork.awaitIdle(root);true} ?: false
            val report=JSONObject().put("native_lease_released",settled)
                .put("cancel_to_release_ms",android.os.SystemClock.elapsedRealtime()-started)
                .put("scope","Actual Transfer/native worker/urllib body over a local socketpair; not remote TLS/header cancellation")
            val run=InstrumentationRegistry.getArguments().getString("evidenceRun","manual")!!
            require(run.matches(Regex("[a-zA-Z0-9-]{1,100}")))
            val evidence=File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
            File(evidence,"native-read-cancellation.json").writeText(report.toString(2))
            assertTrue("Coroutine cancellation left the native socket read and file lease alive",settled)
            assertFalse("Cancelled transfer must not receive a completion marker",File(root,"audio.m4a.complete").exists())
        } finally {
            withContext(NonCancellable) {
                control.stop();task?.cancelAndJoin()
                if(installed) {
                    fixture.get("release")!!.callAttr("set")
                    withTimeout(15_000) {NativeWork.awaitIdle(root)}
                    settled=true
                    python.builtins.callAttr("exec","""
server_thread.join(5)
assert not server_thread.is_alive(), 'Fixture server did not settle'
client.close()
z.yt_dlp.YoutubeDL = original_engine
""".trimIndent(),fixture.get("__dict__"))
                }
                if(settled||!installed) root.deleteRecursively()
            }
        }
    }
}
