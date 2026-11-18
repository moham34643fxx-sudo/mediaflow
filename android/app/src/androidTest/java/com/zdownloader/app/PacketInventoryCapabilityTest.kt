@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.*
import com.zdownloader.app.engine.NativeWork
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PacketInventoryCapabilityTest {
    @Test fun completePacketHashesCanBeWrittenWithoutLargeJvmLogs() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val base=File(requireNotNull(context.getExternalFilesDir(null)),"source-paired-performance").canonicalPath+File.separator
        val spec=File(requireNotNull(InstrumentationRegistry.getArguments().getString("trackSpecPath"))).canonicalFile
        require(spec.path.startsWith(base)&&spec.length() in 1..65536)
        val tracks=JSONObject(spec.readText()).getJSONArray("tracks")
        require(tracks.length()==2)
        val directory=File(context.getExternalFilesDir(null),"packet-inventory-capability/${UUID.randomUUID()}")
        check(!directory.exists()&&directory.mkdirs())
        val sessions=mutableListOf<Pair<FFprobeSession,CountDownLatch>>()
        val report=JSONObject().put("passed",false).put("production_changed",false)
        val results=JSONArray()
        report.put("tracks",results)
        var failure:Throwable?=null
        try {
            assertTrue(FFmpegKitConfig.getSessionsByState(SessionState.RUNNING).isEmpty())
            for(index in 0 until tracks.length()) {
                val row=tracks.getJSONObject(index)
                val kind=row.getString("kind");require(kind in setOf("video","audio"))
                val source=File(row.getString("device_reference")).canonicalFile
                require(source.path.startsWith(base)&&source.isFile&&source.length()<=64L*1024*1024)
                assertEquals(row.getString("sha256"),hash(source))
                val output=File(directory,"$kind.packets.json")
                val command=arrayOf("-v","error","-protocol_whitelist","file,pipe","-show_packets","-show_streams",
                    "-show_data_hash","sha256","-show_entries",
                    "stream=index,codec_name,codec_type,sample_rate,channels,width,height,time_base,start_pts,duration_ts,extradata_size,extradata_hash:packet=stream_index,pts,dts,duration,pos,size,data_hash,flags:packet_side_data=side_data_type,skip_samples,discard_padding,skip_reason,discard_reason",
                    "-of","json","-o",output.path,source.path)
                val latch=CountDownLatch(1)
                val lease=NativeWork.acquire(directory)
                val began=android.os.SystemClock.elapsedRealtime()
                val session=try {FFprobeKit.executeWithArgumentsAsync(command,{lease.release();latch.countDown()})}
                    catch(error:Throwable) {lease.release();throw error}
                sessions+=session to latch
                if(!latch.await(90,TimeUnit.SECONDS)) {
                    FFmpegKit.cancel(session.sessionId)
                    latch.await(10,TimeUnit.SECONDS)
                    error("Native packet inventory deadline exceeded")
                }
                assertTrue(session.allLogsAsString.takeLast(800),ReturnCode.isSuccess(session.returnCode))
                assertTrue("Missing or over-budget native output",output.isFile&&output.length() in 1..(24L*1024*1024))
                val packets=output.useLines {lines->lines.count {it.trimStart().startsWith("\"data_hash\": \"SHA256:")}}
                assertEquals(if(kind=="video") 21312 else 41628,packets)
                val logBytes=session.allLogsAsString.toByteArray().size
                assertTrue("Packet output was retained in JVM logs",logBytes<64*1024)
                assertEquals(row.getString("sha256"),hash(source))
                results.put(JSONObject().put("kind",kind).put("packets",packets).put("output_bytes",output.length())
                    .put("jvm_log_bytes",logBytes).put("inventory_ms",android.os.SystemClock.elapsedRealtime()-began)
                    .put("inventory_file_sha256",hash(output)))
            }
            report.put("passed",true)
        } catch(error:Throwable) {failure=error}
        finally {
            sessions.filter {it.second.count!=0L}.forEach {FFmpegKit.cancel(it.first.sessionId)}
            val settled=sessions.all {it.second.count==0L||it.second.await(10,TimeUnit.SECONDS)}&&!NativeWork.busy(directory)
            report.put("cleanup_confirmed",settled).put("passed",failure==null&&settled)
            failure?.let {report.put("failure",it.javaClass.simpleName+":"+it.message)}
            File(directory,"result.json").writeText(report.toString(2))
            InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply {putString("inventory_report",File(directory,"result.json").path)})
            if(!settled&&failure==null) failure=AssertionError("Native probe ownership did not settle")
        }
        failure?.let {throw it}
    }

    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val bytes=ByteArray(64*1024);while(true) {val n=input.read(bytes);if(n<0) break;digest.update(bytes,0,n)}}
        return digest.digest().joinToString("") {"%02x".format(it)}
    }
}
