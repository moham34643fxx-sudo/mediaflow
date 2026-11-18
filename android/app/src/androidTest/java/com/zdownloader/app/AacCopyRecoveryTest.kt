@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class AacCopyRecoveryTest {
    private lateinit var home:File
    private lateinit var source:File
    private lateinit var output:File
    private lateinit var selection:Selection
    private lateinit var timing:AacCopyTiming

    @Before fun realShortTerminalAacSample()=runBlocking<Unit> {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        home=File(app.cacheDir,"aac-recovery-${UUID.randomUUID()}").apply {check(!exists()&&mkdirs())}
        source=File(home,"source.m4a");output=File(home,"output.m4a")
        val wav=File(home,"tone.wav").apply {writeBytes(shortPcmTone())}
        val generated=FFmpegKit.executeWithArguments(arrayOf("-y","-v","error","-i",wav.path,"-c:a","aac","-movie_timescale","48000",source.path))
        assertTrue(generated.allLogsAsString,ReturnCode.isSuccess(generated.returnCode))
        val audio=FFprobeKit.getMediaInformation(source.path).mediaInformation.streams.first {it.type=="audio"}
        timing=checkNotNull(AacCopyTiming.fromStream(audio.allProperties))
        val format=Format.parse(JSONObject().put("format_id","aac").put("url","https://example.com/source.m4a")
            .put("ext","m4a").put("vcodec","none").put("acodec","aac").put("protocol","https"))
        selection=Selection(format,audioOnly=true,output="m4a",embedMetadata=false)
        Processor().process(source,null,output,selection,TransferControl())
    }

    @Test fun recoveredCopyKeepsExactEndpointWithoutSourcesAndRejectsRoundedTerminalPacket()=runBlocking<Unit> {
        assertTrue(timing.matches(FFprobeKit.getMediaInformation(output.path).mediaInformation.streams.first {it.type=="audio"}.allProperties))
        check(source.delete())
        Processor().resumeValidation(output,Selection.parse(selection.json()),TransferControl(),null,source)
        val rounded=File(home,"rounded.m4a")
        lengthenTerminalSample(output,rounded)
        val changed=FFprobeKit.getMediaInformation(rounded.path).mediaInformation.streams.first {it.type=="audio"}
        assertFalse("Negative control must really change the short final AAC packet",timing.matches(changed.allProperties))
        rounded.copyTo(output,overwrite=true)
        try {Processor().resumeValidation(output,selection,TransferControl(),null,source);fail("Recovered endpoint mismatch accepted")}
        catch(error:MediaException) {assertEquals("audio_timing",error.validationCheck)}
    }

    @Test fun mergedVideoKeepsTheSubMillisecondAacEndpoint()=runBlocking<Unit> {
        val video=File(home,"video.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("av1-validation.mp4").use {input->
            video.outputStream().use {input.copyTo(it)}
        }
        val primary=Format.parse(JSONObject().put("format_id","video").put("url","https://example.com/video.mp4")
            .put("ext","mp4").put("vcodec","av1").put("acodec","none").put("width",128).put("height",96).put("protocol","https"))
        val merged=File(home,"merged.mp4")
        Processor().process(video,source,merged,Selection(primary,selection.primary,output="mp4",embedMetadata=false),TransferControl())
        val streams=FFprobeKit.getMediaInformation(merged.path).mediaInformation.streams
        assertEquals("av1",streams.single {it.type=="video"}.codec)
        assertTrue(timing.matches(streams.single {it.type=="audio"}.allProperties))
    }
    @Test fun fullDecodeDoesNotApproveACorruptOrDifferentRequestTimingContract()=runBlocking<Unit> {
        val marker=File(home,"output.m4a.aac-copy-timing.json")
        assertTrue(marker.isFile)
        try {Processor().resumeValidation(output,selection.copy(bitrate=128),TransferControl(),null,source);fail("A different request reused the timing contract")}
        catch(error:MediaException) {assertEquals("audio_timing_contract",error.validationCheck)}
        marker.writeText("{\"version\":1,\"timing\":{}}")
        try {Processor().resumeValidation(output,selection,TransferControl(),null,source);fail("A corrupt timing contract was ignored")}
        catch(error:MediaException) {assertEquals("audio_timing_contract",error.validationCheck)}
    }

    @Test fun originalAudioDoesNotSilentlyTranscodeMislabelledInputButExplicitConversionStillWorks()=runBlocking<Unit> {
        val pcm=File(home,"tone.wav")
        val original=File(home,"mislabelled.m4a")
        try {Processor().process(pcm,null,original,selection,TransferControl());fail("An original-copy request silently re-encoded PCM")}
        catch(error:MediaException) {assertEquals(ErrorCode.FORMAT_UNAVAILABLE,error.code)}
        assertFalse(original.exists())
        val converted=File(home,"explicit.m4a")
        Processor().process(pcm,null,converted,selection.copy(convertAudio=true),TransferControl())
        assertEquals("aac",FFprobeKit.getMediaInformation(converted.path).mediaInformation.streams.first {it.type=="audio"}.codec)
    }

    @After fun cleanupOnlyOwnedFiles() {
        if(::home.isInitialized) {check(!NativeWork.busy(home));check(home.deleteRecursively())}
    }

    private fun shortPcmTone():ByteArray {
        // The Facebook audit exposed rounding when an endpoint is not a whole millisecond.
        val samples=47521
        val pcm=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36+samples*2)
            .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
            .putInt(48000).putInt(96000).putShort(2).putShort(16)
            .put("data".toByteArray(Charsets.US_ASCII)).putInt(samples*2)
        repeat(samples) {index->pcm.putShort((kotlin.math.sin(2*Math.PI*1000*index/48000)*16000).toInt().toShort())}
        return pcm.array()
    }

    /** Real container mutation, not a demuxer-specific assumption about remux rounding. */
    private fun lengthenTerminalSample(original:File,changed:File) {
        val bytes=original.readBytes();val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        fun box(parent:Pair<Int,Int>,kind:String):Pair<Int,Int> {
            var offset=parent.first;var found:Pair<Int,Int>?=null
            while(offset<parent.second) {
                check(offset+8<=parent.second)
                val size=buffer.getInt(offset);check(size>=8&&offset+size<=parent.second)
                if(String(bytes,offset+4,4,Charsets.US_ASCII)==kind) {check(found==null);found=offset+8 to offset+size}
                offset+=size
            }
            check(offset==parent.second);return checkNotNull(found)
        }
        val movie=box(0 to bytes.size,"moov");val track=box(movie,"trak")
        val movieHeader=box(movie,"mvhd").first
        val trackHeader=box(track,"tkhd").first
        val media=box(track,"mdia");val mediaHeader=box(media,"mdhd").first
        val table=box(box(box(media,"minf"),"stbl"),"stts").first
        for(header in listOf(movieHeader,trackHeader,mediaHeader,table)) check(bytes[header].toInt()==0)
        val movieClock=buffer.getInt(movieHeader+12);val audioClock=buffer.getInt(mediaHeader+12)
        check(movieClock>0&&audioClock%movieClock==0)
        val tickDelta=audioClock/movieClock
        val rows=buffer.getInt(table+4);check(rows>0)
        val last=table+8+(rows-1)*8;check(buffer.getInt(last)==1)
        buffer.putInt(last+4,buffer.getInt(last+4)+tickDelta)
        buffer.putInt(mediaHeader+16,buffer.getInt(mediaHeader+16)+tickDelta)
        buffer.putInt(movieHeader+16,buffer.getInt(movieHeader+16)+1)
        buffer.putInt(trackHeader+20,buffer.getInt(trackHeader+20)+1)
        val edits=box(box(track,"edts"),"elst").first
        check(bytes[edits].toInt()==0)
        val entries=buffer.getInt(edits+4);check(entries in 1..2)
        val finalEdit=edits+8+(entries-1)*12
        check(buffer.getInt(finalEdit+4)>=0)
        buffer.putInt(finalEdit,buffer.getInt(finalEdit)+1)
        changed.writeBytes(bytes)
        assertEquals(original.length(),changed.length())
    }
}
