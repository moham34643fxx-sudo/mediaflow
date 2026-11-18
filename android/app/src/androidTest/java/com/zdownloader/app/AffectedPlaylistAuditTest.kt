package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFprobeKit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Read-only audit of the bounded real-playlist probe after its owned-only cleanup was refused. */
class AffectedPlaylistAuditTest {
    @Test fun inspectOwnedPlaylistProbeAndPlaybackLease(): Unit = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val args=InstrumentationRegistry.getArguments()
        val source=checkNotNull(args.getString("playlistSource"))
        val rows=app.repository.dao.observe().first()
        val batches=app.repository.dao.batches().first().filter {it.url==source&&it.title.startsWith("MediaFlow verification ")}
        val result=JSONArray()
        for(batch in batches) {
            val items=JSONArray()
            for(row in app.repository.dao.batchItems(batch.id)) {
                val item=JSONObject().put("id",row.id).put("status",row.status).put("error",row.error)
                    .put("bytes",row.outputSize).put("uri",row.uri).put("selection",row.selection)
                if(row.uri!=null) {
                    app.contentResolver.query(android.net.Uri.parse(row.uri),arrayOf("_size","is_pending","mime_type"),null,null,null)?.use {cursor->
                        if(cursor.moveToFirst()) item.put("media_store_size",cursor.getLong(0)).put("is_pending",cursor.getInt(1)).put("mime",cursor.getString(2))
                    }
                    if(app.playback.state.value.current?.uri!=row.uri) {
                        val file=File(app.cacheDir,"playlist-audit-${row.id}.mp3")
                        try {
                            app.contentResolver.openInputStream(android.net.Uri.parse(row.uri))?.use {input->file.outputStream().use {input.copyTo(it)}}
                            val info=FFprobeKit.getMediaInformation(file.path).mediaInformation
                            item.put("codec",info?.streams?.firstOrNull {it.type=="audio"}?.codec)
                                .put("duration_s",info?.duration)
                        } finally {file.delete()}
                    } else item.put("playback_lease_held",true)
                }
                items.put(item)
            }
            result.put(JSONObject().put("batch_id",batch.id).put("created_at",batch.createdAt)
                .put("state",batch.state).put("title",batch.title).put("items",items))
        }
        val current=app.playback.state.value.current?.let {JSONObject().put("uri",it.uri).put("title",it.title)}
        val report=JSONObject().put("scope","Read-only inspection; no history, media files, or playback state changed")
            .put("original_playlist_history_rows",rows.count {it.url==source&&it.batchId==null})
            .put("matching_probe_batches",result).put("current_playback",current)
            .put("download_row_count",rows.size)
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${args.getString("evidenceRun")}").apply {mkdirs()}
        File(folder,"affected-playlist-audit.json").writeText(report.toString(2))
        assertEquals("Read-only audit completed",true,report.has("matching_probe_batches"))
    }
}
