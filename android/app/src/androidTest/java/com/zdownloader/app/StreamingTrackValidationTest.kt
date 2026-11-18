@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.os.Bundle
import android.os.Build
import android.content.Intent
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.*
import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.UrlPolicy
import com.zdownloader.app.engine.NativeWork
import com.zdownloader.app.engine.Processor
import com.zdownloader.app.engine.Transfer
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileDescriptor
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class StreamingTrackValidationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private enum class DecodeOutput { FRAMES, STRICT_NULL }

    private data class Track(val kind:String,val url:String,val ext:String,val bytes:Long,val sha:String,val reference:File,val records:Int) {
        val video get()=kind=="video"
        fun format()=Format.parse(JSONObject().put("format_id","stream-proof-$kind").put("url",url)
            .put("ext",ext).put("filesize",bytes).put("protocol","https")
            .put("vcodec",if(video) "vp9" else "none").put("acodec",if(video) "none" else "aac"))
    }

    private class TrackRun(val track:Track,val directory:File,val output:DecodeOutput) {
        val file=File(directory,"download.${track.ext}")
        val signal=Channel<Unit>(Channel.CONFLATED)
        val control=TransferControl()
        val bytes=AtomicLong(0)
        val completed=AtomicBoolean(false)
        val began=AtomicLong(-1)
        val ended=AtomicLong(-1)
        val firstDecoded=AtomicLong(-1)
        val bytesAtFirstDecoded=AtomicLong(-1)
        val decodedBeforeEof=AtomicBoolean(false)
        var fed=0L
        var feedSha:String?=null
        var decoderFinishedAt=-1L
        var failure:Throwable?=null
        var pipeFd:FileDescriptor?=null
        var pipePath:String?=null
        var referenceNative:NativeSession?=null
        var streamedNative:NativeSession?=null
        val referenceFrames=File(directory,"reference.frames")
        val streamedFrames=File(directory,"streamed.frames")
    }

    private class NativeSession(val latch:CountDownLatch,val session:FFmpegSession) {
        fun cancel() {if(latch.count!=0L) FFmpegKit.cancel(session.sessionId)}
        fun settled()=latch.count==0L
        fun videoFrames()=session.getAllStatistics(1000).maxOfOrNull {it.videoFrameNumber} ?: 0
        fun mediaTime()=session.getAllStatistics(1000).maxOfOrNull {it.time} ?: 0.0
    }

    @Test fun independentDownloadsAllowCompleteTrackDecodeBeforeNetworkEof()=runProof(DecodeOutput.FRAMES)
    @Test fun strictSoftwareDecodeMayOverlapTransferWithoutFrameHashOverhead()=runProof(DecodeOutput.STRICT_NULL)
    @Test fun liveTrackDecodeThenFinalCopyProofKeepsFullOraclesBeforePublication()=runProof(DecodeOutput.STRICT_NULL,true)
    @Test fun finalCopyEvidenceOverlapsSourceDecodeButCannotApproveItEarly()=runProof(DecodeOutput.STRICT_NULL,true,true)

    private fun runProof(output:DecodeOutput,finalCopy:Boolean=false,earlyFinal:Boolean=false)=runBlocking<Unit> {
        require(Build.VERSION.SDK_INT>=29)
        val arguments=InstrumentationRegistry.getArguments()
        val mode=arguments.getString("trackValidationMode") ?: "parallel"
        require(mode in setOf("parallel","serial"))
        val specs=loadTracks(requireNotNull(arguments.getString("trackSpecPath")))
        assertTrue("Another native operation is running",FFmpegKitConfig.getSessionsByState(SessionState.RUNNING).isEmpty())
        val directory=File(requireNotNull(context.getExternalFilesDir(null)),"streaming-track-proof/${UUID.randomUUID()}")
        check(!directory.exists()&&directory.mkdirs())
        val runs=specs.map {TrackRun(it,File(directory,it.kind).apply {check(mkdirs())},output)}
        val sessions=CopyOnWriteArrayList<NativeSession>()
        val workers=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val jobs=mutableListOf<Deferred<Unit>>()
        val oracleControl=TransferControl()
        val began=SystemClock.elapsedRealtime()
        val report=JSONObject().put("mode",mode).put("production_changed",false).put("publication_attempted",false)
            .put("decoder_output",if(output==DecodeOutput.FRAMES) "frames" else "strict_null")
            .put("full_final_source_oracles_retained",true).put("conditions_before",conditions()).put("passed",false)
        var primaryError:Throwable?=null
        var activity:MainActivity?=null
        var finalStudy:ProgressiveFinalCopyStudy?=null
        var finalPreparation:Deferred<Unit>?=null
        try {
            val app=context.applicationContext as ZApplication
            check(app.repository.downloads.first().none {it.status !in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")})
            check(!context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            val active=InstrumentationRegistry.getInstrumentation().startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=active
            withContext(Dispatchers.Main) {active.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            for(run in runs) {
                assertEquals(run.track.bytes,run.track.reference.length())
                assertEquals(run.track.sha,hash(run.track.reference))
                val reference=startDecoder(run,run.track.reference.path,run.referenceFrames,sessions,false)
                run.referenceNative=reference
                awaitNative(reference,180_000)
                if(output==DecodeOutput.FRAMES) assertEquals(run.track.records,frameCount(run.referenceFrames))
                else {
                    if(run.track.video) assertEquals(run.track.records,reference.videoFrames())
                    assertTrue("Ordinary-file strict decode has no media progress",reference.mediaTime()>0)
                }
            }
            report.put("reference_preparation_ms",SystemClock.elapsedRealtime()-began)
            val transfer=Transfer(app.extractor,Transfer.networkClient,Processor())
            val deadline=SystemClock.elapsedRealtime()+240_000
            if(mode=="parallel") for(run in runs) jobs+=workers.async {consume(run,sessions,deadline)}
            val producers=runs.map {run->workers.async {
                run.began.set(SystemClock.elapsedRealtime())
                try {
                    transfer.stream(run.track.format(),run.file,"track-proof-${UUID.randomUUID()}",run.control,awaitIdle=false) {bytes,_->
                        check(bytes<=run.track.bytes) {"Producer exceeded pinned source budget"}
                        run.bytes.set(bytes)
                        run.signal.trySend(Unit)
                    }
                    run.completed.set(true)
                } catch(error:Throwable) {run.failure=error;throw error}
                finally {run.ended.set(SystemClock.elapsedRealtime());run.signal.close()}
            }}
            jobs+=producers
            withTimeout(240_000) {producers.forEach {it.await()}}
            val networkEnded=runs.maxOf {it.ended.get()}
            report.put("network_started_at",runs.minOf {it.began.get()}).put("network_ended_at",networkEnded)
                .put("transfer_ms",networkEnded-runs.minOf {it.began.get()})
            if(earlyFinal) {
                check(finalCopy&&output==DecodeOutput.STRICT_NULL)
                for(run in runs) {
                    assertTrue(run.completed.get());assertEquals(run.track.bytes,run.file.length());assertEquals(run.track.sha,hash(run.file))
                }
                report.put("publication_attempted",true).put("final_evidence_schedule","after_network_eof_while_source_decoders_finish")
                val evidence=JSONObject();report.put("final_copy_study",evidence)
                val study=ProgressiveFinalCopyStudy(context,directory,networkEnded).also {finalStudy=it}
                finalPreparation=workers.async {study.prepare(runs.single {it.track.video}.file,runs.single {!it.track.video}.file,evidence)}
                jobs+=requireNotNull(finalPreparation)
            }
            if(mode=="serial") for(run in runs) jobs+=workers.async {consume(run,sessions,deadline)}
            withTimeout(240_000) {jobs.filter {it!==finalPreparation}.forEach {it.await()}}
            for(run in runs) {
                assertTrue(run.completed.get())
                assertEquals(run.track.bytes,run.file.length())
                assertEquals(run.track.sha,hash(run.file))
                assertEquals(run.track.bytes,run.fed)
                assertEquals(run.track.sha,run.feedSha)
                if(output==DecodeOutput.FRAMES) {
                    assertEquals(run.track.records,frameCount(run.streamedFrames))
                    assertArrayEquals("Complete ${run.track.kind} frame records/headers differ",run.referenceFrames.readBytes(),run.streamedFrames.readBytes())
                } else {
                    val streamed=requireNotNull(run.streamedNative)
                    if(run.track.video) assertEquals(run.track.records,streamed.videoFrames())
                    assertEquals("Strict decode did not reach the reference timeline",requireNotNull(run.referenceNative).mediaTime(),streamed.mediaTime(),0.0)
                }
            }
            val trackGateAt=SystemClock.elapsedRealtime()
            report.put("all_track_decode_gates_at",trackGateAt).put("track_gate_tail_ms",trackGateAt-networkEnded)
                .put("full_frame_parity_checked",output==DecodeOutput.FRAMES)
                .put("native_decode_tail_ms",runs.maxOf {it.decoderFinishedAt}-networkEnded)
            if(finalCopy&&!earlyFinal) {
                check(output==DecodeOutput.STRICT_NULL)
                report.put("publication_attempted",true)
                finalStudy=ProgressiveFinalCopyStudy(context,directory,networkEnded)
                val finalEvidence=JSONObject();report.put("final_copy_study",finalEvidence)
                finalStudy!!.prepare(runs.single {it.track.video}.file,runs.single {!it.track.video}.file,finalEvidence)
            }
            finalPreparation?.let {withTimeout(240_000) {it.await()}}
            if(earlyFinal) {
                val began=report.getJSONObject("final_copy_study").getLong("source_contract_started_at")
                assertTrue("Final evidence never began before source native completion",began<runs.maxOf {it.decoderFinishedAt})
                report.put("final_evidence_actually_started_before_source_decode_completion",true)
            }
            finalStudy?.confirmSourceDecoders(trackGateAt)
            val oracleStarted=SystemClock.elapsedRealtime()
            for(run in runs) Processor().validate(run.file,audio=!run.track.video,requireAudio=!run.track.video,
                expectedDuration=888.053,expectedContainer=run.track.ext,control=oracleControl,
                expectedHeight=if(run.track.video) 272 else null,expectedWidth=if(run.track.video) 640 else null,
                expectedVideoCodec=if(run.track.video) "vp9" else null)
            report.put("retained_full_oracles_ms",SystemClock.elapsedRealtime()-oracleStarted)
                .put("all_gates_tail_including_oracles_ms",SystemClock.elapsedRealtime()-networkEnded)
            finalStudy?.let {study->
                report.put("source_gates_tail_including_retained_source_oracles_ms",SystemClock.elapsedRealtime()-networkEnded)
                study.finishAfterSourceOracles()
                report.put("all_gates_tail_including_oracles_ms",SystemClock.elapsedRealtime()-networkEnded)
            }
            if(mode=="parallel") for(run in runs) {
                assertTrue("${run.track.kind} decode never started before its own Transfer EOF",run.decodedBeforeEof.get())
                assertTrue(run.firstDecoded.get() in run.began.get() until run.ended.get())
                assertTrue(run.bytesAtFirstDecoded.get() in 1 until run.track.bytes)
            }
            report.put("passed",true)
        } catch(error:Throwable) {primaryError=error;report.put("failure",error.javaClass.simpleName+":"+error.message)}
        finally {
            runs.forEach {it.control.stop();it.signal.close()}
            oracleControl.stop()
            finalStudy?.requestStop()
            workers.cancel()
            sessions.forEach {it.cancel()}
            val jobsSettled=withContext(NonCancellable) {withTimeoutOrNull(10_000) {jobs.forEach {it.join()};true} ?: false}
            val nativeSettled=sessions.all {it.settled()||it.latch.await(10,TimeUnit.SECONDS)}
            val cleanupFailures=mutableListOf<Throwable>()
            try {finalStudy?.close()} catch(error:Throwable) {cleanupFailures+=error}
            try {withContext(NonCancellable+Dispatchers.Main) {activity?.finish()}} catch(error:Throwable) {cleanupFailures+=error}
            for(run in runs) {
                try {closeWriter(run)} catch(error:Throwable) {cleanupFailures+=error}
                if(nativeSettled) try {closePipe(run)} catch(error:Throwable) {cleanupFailures+=error}
            }
            cleanupFailures.forEach {if(primaryError==null) primaryError=it else primaryError!!.addSuppressed(it)}
            val terminal=jobsSettled&&nativeSettled&&oracleControl.session==null&&runs.all {it.pipeFd==null&&it.pipePath==null}
            report.put("cleanup_confirmed",terminal).put("conditions_after",conditions())
                .put("total_proof_wall_ms",SystemClock.elapsedRealtime()-began).put("tracks",JSONArray(runs.map(::trackReport)))
            if(!terminal) {
                report.put("passed",false)
                val cleanup=AssertionError("Owned jobs/native operations did not settle; evidence preserved")
                if(primaryError==null) primaryError=cleanup else primaryError.addSuppressed(cleanup)
            }
            report.put("passed",primaryError==null&&terminal)
            primaryError?.let {report.put("failure",it.javaClass.simpleName+":"+it.message)}
            try {
                File(directory,"result.json").writeText(report.toString(2))
                InstrumentationRegistry.getInstrumentation().sendStatus(2,Bundle().apply {putString("track_report",File(directory,"result.json").path)})
            } catch(error:Throwable) {if(primaryError==null) primaryError=error else primaryError.addSuppressed(error)}
        }
        primaryError?.let {throw it}
    }

    private suspend fun consume(run:TrackRun,sessions:MutableList<NativeSession>,deadline:Long) {
        val pipe=FFmpegKitConfig.registerNewFFmpegPipe(context)
        run.pipePath=pipe
        var native:NativeSession?=null
        var input:FileInputStream?=null
        val digest=MessageDigest.getInstance("SHA-256")
        try {
            run.pipeFd=Os.open(pipe,OsConstants.O_RDWR or OsConstants.O_NONBLOCK,0)
            native=startDecoder(run,pipe,run.streamedFrames,sessions,true)
            run.streamedNative=native
            val buffer=ByteArray(64*1024)
            suspend fun drain() {
                if(input==null) input=try {FileInputStream(File(run.file.path+".part"))}
                    catch(_:java.io.FileNotFoundException) {if(run.file.isFile) FileInputStream(run.file) else null}
                val stream=input ?: return
                while(stream.channel.position()<stream.channel.size()) {
                    currentCoroutineContext().ensureActive()
                    val count=stream.read(buffer,0,minOf(buffer.size.toLong(),stream.channel.size()-stream.channel.position()).toInt())
                    check(count>0)
                    check(run.fed+count<=run.track.bytes)
                    writeFifo(requireNotNull(run.pipeFd),buffer,count,requireNotNull(native),deadline)
                    digest.update(buffer,0,count);run.fed+=count
                }
            }
            while(true) {
                drain()
                if(run.signal.receiveCatching().isClosed) break
            }
            run.failure?.let {throw it}
            check(run.completed.get()) {"Transfer did not certify source completion"}
            drain()
            check(run.fed==run.track.bytes) {"Incomplete FIFO feed"}
            run.feedSha=digest.digest().hex()
            check(run.feedSha==run.track.sha) {"FIFO source differs from pinned bytes"}
            closeWriter(run)
            awaitNative(requireNotNull(native),(deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1))
            run.decoderFinishedAt=SystemClock.elapsedRealtime()
        } finally {
            try {input?.close()} finally {
                try {closeWriter(run)} finally {
                    native?.cancel()
                    val ended=native?.let {it.settled()||it.latch.await(10,TimeUnit.SECONDS)} ?: true
                    if(ended) closePipe(run)
                }
            }
        }
    }

    private fun startDecoder(run:TrackRun,input:String,output:File,sessions:MutableList<NativeSession>,observe:Boolean):NativeSession {
        val args=mutableListOf("-nostdin","-v","error","-xerror","-err_detect","explode","-hwaccel","none","-stats_period","0.05")
        if(run.track.video) args+=listOf("-threads:v","4")
        args+=listOf("-protocol_whitelist","file,pipe","-i",input,"-map",if(run.track.video) "0:v:0" else "0:a:0")
        if(run.output==DecodeOutput.FRAMES) {
            args+=if(run.track.video) listOf("-c:v","rawvideo","-fps_mode","passthrough") else listOf("-c:a","pcm_s16le")
            args+=listOf("-max_error_rate","0","-f","framemd5",output.path)
        } else args+=listOf("-max_error_rate","0","-f","null","-")
        val latch=CountDownLatch(1)
        val lease=NativeWork.acquire(run.directory)
        val session=try {FFmpegKit.executeWithArgumentsAsync(args.toTypedArray(),{lease.release();latch.countDown()},null,{stat->
            if(observe&&(if(run.track.video) stat.videoFrameNumber>0 else stat.time>0)&&run.firstDecoded.compareAndSet(-1,SystemClock.elapsedRealtime())) {
                run.bytesAtFirstDecoded.set(run.bytes.get())
                run.decodedBeforeEof.set(!run.completed.get())
            }
        })} catch(error:Throwable) {lease.release();throw error}
        return NativeSession(latch,session).also {sessions+=it}
    }

    private fun awaitNative(native:NativeSession,timeoutMs:Long) {
        if(!native.latch.await(timeoutMs,TimeUnit.MILLISECONDS)) {
            native.cancel();native.latch.await(10,TimeUnit.SECONDS)
            error("Native decode deadline exceeded")
        }
        check(ReturnCode.isSuccess(native.session.returnCode)) {"Decode failed: ${native.session.returnCode?.value}: ${native.session.allLogsAsString.takeLast(1200)}"}
    }

    private suspend fun writeFifo(fd:FileDescriptor,bytes:ByteArray,count:Int,native:NativeSession,deadline:Long) {
        var offset=0
        val poll=arrayOf(StructPollfd().apply {this.fd=fd;events=OsConstants.POLLOUT.toShort()})
        while(offset<count) {
            currentCoroutineContext().ensureActive()
            check(!native.settled()) {"FIFO decoder ended before producer completion"}
            check(SystemClock.elapsedRealtime()<deadline) {"FIFO deadline exceeded"}
            try {val written=Os.write(fd,bytes,offset,count-offset);check(written>0);offset+=written}
            catch(error:android.system.ErrnoException) {
                when(error.errno) {OsConstants.EINTR->Unit;OsConstants.EAGAIN->Os.poll(poll,100);else->throw error}
            }
        }
    }

    private fun closeWriter(run:TrackRun) {run.pipeFd?.let {Os.close(it);run.pipeFd=null}}
    private fun closePipe(run:TrackRun) {run.pipePath?.let {FFmpegKitConfig.closeFFmpegPipe(it);run.pipePath=null}}

    private fun loadTracks(path:String):List<Track> {
        val base=File(requireNotNull(context.getExternalFilesDir(null)),"source-paired-performance").canonicalPath+File.separator
        val spec=File(path).canonicalFile
        require(spec.path.startsWith(base)&&spec.length() in 1..65536)
        val entries=JSONObject(spec.readText()).getJSONArray("tracks")
        require(entries.length()==2)
        val tracks=(0 until entries.length()).map {i->val row=entries.getJSONObject(i)
            val kind=row.getString("kind");require(kind in setOf("audio","video"))
            val url=UrlPolicy.normalize(row.getString("url"));require(url.startsWith("https://"))
            val bytes=row.getLong("bytes");require(bytes in 1..(64L*1024*1024))
            val sha=row.getString("sha256");require(sha.matches(Regex("[0-9a-f]{64}")))
            val reference=File(row.getString("device_reference")).canonicalFile;require(reference.path.startsWith(base)&&reference.isFile)
            val ext=row.getString("ext");require(ext==if(kind=="video") "webm" else "m4a")
            Track(kind,url,ext,bytes,sha,reference,if(kind=="video") 21312 else 41628)
        }
        require(tracks.map {it.kind}.toSet()==setOf("video","audio")&&tracks.sumOf {it.bytes}<=96L*1024*1024)
        return tracks
    }

    private fun trackReport(run:TrackRun)=JSONObject().put("kind",run.track.kind).put("bytes",run.track.bytes)
        .put("source_sha256",run.track.sha).put("feed_sha256",run.feedSha).put("fed_bytes",run.fed)
        .put("transfer_started_at",run.began.get()).put("transfer_ended_at",run.ended.get())
        .put("first_decoded_at",run.firstDecoded.get()).put("bytes_at_first_decode",run.bytesAtFirstDecoded.get())
        .put("decoded_before_own_network_eof",run.decodedBeforeEof.get()).put("decoder_finished_at",run.decoderFinishedAt)
        .put("expected_records",run.track.records).put("reference_frames",run.referenceFrames.path).put("streamed_frames",run.streamedFrames.path)
        .put("reference_video_frames",run.referenceNative?.videoFrames()).put("streamed_video_frames",run.streamedNative?.videoFrames())
        .put("reference_media_time_ms",run.referenceNative?.mediaTime()).put("streamed_media_time_ms",run.streamedNative?.mediaTime())

    private fun frameCount(file:File)=file.useLines {it.count {line->line.isNotBlank()&&!line.startsWith('#')}}
    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val bytes=ByteArray(64*1024);while(true) {val n=input.read(bytes);if(n<0) break;digest.update(bytes,0,n)}}
        return digest.digest().hex()
    }
    private fun ByteArray.hex()=joinToString("") {"%02x".format(it)}
    private fun conditions()=context.getSystemService(android.os.PowerManager::class.java).let {
        JSONObject().put("thermal_status",it.currentThermalStatus).put("power_save",it.isPowerSaveMode).put("interactive",it.isInteractive)
    }
}
