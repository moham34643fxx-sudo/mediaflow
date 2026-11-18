package com.zdownloader.app.engine

import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.domain.Selection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.DataInputStream
import java.io.File
import java.math.BigInteger

/** Independent container timing and payload proof. Native packet JSON is neither captured nor buffered. */
internal object PacketTimingVerification {
    private data class Packet(val pts:Long,val duration:Long,val size:Long,val sync:Boolean,val hash:ByteArray)
    private data class PacketPair(val source:File,val output:File,val original:ContainerInventory,val copied:ContainerInventory)
    private class UnsupportedTiming:Exception()
    private fun requireTiming(condition:Boolean) {if(!condition) throw UnsupportedTiming()}

    suspend fun matches(sources:CopyVerification.Sources,output:File,selection:Selection,control:TransferControl):Boolean {
        val files=List(4) {File(output.parentFile,"packet-proof-${java.util.UUID.randomUUID()}.bin")}
        var phase="admission"
        return try {
            requireTiming(android.os.StatFs(output.parentFile!!.path).availableBytes>528L*1024*1024)
            val inputs=listOf(sources.video.file,sources.audio.file,output,output)
            val kinds=listOf("video","audio","video","audio")
            val configurations=inputs.indices.map {configuration(inputs[it],kinds[it])}
            if(configurations[0]!=configurations[2]||configurations[1]!=configurations[3]) {
                NetworkDiagnostics.record("media","copy_configuration_mismatch",null,operation=output.parentFile?.name,
                    details=org.json.JSONObject().put("video_source",org.json.JSONObject(configurations[0]))
                        .put("video_output",org.json.JSONObject(configurations[2])).put("audio_source",org.json.JSONObject(configurations[1]))
                        .put("audio_output",org.json.JSONObject(configurations[3])))
                throw UnsupportedTiming()
            }
            val context=currentCoroutineContext()
            val deadline=android.os.SystemClock.elapsedRealtime()+5*60_000
            val checkpoint={control.check();context.ensureActive();requireTiming(android.os.SystemClock.elapsedRealtime()<deadline)}
            val inventories=inputs.indices.map {index->
                phase="inventory_$index"
                if(index==0) WebmPacketInventory.write(inputs[index],files[index],checkpoint)
                else Mp4PacketInventory.write(inputs[index],files[index],kinds[index],checkpoint)
            }
            phase="compare_video"
            requireTiming(compare(PacketPair(files[0],files[2],inventories[0],inventories[2]),null,checkpoint))
            phase="compare_audio"
            val audioEnd=AudioCopyContract.expected(output,selection)?.json()?.getLong("endTicks")
            if(audioEnd!=null) requireTiming(inventories[1].clock==ContainerClock(1,configurations[1].getValue("sample_rate").toLong()))
            requireTiming(compare(PacketPair(files[1],files[3],inventories[1],inventories[3]),audioEnd,checkpoint))
            true
        } catch(error:CancellationException) {throw error}
          catch(error:BoundedMediaFile.Unsupported) {rejected(output,phase,error.reason);false}
          catch(_:UnsupportedTiming) {rejected(output,phase,"contract");false}
          catch(_:java.io.IOException) {rejected(output,phase,"io");false}
          catch(_:NumberFormatException) {rejected(output,phase,"number");false}
        finally {files.forEach {it.delete()}}
    }

    private fun configuration(file:File,kind:String):Map<String,String> {
        val stream=FFprobeKit.getMediaInformation(file.path).mediaInformation?.streams?.singleOrNull {it.type==kind}
            ?: throw UnsupportedTiming()
        val fields=if(kind=="video") listOf("codec_name","profile","pix_fmt","width","height","sample_aspect_ratio",
            "field_order","color_range","color_space","color_transfer","color_primaries","chroma_location")
            else listOf("codec_name","profile","sample_rate","channels","channel_layout")
        val configuration=fields.associateWith {stream.allProperties.optString(it,"unknown")}
        if(kind=="video") requireTiming(configuration["codec_name"]=="vp9"&&configuration["profile"] in setOf("0","Profile 0")&&configuration["pix_fmt"]=="yuv420p")
        else requireTiming(configuration["codec_name"]=="aac"&&configuration["profile"] in setOf("1","LC"))
        return configuration
    }

    private fun compare(pair:PacketPair,audioEnd:Long?,checkpoint:()->Unit):Boolean {
        val original=pair.original;val copied=pair.copied
        if(original.packets!=copied.packets||original.packets<=0) return false
        // AudioCopyContract's endpoint is in AAC sample ticks, never an arbitrary container clock.
        if(audioEnd!=null&&original.clock.numerator!=1L) return false
        DataInputStream(pair.source.inputStream().buffered()).use {input->DataInputStream(pair.output.inputStream().buffered()).use {actual->
            for(index in 0 until original.packets) {
                checkpoint()
                val packet=packet(input);val candidate=packet(actual)
                val duration=if(index==original.packets-1&&audioEnd!=null) minOf(packet.duration,audioEnd-packet.pts) else packet.duration
                if(duration<=0||packet.size!=candidate.size||packet.sync!=candidate.sync||!packet.hash.contentEquals(candidate.hash)||
                    !sameTime(packet.pts,original.clock,candidate.pts,copied.clock)||
                    !sameTime(duration,original.clock,candidate.duration,copied.clock)) {
                    NetworkDiagnostics.record("media","copy_packet_timing_mismatch",null,operation=pair.output.parentFile?.name,
                        details=org.json.JSONObject().put("packet_index",index).put("source_pts",packet.pts).put("output_pts",candidate.pts)
                            .put("source_duration",packet.duration).put("expected_duration",duration).put("output_duration",candidate.duration)
                            .put("source_clock","${original.clock.numerator}/${original.clock.denominator}")
                            .put("output_clock","${copied.clock.numerator}/${copied.clock.denominator}"))
                    return false
                }
            }
            return input.read()==-1&&actual.read()==-1
        }}
    }
    private fun packet(input:DataInputStream)=Packet(input.readLong(),input.readLong(),input.readLong(),input.readBoolean(),ByteArray(32).also(input::readFully))
    private fun sameTime(left:Long,leftClock:ContainerClock,right:Long,rightClock:ContainerClock):Boolean =
        BigInteger.valueOf(left)*BigInteger.valueOf(leftClock.numerator)*BigInteger.valueOf(rightClock.denominator)==
            BigInteger.valueOf(right)*BigInteger.valueOf(rightClock.numerator)*BigInteger.valueOf(leftClock.denominator)
    private fun rejected(output:File,phase:String,reason:String) {
        NetworkDiagnostics.record("media","copy_packet_inventory_rejected",null,operation=output.parentFile?.name,
            details=org.json.JSONObject().put("phase",phase).put("reason",reason))
    }
}
