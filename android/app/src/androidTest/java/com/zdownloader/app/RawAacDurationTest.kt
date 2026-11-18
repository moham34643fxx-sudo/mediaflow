package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.NativeWork
import com.zdownloader.app.engine.Processor
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Real-world AAC export failed when ADTS bitrate estimation reported 18.7 s for 10.2 s. */
class RawAacDurationTest {
    private lateinit var home:File
    private lateinit var source:File
    private lateinit var raw:File

    @Before fun variableBitrateAudioWithAMisleadingAdtsEstimate() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        home=File(context.cacheDir,"raw-aac-${UUID.randomUUID()}").apply {check(!exists()&&mkdirs())}
        source=File(home,"source.wav");raw=File(home,"fixture.aac")
        val samples=480_000
        val pcm=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(48000).putInt(96000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples*2)
        val random=java.util.Random(7)
        repeat(samples) {index->pcm.putShort(if(index<336_000) 0 else (random.nextInt(24001)-12000).toShort())}
        source.writeBytes(pcm.array())
        ffmpeg("-y","-v","error","-i",source.path,"-c:a","aac","-b:a","192k",raw.path)
        val estimated=FFprobeKit.getMediaInformation(raw.path).mediaInformation.duration.toDouble()
        assertTrue("Fixture must reproduce misleading ADTS duration: $estimated",kotlin.math.abs(estimated-10.0)>.5)
        val decoded=File(home,"decoded.pcm")
        ffmpeg("-y","-v","error","-xerror","-err_detect","explode","-i",raw.path,"-f","s16le",decoded.path)
        assertEquals(10.0,decoded.length()/96000.0,.05)
    }

    @Test fun completeVariableBitrateAacExportsAndRevalidatesUsingItsDecodedDuration()=runBlocking<Unit> {
        val format=Format.parse(JSONObject().put("format_id","wav").put("url","https://example.com/source.wav")
            .put("ext","wav").put("vcodec","none").put("acodec","pcm_s16le").put("protocol","https"))
        val selection=Selection(format,audioOnly=true,output="aac",convertAudio=true,bitrate=192,embedMetadata=false)
        val output=File(home,"export.aac")
        Processor().process(source,null,output,selection,TransferControl(),expectedDuration=10.0)
        assertTrue(output.length()>0)
        Processor().resumeValidation(output,selection,TransferControl(),10.0,source)
    }

    @Test fun shortenedAndDamagedAdtsCannotPassDecodedDurationValidation()=runBlocking<Unit> {
        val bytes=raw.readBytes()
        val boundaries=mutableListOf<Int>();var offset=0
        while(offset<bytes.size) {
            check(offset+7<=bytes.size&&bytes[offset].toInt()and 255==255)
            boundaries+=offset
            val length=((bytes[offset+3].toInt()and 3) shl 11) or
                ((bytes[offset+4].toInt()and 255) shl 3) or ((bytes[offset+5].toInt()and 224) shr 5)
            check(length>=7&&offset+length<=bytes.size);offset+=length
        }
        for((name,end) in listOf("shortened" to boundaries[boundaries.size/2],"damaged" to bytes.size-17)) {
            val changed=File(home,"$name.aac").apply {writeBytes(bytes.copyOf(end))}
            try {Processor().validate(changed,true,expectedDuration=10.0,expectedContainer="aac");fail("$name AAC was accepted")}
            catch(error:MediaException) {
                assertEquals(ErrorCode.OUTPUT_VALIDATION_FAILED,error.code)
                assertEquals(if(name=="shortened") "duration_mismatch" else "decode",error.validationCheck)
            }
        }
    }

    @Test fun cancellingActiveRawAacDecodeReleasesItsPipeAndNativeFiles()=runBlocking<Unit> {
        val longAudio=File(home,"long.aac")
        val bytes=raw.readBytes()
        longAudio.outputStream().use {output->repeat(60) {output.write(bytes)}}
        val control=TransferControl()
        val decoding=async(Dispatchers.IO) {Processor().validate(longAudio,true,expectedContainer="aac",control=control)}
        try {
            withTimeout(5_000) {while(control.session==null&&!decoding.isCompleted) delay(1)}
            assertFalse("Fixture must reach an active native decode",decoding.isCompleted)
            control.stop()
            try {withTimeout(5_000) {decoding.await()};fail("Stopped AAC validation succeeded")}
            catch(error:CancellationException) {assertFalse("Cancellation did not finish promptly",error is TimeoutCancellationException)}
        } finally {
            control.stop();decoding.cancel()
            withContext(NonCancellable) {withTimeout(15_000) {NativeWork.awaitIdle(home)}}
        }
        assertFalse(NativeWork.busy(home))
    }

    private fun ffmpeg(vararg args:String) {
        val result=FFmpegKit.executeWithArguments(args)
        assertTrue(result.allLogsAsString,ReturnCode.isSuccess(result.returnCode))
    }

    @After fun removeOnlyOwnedFixtures() {
        if(::home.isInitialized) {check(!NativeWork.busy(home));check(home.deleteRecursively())}
    }
}
