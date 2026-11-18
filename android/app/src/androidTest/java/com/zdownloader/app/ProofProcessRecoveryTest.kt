@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.engine.NativeWork
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch

/** Host deliberately kills the prepared idle-app process; recovery must distrust saved proof. */
class ProofProcessRecoveryTest {
    @Test fun processRecreationNeverAdoptsSavedProofOrReaderOwnership()=runBlocking<Unit> {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        val app=instrument.targetContext.applicationContext as ZApplication
        val run=requireNotNull(args.getString("proofRecoveryRun"));require(run.matches(Regex("[a-z0-9-]{1,64}")))
        val phase=requireNotNull(args.getString("proofRecoveryPhase"));require(phase in setOf("prepare","recover"))
        val allowed=File(requireNotNull(app.getExternalFilesDir(null)),"source-paired-performance").canonicalPath+File.separator
        val specFile=File(requireNotNull(args.getString("proofLifecycleSpecPath"))).canonicalFile
        require(specFile.path.startsWith(allowed)&&specFile.length() in 1..65536)
        val spec=JSONObject(specFile.readText())
        for(key in listOf("module","runtime","contract")) {
            val file=File(spec.getString(key+"_path")).canonicalFile
            require(file.path.startsWith(allowed)&&file.length() in 1..(24L*1024*1024))
            assertEquals(spec.getString(key+"_sha256"),hash(file))
        }
        val directory=File(app.getExternalFilesDir(null),"proof-process-recovery/$run")
        if(phase=="prepare") check(!directory.exists()&&directory.mkdirs()) else check(directory.isDirectory)
        val saved=File(directory,"saved-untrusted-approval.json")
        val file=File(directory,"output.mp4")
        val expected="45446109c38af58a751c6b4a204c246b602d542cb7d0bfbc630955835042a5ff"
        check(app.repository.downloads.first().none {it.status !in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")})
        var activity:MainActivity?=null
        val workers=mutableListOf<OutputProofLifecycleTest.Worker>()
        var failure:Throwable?=null
        val report=JSONObject().put("passed",false).put("phase",phase).put("process_pid",android.os.Process.myPid())
            .put("production_changed",false).put("certificate_issued",false)
        try {
            check(!app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            val active=instrument.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=active
            withContext(Dispatchers.Main) {active.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            val namespace=app.extractor.python().getModule("runpy").callAttr("run_path",spec.getString("module_path"))
            val study=namespace.callAttr("__getitem__","DeviceStudy").callAttr("__call__",spec.getString("contract_path"))
            if(phase=="prepare") {
                val baseline=File(spec.getString("baseline_path")).canonicalFile
                require(baseline.path.startsWith(allowed)&&baseline.length()==56365991L)
                assertEquals(expected,hash(baseline));baseline.copyTo(file)
                val current=study.callAttr("new_session",false)
                val ticket=JSONObject(current.callAttr("begin_json",file.path).toString()).getString("ticket")
                val accepted=OutputProofLifecycleTest.Worker(current,ticket,directory,false).also {workers+=it}
                assertTrue(withContext(Dispatchers.IO) {accepted.await()}.getBoolean("accepted"))
                assertTrue(accepted.approved(expected))
                val owner=study.callAttr("new_session",false)
                val pendingTicket=JSONObject(owner.callAttr("begin_json",file.path,"hash").toString()).getString("ticket")
                val pending=OutputProofLifecycleTest.Worker(owner,pendingTicket,directory,false).also {workers+=it}
                assertTrue(owner.callAttr("await_pause",pendingTicket,10).toBoolean())
                assertTrue(NativeWork.busy(directory));assertEquals(1,pending.status().getInt("open_handles"))
                val state=JSONObject().put("previous_pid",android.os.Process.myPid()).put("saved_claim_approved",true)
                    .put("accepted_ticket",ticket).put("pending_ticket",pendingTicket).put("output_sha256",expected)
                    .put("owned_reader_open_before_real_kill",true).put("saved_at_elapsed",SystemClock.elapsedRealtime())
                check(!saved.exists());saved.writeText(state.toString(2))
                instrument.sendStatus(2,Bundle().apply {putString("proof_process_ready",saved.path)})
                check(CountDownLatch(1).await(10,java.util.concurrent.TimeUnit.SECONDS)) {"Host did not perform deliberate process kill; preparation cannot pass normally"}
            } else {
                require(saved.length() in 1..65536&&file.length()==56365991L)
                val state=JSONObject(saved.readText())
                assertNotEquals(state.getInt("previous_pid"),android.os.Process.myPid())
                assertTrue(state.getBoolean("saved_claim_approved"));assertTrue(state.getBoolean("owned_reader_open_before_real_kill"))
                assertEquals(expected,hash(file));assertFalse(NativeWork.busy(directory))
                val fresh=study.callAttr("new_session",false)
                for(key in listOf("accepted_ticket","pending_ticket")) {
                    val verdict=JSONObject(fresh.callAttr("approve_json",state.getString(key),expected).toString())
                    assertFalse(verdict.getBoolean("approved"))
                }
                val recovered=study.callAttr("new_session",true)
                assertFalse(JSONObject(recovered.callAttr("begin_json",file.path).toString()).getBoolean("eligible"))
                assertFalse(JSONObject(recovered.callAttr("approve_json",state.getString("accepted_ticket"),expected).toString()).getBoolean("approved"))
                report.put("previous_pid",state.getInt("previous_pid")).put("actual_process_changed",true)
                    .put("saved_approval_and_pending_ticket_rejected",true).put("recovered_requires_fresh_source_proof",true)
                    .put("actual_downloader_resume_or_publication_recovery",false).put("passed",true)
            }
        } catch(error:Throwable) {failure=error}
        finally {
            for(worker in workers) try {
                worker.control.stop();worker.release();withContext(NonCancellable+Dispatchers.IO) {worker.await()}
                worker.executor.shutdown();check(worker.executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));worker.closeCanceller()
            } catch(error:Throwable) {if(failure==null) failure=error else failure!!.addSuppressed(error)}
            try {withContext(NonCancellable+Dispatchers.Main) {activity?.finish()}}
            catch(error:Throwable) {if(failure==null) failure=error else failure!!.addSuppressed(error)}
            report.put("cleanup_confirmed",!NativeWork.busy(directory)).put("passed",phase=="recover"&&failure==null&&!NativeWork.busy(directory))
            failure?.let {report.put("failure",it.javaClass.simpleName+":"+it.message)}
            val output=File(directory,"$phase-result.json");output.writeText(report.toString(2))
            instrument.sendStatus(2,Bundle().apply {putString("proof_process_report",output.path)})
        }
        failure?.let {throw it}
    }

    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val bytes=ByteArray(64*1024);while(true) {val n=input.read(bytes);if(n<0) break;digest.update(bytes,0,n)}}
        return buildString(64) {for(byte in digest.digest()) {val n=byte.toInt() and 255;append("0123456789abcdef"[n ushr 4]);append("0123456789abcdef"[n and 15])}}
    }
}
