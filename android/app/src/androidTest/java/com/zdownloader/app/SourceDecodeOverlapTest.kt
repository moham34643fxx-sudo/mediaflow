@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.Format
import com.zdownloader.app.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Actual native work through Processor's admission; no output approval API exists here. */
class SourceDecodeOverlapTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ZApplication
    private val videoSha="568ba37ba5b60b3393f873e53cdbcb9ded1d999eb8a1ccc65aaec51ec955da83"
    private val audioSha="be6b60e0ec9130e37e3077c24e348e78d5a9be7069fe896d4dde5cde2b13d703"

    private suspend fun directory():File {
        check(app.repository.downloads.first().none {it.status !in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")})
        return File(app.filesDir,"transfers/source-overlap-${UUID.randomUUID()}").apply {check(!exists()&&mkdirs())}
    }

    @Test fun successfulIndependentTransfersBindTheEntireDecodedSources()=runBlocking<Unit> {
        val home=directory()
        val video=File(home,"primary.webm");val audio=File(home,"secondary.m4a")
        val control=TransferControl()
        val processor=Processor()
        val verification=requireNotNull(processor.beginCopyVerification(app,CopyVerification.Inputs(video,audio,control)))
        val report=JSONObject().put("passed",false).put("production_queue_wired",false).put("output_approval_issued",false)
            .put("scope","Real HTTPS producers and production source-reader/native admission module only; no gallery-readiness benchmark")
        var failure:Throwable?=null
        try {
            val transfer=Transfer(app.extractor)
            fun format(kind:String)=Format.parse(JSONObject().put("format_id","source-overlap-$kind").put("protocol","https")
                .put("url",if(kind=="video") "https://storage.googleapis.com/shaka-demo-assets/sintel/v-0360p-0550k-vp9.webm"
                    else "https://storage.googleapis.com/shaka-demo-assets/sintel/a-eng-0128k-aac.mp4")
                .put("ext",if(kind=="video") "webm" else "m4a").put("filesize",if(kind=="video") 41451506L else 14702221L)
                .put("vcodec",if(kind=="video") "vp9" else "none").put("acodec",if(kind=="audio") "aac" else "none"))
            withTimeout(240_000) {
                coroutineScope {
                    listOf("video" to video,"audio" to audio).map {(kind,file)->async(Dispatchers.IO) {
                        transfer.stream(format(kind),file,"source-overlap-${home.name}",control,awaitIdle=false) {_,_->verification.progress(file)}
                        verification.transferred(file)
                    }}.awaitAll()
                }
                val sources=requireNotNull(verification.awaitSources())
                assertEquals(41451506L,sources.video.bytes);assertEquals(14702221L,sources.audio.bytes)
                assertEquals(videoSha,sources.video.sha256);assertEquals(audioSha,sources.audio.sha256)
                assertEquals(videoSha,hash(video));assertEquals(audioSha,hash(audio))
                assertEquals(21312L,sources.video.videoFrames)
                assertTrue(sources.video.mediaTimeMs>0&&sources.audio.mediaTimeMs>0)
                assertTrue(File(video.path+".complete").isFile&&File(audio.path+".complete").isFile)
                report.put("video_bytes",sources.video.bytes).put("audio_bytes",sources.audio.bytes)
                    .put("video_sha256",sources.video.sha256).put("audio_sha256",sources.audio.sha256)
                    .put("video_frames",sources.video.videoFrames).put("video_time_ms",sources.video.mediaTimeMs)
                    .put("audio_time_ms",sources.audio.mediaTimeMs)
            }
            assertFalse(control.stopped.get())
        } catch(error:Throwable) {failure=error}
        finally {
            verification.cancel()
            val settled=verification.awaitSettled()&&!NativeWork.busy(home)
            if(!settled&&failure==null) failure=AssertionError("Source readers/native sessions did not actually settle")
            control.stop()
            report.put("cleanup_confirmed",settled).put("passed",failure==null)
            failure?.let {report.put("failure",it.javaClass.simpleName+":"+it.message)}
            emit(home,report)
        }
        failure?.let {throw it}
    }

    @Test fun ordinaryValidationYieldsBlockedSourceReadersWithoutStoppingDownloads()=runBlocking<Unit> {
        val home=directory()
        val sourceRoot=File(requireNotNull(app.getExternalFilesDir(null)),"source-paired-performance").canonicalPath+File.separator
        val fixture=File(requireNotNull(InstrumentationRegistry.getArguments().getString("sourceOverlapAudioPath"))).canonicalFile
        require(fixture.path.startsWith(sourceRoot)&&fixture.isFile)
        assertEquals(audioSha,hash(fixture))
        val control=TransferControl()
        val processor=Processor()
        val verification=requireNotNull(processor.beginCopyVerification(app,CopyVerification.Inputs(File(home,"primary.webm"),File(home,"secondary.m4a"),control)))
        val report=JSONObject().put("passed",false).put("production_queue_wired",false).put("output_approval_issued",false)
        var failure:Throwable?=null
        try {
            assertTrue(NativeWork.busy(home))
            withTimeout(5_000) {NativeWork.awaitOtherWork(home,verification.readerOwner)}
            val cleanupWait=async(start=CoroutineStart.UNDISPATCHED) {NativeWork.awaitIdle(home)}
            assertFalse(cleanupWait.isCompleted)
            withTimeout(60_000) {processor.validate(fixture,audio=true,requireAudio=true,expectedDuration=888.053,expectedContainer="m4a")}
            assertNull(verification.awaitSources())
            withTimeout(10_000) {cleanupWait.await()}
            assertFalse(control.stopped.get())
            assertEquals(audioSha,hash(fixture))
            report.put("ordinary_full_validation_passed",true).put("optional_source_evidence_denied",true)
                .put("network_control_not_stopped_by_preemption",true)
        } catch(error:Throwable) {failure=error}
        finally {
            verification.cancel()
            val settled=verification.awaitSettled()&&!NativeWork.busy(home)
            if(!settled&&failure==null) failure=AssertionError("Yielded source readers/native sessions did not settle")
            control.stop()
            report.put("cleanup_confirmed",settled).put("passed",failure==null)
            failure?.let {report.put("failure",it.javaClass.simpleName+":"+it.message)}
            emit(home,report)
        }
        failure?.let {throw it}
    }

    private fun emit(home:File,report:JSONObject) {
        val output=File(home,"source-overlap-result.json");output.writeText(report.toString(2))
        instrument.sendStatus(2,Bundle().apply {putString("source_overlap_report",output.path)})
    }
    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val bytes=ByteArray(64*1024);while(true) {val count=input.read(bytes);if(count<0) break;digest.update(bytes,0,count)}}
        return digest.digest().joinToString("") {"%02x".format(it)}
    }
}
