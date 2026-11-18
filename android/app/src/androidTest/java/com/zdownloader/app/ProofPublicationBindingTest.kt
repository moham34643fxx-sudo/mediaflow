@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.content.ContentUris
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.PyObject
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.data.StorageRepository
import com.zdownloader.app.data.ZDatabase
import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.engine.NativeWork
import com.zdownloader.app.engine.Processor
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Actual provider publication binding, with the final full oracle still retained. */
class ProofPublicationBindingTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ZApplication
    private class Fixture(val row:DownloadEntity,val file:File,val selection:Selection) {
        val control=TransferControl()
        var worker:OutputProofLifecycleTest.Worker?=null
        var export:Deferred<Uri>?=null
        var uri:Uri?=null
    }

    @Test fun currentProofAndExactPendingReadbackAreBothRequiredForPublication()=runBlocking<Unit> {supervisorScope {
        require(android.os.Build.VERSION.SDK_INT>=29)
        val began=SystemClock.elapsedRealtime()
        val prefix=File(requireNotNull(app.getExternalFilesDir(null)),"source-paired-performance").canonicalPath+File.separator
        fun owned(path:String)=File(path).canonicalFile.also {require(it.path.startsWith(prefix)&&it.isFile)}
        val specFile=owned(requireNotNull(InstrumentationRegistry.getArguments().getString("proofLifecycleSpecPath")))
        require(specFile.length() in 1..65536)
        val spec=JSONObject(specFile.readText())
        for(key in listOf("module","runtime","contract")) {
            val file=owned(spec.getString(key+"_path"));require(file.length() in 1..(24L*1024*1024))
            assertEquals(spec.getString(key+"_sha256"),hash(file))
        }
        val source=owned(spec.getString("baseline_path"))
        val expectedSha="45446109c38af58a751c6b4a204c246b602d542cb7d0bfbc630955835042a5ff"
        assertEquals(56365991L,source.length());assertEquals(expectedSha,hash(source))
        check(app.repository.downloads.first().none {it.status !in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")})
        check(!app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val run=UUID.randomUUID().toString()
        val home=File(app.cacheDir,"proof-publication-$run").apply {check(!exists()&&mkdirs())}
        val evidence=File(app.getExternalFilesDir(null),"proof-publication-binding/$run").apply {check(!exists()&&mkdirs())}
        require(android.os.StatFs(home.path).availableBytes>=1024L*1024*1024)
        val context=object:ContextWrapper(app) {override fun getFilesDir()=home}
        val database=Room.inMemoryDatabaseBuilder(context,ZDatabase::class.java).build()
        val dao=database.downloads()
        val storage=StorageRepository(context,dao)
        val fixtures=mutableListOf<Fixture>()
        val report=JSONObject().put("passed",false).put("production_changed",false).put("certificate_issued",false)
            .put("scope","Actual provider proof/readback approval research; isolated DB, all full final oracles retained; no production optimization")
        val cases=JSONArray();report.put("cases",cases)
        var activity:MainActivity?=null
        var failure:Throwable?=null
        try {
            val active=instrument.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=active
            withContext(Dispatchers.Main) {active.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            val namespace=app.extractor.python().getModule("runpy").callAttr("run_path",spec.getString("module_path"))
            val study=namespace.callAttr("__getitem__","DeviceStudy").callAttr("__call__",spec.getString("contract_path"))
            for(kind in listOf("success","cancel_pending_proof","stale_generation","corrupt_readback")) {
                val id="proof-publication-gate-${UUID.randomUUID()}"
                val directory=File(home,"transfers/$id").apply {check(!exists()&&mkdirs())}
                val file=File(directory,"output.mp4");source.copyTo(file)
                val format=Format("proof-fixture","mp4",null,null,"vp9","aac",file.length(),null,"https","{}")
                val selection=Selection(format)
                val row=DownloadEntity(id,"https://example.com/proof-publication","Proof publication","test",null,null,
                    selection.json(),"$id.mp4",null,id,status="PROCESSING")
                dao.insert(row)
                val fixture=Fixture(row,file,selection).also {fixtures+=it}
                val case=JSONObject().put("name",kind).put("approved",false);cases.put(case)
                val validationStarted=SystemClock.elapsedRealtime()
                Processor().validate(file,audio=false,requireAudio=true,expectedDuration=888.053,expectedContainer="mp4",
                    expectedWidth=640,expectedHeight=272,expectedVideoCodec="vp9",control=fixture.control)
                case.put("retained_full_final_oracle_ms",SystemClock.elapsedRealtime()-validationStarted)
                val owner=study.callAttr("new_session",false)
                val token=JSONObject(owner.callAttr("begin_json",file.path,if(kind=="cancel_pending_proof") "hash" else "none").toString())
                val worker=OutputProofLifecycleTest.Worker(owner,token.getString("ticket"),directory,false,fixture.control)
                fixture.worker=worker
                val cancelled=kind=="cancel_pending_proof"
                if(cancelled) assertTrue(owner.callAttr("await_pause",worker.ticket,10).toBoolean())
                else assertTrue(withContext(Dispatchers.IO) {worker.await()}.getBoolean("accepted"))
                val copied=CompletableDeferred<Unit>()
                val allow=CompletableDeferred<Unit>()
                fixture.control.awaitPublicationValidation={copied.complete(Unit);allow.await();fixture.control.check()}
                fixture.export=async(Dispatchers.IO) {storage.commit(row,file,selection,fixture.control)}
                withTimeout(15_000) {copied.await()}
                val hidden=Uri.parse(requireNotNull(dao.get(id)?.pendingUri));fixture.uri=hidden
                assertEquals(1,pendingFlag(hidden,id))
                val journal=File(home,"publications/$id.json")
                assertTrue(JSONObject(journal.readText()).getBoolean("validationPending"))
                assertFalse(requireNotNull(fixture.export).isCompleted)
                if(kind=="corrupt_readback") app.contentResolver.openOutputStream(hidden,"rwt")!!.use {it.write(byteArrayOf(88))}
                val readbackStarted=SystemClock.elapsedRealtime()
                val readback=requireNotNull(app.contentResolver.openInputStream(hidden)).use(::hash)
                case.put("pending_readback_ms",SystemClock.elapsedRealtime()-readbackStarted).put("readback_sha256",readback)
                if(kind=="stale_generation") owner.callAttr("invalidate_json","source_refresh")
                if(cancelled) {
                    fixture.control.stop()
                    assertTrue(NativeWork.busy(directory))
                    assertEquals(1,worker.status().getInt("open_handles"))
                }
                val approved=!fixture.control.stopped.get()&&JSONObject(owner.callAttr("approve_json",worker.ticket,readback).toString()).getBoolean("approved")
                case.put("approved",approved)
                if(kind=="success") {
                    assertTrue(approved);assertEquals(expectedSha,readback)
                    allow.complete(Unit)
                    val published=withTimeout(15_000) {requireNotNull(fixture.export).await()}
                    assertEquals(hidden,published);assertEquals(0,pendingFlag(published,id))
                    assertEquals(expectedSha,requireNotNull(app.contentResolver.openInputStream(published)).use(::hash))
                    case.put("published_only_after_current_proof_and_readback",true)
                } else {
                    assertFalse(approved)
                    allow.completeExceptionally(IllegalStateException("Research approval denied: $kind"))
                    val rejected=withTimeout(15_000) {runCatching {requireNotNull(fixture.export).await()}.isFailure}
                    assertTrue(rejected)
                    if(cancelled) {
                        assertTrue(NativeWork.busy(directory))
                        worker.release()
                        assertFalse(withContext(Dispatchers.IO) {worker.await()}.getBoolean("accepted"))
                    }
                    storage.cleanupPending(id)
                    assertGone(hidden)
                    assertNotEquals("COMPLETED",dao.get(id)?.status)
                    case.put("unapproved_copy_not_published_and_removed",true)
                }
                fixture.control.stop();worker.release();withContext(Dispatchers.IO) {worker.await()}
                worker.executor.shutdown();assertTrue(worker.executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS))
                worker.closeCanceller();assertFalse(NativeWork.busy(directory))
                case.put("cleanup_ownership_confirmed",true)
                File(evidence,"interim.json").writeText(report.toString(2))
            }
            assertEquals(expectedSha,hash(source));report.put("all_four_provider_scenarios_completed",true)
        } catch(error:Throwable) {failure=error}
        finally {
            val cleanupErrors=mutableListOf<Throwable>()
            for(fixture in fixtures) try {
                fixture.control.stop()
                fixture.worker?.let {worker->worker.release();withContext(NonCancellable+Dispatchers.IO) {worker.await()};worker.executor.shutdown();check(worker.executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));worker.closeCanceller()}
                fixture.export?.let {withContext(NonCancellable) {withTimeout(15_000) {it.cancelAndJoin()}}}
                val uri=dao.get(fixture.row.id)?.uri?.let(Uri::parse)?:fixture.uri
                storage.cleanupPending(fixture.row.id)
                uri?.let {target->
                    val count=requireNotNull(app.contentResolver.query(target,arrayOf(MediaStore.MediaColumns._ID),null,null,null)).use {it.count}
                    if(count>0) {pendingFlag(target,fixture.row.id);check(app.contentResolver.delete(target,null,null)==1)}
                    assertGone(target)
                }
                check(!NativeWork.busy(fixture.file.parentFile!!))
                dao.cancel(fixture.row.id);dao.removeHistory(fixture.row.id)
            } catch(error:Throwable) {cleanupErrors+=error}
            try {database.close()} catch(error:Throwable) {cleanupErrors+=error}
            try {withContext(NonCancellable+Dispatchers.Main) {activity?.finish()}} catch(error:Throwable) {cleanupErrors+=error}
            if(cleanupErrors.isNotEmpty()) {
                val error=AssertionError("Owned pending/export/reader cleanup failed")
                cleanupErrors.forEach(error::addSuppressed)
                if(failure==null) failure=error else failure!!.addSuppressed(error)
            }
            report.put("cleanup_confirmed",cleanupErrors.isEmpty()).put("passed",failure==null)
                .put("total_study_wall_ms",SystemClock.elapsedRealtime()-began)
            failure?.let {report.put("failure",it.javaClass.simpleName+":"+it.message)}
            File(evidence,"result.json").writeText(report.toString(2))
            instrument.sendStatus(2,android.os.Bundle().apply {putString("proof_publication_report",File(evidence,"result.json").path)})
        }
        failure?.let {throw it}
    }}

    private fun pendingFlag(uri:Uri,id:String):Int {
        check(uri.scheme=="content"&&uri.authority=="media"&&ContentUris.parseId(uri)>0&&ContentUris.parseId(uri)!=97218L)
        return requireNotNull(app.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.IS_PENDING),null,null,null)).use {
            check(it.count==1&&it.moveToFirst());val pending=it.getInt(1)
            check(it.getString(0)==if(pending==1) ".$id.pending.mp4" else "$id.mp4");pending
        }
    }
    private fun assertGone(uri:Uri) {assertEquals(0,requireNotNull(app.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns._ID),null,null,null)).use {it.count})}
    private fun hash(file:File)=file.inputStream().use(::hash)
    private fun hash(input:InputStream):String {
        val digest=MessageDigest.getInstance("SHA-256");val bytes=ByteArray(64*1024)
        while(true) {val n=input.read(bytes);if(n<0) break;digest.update(bytes,0,n)}
        return buildString(64) {for(byte in digest.digest()) {val n=byte.toInt() and 255;append("0123456789abcdef"[n ushr 4]);append("0123456789abcdef"[n and 15])}}
    }
}
