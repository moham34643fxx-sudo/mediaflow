@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.engine.AacCopyTiming
import com.zdownloader.app.engine.Processor
import com.zdownloader.app.engine.TransferControl
import com.zdownloader.app.engine.NativeWork
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.security.MessageDigest

class AacCopyTimingTest {
    @Test fun productionCopyPreservesTheOriginalAacEndpoint() = runBlocking<Unit> {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val args=InstrumentationRegistry.getArguments()
        val base=File(requireNotNull(context.getExternalFilesDir(null)),"source-paired-performance").canonicalPath+File.separator
        fun fixture(key:String):File=File(requireNotNull(args.getString(key))).canonicalFile.also {
            require(it.path.startsWith(base)&&it.isFile&&it.length() in 1..(96L*1024*1024))
        }
        val audio=fixture("timingAudioFixturePath")
        val video=fixture("timingVideoFixturePath")
        fun hash(file:File):String {
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use {input->val buffer=ByteArray(64*1024);while(true) {val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n)}}
            return digest.digest().joinToString("") {"%02x".format(it)}
        }
        val audioHash=hash(audio);val videoHash=hash(video)
        val audioInfo=FFprobeKit.getMediaInformation(audio.path).mediaInformation.streams.first {it.type=="audio"}
        val timing=requireNotNull(AacCopyTiming.fromStream(audioInfo.allProperties))
        val videoInfo=FFprobeKit.getMediaInformation(video.path).mediaInformation.streams.first {it.type=="video"}
        val primary=Format("timing-video","mp4",videoInfo.height?.toInt(),null,videoInfo.codec,"none",video.length(),null,"https","{}",width=videoInfo.width?.toInt())
        val secondary=Format("timing-audio","m4a",null,null,"none","aac",audio.length(),null,"https","{}")
        val directory=File(requireNotNull(context.getExternalFilesDir(null)),"aac-copy-timing-output-${UUID.randomUUID()}")
        check(!directory.exists()&&directory.mkdirs())
        val output=File(directory,"output.mp4")
        val control=TransferControl()
        val report=JSONObject().put("passed",false).put("source_audio_sha256",audioHash).put("source_video_sha256",videoHash)
        try {
            Processor().process(video,audio,output,Selection(primary,secondary,output="mp4",embedMetadata=false),control,888.053)
            val actual=FFprobeKit.getMediaInformation(output.path).mediaInformation.streams.first {it.type=="audio"}
            report.put("source_audio_timing",audioInfo.allProperties).put("output_audio_timing",actual.allProperties)
            assertTrue("Copied audio must retain exact source endpoint",timing.matches(actual.allProperties))
            assertEquals(audioHash,hash(audio));assertEquals(videoHash,hash(video))
            assertFalse(NativeWork.busy(directory))
            report.put("passed",true).put("output_sha256",hash(output))
        } finally {
            control.stop()
            File(directory,"result.json").writeText(report.toString(2))
        }
    }

    @Test fun actualRuntimeSupportsPacketDurationExpressions() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(requireNotNull(context.getExternalFilesDir(null)),"aac-copy-timing-${UUID.randomUUID()}")
        check(!directory.exists()&&directory.mkdirs())
        val listing=FFmpegKit.executeWithArguments(arrayOf("-hide_banner","-bsfs"))
        val available=listing.allLogsAsString.lineSequence().any {it.trim()=="setts"}
        val report=JSONObject().put("listing_exit",listing.returnCode?.value).put("setts_available",available)
        File(directory,"filters.txt").writeText(listing.allLogsAsString)
        if(available) {
            val help=FFmpegKit.executeWithArguments(arrayOf("-hide_banner","-h","bsf=setts"))
            File(directory,"setts-help.txt").writeText(help.allLogsAsString)
            report.put("help_exit",help.returnCode?.value).put("duration_expression_supported",help.allLogsAsString.contains("duration"))
        }
        File(directory,"result.json").writeText(report.toString(2))
        assertTrue(ReturnCode.isSuccess(listing.returnCode))
        assertTrue("Actual FFmpegKit lacks setts; stop before changing Processor",available)
        assertTrue(report.getBoolean("duration_expression_supported"))
    }
}
