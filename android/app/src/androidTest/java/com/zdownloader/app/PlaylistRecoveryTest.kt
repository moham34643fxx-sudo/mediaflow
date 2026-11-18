package com.zdownloader.app

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.Processor
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Two-phase host-driven process restart check; no test extractor/HTTP injection. */
class PlaylistRecoveryTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as ZApplication
    private val key get()=InstrumentationRegistry.getArguments().getString("checkpointKey","playlist-recovery").also {require(it.matches(Regex("[A-Za-z0-9_-]+")))}
    private val snapshot get()=File(app.filesDir,"$key.json")
    private val folder get()=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
    @Test fun checkpointAcrossProcessRestart()=runBlocking {
        when(InstrumentationRegistry.getArguments().getString("checkpointPhase","both")) {
            "prepare"->prepare()
            "verify"->verify()
            else->{prepare();verify()}
        }
    }
    private suspend fun prepare() {
        try {withTimeout(30000) {while(app.coordinator.running) delay(50)}}
        catch(error:TimeoutCancellationException) {queueAuditSnapshot("recovery-idle-timeout");throw error}
        for(old in app.repository.dao.batches().first().filter {it.url=="https://fixture.invalid/playlist"&&it.title=="Playlist recovery"}) {
            app.playlists.cancel(old.id)
            for(row in app.repository.dao.batchItems(old.id)) {
                app.coordinator.stopAndAwait(row.id)
                if(row.status=="COMPLETED") app.storage.deleteFile(row) else {app.repository.cancel(row.id);app.repository.remove(row.id)}
            }
            app.repository.dao.removeEmptyBatch(old.id)
        }
        require(!snapshot.exists()) {"Recovery snapshot already exists; use a new checkpointKey"}
        val parent=PlaylistBatch(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"https://fixture.invalid/playlist","Playlist recovery",PlaylistPolicy(fallback=QualityFallback.BEST).json(),state="PAUSED")
        val format=Format.parse(JSONObject().put("format_id","checkpoint").put("url","https://offline-fixture.invalid/video.mp4").put("ext","mp4").put("protocol","https").put("vcodec","av1").put("acodec","aac").put("width",128).put("height",96).put("fps",8))
        val selection=Selection(format,duration=2.0)
        val first=DownloadEntity(UUID.randomUUID().toString(),"https://offline-fixture.invalid/first","Previously published","Fixture",null,2.0,selection.json(),"checkpoint-first-${parent.id}.mp4",null,parent.id+":1",status="PROCESSING",checkpoint="VALIDATED",batchId=parent.id,playlistPosition=1,relativeFolder="Playlist recovery")
        val second=first.copy(id=UUID.randomUUID().toString(),title="Interrupted validated output",filename="checkpoint-second-${parent.id}.mp4",fingerprint=parent.id+":2",status="INTERRUPTED",checkpoint="MERGED",playlistPosition=2)
        for(row in listOf(first,second)) {
            val output=File(app.storage.temporary(row.id),"output.mp4")
            instrumentation.context.assets.open("av1-validation.mp4").use {input->output.outputStream().use {input.copyTo(it)}}
        }
        val firstFile=File(app.storage.temporary(first.id),"output.mp4")
        Processor().validateSelection(firstFile,selection,TransferControl(),2.0)
        // Seed a genuinely validated and published prior file independently of
        // the queue under test. Only the second item is the recovery subject.
        val resolver=app.contentResolver
        val firstUri=resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,first.filename)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"video/mp4")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Movies/MediaFlow/Playlist recovery/")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING,1)
        })!!
        resolver.openOutputStream(firstUri)!!.use {sink->firstFile.inputStream().use {it.copyTo(sink)}}
        assertEquals(1,resolver.update(firstUri,android.content.ContentValues().apply {put(android.provider.MediaStore.MediaColumns.IS_PENDING,0)},null,null))
        app.repository.dao.submitBatch(parent,listOf(first.copy(status="COMPLETED",uri=firstUri.toString(),selection=selection.historyJson(),outputSize=firstFile.length()),second))
        app.storage.removeTemporary(first.id)
        val record=JSONObject().put("batch",parent.id).put("first",first.id).put("second",second.id).put("first_uri",firstUri.toString()).put("prepared_pid",android.os.Process.myPid()).put("second_checkpoint",app.repository.dao.get(second.id)!!.checkpoint)
        snapshot.writeText(record.toString())
        File(folder,"playlist-recovery-prepared.json").writeText(record.toString(2))
    }
    private suspend fun verify() {
        val record=JSONObject(snapshot.readText())
        val firstId=record.getString("first");val secondId=record.getString("second")
        try {
            assertEquals(record.getString("first_uri"),app.repository.dao.get(firstId)!!.uri)
            val before=app.repository.dao.get(secondId)!!
            assertTrue(before.checkpoint in setOf("MERGED","VALIDATED"))
            app.repository.dao.resume(secondId)
            app.repository.dao.batchState(record.getString("batch"),"ACTIVE")
            app.scheduler.scheduleRecovery(0)
            withTimeout(180000) {while(app.repository.dao.get(secondId)!!.status !in setOf("COMPLETED","FAILED")) delay(100)}
            val done=app.repository.dao.get(secondId)!!
            assertEquals(done.error,"COMPLETED",done.status)
            assertEquals(record.getString("first_uri"),app.repository.dao.get(firstId)!!.uri)
            assertTrue(app.repository.dao.events(secondId).none {it.state in setOf("DOWNLOADING_VIDEO","DOWNLOADING_AUDIO","MERGING")})
            val uri=Uri.parse(done.uri)
            app.contentResolver.openFileDescriptor(uri,"r")!!.use {assertTrue(it.statSize>0)}
            val retriever=android.media.MediaMetadataRetriever()
            try {retriever.setDataSource(app,uri);val frame=retriever.getFrameAtTime(500000);assertNotNull(frame);frame?.recycle()} finally {retriever.release()}
            withTimeout(30000) {while(app.coordinator.running) delay(50)}
            app.scheduler.scheduleRecovery(0)
            delay(2000)
            assertEquals(done.uri,app.repository.dao.get(secondId)!!.uri)
            File(folder,"playlist-recovery-verified.json").writeText(record.put("verified_pid",android.os.Process.myPid()).put("completed",2).put("state","passed").put("no_redownload_or_remerge",true).put("saved_uri_stable_after_second_recovery",true).toString(2))
        } finally {
            for(id in listOf(firstId,secondId)) {
                app.coordinator.stopAndAwait(id)
                val row=app.repository.dao.get(id)
                if(row?.status=="COMPLETED") app.storage.deleteFile(row) else {app.repository.cancel(id);app.repository.remove(id)}
            }
            snapshot.delete()
            app.repository.dao.removeEmptyBatch(record.getString("batch"))
        }
    }
}
