package com.zdownloader.app

import android.content.*
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Real WorkManager/coordinator/processing/publication, deliberately slow controlled transport. */
class BackgroundStabilityTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ZApplication
    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText()}
    @Test fun twoMinuteScheduledTransferSurvivesUiRemovalAndScreenOff():Unit=runBlocking {
        val settings=SettingsRepository(app);val old=settings.current()
        assertFalse("Do not interrupt unrelated active downloads",app.repository.dao.observe().first().any {it.status in setOf("DOWNLOADING","PROCESSING","MERGING","SAVING")})
        val token=UUID.randomUUID().toString();val url="https://mediaflow-long-fixture.invalid/$token.mp4"
        val evidence=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
        fun phase(value:String) {File(evidence,"background-phase.json").writeText(JSONObject().put("phase",value).put("time_ms",System.currentTimeMillis()).toString(2))}
        val source=File(app.cacheDir,"long-$token.mp4")
        instrument.context.assets.open("status-original.mp4").use {input->source.outputStream().use {input.copyTo(it)}}
        val probe=com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(source.path).mediaInformation!!
        val video=probe.streams.first {it.type=="video"};val audio=probe.streams.firstOrNull {it.type=="audio"}
        val python=app.extractor.python();val fixture=python.getModule("types").callAttr("ModuleType","background_fixture")
        fixture.put("source_path",source.path);fixture.put("fixture_url",url)
        val script="""
import json, time, zextractor as z
original = z.download_stream
def slow(fmt_json, path, request_id, callback, fragment_concurrency=0):
    if json.loads(fmt_json).get('url') != fixture_url:
        return original(fmt_json, path, request_id, callback, fragment_concurrency)
    event = z.register_request(request_id)
    event.deadline = float('inf')
    event.metadata = False
    try:
        data = open(source_path, 'rb').read()
        step = (len(data)+31)//32
        written = 0
        with open(path, 'wb') as output:
            for offset in range(0, len(data), step):
                z.check(event)
                chunk = data[offset:offset+step]
                output.write(chunk); output.flush(); written += len(chunk)
                callback.confidence('EXACT'); callback.progress(str(written), str(len(data)))
                event.wait(4)
            z.check(event)
        return json.dumps({'ok': True})
    finally:
        z._current.event = None
        with z._lock: z._requests.pop(request_id, None)
z.download_stream = slow
""".trimIndent()
        python.builtins.callAttr("exec",script,fixture.get("__dict__"))
        var id:String?=null
        var activity:ShareReceiverActivity?=null
        val mainLaunches=java.util.concurrent.atomic.AtomicInteger()
        val lifecycle=androidx.test.runner.lifecycle.ActivityLifecycleCallback {created,stage->
            if(created is MainActivity&&stage==androidx.test.runner.lifecycle.Stage.CREATED) mainLaunches.incrementAndGet()
        }
        instrument.runOnMainSync {androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle)}
        val started=android.os.SystemClock.elapsedRealtime()
        try {
            settings.text("destination","")
            // This sustained-sleep probe measures execution, not an OEM's
            // optional alert wake policy. Other real-media cases verify alerts.
            settings.boolean("completion_notifications",false)
            activity=instrument.startActivitySync(Intent(app,ShareReceiverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ShareReceiverActivity
            val format=Format.parse(JSONObject().put("format_id","long-fixture").put("url",url).put("ext","mp4").put("protocol","m3u8_native")
                .put("width",video.width).put("height",video.height).put("vcodec",video.allProperties.optString("codec_name"))
                .put("acodec",audio?.allProperties?.optString("codec_name") ?: "none").put("filesize",source.length()))
            val media=Media(url,token,"Background stability fixture","QA fixture",null,null,probe.duration?.toDoubleOrNull(),listOf(format))
            id=app.repository.create(media,Selection.video(media,format),"MediaFlow background QA $token",plan=DownloadPlan())
            app.plannedDownloads.enqueue(id)
            phase("scheduled")
            withTimeout(45000) {while((app.repository.dao.get(id)!!.bytes)<=0) delay(100)}
            phase("transfer_started")
            instrument.runOnMainSync {activity?.finishAndRemoveTask()}
            shell("input keyevent KEYCODE_SLEEP");delay(30000)
            val during=app.repository.dao.get(id)!!
            assertTrue(during.bytes>0&&during.status !in setOf("FAILED","CANCELLED"))
            File(evidence,"background-during-sleep.json").writeText(JSONObject().put("bytes",during.bytes).put("status",during.status)
                .put("interactive",app.getSystemService(android.os.PowerManager::class.java).isInteractive).put("time",System.currentTimeMillis()).toString(2))
            phase("screen_off_finished")
            val folder=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
            phase("awaiting_completion")
            withTimeout(200000) {while(app.repository.dao.get(id)!!.status !in setOf("COMPLETED","FAILED","CANCELLED")) delay(250)}
            val completed=app.repository.dao.get(id)!!
            val interactive=app.getSystemService(android.os.PowerManager::class.java).isInteractive
            File(evidence,"background-terminal-row.json").writeText(JSONObject().put("status",completed.status).put("error",completed.error).put("bytes",completed.bytes)
                .put("interactive",interactive).put("finished_at",completed.finishedAt).put("observed_at",System.currentTimeMillis()).toString(2))
            assertEquals(completed.error,"COMPLETED",completed.status)
            assertTrue(app.mediaFiles.accessible(completed.uri))
            val elapsed=android.os.SystemClock.elapsedRealtime()-started
            assertTrue("The controlled transfer really ran for at least two minutes",elapsed>=120000)
            assertFalse("Completion must occur with the screen still off",interactive)
            assertEquals(0,mainLaunches.get())
            File(folder,"background-long-download.json").writeText(JSONObject().put("elapsed_ms",elapsed).put("screen_off_until_completion",true).put("ui_task_removed",true).put("main_activity_created",mainLaunches.get())
                .put("status",completed.status).put("output_bytes",completed.outputSize).put("optional_completion_alert_disabled_for_sleep_probe",true)
                .put("scope","Physical WorkManager/coordinator/native processing/publication with controlled slow Python transport; not a live-network throughput test").toString(2))
        } finally {
            phase("cleanup")
            shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
            id?.let {key->app.repository.cancel(key);app.coordinator.stopAndAwait(key);val row=app.repository.dao.get(key);if(row?.status=="COMPLETED") app.storage.deleteFile(row);app.plannedDownloads.cancel(key);app.repository.remove(key)}
            python.builtins.callAttr("exec","z.download_stream = original",fixture.get("__dict__"))
            settings.text("destination",old.destination.orEmpty())
            settings.boolean("completion_notifications",old.completionNotifications)
            instrument.runOnMainSync {activity?.finish()}
            instrument.runOnMainSync {androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle)}
            source.delete()
            phase("finished")
        }
    }
}
