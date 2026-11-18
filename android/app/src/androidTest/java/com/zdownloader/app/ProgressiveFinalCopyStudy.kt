@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.content.ContentUris
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.*
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One isolated full experimental pipeline; publication still waits for every full oracle. */
internal class ProgressiveFinalCopyStudy(private val context:Context,private val directory:File,private val networkEnded:Long) {
    private val app get()=context.applicationContext as ZApplication
    private val control=TransferControl()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val copied=CompletableDeferred<Unit>()
    private val approval=CompletableDeferred<Unit>()
    private val id="proof-live-copy-${UUID.randomUUID()}"
    private val output=File(directory,"live-final-output.mp4")
    private var report=JSONObject()
    private var database:ZDatabase?=null
    private var storage:StorageRepository?=null
    private var export:Deferred<Uri>?=null
    private var uri:Uri?=null
    private var worker:OutputProofLifecycleTest.Worker?=null
    private var mux:FFmpegSession?=null
    private val muxDone=CountDownLatch(1)
    private var candidateSha:String?=null
    private var sourceDecodeApproved=false

    suspend fun prepare(video:File,audio:File,evidence:JSONObject) {
        report=evidence
        report.put("production_changed",false).put("certificate_issued",false).put("full_source_and_final_oracles_retained",true)
            .put("candidate_checkpoint_not_gallery_readiness",true).put("passed",false)
        val began=SystemClock.elapsedRealtime()
        report.put("evidence_preparation_started_at",began)
        val prefix=File(requireNotNull(context.getExternalFilesDir(null)),"source-paired-performance").canonicalPath+File.separator
        val specFile=File(requireNotNull(InstrumentationRegistry.getArguments().getString("finalCopyProofSpecPath"))).canonicalFile
        require(specFile.path.startsWith(prefix)&&specFile.length() in 1..65536)
        val spec=JSONObject(specFile.readText())
        for(name in listOf("metadata_contract.py","container_proof.py","proof_runtime.py")) {
            val entry=spec.getJSONObject("programs").getJSONObject(name)
            val file=File(entry.getString("path")).canonicalFile
            require(file.path.startsWith(prefix)&&file.length() in 1..(128L*1024))
            assertEquals(entry.getString("sha256"),hash(file))
        }
        val sourceStarted=SystemClock.elapsedRealtime()
        report.put("source_contract_started_at",sourceStarted)
        val namespace=app.extractor.python().getModule("runpy").callAttr("run_path",spec.getJSONObject("programs").getJSONObject("metadata_contract.py").getString("path"))
        val study=namespace.callAttr("__getitem__","MetadataDeviceStudy").callAttr("__call__",video.path,audio.path)
        val summary=JSONObject(study.callAttr("source_summary_json").toString())
        assertEquals(hash(video),summary.getJSONObject("video").getString("sha256"))
        assertEquals(hash(audio),summary.getJSONObject("audio").getString("sha256"))
        report.put("source_contract",summary).put("fresh_source_contract_ms",SystemClock.elapsedRealtime()-sourceStarted)
            .put("source_contract_completed_at",SystemClock.elapsedRealtime())
        val timing=requireNotNull(AacCopyTiming.fromStream(requireNotNull(FFprobeKit.getMediaInformation(audio.path).mediaInformation)
            .streams.single {it.type=="audio"}.allProperties))
        control.check()
        val muxStarted=SystemClock.elapsedRealtime()
        val lease=NativeWork.acquire(directory)
        mux=try {FFmpegKit.executeWithArgumentsAsync(arrayOf("-nostdin","-v","error","-protocol_whitelist","file,pipe","-i",video.path,
            "-protocol_whitelist","file,pipe","-i",audio.path,"-map","0:v:0","-map","1:a:0","-c","copy","-bsf:a",timing.bitstreamFilter,
            "-movflags","+faststart","-map_metadata","-1",output.path),{lease.release();muxDone.countDown()})}
            catch(error:Throwable) {lease.release();throw error}
        if(control.stopped.get()) FFmpegKit.cancel(mux!!.sessionId)
        if(!muxDone.await(90,TimeUnit.SECONDS)) {FFmpegKit.cancel(mux!!.sessionId);muxDone.await(10,TimeUnit.SECONDS);error("Copy mux did not settle before deadline")}
        check(ReturnCode.isSuccess(mux!!.returnCode)) {"Copy mux failed: ${mux!!.allLogsAsString.takeLast(800)}"}
        report.put("mux_ms",SystemClock.elapsedRealtime()-muxStarted)
        assertTrue(timing.matches(requireNotNull(FFprobeKit.getMediaInformation(output.path).mediaInformation).streams.single {it.type=="audio"}.allProperties))
        val home=File(directory,"isolated-publication").apply {check(!exists()&&mkdirs())}
        val wrapped=object:ContextWrapper(context) {override fun getFilesDir()=home}
        database=Room.inMemoryDatabaseBuilder(wrapped,ZDatabase::class.java).build()
        val dao=requireNotNull(database).downloads()
        storage=StorageRepository(wrapped,dao)
        val format=Format("live-copy-fixture","mp4",null,null,"vp9","aac",output.length(),null,"https","{}")
        val selection=Selection(format,embedMetadata=false)
        val row=DownloadEntity(id,"https://example.com/live-copy-study","Live copy study","test",null,null,
            selection.json(),"$id.mp4",null,id,status="PROCESSING")
        dao.insert(row)
        control.awaitPublicationValidation={copied.complete(Unit);approval.await();control.check()}
        export=scope.async {requireNotNull(storage).commit(row,output,selection,control)}
        val policy=JSONObject().put("mode","strip").put("title",JSONObject.NULL).put("artist",JSONObject.NULL)
        val owner=study.callAttr("new_current_copy_session",policy.toString())
        val ticket=JSONObject(owner.callAttr("begin_json",output.path).toString()).getString("ticket")
        val proofStarted=SystemClock.elapsedRealtime()
        val reader=OutputProofLifecycleTest.Worker(owner,ticket,directory,false,control).also {worker=it}
        val proof=withContext(Dispatchers.IO) {reader.await()}
        report.put("candidate_gate_ms",SystemClock.elapsedRealtime()-proofStarted).put("candidate",proof)
        assertTrue(proof.getBoolean("accepted"))
        val seeksStarted=SystemClock.elapsedRealtime()
        val seeks=ContainerAwareOutputProofTest().checkFinalSeeks(output,JSONArray(study.callAttr("expected_seeks_json").toString()))
        report.put("final_seek_ms",SystemClock.elapsedRealtime()-seeksStarted).put("final_seeks",seeks)
        withTimeout(15_000) {copied.await()}
        val hidden=Uri.parse(requireNotNull(dao.get(id)?.pendingUri)).also {uri=it}
        assertEquals(1,pendingFlag(hidden))
        assertFalse(requireNotNull(export).isCompleted)
        val journal=File(home,"publications/$id.json")
        assertTrue(JSONObject(journal.readText()).getBoolean("validationPending"))
        val readbackStarted=SystemClock.elapsedRealtime()
        val readback=requireNotNull(context.contentResolver.openInputStream(hidden)).use(::hash)
        assertFalse("Container evidence alone must not approve an undecoded source",currentApproval(readback))
        assertEquals(proof.getString("file_sha256"),readback)
        candidateSha=readback
        report.put("pending_extra_readback_ms",SystemClock.elapsedRealtime()-readbackStarted).put("output_sha256",readback)
            .put("provisional_container_evidence_completed_at",SystemClock.elapsedRealtime())
            .put("provisional_container_evidence_tail_ms",SystemClock.elapsedRealtime()-networkEnded)
            .put("approval_denied_before_source_decode_gates",true)
            .put("final_evidence_preparation_ms",SystemClock.elapsedRealtime()-began)
        save()
    }

    suspend fun confirmSourceDecoders(sourceGateAt:Long) {
        control.check()
        val sha=requireNotNull(candidateSha)
        assertFalse(sourceDecodeApproved)
        sourceDecodeApproved=true
        report.put("source_decode_gates_completed_at",sourceGateAt)
        val current=requireNotNull(uri)
        assertEquals(1,pendingFlag(current))
        assertEquals(sha,requireNotNull(context.contentResolver.openInputStream(current)).use(::hash))
        assertTrue(currentApproval(sha))
        report.put("candidate_plus_readback_completed_at",SystemClock.elapsedRealtime())
            .put("candidate_plus_readback_tail_ms",SystemClock.elapsedRealtime()-networkEnded)
        save()
    }

    private fun currentApproval(sha:String)=sourceDecodeApproved&&!control.stopped.get()&&worker?.approved(sha)==true

    fun requestStop() {
        control.stop()
        mux?.takeIf {muxDone.count!=0L}?.let {FFmpegKit.cancel(it.sessionId)}
    }

    suspend fun finishAfterSourceOracles() {
        val strictStarted=SystemClock.elapsedRealtime()
        Processor().validate(output,audio=false,requireAudio=true,expectedDuration=888.053,expectedContainer="mp4",
            expectedWidth=640,expectedHeight=272,expectedVideoCodec="vp9",control=control)
        report.put("retained_full_final_oracle_ms",SystemClock.elapsedRealtime()-strictStarted).put("retained_full_final_oracle_passed",true)
        val sha=requireNotNull(candidateSha)
        assertEquals(sha,hash(output))
        val pending=requireNotNull(uri)
        assertEquals(1,pendingFlag(pending))
        assertEquals(sha,requireNotNull(context.contentResolver.openInputStream(pending)).use(::hash))
        assertTrue(currentApproval(sha))
        report.put("approval_after_all_source_and_final_oracles_at",SystemClock.elapsedRealtime())
        approval.complete(Unit)
        val published=withTimeout(15_000) {requireNotNull(export).await()}
        assertEquals(pending,published);assertEquals(0,pendingFlag(published))
        assertEquals(sha,requireNotNull(context.contentResolver.openInputStream(published)).use(::hash))
        report.put("actual_gallery_publication_at",SystemClock.elapsedRealtime())
            .put("actual_gallery_tail_including_all_retained_oracles_ms",SystemClock.elapsedRealtime()-networkEnded)
            .put("passed",true)
        save()
    }

    suspend fun close() {
        val errors=mutableListOf<Throwable>()
        requestStop()
        approval.cancel()
        try {worker?.let {reader->reader.release();withContext(NonCancellable+Dispatchers.IO) {reader.await()};reader.executor.shutdown();check(reader.executor.awaitTermination(5,TimeUnit.SECONDS));reader.closeCanceller()}}
        catch(error:Throwable) {errors+=error}
        try {export?.let {withContext(NonCancellable) {withTimeout(15_000) {it.cancelAndJoin()}}}}
        catch(error:Throwable) {errors+=error}
        scope.cancel()
        mux?.let {if(muxDone.count!=0L) FFmpegKit.cancel(it.sessionId)}
        if(mux!=null&&!muxDone.await(10,TimeUnit.SECONDS)) errors+=AssertionError("Owned copy mux remains active")
        try {
            val dao=database?.downloads()
            val target=dao?.get(id)?.uri?.let(Uri::parse)?:uri
            storage?.cleanupPending(id)
            target?.let {value->
                val count=requireNotNull(context.contentResolver.query(value,arrayOf(MediaStore.MediaColumns._ID),null,null,null)).use {it.count}
                if(count>0) {pendingFlag(value);check(context.contentResolver.delete(value,null,null)==1)}
                assertEquals(0,requireNotNull(context.contentResolver.query(value,arrayOf(MediaStore.MediaColumns._ID),null,null,null)).use {it.count})
            }
            dao?.cancel(id);dao?.removeHistory(id)
        } catch(error:Throwable) {errors+=error}
        try {database?.close()} catch(error:Throwable) {errors+=error}
        if(NativeWork.busy(directory)||control.session!=null) errors+=AssertionError("Final-copy proof/native ownership unsettled")
        report.put("cleanup_confirmed",errors.isEmpty())
        if(errors.isNotEmpty()) report.put("passed",false)
        save()
        if(errors.isNotEmpty()) throw AssertionError("Final-copy evidence cleanup failed").apply {errors.forEach(::addSuppressed)}
    }

    private fun pendingFlag(uri:Uri):Int {
        check(uri.scheme=="content"&&uri.authority=="media"&&ContentUris.parseId(uri)>0&&ContentUris.parseId(uri)!=97218L)
        return requireNotNull(context.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.IS_PENDING),null,null,null)).use {
            check(it.count==1&&it.moveToFirst());val pending=it.getInt(1)
            check(it.getString(0)==if(pending==1) ".$id.pending.mp4" else "$id.mp4");pending
        }
    }
    private fun save() {File(directory,"final-copy-result.json").writeText(report.toString(2))}
    private fun hash(file:File)=file.inputStream().use(::hash)
    private fun hash(input:InputStream):String {
        val digest=MessageDigest.getInstance("SHA-256");val bytes=ByteArray(64*1024)
        while(true) {val n=input.read(bytes);if(n<0) break;digest.update(bytes,0,n)}
        return buildString(64) {for(byte in digest.digest()) {val n=byte.toInt() and 255;append("0123456789abcdef"[n ushr 4]);append("0123456789abcdef"[n and 15])}}
    }
}
