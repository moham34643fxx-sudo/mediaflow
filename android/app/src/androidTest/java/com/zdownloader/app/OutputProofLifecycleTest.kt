@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.PyObject
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
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real file readers and real NativeWork/TransferControl; no production proof hook. */
class OutputProofLifecycleTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrument.targetContext
    private val workers=mutableListOf<Worker>()

    internal class Worker(val session:PyObject,val ticket:String,directory:File,queued:Boolean,val control:TransferControl=TransferControl()) {
        val reservation=NativeWork.Reservation(directory)
        val executor=Executors.newSingleThreadExecutor()
        val blocker=CountDownLatch(if(queued) 1 else 0)
        val blockerStarted=CountDownLatch(if(queued) 1 else 0)
        val done=CountDownLatch(1)
        val value=AtomicReference<JSONObject?>()
        val failure=AtomicReference<Throwable?>()
        private val cancelKey=Any()
        init {
            control.addCanceller(cancelKey) {try {session.callAttr("invalidate_json","cancel")} finally {reservation.cancelPending()}}
            if(queued) executor.submit {blockerStarted.countDown();check(blocker.await(15,TimeUnit.SECONDS))}
            executor.submit {
                try {
                    value.set(if(reservation.start()) JSONObject(session.callAttr("run_json",ticket).toString())
                        else JSONObject().put("accepted",false).put("status","CANCELLED_BEFORE_START"))
                } catch(error:Throwable) {failure.set(error)}
                finally {reservation.finish();done.countDown()}
            }
        }
        fun release() {session.callAttr("release_pause",ticket);blocker.countDown()}
        fun await():JSONObject {
            check(done.await(20,TimeUnit.SECONDS)) {"Actual reader did not settle; timeout is not cancellation success"}
            failure.get()?.let {throw it}
            return requireNotNull(value.get())
        }
        fun status()=JSONObject(session.callAttr("status_json",ticket).toString())
        fun approved(hash:String)=!control.stopped.get()&&JSONObject(session.callAttr("approve_json",ticket,hash).toString()).getBoolean("approved")
        fun closeCanceller() {check(done.count==0L);control.removeCanceller(cancelKey)}
    }

    @Test fun cancelledChangedAndStaleProofsNeverAuthorizeOutput()=runBlocking<Unit> {
        require(android.os.Build.VERSION.SDK_INT>=29)
        val began=SystemClock.elapsedRealtime()
        val allowed=File(requireNotNull(context.getExternalFilesDir(null)),"source-paired-performance").canonicalPath+File.separator
        fun owned(path:String)=File(path).canonicalFile.also {require(it.path.startsWith(allowed)&&it.isFile)}
        val specFile=owned(requireNotNull(InstrumentationRegistry.getArguments().getString("proofLifecycleSpecPath")))
        require(specFile.length() in 1..(4L*1024*1024))
        val spec=JSONObject(specFile.readText())
        for(key in listOf("module","runtime","contract")) {
            val file=owned(spec.getString(key+"_path"))
            require(file.length() in 1..(24L*1024*1024))
            assertEquals(spec.getString(key+"_sha256"),hash(file))
        }
        val baseline=owned(spec.getString("baseline_path"))
        val expectedSha="45446109c38af58a751c6b4a204c246b602d542cb7d0bfbc630955835042a5ff"
        assertEquals(56365991L,baseline.length());assertEquals(expectedSha,hash(baseline))
        val directory=File(context.getExternalFilesDir(null),"proof-ownership-lifecycle/${UUID.randomUUID()}")
        check(!directory.exists()&&directory.mkdirs())
        require(android.os.StatFs(directory.path).availableBytes>=2L*1024*1024*1024)
        val report=JSONObject().put("passed",false).put("production_changed",false).put("certificate_issued",false)
            .put("scope","Actual app runtime reads/cancel/file-race/NativeWork tests; generation labels are not live downloader resume or OS-kill evidence")
        val records=JSONArray();report.put("cases",records)
        var activity:MainActivity?=null
        var failure:Throwable?=null
        val oracle=TransferControl()
        try {
            val app=context.applicationContext as ZApplication
            check(app.repository.downloads.first().none {it.status !in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")})
            check(!context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            val active=instrument.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=active
            withContext(Dispatchers.Main) {active.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            val python=app.extractor.python()
            val namespace=python.getModule("runpy").callAttr("run_path",spec.getString("module_path"))
            val study=namespace.callAttr("__getitem__","DeviceStudy").callAttr("__call__",spec.getString("contract_path"))
            fun fixture(name:String)=File(directory,"$name.mp4").also {check(!it.exists());baseline.copyTo(it)}
            fun session(recovered:Boolean=false)=study.callAttr("new_session",recovered)
            fun start(file:File,pause:String="none",queued:Boolean=false):Worker {
                val owner=session()
                val token=JSONObject(owner.callAttr("begin_json",file.path,pause).toString())
                assertTrue(token.getBoolean("eligible"))
                return Worker(owner,token.getString("ticket"),directory,queued).also {workers+=it}
            }
            fun save(name:String,detail:JSONObject) {
                records.put(detail.put("name",name).put("passed",true))
                File(directory,"interim.json").writeText(report.toString(2))
            }
            fun paused(name:String,phase:String):Pair<File,Worker> {
                val file=fixture(name)
                val worker=start(file,phase)
                assertTrue(worker.session.callAttr("await_pause",worker.ticket,10).toBoolean())
                val status=worker.status()
                assertTrue(status.getBoolean("started"));assertFalse(status.getBoolean("finished"))
                assertEquals(1,status.getInt("open_handles"));assertTrue(status.getLong("bytes_read")>0)
                assertTrue(NativeWork.busy(directory))
                return file to worker
            }
            suspend fun finish(worker:Worker):JSONObject {
                worker.release()
                val result=withContext(Dispatchers.IO) {worker.await()}
                val status=worker.status()
                assertTrue(status.getBoolean("finished"));assertEquals(0,status.getInt("open_handles"))
                assertEquals(1,status.getInt("closed_handles"));assertFalse(NativeWork.busy(directory))
                return result
            }
            val valid=fixture("valid_current")
            val current=start(valid)
            val validStarted=SystemClock.elapsedRealtime()
            val validResult=withContext(Dispatchers.IO) {current.await()}
            val gateMs=SystemClock.elapsedRealtime()-validStarted
            assertTrue(validResult.getBoolean("accepted"));assertEquals(expectedSha,validResult.getString("file_sha256"))
            assertFalse(current.approved("0".repeat(64)));assertTrue(current.approved(hash(valid)))
            val strictStarted=SystemClock.elapsedRealtime()
            Processor().validate(valid,audio=false,requireAudio=true,expectedDuration=888.053,expectedContainer="mp4",
                expectedWidth=640,expectedHeight=272,expectedVideoCodec="vp9",control=oracle)
            val strictMs=SystemClock.elapsedRealtime()-strictStarted
            current.control.stop();assertFalse(current.approved(expectedSha))
            assertFalse(JSONObject(current.session.callAttr("approve_json",current.ticket,expectedSha).toString()).getBoolean("approved"))
            save("valid_readback_binding_and_late_cancel",JSONObject().put("candidate_ms",gateMs).put("retained_strict_ms",strictMs))
            for(phase in listOf("read","hash","before_accept")) {
                val (file,worker)=paused("cancel_$phase",phase)
                val waiter=async(start=CoroutineStart.UNDISPATCHED) {NativeWork.awaitIdle(directory)}
                assertFalse(waiter.isCompleted)
                worker.control.stop()
                assertTrue(NativeWork.busy(directory));assertFalse(worker.approved(expectedSha));assertFalse(waiter.isCompleted)
                val result=finish(worker)
                withTimeout(5_000) {waiter.await()}
                assertFalse(result.getBoolean("accepted"));assertEquals("CANCELLED_OR_STALE",result.getString("status"))
                assertEquals(expectedSha,hash(file))
                save("cancel_$phase",JSONObject().put("result",result).put("actual_reader_settled",true))
            }
            for(kind in listOf("append","truncate","replace","rewrite_first_chunk")) {
                val (file,worker)=paused("race_$kind","read")
                when(kind) {
                    "append"->file.appendBytes(byteArrayOf(88))
                    "truncate"->RandomAccessFile(file,"rw").use {it.setLength(it.length()*3/4)}
                    "replace"->{val replacement=File(directory,"race_replace.replacement");check(!replacement.exists());baseline.copyTo(replacement);check(replacement.renameTo(file))}
                    else->RandomAccessFile(file,"rw").use {it.seek(64);val byte=it.readByte();it.seek(64);it.writeByte(byte.toInt() xor 1)}
                }
                val result=finish(worker)
                assertFalse(result.getBoolean("accepted"));assertFalse(worker.approved(hash(file)))
                save("race_$kind",JSONObject().put("result",result).put("actual_file_mutation_completed",true))
            }
            for(reason in listOf("retry","resume","range_change","source_refresh","process_death")) {
                val (file,worker)=paused("generation_$reason","hash")
                worker.session.callAttr("invalidate_json",reason)
                val result=finish(worker)
                assertFalse(result.getBoolean("accepted"));assertFalse(worker.approved(expectedSha))
                assertFalse(JSONObject(worker.session.callAttr("begin_json",file.path).toString()).getBoolean("eligible"))
                save("generation_$reason",JSONObject().put("result",result).put("qualification","In-memory invalidation, not actual downloader resume or OS process death"))
            }
            val queuedFile=fixture("queued_cancel")
            val queued=start(queuedFile,queued=true)
            assertTrue(queued.blockerStarted.await(5,TimeUnit.SECONDS));assertTrue(NativeWork.busy(directory))
            queued.control.stop();assertFalse(NativeWork.busy(directory))
            queued.release()
            val queuedResult=withContext(Dispatchers.IO) {queued.await()}
            assertFalse(queuedResult.getBoolean("accepted"));assertFalse(queued.status().getBoolean("started"))
            assertEquals(0L,queued.status().getLong("bytes_read"));assertFalse(queued.approved(expectedSha))
            val foreign=session()
            assertFalse(JSONObject(foreign.callAttr("approve_json",current.ticket,expectedSha).toString()).getBoolean("approved"))
            assertFalse(JSONObject(session(true).callAttr("begin_json",valid.path).toString()).getBoolean("eligible"))
            save("queued_cancel_and_saved_ticket_rejection",JSONObject().put("queued_result",queuedResult).put("actual_nativework_reservation_exercised",true))
            assertEquals(expectedSha,hash(baseline));assertFalse(NativeWork.busy(directory))
            report.put("all_fourteen_scenarios_completed",records.length()==14)
            assertEquals(14,records.length())
        } catch(error:Throwable) {failure=error}
        finally {
            oracle.stop()
            for(worker in workers) try {worker.control.stop();worker.release();worker.executor.shutdown()}
                catch(error:Throwable) {if(failure==null) failure=error else failure!!.addSuppressed(error)}
            val settled=workers.all {it.done.await(20,TimeUnit.SECONDS)&&it.executor.awaitTermination(5,TimeUnit.SECONDS)}&&oracle.session==null&&!NativeWork.busy(directory)
            if(settled) workers.forEach {it.closeCanceller()}
            if(!settled) {
                val cleanup=AssertionError("Owned reader/executor/native lease did not settle")
                if(failure==null) failure=cleanup else failure!!.addSuppressed(cleanup)
            }
            try {withContext(NonCancellable+Dispatchers.Main) {activity?.finish()}}
            catch(error:Throwable) {if(failure==null) failure=error else failure!!.addSuppressed(error)}
            report.put("cleanup_confirmed",settled).put("passed",failure==null&&settled).put("total_study_wall_ms",SystemClock.elapsedRealtime()-began)
            failure?.let {report.put("failure",it.javaClass.simpleName+":"+it.message)}
            File(directory,"result.json").writeText(report.toString(2))
            instrument.sendStatus(2,android.os.Bundle().apply {putString("proof_lifecycle_report",File(directory,"result.json").path)})
        }
        failure?.let {throw it}
    }

    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val bytes=ByteArray(64*1024);while(true) {val n=input.read(bytes);if(n<0) break;digest.update(bytes,0,n)}}
        return buildString(64) {for(byte in digest.digest()) {val n=byte.toInt() and 255;append("0123456789abcdef"[n ushr 4]);append("0123456789abcdef"[n and 15])}}
    }
}
