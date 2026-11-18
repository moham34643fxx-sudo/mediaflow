package com.zdownloader.app

import android.os.SystemClock
import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.arthenica.ffmpegkit.*
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.domain.PlaylistPolicy
import com.zdownloader.app.engine.AnalysisTrace
import com.zdownloader.app.ui.ZViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** One real UI/scheduler trial. Retains history, successful outputs and diagnostic evidence. */
class YoutubePlaylistStartupTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
    private val terminal=setOf("COMPLETED","FAILED","CANCELLED","PAUSED")
    private val byteBudget=64L*1024*1024

    @Test fun boundedAudioPlaylistUsesRealDownloadButtonAndSequentialPublication():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val source=requireNotNull(args.getString("playlistSource"))
        val expectedChildren=args.getString("expectedChildren","2").toInt().also {require(it in 1..2)}
        require(source.startsWith("https://youtube.com/playlist?")||source.startsWith("https://www.youtube.com/playlist?"))
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${args.getString("evidenceRun","youtube-startup")}").apply {mkdirs()}
        val report=JSONObject().put("source",source).put("matched_baseline_available",false)
            .put("max_children",expectedChildren).put("max_child_duration_s",660).put("byte_budget",byteBudget).put("automatic_live_retries_allowed",false)
        val before=app.repository.dao.observe().first()
        val settingsBefore=com.zdownloader.app.data.SettingsRepository(app).current()
        var batchId:String?=null
        var controller:com.zdownloader.app.ui.PlaylistController?=null
        var succeeded=false
        try {
            // The user explicitly authorized cancellation if needed; preserve inactive history.
            val unsafe=before.filter {it.status !in terminal&&it.scheduledAt<=System.currentTimeMillis()+600_000}
            for(row in unsafe) {app.repository.cancel(row.id);app.coordinator.stopAndAwait(row.id)}
            report.put("preexisting_cancelled_for_isolation",unsafe.size)
            check(app.repository.dao.observe().first().none {it.pendingUri!=null||it.status !in terminal&&it.scheduledAt<=System.currentTimeMillis()+600_000})
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                var model:ZViewModel?=null
                scenario.onActivity {activity->
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    model=ViewModelProvider(activity)[ZViewModel::class.java]
                    controller=model!!.playlists
                    report.put("discovery_requested_ms",SystemClock.elapsedRealtime())
                    controller!!.open(source,PlaylistPolicy(audio=true,output="mp3",bitrate=192))
                }
                compose.waitUntil(100_000) {controller!!.state.value.let {it.page!=null||it.error!=null}}
                val discovered=controller!!.state.value
                check(discovered.error==null) {"Playlist discovery failed: ${discovered.error}"}
                val page=requireNotNull(discovered.page)
                compose.onNodeWithTag("playlist_preview").assertIsDisplayed()
                report.put("first_page_displayed_ms",SystemClock.elapsedRealtime()).put("first_page_entries",page.items.size)
                    .put("first_page_duration_bounds",JSONArray().apply {page.items.forEach {entry->
                        put(JSONObject().put("position",entry.position).put("duration_s",entry.duration).put("selectable",entry.selectable).put("reason",entry.reason))
                    }})
                assertTrue("The UI must use the new 20-entry boundary",page.items.size<=20)
                val chosen=page.items.filter {it.selectable&&it.duration?.let {seconds->seconds>0&&seconds<=660}==true}.take(expectedChildren)
                check(chosen.size==expectedChildren) {"First page lacks $expectedChildren duration-bounded public children; refusing an unbounded trial"}
                report.put("selected_positions",JSONArray(chosen.map {it.position}))
                scenario.onActivity {controller!!.selectAll(false);chosen.forEach {controller!!.select(it.key,true)}}
                compose.waitUntil(15_000) {!controller!!.state.value.checkingDuplicates}
                if(args.getString("allowExistingFixture")=="true") {
                    val locale=java.util.Locale.forLanguageTag(settingsBefore.language)
                    val context=app.createConfigurationContext(android.content.res.Configuration(app.resources.configuration).apply {setLocale(locale)})
                    val label=context.getString(R.string.duplicate_playlist_skip)
                    compose.onNodeWithTag("playlist_list").performScrollToNode(hasText(label))
                    compose.onNode(isToggleable() and hasAnySibling(hasText(label))).assertIsOn().performClick()
                }
                if(args.getString("requireColdPlayer")=="true") {
                    val python=app.extractor.python()
                    val entries=python.getModule("android_jsc").get("_player_transforms")!!.get("_entries")!!
                    val retained=python.builtins.callAttr("len",entries).toInt()
                    report.put("player_transform_entries_before_download",retained)
                    assertEquals("Cold trial cannot reuse a prior player transformation",0,retained)
                }
                val session=discovered.sessionId
                report.put("session_id",session).put("download_pressed_ms",SystemClock.elapsedRealtime())
                compose.onNodeWithTag("playlist_download").assertIsEnabled().performClick()
                withTimeout(30_000) {
                    while(batchId==null) {
                        val sessionTrace=traces().firstOrNull {it.optString("id")==session}
                        batchId=sessionTrace?.optString("playlist_batch_id")?.takeIf {it.isNotBlank()}
                        check(controller!!.state.value.error==null) {"Submission failed: ${controller!!.state.value.error}"}
                        if(batchId==null) delay(100)
                    }
                }
                report.put("batch_id",batchId)
                withTimeout(240_000) {
                    while(true) {
                        val rows=app.repository.dao.batchItems(batchId!!)
                        check(rows.sumOf {it.bytes.coerceAtLeast(0)}<=byteBudget) {"64 MiB trial budget exceeded"}
                        check(rows.none {it.retries>0||it.status=="RETRYING"}) {"Provider/transfer failure: stopping before an automatic live retry"}
                        check(rows.none {it.status=="WAITING_FOR_NETWORK"}) {"Network/policy blocks the trial"}
                        if(rows.size==expectedChildren&&rows.all {it.status in terminal}) break
                        delay(200)
                    }
                }
                val rows=app.repository.dao.batchItems(batchId!!).sortedBy {it.playlistPosition}
                assertEquals(expectedChildren,rows.size)
                val outputs=JSONArray()
                for(row in rows) {
                    assertEquals("Child failed: ${row.error}","COMPLETED",row.status)
                    outputs.put(verifyOutput(row))
                }
                report.put("outputs",outputs)
                if(expectedChildren==2) {
                    val secondStart=app.repository.dao.events(rows[1].id).first {it.state=="DOWNLOADING_AUDIO"}.time
                    assertTrue("Second transfer must follow first publication",requireNotNull(rows[0].finishedAt)<=secondStart)
                }
                report.put("sequential_publication_verified",expectedChildren==2)
                succeeded=true
            }
        } finally {
            withContext(NonCancellable) {
                // Pause only this trial's unfinished work. Never delete retained outputs/history.
                batchId?.let {id->if(app.repository.dao.batchItems(id).any {it.status !in terminal}) app.playlists.pause(id)}
                controller?.let {own->InstrumentationRegistry.getInstrumentation().runOnMainSync {own.cancelDiscovery()}}
                val after=app.repository.dao.observe().first()
                report.put("existing_history_preserved",after.map {it.id}.toSet().containsAll(before.map {it.id}))
                    .put("settings_unchanged",settingsBefore==com.zdownloader.app.data.SettingsRepository(app).current())
                    .put("completed",succeeded&&report.getBoolean("existing_history_preserved")&&report.getBoolean("settings_unchanged"))
                    .put("traces",JSONArray(AnalysisTrace.snapshot()))
                batchId?.let {id->report.put("rows",JSONArray().apply {app.repository.dao.batchItems(id).forEach {row->
                    put(JSONObject().put("id",row.id).put("state",row.status).put("error",row.error).put("bytes",row.bytes)
                        .put("events",JSONArray().apply {app.repository.dao.events(row.id).forEach {event->put(JSONObject().put("state",event.state).put("time",event.time).put("error",event.error))}}))
                }})}
                File(folder,"youtube-startup.json").writeText(report.toString(2))
                for(name in listOf("network-diagnostics.previous.jsonl","network-diagnostics.jsonl")) {
                    File(app.filesDir,name).takeIf {it.isFile}?.copyTo(File(folder,name),overwrite=false)
                }
            }
        }
        assertTrue("The trial must preserve existing history and settings",report.getBoolean("completed"))
    }

    private fun traces():List<JSONObject> = JSONArray(AnalysisTrace.snapshot()).let {rows->(0 until rows.length()).map {rows.getJSONObject(it)}}

    private fun verifyOutput(row:DownloadEntity):JSONObject {
        val uri=android.net.Uri.parse(requireNotNull(row.uri))
        val published=app.contentResolver.query(uri,arrayOf("_size","is_pending"),null,null,null)!!.use {cursor->
            check(cursor.moveToFirst());assertEquals(0,cursor.getInt(1));cursor.getLong(0)
        }
        val probe=File(app.cacheDir,"youtube-startup-${row.id}.mp3")
        try {
            app.contentResolver.openInputStream(uri)!!.use {input->probe.outputStream().use {sink->
                val buffer=ByteArray(32*1024);var copied=0L
                while(true) {val count=input.read(buffer);if(count<0) break;copied+=count;check(copied<=byteBudget);sink.write(buffer,0,count)}
            }}
            assertTrue(published>0);assertEquals(published,probe.length())
            val info=FFprobeKit.getMediaInformation(probe.path).mediaInformation
            assertEquals("mp3",info.streams.first {it.type=="audio"}.codec)
            val decoded=FFmpegKit.executeWithArguments(arrayOf("-v","error","-xerror","-i",probe.path,"-map","0:a:0","-f","null","-"))
            assertTrue("Published audio must decode completely",ReturnCode.isSuccess(decoded.returnCode))
            return JSONObject().put("id",row.id).put("output_bytes",published).put("duration_s",info.duration).put("full_decode_verified",true)
        } finally {probe.delete()}
    }
}
