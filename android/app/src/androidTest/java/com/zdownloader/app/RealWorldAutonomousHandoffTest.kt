package com.zdownloader.app

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** The host observes publication with no instrumentation running between these two tests. */
class RealWorldAutonomousHandoffTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ZApplication
    private val args get()=InstrumentationRegistry.getArguments()
    private fun directory():File {
        val run=requireNotNull(args.getString("evidenceRun"))
        require(run.matches(Regex("[a-z0-9-]{1,64}")))
        return File(app.getExternalFilesDir(null),"autonomous-real-world/$run")
    }
    private fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(
        instrument.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText()}

    @Test fun submitRealTransferThenExitInstrumentation()=runBlocking<Unit> {
        val preExisting=app.repository.downloads.first()
        assertTrue("Unrelated requests must remain untouched",preExisting
            .all {it.status in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")})
        assertFalse(app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val folder=directory();check(!folder.exists()&&folder.mkdirs())
        val url=requireNotNull(args.getString("probeSource"))
        val media=withTimeout(100_000) {app.extractor.analyze(url,forceRefresh=true,enrichOptional=false)}
        val selection=media.formats.filter {it.video&&codecFamily(it.videoCodec)=="h264"&&
            verifiedQualityEdge(it)?.let {edge->edge<=1080}==true}
            .map {Selection.video(media,it,"mp4")}.first {it.transferSize?.bytes?.let {bytes->bytes in 1..(32L*1024*1024)}==true}
        val activity=instrument.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var requestId:String?=null;var handedOff=false
        try {
            assertFalse("Do not reuse a user request",app.repository.duplicateReview(media).found)
            val id=app.repository.create(media,selection,"Autonomous-QA-${args.getString("evidenceRun")}",allowCompleted=false)
            check(preExisting.none {it.id==id})
            check(app.repository.dao.get(id)?.title=="Autonomous-QA-${args.getString("evidenceRun")}")
            requestId=id;app.repository.prepareForSubmission(id)
            val receipt=app.scheduler.startFromUser(id)
            instrument.runOnMainSync {activity.finish()}
            shell("input keyevent KEYCODE_SLEEP")
            val power=app.getSystemService(PowerManager::class.java)
            withTimeout(3_000) {while(power.isInteractive) delay(50)}
            val row=requireNotNull(app.repository.dao.get(id))
            assertTrue("Transfer completed before instrumentation exit; not autonomous evidence",
                row.status !in setOf("COMPLETED","FAILED","CANCELLED","PAUSED"))
            File(folder,"prepared.json").writeText(JSONObject().put("request_id",id).put("source",url)
                .put("filename",row.filename).put("fingerprint",row.fingerprint).put("pid",android.os.Process.myPid())
                .put("handoff_state",row.status).put("handoff_wall_ms",System.currentTimeMillis())
                .put("runner",receipt.mechanism).put("screen_off",!power.isInteractive).toString(2))
            handedOff=true
        } finally {
            instrument.runOnMainSync {activity.finish()}
            if(!handedOff) requestId?.let {removeOwned(it)}
        }
    }

    @Test fun verifyOnlyAfterHostObservedAutonomousPublication()=runBlocking<Unit> {
        val folder=directory();val prepared=JSONObject(File(folder,"prepared.json").readText())
        val id=UUID.fromString(prepared.getString("request_id")).toString()
        val row=requireNotNull(app.repository.dao.get(id))
        check(row.url==prepared.getString("source")&&row.filename==prepared.getString("filename")&&row.fingerprint==prepared.getString("fingerprint"))
        requireOwned(row,prepared)
        val proofId="autonomous-proof-${UUID.randomUUID()}"
        try {
            assertEquals("true",args.getString("publishedBeforeInstrumentation"))
            val observed=Uri.parse(requireNotNull(args.getString("observedUri")))
            require(observed.authority=="media"&&observed.lastPathSegment?.toLongOrNull()?.let {it>0}==true)
            assertEquals("COMPLETED",row.status);assertEquals("VALIDATED",row.checkpoint)
            assertEquals(observed.toString(),row.uri)
            val proof=File(app.storage.temporary(proofId),"published.mp4")
            app.contentResolver.openInputStream(observed)!!.use {input->proof.outputStream().use {input.copyTo(it)}}
            assertEquals(row.outputSize,proof.length());assertTrue(proof.length()>0)
            val info=requireNotNull(FFprobeKit.getMediaInformation(proof.path).mediaInformation)
            val selection=Selection.parse(row.selection)
            assertEquals("h264",info.streams.single {it.type=="video"}.codec)
            assertEquals(selection.primary.audio||selection.secondary!=null,info.streams.any {it.type=="audio"})
            val decoded=FFmpegKit.executeWithArguments(arrayOf("-nostdin","-v","error","-xerror","-err_detect","explode",
                "-i",proof.path,"-map","0:v:0","-map","0:a:0?","-max_error_rate","0","-f","null","-"))
            assertTrue("Autonomous output failed full decode",ReturnCode.isSuccess(decoded.returnCode))
            File(folder,"verified.json").writeText(JSONObject().put("passed",true).put("request_id",id)
                .put("published_before_observer_instrumentation",true).put("independent_full_decode",true)
                .put("published_bytes",proof.length()).put("handoff_state",prepared.getString("handoff_state"))
                .put("finished_wall_ms",row.finishedAt).put("handoff_wall_ms",prepared.getLong("handoff_wall_ms"))
                .put("events",org.json.JSONArray(app.repository.dao.events(id).map {JSONObject().put("state",it.state).put("time",it.time)}))
                .toString(2))
        } finally {withContext(NonCancellable) {app.storage.removeTemporary(proofId);removeOwned(id)}}
    }

    private suspend fun removeOwned(id:String) {
        val row=app.repository.dao.get(id) ?: return
        if(row.title!="Autonomous-QA-${args.getString("evidenceRun")}") {
            requireOwned(row,JSONObject(File(directory(),"prepared.json").readText()))
        }
        app.coordinator.stopAndAwait(id)
        val current=app.repository.dao.get(id) ?: return
        if(current.status=="COMPLETED") app.storage.deleteFile(current) else app.repository.cancel(id)
        app.coordinator.stopAndAwait(id);app.repository.remove(id)
    }

    private fun requireOwned(row:com.zdownloader.app.data.DownloadEntity,prepared:JSONObject) {
        if(row.title=="Autonomous-QA-${args.getString("evidenceRun")}") return
        check(prepared.getString("ownership")=="host_ui_new_request")
        check(row.id==prepared.getString("request_id")&&row.url==prepared.getString("source")&&
            row.title==prepared.getString("title")&&row.fingerprint==prepared.getString("fingerprint"))
        val baseline=prepared.getJSONArray("baseline_ids")
        check((0 until baseline.length()).none {baseline.getString(it)==row.id})
    }
}
