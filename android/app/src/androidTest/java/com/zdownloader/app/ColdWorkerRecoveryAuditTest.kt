package com.zdownloader.app

import android.content.ContentUris
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.engine.NativeWork
import com.zdownloader.app.playback.PlaybackRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Host kills only an idle background process and observes MediaStore before relaunching instrumentation. */
class ColdWorkerRecoveryAuditTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
    private val arguments get()=InstrumentationRegistry.getArguments()
    private fun fixtureId():String {
        val id=requireNotNull(arguments.getString("coldFixtureId"))
        require(id=="cold-"+UUID.fromString(id.removePrefix("cold-")).toString())
        return id
    }
    private fun sourceUrl(id:String)="https://cold-worker-audit.invalid/$id/input.wav"
    private fun filename(id:String)="MediaFlow-cold-audit-$id.mp3"
    private fun evidence(id:String)=File(app.getExternalFilesDir(null),"cold-worker-audit/$id")

    @Test fun prepareDownloadedCheckpointForExternalBackgroundProcessKill()=runBlocking<Unit> {
        val id=fixtureId()
        withTimeout(15_000) {while(app.coordinator.running) delay(100)}
        assertFalse("Do not interrupt user playback",PlaybackRuntime.active)
        assertTrue("Do not interrupt unrelated nonterminal work",app.repository.dao.observe().first()
            .all {it.status in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")})
        check(app.repository.dao.get(id)==null)
        val directory=File(app.filesDir,"transfers/$id")
        val reportDirectory=evidence(id)
        check(!directory.exists()&&!reportDirectory.exists())
        var prepared=false
        try {
            val source=File(app.storage.temporary(id),"primary.wav")
            val bytes=44100*2
            val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray());putInt(bytes+36);put("WAVEfmt ".toByteArray());putInt(16)
                putShort(1);putShort(1);putInt(44100);putInt(88200);putShort(2);putShort(16)
                put("data".toByteArray());putInt(bytes)
            }.array()
            source.writeBytes(header+ByteArray(bytes))
            val payload=JSONObject().put("format_id","cold-worker-audit").put("url",sourceUrl(id)).put("ext","wav")
                .put("protocol","https").put("vcodec","none").put("acodec","pcm_s16le").toString()
            val format=Format("cold-worker-audit","wav",null,null,"none","pcm_s16le",source.length(),null,"https",payload)
            val selection=Selection(format,audioOnly=true,output="mp3",convertAudio=true,duration=1.0)
            val notBefore=System.currentTimeMillis()+60_000
            app.repository.dao.insert(DownloadEntity(id,sourceUrl(id),id,"Cold worker audit",null,1.0,selection.json(),
                filename(id),null,id,status="SCHEDULED",checkpoint="DOWNLOADED",scheduledAt=notBefore))
            app.plannedDownloads.enqueue(id)
            assertEquals("SCHEDULED",app.repository.dao.get(id)!!.status)
            check(!app.coordinator.active(id))
            check(reportDirectory.mkdirs())
            File(reportDirectory,"prepared.json").writeText(JSONObject().put("id",id).put("pid",android.os.Process.myPid())
                .put("not_before_ms",notBefore).put("filename",filename(id)).put("source_bytes",source.length())
                .put("scope","Durable scheduled DOWNLOADED checkpoint, no network source or Activity").toString(2))
            prepared=true
        } finally {if(!prepared) withContext(NonCancellable) {removeOnlyFixture(id)}}
    }

    @Test fun verifyExternallyObservedColdWorkerPublication()=runBlocking<Unit> {
        val id=fixtureId()
        val prepared=JSONObject(File(evidence(id),"prepared.json").readText())
        check(prepared.getString("id")==id)
        val workerPid=requireNotNull(arguments.getString("coldWorkerPid")).toInt()
        assertNotEquals("The worker must run in a new process",prepared.getInt("pid"),workerPid)
        assertEquals("true",arguments.getString("coldObservedBeforeInstrumentation"))
        val observed=Uri.parse(requireNotNull(arguments.getString("coldObservedUri")))
        require(observed.authority=="media"&&ContentUris.parseId(observed)>0&&ContentUris.parseId(observed)!=97218L)
        val row=requireNotNull(app.repository.dao.get(id))
        check(row.url==sourceUrl(id)&&row.fingerprint==id&&row.filename==filename(id))
        assertEquals("COMPLETED",row.status);assertEquals("VALIDATED",row.checkpoint)
        assertEquals(observed.toString(),row.uri)
        assertTrue((row.outputSize ?: 0)>0)
        val retriever=MediaMetadataRetriever()
        try {
            retriever.setDataSource(app,observed)
            assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()>0)
        } finally {retriever.release()}
        File(evidence(id),"verified.json").writeText(JSONObject().put("passed",true)
            .put("prepared_pid",prepared.getInt("pid")).put("autonomous_worker_pid",workerPid)
            .put("observer_instrumentation_pid",android.os.Process.myPid()).put("published_before_observer_instrumentation",true)
            .put("checkpoint",row.checkpoint).put("output_bytes",row.outputSize)
            .put("scope","Autonomous planned-worker cold start from a persisted local processing checkpoint after ordinary background process kill; not Force Stop, OOM, reboot or interrupted-network resume proof")
            .toString(2))
    }

    @Test fun removeOnlyTheExternallyPreparedFixture()=runBlocking<Unit> {withContext(NonCancellable) {
        removeOnlyFixture(fixtureId())
    }}

    private suspend fun removeOnlyFixture(id:String) {
        val row=app.repository.dao.get(id)
        if(row!=null) {
            check(row.url==sourceUrl(id)&&row.fingerprint==id&&row.filename==filename(id))
            app.plannedDownloads.cancel(id)
            app.repository.cancel(id);app.coordinator.stopAndAwait(id)
            withTimeout(15_000) {NativeWork.awaitIdle(File(app.filesDir,"transfers/$id"))}
            val current=app.repository.dao.get(id)
            if(current?.status=="COMPLETED") {
                val uri=Uri.parse(requireNotNull(current.uri))
                check(uri.authority=="media"&&ContentUris.parseId(uri)!=97218L)
                app.storage.deleteFile(current)
            }
            app.repository.remove(id)
            check(app.repository.dao.get(id)==null)
        }
        app.storage.removeTemporary(id)
    }
}
