package com.zdownloader.app

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.engine.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real source measurements; no modification or retry of existing download rows. */
class AudioPerformanceEvidenceTest {
    @Test fun separatesAudioNetworkEncodingValidationAndCopyCosts():Unit=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        val arguments=InstrumentationRegistry.getArguments()
        val url=checkNotNull(arguments.getString("audioSource"))
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${arguments.getString("evidenceRun")}").apply {mkdirs()}
        val directory=app.storage.temporary("audio-performance-${UUID.randomUUID()}")
        val cpu=app.getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"MediaFlow:audio-benchmark")
        cpu.acquire(10*60_000L)
        val report=JSONObject().put("scope","Instrumented real-source transfers on the affected phone; two interleaved runs per fragment setting, then local MP3 processing. No existing jobs are resumed or removed.")
        val network=JSONArray();report.put("transfers",network)
        fun save()=File(folder,"audio-performance.json").writeText(report.toString(2))
        try {
            val started=SystemClock.elapsedRealtime()
            val media=app.extractor.analyze(url,forceRefresh=true,enrichOptional=false)
            val selection=Selection.audio(media,"mp3").copy(bitrate=192)
            report.put("analysis_ms",SystemClock.elapsedRealtime()-started).put("duration_s",media.duration)
                .put("protocol",selection.primary.protocol).put("source_audio_codec",selection.primary.audioCodec)
                .put("source_has_video",selection.primary.video)
            var reference:String?=null;var primary:File?=null
            for((index,concurrency) in listOf(2,4,4,2).withIndex()) {
                val file=File(directory,"source-$index.${selection.primary.ext}")
                val control=TransferControl().apply {fragmentConcurrency=concurrency}
                var firstByte:Long?=null;val begin=SystemClock.elapsedRealtime()
                app.transfer.stream(selection.primary,file,"audio-perf-${UUID.randomUUID()}",control) {bytes,_->
                    if(bytes>0&&firstByte==null) firstByte=SystemClock.elapsedRealtime()-begin
                }
                val elapsed=SystemClock.elapsedRealtime()-begin
                val hash=withContext(Dispatchers.IO) {
                    val digest=MessageDigest.getInstance("SHA-256")
                    file.inputStream().use {input->val buffer=ByteArray(65536);while(true) {val count=input.read(buffer);if(count<0) break;digest.update(buffer,0,count)}}
                    digest.digest().joinToString("") {"%02x".format(it)}
                }
                network.put(JSONObject().put("concurrency",concurrency).put("elapsed_ms",elapsed).put("first_bytes_ms",firstByte)
                    .put("bytes",file.length()).put("average_bytes_s",file.length()*1000/elapsed.coerceAtLeast(1)).put("sha256",hash))
                save()
                if(reference==null) {reference=hash;primary=file} else {assertEquals("Fragment concurrency must preserve exact source bytes",reference,hash);file.delete()}
            }
            val output=File(directory,"output.mp3")
            val begin=SystemClock.elapsedRealtime();var validating=begin
            val control=TransferControl().apply {onValidationReady={validating=SystemClock.elapsedRealtime()}}
            Processor().process(primary!!,null,output,selection,control,media.duration,"MediaFlow audio performance fixture")
            val finished=SystemClock.elapsedRealtime()
            val info=FFprobeKit.getMediaInformation(output.path).mediaInformation!!
            report.put("mp3_processing_ms",validating-begin).put("mp3_full_validation_ms",finished-validating)
                .put("mp3_total_ms",finished-begin).put("output_bytes",output.length()).put("output_duration_s",info.duration)
            assertEquals("mp3",info.streams.first {it.type=="audio"}.codec)
            val copy=File(directory,"publication-copy.mp3");val copyStart=SystemClock.elapsedRealtime()
            withContext(Dispatchers.IO) {output.inputStream().use {input->copy.outputStream().use {sink->input.copyTo(sink,256*1024)}}}
            report.put("local_copy_ms",SystemClock.elapsedRealtime()-copyStart).put("complete",true);save()
        } finally {if(cpu.isHeld) cpu.release();save();NativeWork.awaitIdle(directory);directory.deleteRecursively()}
    }
}
