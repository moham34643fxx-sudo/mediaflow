@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.PyObject
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Actual Transfer/Processor calls; HTTP fixture only at the external request boundary. */
class ProofTransferBoundaryTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ZApplication
    private val workers=mutableListOf<OutputProofLifecycleTest.Worker>()
    private val controls=mutableListOf<TransferControl>()
    private val videoSha="568ba37ba5b60b3393f873e53cdbcb9ded1d999eb8a1ccc65aaec51ec955da83"
    private val audioSha="be6b60e0ec9130e37e3077c24e348e78d5a9be7069fe896d4dde5cde2b13d703"
    private val outputSha="45446109c38af58a751c6b4a204c246b602d542cb7d0bfbc630955835042a5ff"

    private fun owned(path:String):File {
        val base=File(requireNotNull(app.getExternalFilesDir(null)),"source-paired-performance").canonicalPath+File.separator
        return File(path).canonicalFile.also {require(it.path.startsWith(base)&&it.isFile)}
    }
    private fun study():Pair<PyObject,File> {
        val file=owned(requireNotNull(InstrumentationRegistry.getArguments().getString("proofLifecycleSpecPath")))
        require(file.length() in 1..65536)
        val spec=JSONObject(file.readText())
        for(key in listOf("module","runtime","contract")) {
            val path=owned(spec.getString(key+"_path"));require(path.length() in 1..(24L*1024*1024))
            assertEquals(spec.getString(key+"_sha256"),hash(path))
        }
        val baseline=owned(spec.getString("baseline_path"));assertEquals(outputSha,hash(baseline))
        val namespace=app.extractor.python().getModule("runpy").callAttr("run_path",spec.getString("module_path"))
        return namespace.callAttr("__getitem__","DeviceStudy").callAttr("__call__",spec.getString("contract_path")) to baseline
    }
    private fun begin(study:PyObject,file:File,pause:String,control:TransferControl):OutputProofLifecycleTest.Worker {
        val owner=study.callAttr("new_session",false)
        val token=JSONObject(owner.callAttr("begin_json",file.path,pause).toString())
        return OutputProofLifecycleTest.Worker(owner,token.getString("ticket"),file.parentFile!!,false,control).also {workers+=it}
    }
    private suspend fun settle():Boolean {
        var settled=true
        for(worker in workers) {
            try {
                worker.control.stop();worker.release()
                withContext(NonCancellable+Dispatchers.IO) {worker.await()}
                worker.executor.shutdown();check(worker.executor.awaitTermination(5,TimeUnit.SECONDS));worker.closeCanceller()
            } catch(_:Throwable) {settled=false}
        }
        controls.forEach {it.stop()}
        return settled&&controls.all {it.session==null}
    }
    private fun emit(directory:File,key:String,report:JSONObject) {
        val file=File(directory,"result.json");file.writeText(report.toString(2))
        instrument.sendStatus(2,Bundle().apply {putString(key,file.path)})
    }
    private suspend fun idle() {check(app.repository.downloads.first().none {it.status !in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")})}

    @Test fun interruptedSourceResumesOnlyThroughFreshFullValidationFallback()=runBlocking<Unit> {
        require(android.os.Build.VERSION.SDK_INT>=29);idle()
        val (study,baseline)=study()
        val spec=owned(requireNotNull(InstrumentationRegistry.getArguments().getString("trackSpecPath")))
        require(spec.length() in 1..65536)
        val tracks=JSONObject(spec.readText()).getJSONArray("tracks")
        fun source(kind:String)=owned((0 until tracks.length()).map {tracks.getJSONObject(it)}.single {it.getString("kind")==kind}.getString("device_reference"))
        val originalAudio=source("audio");val originalVideo=source("video")
        assertEquals(audioSha,hash(originalAudio));assertEquals(videoSha,hash(originalVideo))
        val audioBytes=originalAudio.readBytes()
        val directory=File(app.getExternalFilesDir(null),"proof-transfer-boundaries/${UUID.randomUUID()}").apply {check(!exists()&&mkdirs())}
        val report=JSONObject().put("passed",false).put("production_changed",false).put("certificate_issued",false)
            .put("scope","Real Transfer cancel/resume/ETag and full Processor fallback with intercepted HTTP fixture; not live network or production proof integration")
        val cases=JSONArray();report.put("cases",cases)
        var activity:MainActivity?=null
        var failure:Throwable?=null
        try {
            check(!app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            val active=instrument.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=active
            withContext(Dispatchers.Main) {active.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            val format=Format.parse(JSONObject().put("format_id","resume-aac-proof").put("url","https://example.com/proof-audio.m4a")
                .put("ext","m4a").put("protocol","https").put("vcodec","none").put("acodec","aac").put("filesize",audioBytes.size)
                .put("asr",48000).put("audio_channels",2).put("language","en"))
            fun reply(request:Request,bytes:ByteArray,start:Int,end:Int,etag:String)=Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(206).message("Controlled public-source byte fixture").header("ETag",etag)
                .header("Content-Range","bytes $start-$end/${audioBytes.size}").body(ResponseBody.create(null,bytes)).build()
            for(kind in listOf("resume_matching_etag","resume_changed_etag")) {
                val home=File(directory,kind).apply {check(mkdirs())}
                val proofDir=File(home,"proof").apply {check(mkdirs())}
                val proofOutput=File(proofDir,"output.mp4");baseline.copyTo(proofOutput)
                val target=File(File(home,"transfer").apply {check(mkdirs())},"audio.m4a")
                val control=TransferControl().also {controls+=it}
                val worker=begin(study,proofOutput,"hash",control)
                assertTrue(worker.session.callAttr("await_pause",worker.ticket,10).toBoolean());assertTrue(NativeWork.busy(proofDir))
                val requests=JSONArray()
                val client=OkHttpClient.Builder().addInterceptor {chain->
                    val request=chain.request();requests.put(JSONObject().put("range",request.header("Range")).put("if_range",request.header("If-Range")))
                    reply(request,audioBytes.copyOfRange(0,65536),0,65535,"\"proof-source-v1\"")
                }.build()
                try {
                    Transfer(app.extractor,client).stream(format,target,"proof-$kind-first",control,awaitIdle=false) {count,_->if(count>=65536) control.stop()}
                    fail("The actual first transfer must cancel")
                } catch(_:CancellationException) {}
                val part=File(target.path+".part");val resume=File(target.path+".resume")
                assertTrue(part.isFile&&resume.isFile);assertEquals(65536L,part.length());assertFalse(target.exists())
                assertFalse(File(target.path+".complete").exists())
                assertEquals("\"proof-source-v1\"",JSONObject(resume.readText()).getString("validator"))
                assertTrue(NativeWork.busy(proofDir));assertFalse(worker.approved(outputSha))
                worker.release();assertFalse(withContext(Dispatchers.IO) {worker.await()}.getBoolean("accepted"));assertFalse(NativeWork.busy(proofDir))
                val next=TransferControl().also {controls+=it}
                val resumeClient=OkHttpClient.Builder().addInterceptor {chain->
                    val request=chain.request();val range=requireNotNull(request.header("Range"));val bounds=range.removePrefix("bytes=").split('-').map(String::toInt)
                    requests.put(JSONObject().put("range",range).put("if_range",request.header("If-Range")))
                    assertEquals(65536,bounds[0]);assertEquals("\"proof-source-v1\"",request.header("If-Range"))
                    val end=minOf(bounds[1],audioBytes.lastIndex)
                    reply(request,audioBytes.copyOfRange(bounds[0],end+1),bounds[0],end,
                        if(kind=="resume_matching_etag") "\"proof-source-v1\"" else "\"changed-source-v2\"")
                }.build()
                val case=JSONObject().put("name",kind).put("requests",requests).put("old_proof_denied",true)
                if(kind=="resume_matching_etag") {
                    Transfer(app.extractor,resumeClient).stream(format,target,"proof-resume-second",next,awaitIdle=false) {_,_->}
                    assertEquals(audioBytes.size.toLong(),target.length());assertEquals(audioSha,hash(target))
                    assertTrue(File(target.path+".complete").isFile)
                    val recovered=study.callAttr("new_session",true)
                    assertFalse(JSONObject(recovered.callAttr("begin_json",proofOutput.path).toString()).getBoolean("eligible"))
                    val started=SystemClock.elapsedRealtime()
                    Processor().validate(target,audio=true,requireAudio=true,expectedDuration=888.053,expectedContainer="m4a",control=next)
                    case.put("resumed_source_full_oracle_ms",SystemClock.elapsedRealtime()-started)
                    val videoFormat=Format.parse(JSONObject().put("format_id","proof-video").put("url","https://example.com/proof-video.webm")
                        .put("ext","webm").put("protocol","https").put("vcodec","vp9").put("acodec","none").put("width",640).put("height",272))
                    val output=File(home,"resumed-full-fallback.mp4")
                    val processStarted=SystemClock.elapsedRealtime()
                    Processor().process(originalVideo,target,output,Selection(videoFormat,format,output="mp4",embedMetadata=false),next,888.053)
                    case.put("copy_and_complete_final_fallback_ms",SystemClock.elapsedRealtime()-processStarted)
                        .put("output_sha256",hash(output)).put("full_output_fallback_after_actual_resume_passed",true)
                    assertEquals(outputSha,hash(output));assertFalse(worker.approved(outputSha))
                } else {
                    try {
                        Transfer(app.extractor,resumeClient).stream(format,target,"proof-resume-changed",next,awaitIdle=false) {_,_->}
                        fail("Changed validator must reject actual resumed input")
                    } catch(error:MediaException) {assertEquals(ErrorCode.DOWNLOAD_INTERRUPTED,error.code)}
                    assertFalse(part.exists());assertFalse(target.exists());assertFalse(File(target.path+".complete").exists())
                    assertFalse(worker.approved(outputSha));case.put("changed_source_partial_deleted_and_old_proof_denied",true)
                }
                assertEquals(2,requests.length());assertEquals(outputSha,hash(proofOutput));cases.put(case)
            }
            assertEquals(audioSha,hash(originalAudio));assertEquals(videoSha,hash(originalVideo));assertEquals(outputSha,hash(baseline))
        } catch(error:Throwable) {failure=error}
        finally {
            val settled=settle()&&!NativeWork.busy(directory)
            if(!settled&&failure==null) failure=AssertionError("Owned source/proof reader did not settle")
            try {withContext(NonCancellable+Dispatchers.Main) {activity?.finish()}} catch(error:Throwable) {if(failure==null) failure=error else failure!!.addSuppressed(error)}
            report.put("cleanup_confirmed",settled).put("passed",failure==null&&cases.length()==2)
            failure?.let {report.put("failure",it.javaClass.simpleName+":"+it.message)}
            emit(directory,"proof_transfer_report",report)
        }
        failure?.let {throw it}
    }

    @Test fun screenOffReaderAndFullFinalOracleSettleWithoutStaleApproval()=runBlocking<Unit> {
        require(android.os.Build.VERSION.SDK_INT>=29);idle()
        val (study,baseline)=study()
        val directory=File(app.getExternalFilesDir(null),"proof-screenoff/${UUID.randomUUID()}").apply {check(!exists()&&mkdirs())}
        val output=File(directory,"output.mp4");baseline.copyTo(output)
        val control=TransferControl().also {controls+=it}
        val report=JSONObject().put("passed",false).put("production_changed",false).put("certificate_issued",false)
            .put("scope","Actual display-off proof reader and full native validation; not a production service or network-background acceptance")
        val off=CompletableDeferred<Unit>()
        val receiver=object:BroadcastReceiver() {override fun onReceive(context:Context?,intent:Intent?) {if(intent?.action==Intent.ACTION_SCREEN_OFF) off.complete(Unit)}}
        var failure:Throwable?=null
        app.registerReceiver(receiver,IntentFilter(Intent.ACTION_SCREEN_OFF))
        try {
            val worker=begin(study,output,"hash",control)
            assertTrue(worker.session.callAttr("await_pause",worker.ticket,10).toBoolean())
            assertTrue(NativeWork.busy(directory))
            instrument.sendStatus(2,Bundle().apply {putString("proof_screenoff_ready",directory.path)})
            withTimeout(10_000) {off.await()}
            val power=app.getSystemService(android.os.PowerManager::class.java)
            assertFalse(power.isInteractive)
            worker.release()
            val started=SystemClock.elapsedRealtime()
            val accepted=withContext(Dispatchers.IO) {worker.await()}
            assertTrue(accepted.getBoolean("accepted"));assertFalse(power.isInteractive)
            report.put("actual_screen_off_broadcast_observed",true).put("candidate_after_release_ms",SystemClock.elapsedRealtime()-started)
            val strictStarted=SystemClock.elapsedRealtime()
            Processor().validate(output,audio=false,requireAudio=true,expectedDuration=888.053,expectedContainer="mp4",
                expectedWidth=640,expectedHeight=272,expectedVideoCodec="vp9",control=control)
            assertFalse(power.isInteractive);assertEquals(outputSha,hash(output));assertTrue(worker.approved(outputSha))
            control.stop();assertFalse(worker.approved(outputSha))
            report.put("retained_full_final_oracle_ms",SystemClock.elapsedRealtime()-strictStarted).put("screen_remained_off_through_complete_validation",true)
        } catch(error:Throwable) {failure=error}
        finally {
            app.unregisterReceiver(receiver)
            val settled=settle()&&!NativeWork.busy(directory)
            if(!settled&&failure==null) failure=AssertionError("Screen-off work ownership did not settle")
            report.put("cleanup_confirmed",settled).put("passed",failure==null)
            failure?.let {report.put("failure",it.javaClass.simpleName+":"+it.message)}
            emit(directory,"proof_screenoff_report",report)
        }
        failure?.let {throw it}
    }

    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val buffer=ByteArray(64*1024);while(true) {val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n)}}
        return buildString(64) {for(byte in digest.digest()) {val n=byte.toInt() and 255;append("0123456789abcdef"[n ushr 4]);append("0123456789abcdef"[n and 15])}}
    }
}
