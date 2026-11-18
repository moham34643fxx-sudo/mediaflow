package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class Mp3StreamingRegressionTest {
    @Test fun metadataDisabledKeepsNativeMp3WithoutASecondMuxPass():Unit=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val directory=app.storage.temporary("mp3-no-tags-${UUID.randomUUID()}")
        try {
            val pcm=File(directory,"source.pcm").apply {writeBytes(ByteArray(44100*2*2))}
            val source=File(directory,"source.wav")
            assertTrue(ReturnCode.isSuccess(FFmpegKit.executeWithArguments(arrayOf("-y","-v","error","-f","s16le","-ar","44100","-ac","2","-i",pcm.path,source.path)).returnCode))
            val expected=File(directory,"expected.mp3")
            assertEquals(0,LameEncoder.encodeEx(pcm.path,expected.path,192,44100,2,java.util.concurrent.atomic.AtomicBoolean(false)))
            val format=Format.parse(JSONObject().put("format_id","no-tags").put("ext","wav").put("protocol","https")
                .put("url","https://example.com/audio.wav").put("vcodec","none").put("acodec","pcm_s16le"))
            val selection=Selection(format,audioOnly=true,output="mp3",convertAudio=true,bitrate=192,duration=1.0,embedMetadata=false)
            val output=File(directory,"output.mp3")
            Processor().process(source,null,output,selection,TransferControl(),1.0,"Must not be embedded")
            assertArrayEquals("No metadata remux or encoded audio change",expected.readBytes(),output.readBytes())
            assertTrue(directory.listFiles().orEmpty().none {it.name.startsWith("encoded-")})
            val info=FFprobeKit.getMediaInformation(output.path).mediaInformation!!
            assertEquals("mp3",info.streams.first {it.type=="audio"}.codec)
            assertFalse(info.allProperties.toString().contains("Must not be embedded"))
        } finally {NativeWork.awaitIdle(directory);directory.deleteRecursively()}
    }
    @Test fun anIdlePipeCanBeCancelledWithoutWaitingForItsWriter():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val output=File(context.cacheDir,"pipe-cancel-${UUID.randomUUID()}.mp3")
        val pipe=android.os.ParcelFileDescriptor.createPipe()
        val stopped=java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            val work=async(Dispatchers.IO) {LameEncoder.encodePipe(pipe[0].fd,output.path,192,44100,2,stopped)}
            delay(150);stopped.set(true)
            assertEquals(-2,withTimeout(3000) {work.await()})
            assertFalse(output.exists())
        } finally {stopped.set(true);pipe.forEach {it.close()};output.delete()}
    }
    @Test fun streamingMonoAcceptsUnalignedReadBoundariesAndRejectsTruncatedFrames():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        for(valid in listOf(true,false)) {
            val output=File(context.cacheDir,"pipe-frames-${UUID.randomUUID()}.mp3")
            val pipe=android.os.ParcelFileDescriptor.createPipe()
            try {
                val work=async(Dispatchers.IO) {LameEncoder.encodePipe(pipe[0].fd,output.path,128,48000,1,java.util.concurrent.atomic.AtomicBoolean(false))}
                withContext(Dispatchers.IO) {android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use {sink->
                    val data=ByteArray(if(valid) 96000 else 96001)
                    var offset=0;while(offset<data.size) {val count=minOf(257,data.size-offset);sink.write(data,offset,count);offset+=count}
                }}
                val result=withTimeout(5000) {work.await()}
                if(valid) {
                    assertEquals(0,result)
                    val info=FFprobeKit.getMediaInformation(output.path).mediaInformation!!
                    val audio=info.streams.first {it.type=="audio"}.allProperties
                    assertEquals(1,audio.optInt("channels"));assertEquals("48000",audio.optString("sample_rate"))
                } else {assertNotEquals(0,result);assertFalse(output.exists())}
            } finally {pipe.forEach {it.close()};output.delete()}
        }
    }
    @Test fun realMp3KeepsDurationChannelsBitrateAndBoundsPcmStaging():Unit=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        val directory=app.storage.temporary("mp3-space-${UUID.randomUUID()}")
        val peak=AtomicLong();val started=android.os.SystemClock.elapsedRealtime()
        var observer:Job?=null
        try {
            val primary=File(directory,"source.m4a")
            val wave=File(directory,"fixture.wav")
            val bytes=120*44100*2*2
            val header=java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray());putInt(bytes+36);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(2)
                putInt(44100);putInt(176400);putShort(4);putShort(16);put("data".toByteArray());putInt(bytes)
            }.array()
            val block=java.nio.ByteBuffer.allocate(4410*4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                repeat(4410) {index->val value=(kotlin.math.sin(2.0*Math.PI*440*index/44100)*4000).toInt().toShort();putShort(value);putShort(value)}
            }.array()
            wave.outputStream().use {stream->stream.write(header);repeat(1200) {stream.write(block)}}
            val generated=FFmpegKit.executeWithArguments(arrayOf("-y","-v","error","-i",wave.path,"-c:a","aac","-b:a","64k",primary.path))
            assertTrue(generated.allLogsAsString,ReturnCode.isSuccess(generated.returnCode))
            wave.delete()
            val format=Format.parse(JSONObject().put("format_id","mp3-space").put("ext","m4a").put("protocol","https")
                .put("url","https://example.com/audio.m4a").put("vcodec","none").put("acodec","aac"))
            val selection=Selection(format,audioOnly=true,output="mp3",convertAudio=true,bitrate=192,duration=120.0)
            observer=launch(Dispatchers.IO) {while(isActive) {
                val bytes=directory.listFiles().orEmpty().filter {it.extension=="pcm"}.sumOf {it.length()}
                peak.updateAndGet {old->maxOf(old,bytes)};delay(5)
            }}
            val output=File(directory,"output.mp3")
            Processor().process(primary,null,output,selection,TransferControl(),120.0)
            observer.cancelAndJoin();observer=null
            val info=FFprobeKit.getMediaInformation(output.path).mediaInformation!!
            val audio=info.streams.first {it.type=="audio"}.allProperties
            val streaming=InstrumentationRegistry.getArguments().getString("expectStreaming","true").toBoolean()
            val report=JSONObject().put("input_duration_s",120).put("output_duration_s",info.duration)
                .put("output_bytes",output.length()).put("peak_pcm_staging_bytes",peak.get()).put("expected_streaming",streaming)
                .put("channels",audio.optInt("channels")).put("sample_rate",audio.optString("sample_rate"))
                .put("bitrate",audio.optString("bit_rate")).put("elapsed_ms",android.os.SystemClock.elapsedRealtime()-started)
            val folder=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
            File(folder,"mp3-streaming-space.json").writeText(report.toString(2))
            assertEquals("mp3",info.streams.first {it.type=="audio"}.codec)
            assertEquals(2,audio.optInt("channels"));assertEquals("44100",audio.optString("sample_rate"))
            assertTrue(kotlin.math.abs(info.duration.toDouble()-120.0)<0.2)
            assertTrue(kotlin.math.abs(audio.optString("bit_rate").toLong()-192000L)<=4000)
            if(streaming) assertEquals("No complete uncompressed PCM staging file",0L,peak.get())
            else assertTrue("The baseline must observe the original full PCM stage",peak.get()>20_000_000L)
            if(streaming) {
                val control=TransferControl();val cancelledOutput=File(directory,"cancelled.mp3")
                val converting=async(Dispatchers.IO) {runCatching {Processor().process(primary,null,cancelledOutput,selection,control,120.0)}}
                withTimeout(5000) {while(!NativeWork.busy(directory)) delay(1)}
                control.stop()
                assertTrue(withTimeout(12000) {converting.await()}.isFailure)
                withTimeout(12000) {NativeWork.awaitIdle(directory)}
                assertFalse(cancelledOutput.exists())
            }
        } finally {observer?.cancelAndJoin();NativeWork.awaitIdle(directory);directory.deleteRecursively()}
    }
}
