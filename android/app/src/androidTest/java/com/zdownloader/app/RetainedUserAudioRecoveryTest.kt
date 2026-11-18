package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.Selection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Explicit repair experiment: retains the user's completed output and history. */
class RetainedUserAudioRecoveryTest {
    @Test fun rebuildPreviouslyInvalidMp3FromRetainedTracksAndPublishIt():Unit=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val dao=app.repository.dao
        val before=dao.observe().first()
        val row=before.firstOrNull {it.status=="FAILED"&&it.error=="OUTPUT_VALIDATION_FAILED"&&it.batchId!=null&&
            runCatching {Selection.parse(it.selection).audioOnly}.getOrDefault(false)&&
            app.storage.temporary(it.id).listFiles().orEmpty().any {file->file.name.matches(Regex("primary\\.(mp4|m4a|webm|aac|opus|ogg|mp3|flac|wav|mkv|ts)"))&&file.length()>0}}
            ?: error("No retained failed audio is available for this explicit recovery experiment")
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun")}").apply {mkdirs()}
        val report=JSONObject().put("scope","Reprocess one saved failed user audio; do not delete its output or history. Other items in its batch are paused for this experiment.")
            .put("id",row.id).put("input_bytes",row.bytes).put("original_checkpoint",row.checkpoint)
        val samples=JSONArray();report.put("samples",samples)
        val started=android.os.SystemClock.elapsedRealtime()
        var owner:Job?=null
        try {
            app.playlists.pause(row.batchId!!)
            for(item in dao.batchItems(row.batchId!!)) app.coordinator.stopAndAwait(item.id)
            app.repository.prepareForSubmission(row.id)
            dao.batchState(row.batchId!!,"ACTIVE")
            if(!app.coordinator.running) owner=launch(Dispatchers.IO) {app.coordinator.run()}
            withTimeout(10*60_000L) {
                while(true) {
                    val current=dao.get(row.id)!!
                    samples.put(JSONObject().put("elapsed_ms",android.os.SystemClock.elapsedRealtime()-started)
                        .put("state",current.status).put("checkpoint",current.checkpoint).put("bytes",current.bytes).put("error",current.error))
                    File(folder,"retained-user-audio-recovery.json").writeText(report.toString(2))
                    if(current.status in setOf("COMPLETED","FAILED","CANCELLED")) break
                    delay(2000)
                }
            }
            val completed=dao.get(row.id)!!
            assertEquals("COMPLETED",completed.status)
            val uri=android.net.Uri.parse(completed.uri!!)
            app.contentResolver.query(uri,arrayOf("_size","is_pending"),null,null,null)!!.use {cursor->
                assertTrue(cursor.moveToFirst());assertTrue(cursor.getLong(0)>0);assertEquals(0,cursor.getInt(1))
            }
            val retriever=android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(app,uri)
                assertEquals("yes",retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                assertNotNull(retriever.embeddedPicture)
            } finally {retriever.release()}
            assertTrue(dao.observe().first().map {it.id}.containsAll(before.map {it.id}))
            report.put("published",true).put("output_bytes",completed.outputSize).put("elapsed_ms",android.os.SystemClock.elapsedRealtime()-started)
        } finally {
            owner?.cancelAndJoin()
            app.playlists.pause(row.batchId!!)
            File(folder,"retained-user-audio-recovery.json").writeText(report.toString(2))
        }
    }
}
