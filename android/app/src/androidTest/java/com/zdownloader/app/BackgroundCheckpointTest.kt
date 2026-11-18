package com.zdownloader.app

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.domain.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class BackgroundCheckpointTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as ZApplication
    private fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText().trim()}
    @Test fun mergedOutputRecoversWithoutDownloadingOrMergingAgain()=runBlocking {
        withTimeout(60000) {while(app.coordinator.running) delay(100)}
        val id="merged-${UUID.randomUUID()}";val dir=app.storage.temporary(id)
        val output=File(dir,"output.mp4")
        instrumentation.context.assets.open("av1-validation.mp4").use {source->output.outputStream().use {source.copyTo(it)}}
        val payload=JSONObject().put("format_id","merged").put("url","https://offline-fixture.invalid/video.mp4").put("ext","mp4").put("protocol","https").put("vcodec","av1").put("acodec","aac").put("width",128).put("height",96).put("fps",8)
        val format=Format.parse(payload);val selection=Selection(format,duration=2.0)
        val row=DownloadEntity(id,"https://offline-fixture.invalid/video.mp4",id,"Merged checkpoint",null,2.0,selection.json(),"$id.mp4",null,id,status="VALIDATING",checkpoint="MERGED")
        app.repository.dao.insert(row)
        app.repository.dao.event(com.zdownloader.app.data.DownloadEvent(downloadId=id,state="MERGING"))
        try {
            // Deliberately no primary/secondary file: repeating the merge cannot work.
            app.scheduler.scheduleRecovery(0)
            withTimeout(180000) {while(app.repository.dao.get(id)!!.status !in listOf("COMPLETED","FAILED")) delay(100)}
            val completed=app.repository.dao.get(id)!!
            assertEquals(completed.error,"COMPLETED",completed.status)
            assertEquals(1,app.repository.dao.events(id).count {it.state=="MERGING"})
            val folder=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "manual"}").apply {mkdirs()}
            File(folder,"merged-processing-recovery.json").writeText(JSONObject().put("state","passed").put("status",completed.status).put("merge_events",1).put("input_files_absent",true).toString(2))
            withTimeout(30000) {while(app.coordinator.running) delay(100)}
        } finally {
            app.coordinator.stopAndAwait(id)
            val completed=app.repository.dao.get(id)
            if(completed?.status=="COMPLETED") app.storage.deleteFile(completed) else {app.repository.cancel(id);app.repository.remove(id)}
            app.storage.removeTemporary(id)
        }
        Unit
    }

    @Test fun interruptedDownloadedAudioFinishesOfflineThroughRealRecoveryWorker()=runBlocking {
        withTimeout(60000) {while(app.coordinator.running) delay(100)}
        val id="checkpoint-${UUID.randomUUID()}";val dir=app.storage.temporary(id)
        val source=File(dir,"primary.wav")
        val size=44100*2
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {put("RIFF".toByteArray());putInt(size+36);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1);putInt(44100);putInt(88200);putShort(2);putShort(16);put("data".toByteArray());putInt(size)}.array()
        source.writeBytes(header+ByteArray(size))
        val payload=JSONObject().put("format_id","offline-complete").put("ext","wav").put("vcodec","none").put("acodec","pcm_s16le").put("protocol","https").put("url","https://offline-fixture.invalid/input.wav").toString()
        val format=Format("offline-complete","wav",null,null,"none","pcm_s16le",source.length(),null,"https",payload)
        val selection=Selection(format,audioOnly=true,output="mp3",convertAudio=true,duration=1.0)
        val row=DownloadEntity(id,"https://offline-fixture.invalid/input.wav",id,"Offline checkpoint",null,1.0,selection.json(),"$id.mp3",null,id,status="VALIDATING",checkpoint="DOWNLOADED")
        app.repository.dao.insert(row)
        val wifi=shell("settings get global wifi_on")!="0";val data=shell("settings get global mobile_data")!="0"
        val airplane=shell("settings get global airplane_mode_on")=="1"
        var phase="disable_network"
        val radio=JSONObject()
        try {
            radio.put("wifi_disable_result",shell("svc wifi disable")).put("data_disable_result",shell("svc data disable"))
                .put("airplane_enable_result",shell("cmd connectivity airplane-mode enable"))
            val offline=withTimeoutOrNull(30000) {while(app.coordinator.waitingReason(com.zdownloader.app.data.SettingsRepository(app).current())==null) delay(100);true}==true
            org.junit.Assume.assumeTrue("BLOCKED: disabling Wi-Fi/mobile/airplane mode did not remove the active validated network on this device",offline)
            phase="schedule_recovery"
            app.scheduler.scheduleRecovery(0)
            phase="await_completed"
            withTimeout(120000) {while(app.repository.dao.get(id)!!.status !in listOf("COMPLETED","FAILED")) delay(100)}
            val completed=app.repository.dao.get(id)!!
            assertEquals(completed.error,"COMPLETED",completed.status)
            assertEquals("VALIDATED",completed.checkpoint)
            assertTrue(completed.uri!!.contains("/audio/media/"))
            app.contentResolver.query(Uri.parse(completed.uri),arrayOf(MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.RELATIVE_PATH),null,null,null)!!.use {cursor->
                assertTrue(cursor.moveToFirst());assertEquals(0,cursor.getInt(0));assertEquals("Music/MediaFlow/",cursor.getString(1))
            }
            val retriever=MediaMetadataRetriever()
            try {retriever.setDataSource(app,Uri.parse(completed.uri));assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()>0)} finally {retriever.release()}
            val folder=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "manual"}").apply {mkdirs()}
            File(folder,"offline-processing-recovery.json").writeText(JSONObject().put("status",completed.status).put("checkpoint",completed.checkpoint).put("uri",completed.uri).put("network_disabled",true).put("entry","persisted RecoveryWorker, no Activity").put("state","passed").toString(2))
            withTimeout(30000) {while(app.coordinator.running) delay(100)}
        } finally {
            val diagnostic=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "manual"}").apply {mkdirs()}
            val connectivity=app.getSystemService(android.net.ConnectivityManager::class.java)
            val caps=connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            File(diagnostic,"offline-checkpoint-diagnostic.json").writeText(JSONObject().put("phase",phase)
                .put("radio_commands",radio)
                .put("validated",caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true)
                .put("active_transport_codes",JSONArray((0..8).filter {caps?.hasTransport(it)==true}))
                .put("state",app.repository.dao.get(id)?.status).put("coordinator_running",app.coordinator.running)
                .put("waiting_reason",app.coordinator.waitingReason(com.zdownloader.app.data.SettingsRepository(app).current())?.name).toString(2))
            shell("cmd connectivity airplane-mode ${if(airplane) "enable" else "disable"}");shell("svc wifi ${if(wifi) "enable" else "disable"}");shell("svc data ${if(data) "enable" else "disable"}")
            val cm=app.getSystemService(android.net.ConnectivityManager::class.java)
            if(!airplane && (wifi||data)) withTimeout(60000) {while(cm.activeNetwork?.let(cm::getNetworkCapabilities)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)!=true) delay(250)}
            app.coordinator.stopAndAwait(id)
            val current=app.repository.dao.get(id)
            if(current?.status=="COMPLETED") app.storage.deleteFile(current) else {app.repository.cancel(id);app.repository.remove(id)}
            app.storage.removeTemporary(id)
        }
        Unit
    }
}
