package com.zdownloader.app.engine

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.system.Os
import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.domain.codecFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** A complete packet comparison, not sampling. Only certified unchanged VP9/AAC copies qualify. */
internal object CopyOutputVerification {
    private const val MAX_SAMPLE_BYTES=8*1024*1024
    private const val MAX_PACKETS=2_000_000L
    private const val MAX_SCAN_MS=5*60_000L
    private const val END=-1L
    private data class TrackContract(val mime:String,val properties:Map<String,String>,val packets:Long)
    private data class Scan(val control:TransferControl,val started:Long)
    private class UnsupportedCopy:Exception()
    private fun requireCopy(condition:Boolean) {if(!condition) throw UnsupportedCopy()}

    fun eligible(selection:Selection):Boolean {
        val audio=selection.secondary ?: return false
        return !selection.audioOnly&&!selection.videoOnly&&!selection.imageOnly&&selection.subtitle==null&&
            !selection.transcodeVideo&&!selection.transcodeAudio&&selection.output=="mp4"&&
            codecFamily(selection.primary.videoCodec)=="vp9"&&codecFamily(audio.audioCodec)=="aac"&&
            selection.primary.width?.let {width->selection.primary.height?.let {height->width.toLong()*height<=1920L*1080} }==true&&
            selection.primary.ext=="webm"&&audio.ext in setOf("m4a","mp4")&&
            listOf(selection.primary,audio).all {it.protocol in setOf("https","http")}
    }

    suspend fun verify(file:File,selection:Selection,control:TransferControl):String? {
        val sources=control.copySources ?: return null
        if(!eligible(selection)) return null
        val started=SystemClock.elapsedRealtime()
        val scan=Scan(control,started)
        val stamp=fileStamp(file)
        val catalogs=listOf(File(file.parentFile,"proof-video-${java.util.UUID.randomUUID()}.bin"),
            File(file.parentFile,"proof-audio-${java.util.UUID.randomUUID()}.bin"))
        var phase="source_catalogs"
        return try {
            val (video, audio) = coroutineScope {
                val v = async(Dispatchers.IO) { measurePhase(file, "video_source_catalog") { sourceCatalog(sources.video, catalogs[0], "video/x-vnd.on2.vp9", scan) } }
                val a = async(Dispatchers.IO) { measurePhase(file, "audio_source_catalog") { sourceCatalog(sources.audio, catalogs[1], "audio/mp4a-latm", scan) } }
                Pair(v.await(), a.await())
            }
            phase="output_sample_comparison"
            measurePhase(file,phase) {compareOutput(file,listOf(video,audio),catalogs,scan)}
            phase="independent_packet_timing"
            measurePhase(file,phase) {requireCopy(PacketTimingVerification.matches(sources,file,selection,control))}
            phase="output_file_binding"
            val hash=measurePhase(file,phase) {hash(file,scan)}
            requireCopy(fileStamp(file)==stamp)
            NetworkDiagnostics.record("media","copy_output_proof_accepted",null,operation=file.parentFile?.name,
                details=org.json.JSONObject().put("proof_ms",SystemClock.elapsedRealtime()-started)
                    .put("video_packets",video.packets).put("audio_packets",audio.packets).put("output_bytes",file.length()))
            hash
        } catch(error:CancellationException) {throw error}
          catch(_:UnsupportedCopy) {fallback(file,started,phase);null}
          catch(_:java.io.IOException) {fallback(file,started,"io");null}
          catch(_:IllegalArgumentException) {fallback(file,started,"extractor_format");null}
          catch(_:IllegalStateException) {fallback(file,started,"extractor_state");null}
          catch(_:android.system.ErrnoException) {fallback(file,started,"file_identity");null}
        finally {catalogs.forEach {it.delete()}}
    }

    private suspend fun <T> measurePhase(file:File,phase:String,work:suspend ()->T):T {
        val started=SystemClock.elapsedRealtime()
        val verified=work()
        NetworkDiagnostics.record("media","copy_output_proof_phase",null,operation=file.parentFile?.name,
            details=org.json.JSONObject().put("phase",phase).put("elapsed_ms",SystemClock.elapsedRealtime()-started))
        return verified
    }

    private fun fallback(file:File,started:Long,reason:String) {
        NetworkDiagnostics.record("media","copy_output_proof_fallback",null,operation=file.parentFile?.name,
            details=org.json.JSONObject().put("reason",reason).put("proof_ms",SystemClock.elapsedRealtime()-started))
    }

    private suspend fun sourceCatalog(source:CopyVerification.DecodedSource,catalog:File,mime:String,
        scan:Scan):TrackContract {
        val stamp=fileStamp(source.file)
        requireCopy(source.bytes==source.file.length()&&source.bytes>0&&source.mediaTimeMs>0)
        requireCopy(hash(source.file,scan)==source.sha256)
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(source.file.path)
            requireCopy(extractor.trackCount==1)
            val format=extractor.getTrackFormat(0)
            requireCopy(format.getString(MediaFormat.KEY_MIME)==mime)
            var expectedProperties=properties(format,mime)
            extractor.selectTrack(0)
            var packets=0L
            var previous=-1L
            val buffer=ByteBuffer.allocate(MAX_SAMPLE_BYTES)
            val reader=SampleReader(extractor,buffer)
            DataOutputStream(catalog.outputStream().buffered()).use {out->
                while(true) {
                    checkActive(scan)
                    val time=extractor.sampleTime
                    if(time<0) break
                    val flags=extractor.sampleFlags
                    requireCopy(++packets<=MAX_PACKETS)
                    requireCopy(time>previous)
                    if(packets==1L) requireCopy(time==0L)
                    previous=time
                    val sample=reader.read(flags)
                    if(packets==1L&&mime.startsWith("video/")) {
                        requireCopy(flags==MediaExtractor.SAMPLE_FLAG_SYNC)
                        val prefix=ByteArray(minOf(sample.size,32))
                        buffer.duplicate().apply {position(0)}.get(prefix)
                        expectedProperties=expectedVideoProperties(source.file,expectedProperties,prefix)
                    }
                    out.writeLong(time)
                    out.writeInt(expectedSampleFlags(mime,flags))
                    out.writeInt(sample.size)
                    out.write(sample.hash)
                    if(!extractor.advance()) break
                }
                out.writeLong(END)
            }
            requireCopy(packets>0&&fileStamp(source.file)==stamp)
            if(mime.startsWith("video/")) requireCopy(source.videoFrames==packets)
            return TrackContract(mime,expectedProperties,packets)
        } finally {extractor.release()}
    }

    private fun expectedSampleFlags(mime:String,flags:Int):Int {
        if(mime!="audio/mp4a-latm") return flags
        // AAC-LC access units are independently decodable. Android may expose only the
        // first input unit as sync; the MP4 mux marks every unit sync. No other bit maps.
        requireCopy(flags==0||flags==MediaExtractor.SAMPLE_FLAG_SYNC)
        return MediaExtractor.SAMPLE_FLAG_SYNC
    }

    private suspend fun compareOutput(file:File,contracts:List<TrackContract>,catalogs:List<File>,
        scan:Scan) {
        val extractor=MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            requireCopy(extractor.trackCount==2)
            val indices=contracts.map {contract->
                (0 until extractor.trackCount).singleOrNull {extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)==contract.mime}
                    ?: throw UnsupportedCopy()
            }
            val buffer=ByteBuffer.allocate(MAX_SAMPLE_BYTES)
            for(index in contracts.indices) {
                val track=indices[index]
                val actual=properties(extractor.getTrackFormat(track),contracts[index].mime)
                if(actual!=contracts[index].properties) NetworkDiagnostics.record("media","copy_android_configuration_mismatch",null,
                    operation=file.parentFile?.name,details=org.json.JSONObject().put("mime",contracts[index].mime)
                        .put("source",org.json.JSONObject(contracts[index].properties)).put("output",org.json.JSONObject(actual)))
                requireCopy(actual==contracts[index].properties)
                extractor.selectTrack(track)
                extractor.seekTo(0,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                comparePackets(extractor,buffer,catalogs[index],scan)
                extractor.unselectTrack(track)
            }
        } finally {extractor.release()}
    }

    private fun expectedVideoProperties(file:File,source:Map<String,String>,prefix:ByteArray):Map<String,String> {
        // Container-private VP9 features outside this exact supported contract retain full validation.
        val width=source["width"]?.toIntOrNull() ?: throw UnsupportedCopy()
        val height=source["height"]?.toIntOrNull() ?: throw UnsupportedCopy()
        requireCopy(source==mapOf("width" to "$width","height" to "$height","profile" to "1",
            "csd-0" to "010100030108040100"))
        val facts=FFprobeKit.getMediaInformation(file.path).mediaInformation?.streams?.singleOrNull {it.type=="video"}
            ?.allProperties ?: throw UnsupportedCopy()
        requireCopy(facts.optString("codec_name")=="vp9"&&facts.optString("profile") in setOf("0","Profile 0")&&
            facts.optString("pix_fmt")=="yuv420p"&&facts.optInt("width")==width&&facts.optInt("height")==height)
        requireCopy(listOf("color_space","color_transfer","color_primaries","chroma_location").all {
            !facts.has(it)||facts.optString(it)=="unknown"
        })
        val configuration=Vp9CopyConfiguration.canonical(prefix,width,height,facts.optString("r_frame_rate"))
            ?: throw UnsupportedCopy()
        return expectedVideoGeometry(width,height,facts.optString("sample_aspect_ratio"))+
            mapOf("csd-0" to configuration.hex())
    }

    private fun expectedVideoGeometry(width:Int,height:Int,aspect:String):Map<String,String> {
        val parts=aspect.split(':')
        requireCopy(parts.size==2)
        val numerator=parts[0].toIntOrNull() ?: throw UnsupportedCopy()
        val denominator=parts[1].toIntOrNull() ?: throw UnsupportedCopy()
        requireCopy(numerator>0&&denominator>0)
        val divisor=java.math.BigInteger.valueOf(numerator.toLong()).gcd(java.math.BigInteger.valueOf(denominator.toLong())).toInt()
        return mapOf("width" to "$width","height" to "$height",
            "sar-width" to "${numerator/divisor}","sar-height" to "${denominator/divisor}")
    }

    private suspend fun comparePackets(extractor:MediaExtractor,buffer:ByteBuffer,catalog:File,
        scan:Scan) {
        val reader=SampleReader(extractor,buffer)
        DataInputStream(catalog.inputStream().buffered()).use {input->
            var packetIndex=0L
            while(true) {
                checkActive(scan)
                val time=input.readLong()
                val actualTime=extractor.sampleTime
                if(time==END) {
                    if(actualTime>=0) sampleMismatch(catalog,org.json.JSONObject().put("packet_index",packetIndex)
                        .put("reason","extra_output_sample").put("output_time_us",actualTime))
                    requireCopy(actualTime<0&&input.read()==-1);break
                }
                // Android exposes both containers in integer microseconds; accept only exact equality.
                val flags=input.readInt()
                val actualFlags=extractor.sampleFlags
                if(actualTime!=time||actualFlags!=flags) sampleMismatch(catalog,org.json.JSONObject()
                    .put("packet_index",packetIndex).put("reason","timeline_or_flags").put("source_time_us",time)
                    .put("output_time_us",actualTime).put("source_flags",flags).put("output_flags",actualFlags))
                requireCopy(actualTime==time&&actualFlags==flags)
                val size=input.readInt()
                val expected=ByteArray(32).also(input::readFully)
                val sample=reader.read(actualFlags)
                if(sample.size!=size||!sample.hash.contentEquals(expected)) sampleMismatch(catalog,org.json.JSONObject()
                    .put("packet_index",packetIndex).put("reason","size_or_payload").put("source_size",size).put("output_size",sample.size))
                requireCopy(sample.size==size&&sample.hash.contentEquals(expected))
                packetIndex++
                extractor.advance()
            }
        }
    }

    private fun sampleMismatch(catalog:File,details:org.json.JSONObject) {
        details.put("track",if(catalog.name.startsWith("proof-video-")) "video" else "audio")
        NetworkDiagnostics.record("media","copy_android_sample_mismatch",null,operation=catalog.parentFile?.name,details=details)
    }

    private data class Sample(val size:Int,val hash:ByteArray)
    private class SampleReader(private val extractor:MediaExtractor,private val buffer:ByteBuffer) {
        private val digest=MessageDigest.getInstance("SHA-256")
        fun read(flags:Int):Sample {
            requireCopy(flags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED==0)
            if(android.os.Build.VERSION.SDK_INT<28) throw UnsupportedCopy()
            val declaredSize=extractor.sampleSize
            requireCopy(declaredSize in 1..MAX_SAMPLE_BYTES.toLong())
            buffer.clear()
            val size=extractor.readSampleData(buffer,0)
            requireCopy(size>0&&size<=MAX_SAMPLE_BYTES&&size.toLong()==declaredSize)
            buffer.position(0);buffer.limit(size)
            digest.update(buffer)
            // digest() resets the per-reader state; every packet still gets its own SHA-256.
            return Sample(size,digest.digest())
        }
    }

    private fun properties(format:MediaFormat,mime:String):Map<String,String> {
        val keys=if(mime.startsWith("video/")) listOf(MediaFormat.KEY_WIDTH,MediaFormat.KEY_HEIGHT,
            "profile","level","color-standard","color-range","color-transfer","sar-width","sar-height","rotation-degrees")
            else listOf(MediaFormat.KEY_SAMPLE_RATE,MediaFormat.KEY_CHANNEL_COUNT,"aac-profile")
        val properties=keys.filter(format::containsKey).associateWith {format.getInteger(it).toString()}.toMutableMap()
        for(index in 0..2) format.getByteBuffer("csd-$index")?.duplicate()?.let {buffer->
            requireCopy(buffer.remaining() in 1..256)
            val bytes=ByteArray(buffer.remaining());buffer.get(bytes)
            properties["csd-$index"]=bytes.hex()
        }
        // Missing configuration is not a license to assume decoder equivalence.
        if(mime.startsWith("audio/")) requireCopy(properties.containsKey("csd-0"))
        return properties
    }

    private suspend fun hash(file:File,scan:Scan):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->
            val buffer=ByteArray(256*1024)
            while(true) {
                checkActive(scan)
                val count=input.read(buffer);if(count<0) break
                digest.update(buffer,0,count)
            }
        }
        return digest.digest().hex()
    }

    private suspend fun checkActive(scan:Scan) {
        scan.control.check();currentCoroutineContext().ensureActive()
        requireCopy(SystemClock.elapsedRealtime()-scan.started<MAX_SCAN_MS)
    }

    private fun fileStamp(file:File):String=Os.stat(file.path).let {
        "${it.st_dev}:${it.st_ino}:${it.st_size}:${it.st_mtim.tv_sec}:${it.st_mtim.tv_nsec}:${it.st_ctim.tv_sec}:${it.st_ctim.tv_nsec}"
    }
    private fun ByteArray.hex()=joinToString("") {"%02x".format(it)}
}
