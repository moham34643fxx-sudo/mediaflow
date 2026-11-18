@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.*
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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SoftwareValidationThreadStudyTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val sessions=mutableListOf<Pair<FFmpegSession,CountDownLatch>>()

    @Test fun strictSoftwareThreadBudgetsPreserveEveryDecodedFrame()=runBlocking<Unit> {
        require(android.os.Build.VERSION.SDK_INT>=29)
        val studyBegan=SystemClock.elapsedRealtime()
        val args=InstrumentationRegistry.getArguments()
        val files=requireNotNull(context.getExternalFilesDir(null)).canonicalPath+File.separator
        fun owned(key:String)=File(requireNotNull(args.getString(key))).canonicalFile.also {require(it.path.startsWith(files)&&it.isFile)}
        val spec=owned("trackSpecPath")
        require(spec.length() in 1..65536)
        val tracks=JSONObject(spec.readText()).getJSONArray("tracks")
        val video=(0 until tracks.length()).map {tracks.getJSONObject(it)}.single {it.getString("kind")=="video"}
        val source=File(video.getString("device_reference")).canonicalFile
        require(source.path.startsWith(files+"source-paired-performance"+File.separator)&&source.isFile)
        assertEquals(41451506L,source.length())
        assertEquals("568ba37ba5b60b3393f873e53cdbcb9ded1d999eb8a1ccc65aaec51ec955da83",hash(source))
        val reference=owned("threadStudyReferenceFramesPath")
        require(reference.length() in 1..(8L*1024*1024))
        assertEquals("a55514c9c5786977e19d2c942647f90bb2b3e990ed7fd1f06b19376e224bfefb",hash(reference))
        assertTrue(FFmpegKitConfig.getSessionsByState(SessionState.RUNNING).isEmpty())
        val directory=File(context.getExternalFilesDir(null),"software-thread-study/${UUID.randomUUID()}")
        check(!directory.exists()&&directory.mkdirs())
        val samples=JSONArray()
        val parity=JSONArray()
        val plan=listOf(2,4,4,2,4,8,8,4)
        val report=JSONObject().put("passed",false).put("production_changed",false).put("plan",JSONArray(plan))
            .put("scope","Actual software decode thread study, not a production queue speedup")
            .put("available_processors",Runtime.getRuntime().availableProcessors()).put("samples",samples).put("frame_parity",parity)
        File(directory,"plan.json").writeText(report.toString(2))
        var activity:MainActivity?=null
        var failure:Throwable?=null
        val oracle=TransferControl()
        try {
            val app=context.applicationContext as ZApplication
            check(app.repository.downloads.first().none {it.status !in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")})
            check(!context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            val active=InstrumentationRegistry.getInstrumentation().startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=active
            withContext(Dispatchers.Main) {active.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            val warmupStarted=SystemClock.elapsedRealtime()
            val warmup=decode(source,4,null,directory)
            assertEquals(21312,warmup.getAllStatistics(1000).maxOfOrNull {it.videoFrameNumber} ?: 0)
            report.put("explicit_warmup_ms",SystemClock.elapsedRealtime()-warmupStarted)
            for((index,threads) in plan.withIndex()) {
                val before=conditions()
                val started=SystemClock.elapsedRealtime()
                val session=decode(source,threads,null,directory)
                val elapsed=SystemClock.elapsedRealtime()-started
                val frames=session.getAllStatistics(1000).maxOfOrNull {it.videoFrameNumber} ?: 0
                val sample=JSONObject().put("index",index).put("threads",threads).put("native_command_elapsed_ms",elapsed)
                    .put("video_frames",frames).put("conditions_before",before).put("conditions_after",conditions())
                samples.put(sample)
                assertEquals(21312,frames)
                assertEquals(video.getString("sha256"),hash(source))
                File(directory,"interim.json").writeText(report.toString(2))
            }
            for(threads in listOf(2,8)) {
                val output=File(directory,"threads-$threads.frames")
                decode(source,threads,output,directory)
                val same=output.readBytes().contentEquals(reference.readBytes())
                parity.put(JSONObject().put("threads",threads).put("all_frame_headers_records_identical",same).put("frame_file_sha256",hash(output)))
                assertTrue("Thread count $threads altered the decoded stream",same)
            }
            Processor().validate(source,audio=false,requireAudio=false,expectedDuration=888.053,expectedContainer="webm",
                expectedWidth=640,expectedHeight=272,expectedVideoCodec="vp9",control=oracle)
            assertEquals(video.getString("sha256"),hash(source))
            report.put("retained_full_source_oracle_passed",true).put("source_sha256_after",hash(source)).put("passed",true)
        } catch(error:Throwable) {failure=error;report.put("failure",error.javaClass.simpleName+":"+error.message)}
        finally {
            oracle.stop()
            sessions.filter {it.second.count!=0L}.forEach {FFmpegKit.cancel(it.first.sessionId)}
            val settled=sessions.all {it.second.count==0L||it.second.await(10,TimeUnit.SECONDS)}&&oracle.session==null&&!NativeWork.busy(directory)
            try {withContext(NonCancellable+Dispatchers.Main) {activity?.finish()}}
            catch(error:Throwable) {if(failure==null) failure=error else failure.addSuppressed(error)}
            if(!settled) {
                val cleanup=AssertionError("Native ownership unsettled; all artifacts preserved")
                if(failure==null) failure=cleanup else failure.addSuppressed(cleanup)
            }
            report.put("cleanup_confirmed",settled).put("passed",failure==null&&settled)
                .put("total_study_wall_ms",SystemClock.elapsedRealtime()-studyBegan)
            failure?.let {report.put("failure",it.javaClass.simpleName+":"+it.message)}
            File(directory,"result.json").writeText(report.toString(2))
            InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply {putString("thread_report",File(directory,"result.json").path)})
        }
        failure?.let {throw it}
    }

    private fun decode(source:File,threads:Int,frames:File?,directory:File):FFmpegSession {
        val args=mutableListOf("-nostdin","-v","error","-xerror","-err_detect","explode","-hwaccel","none",
            "-threads:v",threads.toString(),"-stats_period","0.05","-protocol_whitelist","file,pipe","-i",source.path,"-map","0:v:0")
        args+=if(frames==null) listOf("-max_error_rate","0","-f","null","-") else
            listOf("-c:v","rawvideo","-fps_mode","passthrough","-max_error_rate","0","-f","framemd5",frames.path)
        val latch=CountDownLatch(1)
        val lease=NativeWork.acquire(directory)
        val session=try {FFmpegKit.executeWithArgumentsAsync(args.toTypedArray(),{lease.release();latch.countDown()},null,{_ -> Unit})}
            catch(error:Throwable) {lease.release();throw error}
        sessions+=session to latch
        if(!latch.await(180,TimeUnit.SECONDS)) {
            FFmpegKit.cancel(session.sessionId);latch.await(10,TimeUnit.SECONDS)
            error("Thread $threads decode exceeded deadline")
        }
        assertTrue("Thread $threads failed: ${session.allLogsAsString.takeLast(800)}",ReturnCode.isSuccess(session.returnCode))
        return session
    }

    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val bytes=ByteArray(64*1024);while(true) {val n=input.read(bytes);if(n<0) break;digest.update(bytes,0,n)}}
        return digest.digest().joinToString("") {"%02x".format(it)}
    }
    private fun conditions()=context.getSystemService(android.os.PowerManager::class.java).let {
        JSONObject().put("thermal_status",it.currentThermalStatus).put("power_save",it.isPowerSaveMode).put("interactive",it.isInteractive)
    }
}
