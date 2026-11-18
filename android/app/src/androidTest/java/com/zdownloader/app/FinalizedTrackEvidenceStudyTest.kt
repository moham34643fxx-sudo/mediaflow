@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
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
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Measurement/evidence only: all final decoding stays mandatory, no certificate is issued. */
class FinalizedTrackEvidenceStudyTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext

    @Test fun collectsFinalizedCopyEvidenceWithoutRemovingCompleteOracle()=runBlocking<Unit> {
        require(android.os.Build.VERSION.SDK_INT>=29)
        val began=SystemClock.elapsedRealtime()
        val args=InstrumentationRegistry.getArguments()
        val base=File(requireNotNull(context.getExternalFilesDir(null)),"source-paired-performance").canonicalPath+File.separator
        fun owned(path:String)=File(path).canonicalFile.also {require(it.path.startsWith(base)&&it.isFile)}
        val spec=owned(requireNotNull(args.getString("trackSpecPath")))
        require(spec.length() in 1..65536)
        val tracks=JSONObject(spec.readText()).getJSONArray("tracks")
        require(tracks.length()==2)
        val sources=(0 until tracks.length()).map {tracks.getJSONObject(it)}.associate {row->
            val kind=row.getString("kind");require(kind in setOf("video","audio"))
            val source=owned(row.getString("device_reference"))
            val expected=if(kind=="video") "568ba37ba5b60b3393f873e53cdbcb9ded1d999eb8a1ccc65aaec51ec955da83" else
                "be6b60e0ec9130e37e3077c24e348e78d5a9be7069fe896d4dde5cde2b13d703"
            require(source.length()==if(kind=="video") 41451506L else 14702221L)
            assertEquals(expected,hash(source))
            kind to source
        }
        require(sources.keys==setOf("video","audio"))
        val output=owned(requireNotNull(args.getString("finalEvidenceOutputPath")))
        assertEquals(56365991L,output.length())
        val outputSha="45446109c38af58a751c6b4a204c246b602d542cb7d0bfbc630955835042a5ff"
        assertEquals(outputSha,hash(output))
        val directory=File(context.getExternalFilesDir(null),"finalized-track-evidence/${UUID.randomUUID()}")
        check(!directory.exists()&&directory.mkdirs())
        val report=JSONObject().put("passed",false).put("production_changed",false).put("certificate_issued",false)
            .put("publication_attempted",false).put("scope","Known-good long-copy evidence study; no production readiness or speedup")
            .put("output_sha256",outputSha)
        var activity:MainActivity?=null
        var probe:FFprobeSession?=null
        val latch=CountDownLatch(1)
        val oracle=TransferControl()
        var failure:Throwable?=null
        try {
            assertTrue(FFmpegKitConfig.getSessionsByState(SessionState.RUNNING).isEmpty())
            val app=context.applicationContext as ZApplication
            check(app.repository.downloads.first().none {it.status !in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")})
            check(!context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            val active=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=active
            withContext(Dispatchers.Main) {active.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            report.put("conditions_before",conditions())
            val sourceStarted=SystemClock.elapsedRealtime()
            val sourceEvidence=sources.mapValues {(kind,file)->indexed(file,kind,File(directory,"source-$kind.samples"))}
            report.put("source_indexed_evidence",JSONObject(sourceEvidence)).put("source_indexed_evidence_ms",SystemClock.elapsedRealtime()-sourceStarted)
            val gateStarted=SystemClock.elapsedRealtime()
            val packets=File(directory,"final.packets.json")
            val command=arrayOf("-v","error","-protocol_whitelist","file,pipe","-show_packets","-show_streams","-show_format",
                "-show_data_hash","sha256","-show_entries",
                "stream:format=format_name,start_time,duration,size,tags:packet=stream_index,pts,dts,duration,pos,size,data_hash,flags:packet_side_data=side_data_type,skip_samples,discard_padding,skip_reason,discard_reason",
                "-of","json","-o",packets.path,output.path)
            val lease=NativeWork.acquire(directory)
            val current=try {FFprobeKit.executeWithArgumentsAsync(command,{lease.release();latch.countDown()})}
                catch(error:Throwable) {lease.release();throw error}
            probe=current
            if(!latch.await(90,TimeUnit.SECONDS)) {
                FFmpegKit.cancel(current.sessionId);latch.await(10,TimeUnit.SECONDS)
                error("Final packet inventory deadline exceeded")
            }
            assertTrue(current.allLogsAsString.takeLast(800),ReturnCode.isSuccess(current.returnCode))
            assertTrue(packets.isFile&&packets.length() in 1..(48L*1024*1024))
            assertEquals(62940,packets.useLines {lines->lines.count {it.trimStart().startsWith("\"data_hash\": \"SHA256:")}})
            val logBytes=current.allLogsAsString.toByteArray().size
            assertTrue(logBytes<64*1024)
            report.put("final_packet_inventory_ms",SystemClock.elapsedRealtime()-gateStarted)
                .put("final_packet_inventory_bytes",packets.length()).put("jvm_log_bytes",logBytes)
                .put("final_packet_file_sha256",hash(packets))
            val indexedStarted=SystemClock.elapsedRealtime()
            val finalEvidence=sources.mapValues {(kind,_)->indexed(output,kind,File(directory,"final-$kind.samples"))}
            report.put("final_indexed_evidence",JSONObject(finalEvidence)).put("final_indexed_evidence_ms",SystemClock.elapsedRealtime()-indexedStarted)
            val comparisons=JSONArray()
            for(kind in listOf("video","audio")) {
                comparisons.put(JSONObject().put("kind",kind)
                    .put("complete_indexed_records_equal",File(directory,"source-$kind.samples").readBytes().contentEquals(File(directory,"final-$kind.samples").readBytes()))
                    .put("common_seek_records_equal",sourceEvidence.getValue(kind).getJSONArray("seeks").toString()==finalEvidence.getValue(kind).getJSONArray("seeks").toString()))
            }
            report.put("comparisons",comparisons).put("all_final_evidence_ms",SystemClock.elapsedRealtime()-gateStarted)
                .put("indexed_comparison_would_accept",(0 until comparisons.length()).all {
                    comparisons.getJSONObject(it).getBoolean("complete_indexed_records_equal")&&comparisons.getJSONObject(it).getBoolean("common_seek_records_equal")})
            val strictStarted=SystemClock.elapsedRealtime()
            Processor().validate(output,audio=false,requireAudio=true,expectedDuration=888.053,expectedContainer="mp4",
                expectedWidth=640,expectedHeight=272,expectedVideoCodec="vp9",control=oracle)
            report.put("retained_complete_final_oracle_ms",SystemClock.elapsedRealtime()-strictStarted)
                .put("retained_complete_final_oracle_passed",true)
            assertEquals(outputSha,hash(output))
            for((kind,source) in sources) assertEquals(if(kind=="video")
                "568ba37ba5b60b3393f873e53cdbcb9ded1d999eb8a1ccc65aaec51ec955da83" else
                "be6b60e0ec9130e37e3077c24e348e78d5a9be7069fe896d4dde5cde2b13d703",hash(source))
            report.put("conditions_after",conditions()).put("evidence_only_not_validated_certificate",true)
        } catch(error:Throwable) {failure=error}
        finally {
            oracle.stop()
            probe?.takeIf {latch.count!=0L}?.let {FFmpegKit.cancel(it.sessionId)}
            val settled=(probe==null||latch.count==0L||latch.await(10,TimeUnit.SECONDS))&&oracle.session==null&&!NativeWork.busy(directory)
            try {withContext(NonCancellable+Dispatchers.Main) {activity?.finish()}}
            catch(error:Throwable) {if(failure==null) failure=error else failure.addSuppressed(error)}
            if(!settled) {
                val cleanup=AssertionError("Final-evidence native ownership did not settle")
                if(failure==null) failure=cleanup else failure.addSuppressed(cleanup)
            }
            report.put("cleanup_confirmed",settled).put("passed",failure==null&&settled).put("total_study_wall_ms",SystemClock.elapsedRealtime()-began)
            failure?.let {report.put("failure",it.javaClass.simpleName+":"+it.message)}
            File(directory,"result.json").writeText(report.toString(2))
            instrumentation.sendStatus(2,android.os.Bundle().apply {putString("final_evidence_report",File(directory,"result.json").path)})
        }
        failure?.let {throw it}
    }

    private fun indexed(file:File,kind:String,records:File):JSONObject {
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val index=(0 until extractor.trackCount).single {extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("$kind/")==true}
            val format=extractor.getTrackFormat(index)
            val config=JSONObject()
            for(key in listOf("mime","language")) if(format.containsKey(key)) config.put(key,format.getString(key))
            for(key in listOf("width","height","sample-rate","channel-count","rotation-degrees","sar-width","sar-height"))
                if(format.containsKey(key)) config.put(key,format.getInteger(key))
            for(key in listOf("csd-0","csd-1","csd-2")) if(format.containsKey(key)) format.getByteBuffer(key)?.duplicate()?.let {buffer->
                val bytes=ByteArray(buffer.remaining());buffer.get(bytes);config.put(key,digest(bytes))
            }
            if(format.containsKey(MediaFormat.KEY_DURATION)) config.put("duration_us",format.getLong(MediaFormat.KEY_DURATION))
            extractor.selectTrack(index)
            extractor.seekTo(0,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buffer=ByteBuffer.allocate(2*1024*1024)
            fun read():JSONObject {
                buffer.clear()
                val size=extractor.readSampleData(buffer,0)
                check(size in 1..buffer.capacity()) {"Indexed sample outside budget"}
                val bytes=ByteArray(size);buffer.position(0);buffer.get(bytes)
                return JSONObject().put("pts_us",extractor.sampleTime).put("flags",extractor.sampleFlags)
                    .put("size",size).put("sha256",digest(bytes))
            }
            var count=0
            var last=-1L
            records.bufferedWriter().use {writer->
                while(extractor.sampleTime>=0) {
                    val record=read()
                    check(record.getLong("pts_us")>=last)
                    last=record.getLong("pts_us")
                    writer.append(record.toString()).append('\n')
                    count++
                    check(count<=if(kind=="video") 21312 else 41628)
                    if(!extractor.advance()) break
                }
            }
            assertEquals(if(kind=="video") 21312 else 41628,count)
            val seeks=JSONArray()
            for(target in listOf(0L,444026500L,887553000L)) {
                extractor.seekTo(target,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                seeks.put(read().put("target_us",target))
            }
            return JSONObject().put("count",count).put("last_pts_us",last).put("format",config).put("seeks",seeks).put("records_sha256",hash(records))
        } finally {extractor.release()}
    }

    private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun hash(file:File):String {
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val buffer=ByteArray(64*1024);while(true) {val n=input.read(buffer);if(n<0) break;hash.update(buffer,0,n)}}
        return hash.digest().hex()
    }
    private fun ByteArray.hex()=buildString(size*2) {for(byte in this@hex) {
        val value=byte.toInt() and 255;append("0123456789abcdef"[value ushr 4]);append("0123456789abcdef"[value and 15])
    }}
    private fun conditions()=context.getSystemService(android.os.PowerManager::class.java).let {
        JSONObject().put("thermal_status",it.currentThermalStatus).put("power_save",it.isPowerSaveMode).put("interactive",it.isInteractive)
    }
}
