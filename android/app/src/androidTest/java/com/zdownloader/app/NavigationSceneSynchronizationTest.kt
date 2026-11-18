@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.playback.MediaPlaybackService
import com.zdownloader.app.playback.PlaybackRuntime
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.domain.*
import com.zdownloader.app.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.Collections

/** Regression: the target back-stack changed chrome before the outgoing page left. */
class NavigationSceneSynchronizationTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test fun settingsAndItsOriginKeepTheirOwnChromeThroughoutEnterAndBack()=runBlocking<Unit> {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        assertFalse("Do not disturb existing playback",PlaybackRuntime.active)
        check(app.repository.downloads.first().none {it.status !in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")})
        val preferences=app.getSharedPreferences("mediaflow_playback",Context.MODE_PRIVATE)
        val previous=preferences.all.toMap()
        val home=File(app.filesDir,"navigation-scenes/${UUID.randomUUID()}").apply {check(mkdirs())}
        val snapshot=JSONObject()
        for((key,value) in previous) snapshot.put(key,JSONObject().put("type",value?.javaClass?.simpleName)
            .put("value",if(value is Set<*>) JSONArray(value.toList()) else value))
        File(home,"playback-before.json").writeText(snapshot.toString())
        val report=JSONObject().put("passed",false)
        val samples=JSONArray();report.put("samples",samples)
        var scenario:ActivityScenario<MainActivity>?=null
        val durations=Collections.synchronizedList(mutableListOf<Long>())
        val metricsThread=HandlerThread("navigation-scene-metrics").apply {start()}
        val metrics=Window.OnFrameMetricsAvailableListener {_,frame,_->
            if(frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME)==0L) durations+=frame.getMetric(FrameMetrics.TOTAL_DURATION)
        }
        try {
            scenario=ActivityScenario.launch(MainActivity::class.java)
            var controller:NavHostController?=null
            scenario.onActivity {activity->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.window.addOnFrameMetricsAvailableListener(metrics,Handler(metricsThread.looper))
                val model=ViewModelProvider(activity)[ZViewModel::class.java]
                activity.setContent {val nav=rememberMediaFlowController();SideEffect {controller=nav};ZRoot(model,nav)}
            }
            compose.waitUntil(15_000) {controller!=null&&compose.onAllNodesWithTag("nav_home").fetchSemanticsNodes().isNotEmpty()}
            compose.waitForIdle();compose.mainClock.autoAdvance=false
            for(origin in PrimaryDestination.entries) {
                compose.runOnUiThread {requireNotNull(controller).selectPrimary(origin)}
                compose.mainClock.advanceTimeBy(400)
                val pageTag=when(origin) {PrimaryDestination.HOME->"home_brand_logo";PrimaryDestination.DOWNLOADS->"downloads_grid";PrimaryDestination.LIBRARY->"standalone_library"}
                for(direction in listOf("enter","back")) {
                    compose.runOnUiThread {if(direction=="enter") requireNotNull(controller).openSecondary(SecondaryDestination.SETTINGS) else requireNotNull(controller).closeSecondary()}
                    repeat(20) {frame->
                        compose.mainClock.advanceTimeByFrame()
                        val page=compose.onAllNodesWithTag(pageTag).fetchSemanticsNodes().isNotEmpty()
                        val bar=compose.onAllNodesWithTag("bottom_navigation").fetchSemanticsNodes().isNotEmpty()
                        val settings=compose.onAllNodesWithTag("settings_list").fetchSemanticsNodes().isNotEmpty()
                        samples.put(JSONObject().put("origin",origin.route).put("direction",direction).put("frame",frame)
                            .put("origin_present",page).put("bar_present",bar).put("settings_present",settings))
                        assertEquals("${origin.route} $direction frame $frame detached page from its navigation",page,bar)
                        assertTrue("Navigation produced an empty scene",page||settings)
                    }
                    if(direction=="enter") compose.onNodeWithTag("settings_list").assertIsDisplayed()
                    else compose.onNodeWithTag("nav_${origin.route}").assertIsSelected()
                }
            }
            compose.mainClock.autoAdvance=true
            val transfer=NavigationTransfer(app,home)
            try {
                transfer.start()
                val before=transfer.bytes()
                val latency=JSONArray();report.put("live_transfer_navigation",latency)
                for(origin in PrimaryDestination.entries) {
                    compose.runOnUiThread {requireNotNull(controller).selectPrimary(origin)}
                    compose.waitForIdle()
                    val started=SystemClock.elapsedRealtimeNanos()
                    if(origin==PrimaryDestination.HOME) compose.onNodeWithTag("open_settings").performClick()
                    else compose.runOnUiThread {requireNotNull(controller).openSecondary(SecondaryDestination.SETTINGS)}
                    compose.waitUntil(5_000) {compose.onAllNodesWithTag("settings_list").fetchSemanticsNodes().isNotEmpty()}
                    compose.waitForIdle()
                    assertFalse("Real transfer ended before navigation-under-load coverage",transfer.terminal())
                    latency.put(JSONObject().put("origin",origin.route).put("settings_settled_ms",(SystemClock.elapsedRealtimeNanos()-started)/1_000_000.0))
                    scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
                    compose.waitUntil(5_000) {compose.onAllNodesWithTag("nav_${origin.route}").fetchSemanticsNodes().isNotEmpty()}
                    compose.onNodeWithTag("nav_${origin.route}").assertIsSelected()
                }
                repeat(6) {
                    compose.runOnUiThread {
                        val nav=requireNotNull(controller)
                        PrimaryDestination.entries.forEach(nav::selectPrimary)
                        repeat(4) {nav.openSecondary(SecondaryDestination.SETTINGS)}
                    }
                    compose.waitForIdle()
                    scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
                    compose.waitForIdle()
                    compose.onNodeWithTag("nav_library").assertIsSelected()
                }
                assertTrue("The real transfer did not advance while navigation ran",transfer.bytes()>before)
                report.put("live_transfer_progress_advanced",true).put("rapid_navigation_and_android_back",true)
            } finally {transfer.stop()}
            report.put("passed",true)
        } finally {
            compose.mainClock.autoAdvance=true
            scenario?.onActivity {it.window.removeOnFrameMetricsAvailableListener(metrics)}
            scenario?.close()
            metricsThread.quitSafely();metricsThread.join(5_000)
            val ordered=synchronized(durations) {durations.sorted()}
            report.put("window_frames",ordered.size).put("frames_above_100ms",ordered.count {it>100_000_000})
                .put("p95_total_frame_ms",ordered.getOrNull(((ordered.size-1)*.95).toInt())?.div(1_000_000.0) ?: JSONObject.NULL)
                .put("timing_qualification","Instrumented debug build; semantics/clock probes add overhead. Wall times are settled-destination test latency, not production FPS or touch-to-photon latency.")
            instrument.runOnMainSync {app.playback.disconnect();app.stopService(Intent(app,MediaPlaybackService::class.java))}
            withTimeout(10_000) {while(PlaybackRuntime.active) delay(50)}
            val edit=preferences.edit().clear()
            for((key,value) in previous) when(value) {
                is String->edit.putString(key,value);is Long->edit.putLong(key,value);is Int->edit.putInt(key,value)
                is Boolean->edit.putBoolean(key,value);is Float->edit.putFloat(key,value)
                is Set<*>->edit.putStringSet(key,value.filterIsInstance<String>().toSet())
            }
            check(edit.commit());assertEquals(previous,preferences.all)
            report.put("preferences_restored",true)
            File(home,"result.json").writeText(report.toString(2))
            instrument.sendStatus(2,Bundle().apply {putString("navigation_scene_report",File(home,"result.json").path)})
        }
    }
}

/** A real scheduled queue job; only the external transport is a gated local fixture. */
private class NavigationTransfer(private val app:ZApplication,private val evidence:File) {
    private val token=UUID.randomUUID().toString()
    private val source=File(app.cacheDir,"navigation-transfer-$token.mp4")
    private var fixture:com.chaquo.python.PyObject?=null
    private var id:String?=null
    private var previous:com.zdownloader.app.data.Settings?=null

    suspend fun start()=withContext(Dispatchers.IO) {
        val instrument=InstrumentationRegistry.getInstrumentation()
        previous=SettingsRepository(app).current()
        File(evidence,"settings-before.json").writeText(JSONObject().put("destination",previous!!.destination ?: JSONObject.NULL)
            .put("completion_notifications",previous!!.completionNotifications).toString())
        instrument.context.assets.open("status-original.mp4").use {input->source.outputStream().use {input.copyTo(it)}}
        val probe=checkNotNull(com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(source.path).mediaInformation)
        val video=probe.streams.first {it.type=="video"};val audio=probe.streams.firstOrNull {it.type=="audio"}
        val url="https://navigation-fixture.invalid/$token.mp4"
        val python=app.extractor.python()
        val boundary=python.getModule("types").callAttr("ModuleType","navigation_fixture")
        fixture=boundary;boundary.put("source_path",source.path);boundary.put("fixture_url",url)
        python.builtins.callAttr("exec","""
import json, zextractor as z
original = z.download_stream
def gated(fmt_json, path, request_id, callback, fragment_concurrency=0):
    if json.loads(fmt_json).get('url') != fixture_url:
        return original(fmt_json, path, request_id, callback, fragment_concurrency)
    event = z.register_request(request_id)
    event.metadata = False
    try:
        data = open(source_path, 'rb').read()
        step = (len(data)+255)//256
        written = 0
        with open(path, 'wb') as output:
            for offset in range(0, len(data), step):
                z.check(event)
                chunk = data[offset:offset+step]
                output.write(chunk); output.flush(); written += len(chunk)
                callback.confidence('EXACT'); callback.progress(str(written), str(len(data)))
                event.wait(0.5)
        return json.dumps({'ok': True})
    finally:
        z._current.event = None
        with z._lock: z._requests.pop(request_id, None)
z.download_stream = gated
""".trimIndent(),boundary.get("__dict__"))
        val settings=SettingsRepository(app);settings.text("destination","");settings.boolean("completion_notifications",false)
        val format=Format.parse(JSONObject().put("format_id","navigation-transfer").put("url",url).put("ext","mp4").put("protocol","m3u8_native")
            .put("width",video.width).put("height",video.height).put("vcodec",video.allProperties.optString("codec_name"))
            .put("acodec",audio?.allProperties?.optString("codec_name") ?: "none").put("filesize",source.length()))
        val media=Media(url,token,"Navigation QA fixture","QA fixture",null,null,probe.duration?.toDoubleOrNull(),listOf(format))
        val key=app.repository.create(media,Selection.video(media,format),"Navigation QA $token",plan=DownloadPlan());id=key;app.plannedDownloads.enqueue(key)
        withTimeout(45_000) {while(bytes()<=0) delay(100)}
    }

    suspend fun bytes():Long=id?.let {app.repository.dao.get(it)?.bytes} ?: 0
    suspend fun terminal():Boolean=id?.let {app.repository.dao.get(it)?.status in setOf("COMPLETED","FAILED","CANCELLED")} ?: true

    suspend fun stop()=withContext(Dispatchers.IO) {
        val failures=mutableListOf<Throwable>()
        suspend fun step(block:suspend ()->Unit) {try {block()} catch(error:Throwable) {failures+=error}}
        step {id?.let {key->
            app.repository.cancel(key);app.coordinator.stopAndAwait(key)
            val row=app.repository.dao.get(key)
            if(row?.status=="COMPLETED") app.storage.deleteFile(row)
            app.plannedDownloads.cancel(key);app.repository.remove(key)
        }}
        var transportRestored=false
        step {fixture?.let {app.extractor.python().builtins.callAttr("exec","z.download_stream = original",it.get("__dict__"))};transportRestored=true}
        var settingsRestored=false
        step {previous?.let {old->SettingsRepository(app).apply {
            text("destination",old.destination.orEmpty());boolean("completion_notifications",old.completionNotifications)
            assertEquals(old,current());settingsRestored=true
        }}}
        if(failures.isEmpty()) source.delete()
        File(evidence,"transfer-cleanup.json").writeText(JSONObject().put("owned_queue_row_removed",id==null||app.repository.dao.get(id!!)==null)
            .put("transport_restored",transportRestored).put("settings_restored",settingsRestored).put("cleanup_errors",failures.size).toString())
        if(failures.isNotEmpty()) throw AssertionError("Navigation transport cleanup failed; snapshots retained").apply {failures.forEach(::addSuppressed)}
    }
}
