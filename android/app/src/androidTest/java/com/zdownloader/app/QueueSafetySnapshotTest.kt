package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.playback.PlaybackRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Read-only precondition: do not kill the app process while user work is active. */
class QueueSafetySnapshotTest {
    @Test fun noActiveOrRecoveringUserDownloadsBeforeExternalProcessTest()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        assertFalse("Do not interrupt an existing MediaSession",PlaybackRuntime.active)
        assertFalse("Do not interrupt user audio",app.playback.state.value.playing)
        val rows=app.database.downloads().observe().first()
        val unsafe=rows.filter {it.pendingUri!=null||it.status in setOf("QUEUED","WAITING_FOR_NETWORK","RETRYING","RESUMING",
            "DOWNLOADING","DOWNLOADING_VIDEO","DOWNLOADING_AUDIO","PROCESSING","WAITING_FOR_PROCESSOR","MERGING","CONVERTING","VALIDATING","SAVING","PAUSING","INTERRUPTED")}
        val report=JSONObject().put("safe_to_kill_app_process",unsafe.isEmpty()).put("playback_active",PlaybackRuntime.active)
            .put("status_counts",JSONObject(rows.groupingBy {it.status}.eachCount()))
            .put("unsafe_rows",JSONArray().apply {unsafe.forEach {row->
                put(JSONObject().put("status",row.status).put("has_recovery_marker",row.pendingUri!=null)
                    .put("test_owned",row.url=="https://example.com/blocked-recovery"&&row.id.startsWith("blocked-recovery-")||
                        row.url=="https://example.com/upgrade"&&row.id.startsWith("upgrade-")||
                        row.url=="https://example.com/journal"&&row.id.startsWith("journal-"))
                    .put("id_prefix",row.id.substringBefore('-').take(16))
                    .put("journal_exists",java.io.File(app.filesDir,"publications/${row.id}.json").isFile||java.io.File(app.filesDir,"publications/${row.id}.json.bak").isFile)
                    .put("pending_uri_scheme",row.pendingUri?.let {runCatching {android.net.Uri.parse(it).scheme}.getOrNull()}))
            }})
        val folder=java.io.File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
        java.io.File(folder,"queue-safety-before-external-process.json").writeText(report.toString(2))
        assertTrue("External process recreation must not interrupt queued/recovering user work: ${unsafe.map {it.status+":"+(it.url=="https://example.com/blocked-recovery"&&it.id.startsWith("blocked-recovery-")||it.url=="https://example.com/upgrade"&&it.id.startsWith("upgrade-")||it.url=="https://example.com/journal"&&it.id.startsWith("journal-"))}}",unsafe.isEmpty())
    }
}
