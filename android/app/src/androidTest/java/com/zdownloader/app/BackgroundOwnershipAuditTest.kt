@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.NativeWork
import com.zdownloader.app.playback.PlaybackRuntime
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real scheduled worker and native downloader; only HTTP body delivery is paced locally. */
class BackgroundOwnershipAuditTest {
    @Test fun scheduledNativeTransferSurvivesShareTaskRemovalWithoutChangingSettings()=runBlocking<Unit> {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as ZApplication
        check(!app.coordinator.running)
        assertEquals(0,app.repository.dao.remaining())
        assertFalse("Do not interrupt active playback",PlaybackRuntime.active)
        check(!app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {"Phone must be normally unlocked before the share-task background probe"}
        val settings=SettingsRepository(app)
        val before=settings.current()
        val id=UUID.randomUUID().toString()
        val source=File(app.cacheDir,"background-native-$id.mp4")
        instrumentation.context.assets.open("status-original.mp4").use {input->source.outputStream().use {input.copyTo(it)}}
        val probe=requireNotNull(FFprobeKit.getMediaInformation(source.path).mediaInformation)
        val video=probe.streams.first {it.type=="video"}
        val audio=probe.streams.firstOrNull {it.type=="audio"}
        val url="https://background-native-audit.example/$id"
        val python=app.extractor.python()
        val fixture=python.getModule("types").callAttr("ModuleType","background_native_ownership_audit")
        fixture.put("source_path",source.path);fixture.put("fixture_url",url)
        val mainLaunches=AtomicInteger()
        val lifecycle=ActivityLifecycleCallback {activity,stage->if(activity is MainActivity&&stage==Stage.CREATED) mainLaunches.incrementAndGet()}
        var activity:ShareReceiverActivity?=null
        var installed=false
        var observer=false
        var inserted=false
        val started=android.os.SystemClock.elapsedRealtime()
        try {
            python.builtins.callAttr("exec","""
import io, threading
import zextractor as z
from yt_dlp.networking.common import Response
original_engine = z.yt_dlp.YoutubeDL
body_closed = threading.Event()
class PacedBody(io.BytesIO):
    def __init__(self, payload):
        super().__init__(payload)
        self.step = (len(payload) + 31) // 32
    def read(self, amount=-1):
        payload = super().read(min(amount if amount is not None and amount >= 0 else self.step, self.step))
        if body_closed.wait(4 * len(payload) / self.step): raise OSError('Test response was closed')
        return payload
    def close(self):
        body_closed.set()
        super().close()
class FixtureEngine(original_engine):
    def urlopen(self, request):
        url = request if isinstance(request, str) else request.url
        if url != fixture_url: return super().urlopen(request)
        with open(source_path, 'rb') as source:
            payload = source.read()
        assert len(payload) < 1024 * 1024
        return Response(PacedBody(payload), url, {'Content-Length': str(len(payload))}, 200)
z.yt_dlp.YoutubeDL = FixtureEngine
""".trimIndent(),fixture.get("__dict__"))
            installed=true
            instrumentation.runOnMainSync {ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle)}
            observer=true
            activity=instrumentation.startActivitySync(Intent(app,ShareReceiverActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ShareReceiverActivity
            val format=Format.parse(JSONObject().put("format_id","background-native-audit").put("url",url)
                .put("ext","mp4").put("protocol","http_dash_segments").put("width",video.width).put("height",video.height)
                .put("vcodec",video.allProperties.optString("codec_name"))
                .put("acodec",audio?.allProperties?.optString("codec_name") ?: "none")
                .put("filesize",source.length()).put("fragments",JSONArray().put(JSONObject().put("url",url))))
            val media=Media(url,id,"Background native audit","audit",null,null,probe.duration?.toDoubleOrNull(),listOf(format))
            val selection=Selection.video(media,format)
            app.repository.dao.insert(DownloadEntity(id,url,media.title,"audit",null,media.duration,selection.json(),
                "MediaFlow-background-audit-$id.mp4",null,id,status="SCHEDULED"))
            inserted=true
            app.plannedDownloads.enqueue(id)
            withTimeout(45_000) {while(app.repository.dao.get(id)!!.bytes<=0) delay(100)}
            val beforeRemoval=app.repository.dao.get(id)!!.bytes
            instrumentation.runOnMainSync {activity?.finishAndRemoveTask()}
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP")).use {it.readBytes()}
            delay(30_000)
            val during=app.repository.dao.get(id)!!
            assertTrue("Download stopped with the share task",during.bytes>beforeRemoval)
            assertFalse(app.getSystemService(android.os.PowerManager::class.java).isInteractive)
            withTimeout(200_000) {while(app.repository.dao.get(id)!!.status !in setOf("COMPLETED","FAILED","CANCELLED")) delay(250)}
            val completed=app.repository.dao.get(id)!!
            assertEquals(completed.error,"COMPLETED",completed.status)
            assertTrue(app.mediaFiles.accessible(completed.uri))
            assertEquals(0,mainLaunches.get())
            val elapsed=android.os.SystemClock.elapsedRealtime()-started
            assertTrue("The native transfer must actually span two minutes",elapsed>=120_000)
            assertTrue("User settings changed",before==settings.current())
            assertTrue("The native HTTP body was not closed",fixture.get("body_closed")!!.callAttr("is_set").toBoolean())
            val report=JSONObject().put("passed",true).put("elapsed_ms",elapsed).put("progress_after_task_removal",true)
                .put("screen_noninteractive_during_transfer",true).put("main_activity_launches",mainLaunches.get())
                .put("settings_unchanged",true).put("completion_notification_policy_unchanged",true)
                .put("interactive_at_completion",app.getSystemService(android.os.PowerManager::class.java).isInteractive)
                .put("scope","Real planned worker/coordinator/native downloader/processing/MediaStore in an instrumented process; HTTP delivery paced locally, not cold process-death or Internet performance proof")
            val run=InstrumentationRegistry.getArguments().getString("evidenceRun","manual")!!
            require(run.matches(Regex("[a-zA-Z0-9-]{1,100}")))
            val evidence=File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
            File(evidence,"background-native-ownership.json").writeText(report.toString(2))
        } finally {withContext(NonCancellable) {
            if(installed) fixture.get("body_closed")!!.callAttr("set")
            if(inserted) {
                app.repository.cancel(id);app.coordinator.stopAndAwait(id)
                withTimeout(15_000) {NativeWork.awaitIdle(app.storage.temporary(id))}
                app.plannedDownloads.cancel(id)
                val row=app.repository.dao.get(id)
                if(row?.status=="COMPLETED") app.storage.deleteFile(row)
                app.repository.remove(id)
                check(app.repository.dao.get(id)==null) {"Test-owned history row was not removed"}
                app.storage.removeTemporary(id)
            }
            if(installed) python.builtins.callAttr("exec","z.yt_dlp.YoutubeDL = original_engine",fixture.get("__dict__"))
            instrumentation.runOnMainSync {
                activity?.finish()
                if(observer) ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle)
            }
            source.delete()
        }}
    }
}
