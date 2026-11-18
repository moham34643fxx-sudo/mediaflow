package com.zdownloader.app

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.NativeWork
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Compare real queue paths, alternating order, without converting or publishing probe files. */
class PlaylistTransferComparisonTest {
    @Test fun compareTheSameAudioAsSingleAndSequentialPlaylist():Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val dao=app.repository.dao
        assertEquals("An idle queue is needed for a matched transfer comparison",0,dao.remaining())
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${args.getString("evidenceRun")}").apply {mkdirs()}
        val file=File(folder,"playlist-transfer-comparison.json")
        val source=checkNotNull(args.getString("playlistSource"))
        val page=PlaylistPage.parse(app.extractor.playlist(source,UUID.randomUUID().toString(),0),source)
        val entry=page.items.first {it.selectable}
        val before=daoIds(app)
        val trials=JSONArray()
        val target=(args.getString("probeMiB")?.toLong() ?: 4L)*1024*1024
        val report=JSONObject().put("scope","Same entry, MP3 192 kb/s intent; bounded network transfer only, no conversion/publication")
            .put("source",entry.url).put("target_bytes",target).put("trials",trials)
        try {
            // ABBA reduces the bias from cold startup and changing network conditions.
            for(mode in listOf("single","playlist","playlist","single")) {
                val nonce=UUID.randomUUID().toString()
                var batch:String?=null
                var id:String?=null
                var runner:Job?=null
                val trial=JSONObject().put("mode",mode)
                val samples=JSONArray();trial.put("samples",samples);trials.put(trial)
                val began=SystemClock.elapsedRealtime()
                try {
                    if(mode=="single") {
                        id=UUID.randomUUID().toString()
                        app.repository.createRequest(id,entry.url!!,
                            DownloadRequest(audioOnly=true,output="mp3",bitrate=192,template="comparison-$nonce"),null,allowAgain=true)
                    } else {
                        batch=app.playlists.submit("comparison-$nonce",page.copy(title="MediaFlow comparison $nonce"),listOf(entry),
                            PlaylistPolicy(audio=true,output="mp3",bitrate=192),numbered=false,concurrency=1)
                        id=dao.batchItems(batch).single().id
                    }
                    val ownId=checkNotNull(id)
                    trial.put("id",ownId)
                    runner=launch(Dispatchers.IO) {app.coordinator.run()}
                    var firstBytesAt:Long?=null
                    withTimeout(180_000) {
                        while(true) {
                            val row=checkNotNull(dao.get(ownId))
                            val now=SystemClock.elapsedRealtime()
                            if(row.bytes>0&&firstBytesAt==null) firstBytesAt=now
                            samples.put(JSONObject().put("elapsed_ms",now-began).put("bytes",row.bytes)
                                .put("state",row.status).put("speed",row.speed))
                            if(row.selection.isNotBlank()) {
                                val selection=Selection.parse(row.selection)
                                trial.put("format_id",selection.primary.id).put("protocol",selection.primary.protocol)
                                    .put("audio_codec",selection.primary.audioCodec).put("source_bitrate",selection.primary.audioBitrate)
                                    .put("duration_s",row.duration).put("output",selection.output).put("bitrate",selection.bitrate)
                            }
                            file.writeText(report.toString(2))
                            assertFalse("Transfer failed: ${row.error}",row.status in setOf("FAILED","CANCELLED"))
                            if(row.bytes>=target) {
                                trial.put("bytes",row.bytes).put("elapsed_ms",now-began)
                                    .put("first_bytes_ms",firstBytesAt?.minus(began))
                                    .put("body_elapsed_ms",firstBytesAt?.let {now-it})
                                break
                            }
                            delay(500)
                        }
                    }
                } finally {
                    id?.let {ownId->
                        app.repository.cancel(ownId)
                        app.coordinator.stopAndAwait(ownId)
                        trial.put("events",JSONArray().apply {dao.events(ownId).forEach {
                            put(JSONObject().put("state",it.state).put("time",it.time).put("error",it.error))
                        }})
                        val diagnostics=File(app.filesDir,"network-diagnostics.jsonl")
                        val budgets=diagnostics.takeIf {it.isFile}?.useLines {lines->lines.mapNotNull {line->
                            runCatching {JSONObject(line)}.getOrNull()
                        }.filter {it.optString("operation")==ownId&&it.optString("stage")=="fragment_budget_allocated"}.toList()}.orEmpty()
                        trial.put("fragment_budgets",JSONArray(budgets))
                        app.repository.remove(ownId)
                        val directory=app.storage.temporary(ownId)
                        if(withTimeoutOrNull(30_000) {NativeWork.awaitIdle(directory);true}==true) directory.deleteRecursively()
                        else NativeWork.afterIdle(directory) {directory.deleteRecursively()}
                    }
                    batch?.let {dao.removeEmptyBatch(it)}
                    runner?.let {if(withTimeoutOrNull(15_000) {it.join();true}!=true) it.cancelAndJoin()}
                    file.writeText(report.toString(2))
                }
            }
            val formats=(0 until trials.length()).map {trials.getJSONObject(it).getString("format_id") to trials.getJSONObject(it).getString("protocol")}
            report.put("matched_representations",formats.distinct().size==1)
            assertEquals("Source representations must match before comparing speed",1,formats.distinct().size)
            report.put("complete",true)
        } finally {
            report.put("original_ids_retained",daoIds(app).containsAll(before))
            file.writeText(report.toString(2))
            assertTrue("Original download history must survive the comparison",daoIds(app).containsAll(before))
        }
    }

    private fun daoIds(app:ZApplication):Set<String> = app.database.openHelper.readableDatabase.query("SELECT id FROM downloads").use {cursor->
        buildSet {while(cursor.moveToNext()) add(cursor.getString(0))}
    }
}
