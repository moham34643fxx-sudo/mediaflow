package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.Media
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.engine.LameEncoder
import com.zdownloader.app.engine.NativeWork
import com.zdownloader.app.engine.Processor
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Exercises native output selection and the real muxer, not just format labels. */
class NativeAudioCopyAcceptanceTest {
    @Test fun compatibleMp3M4aAndOpusKeepTheirEncodedAudioPackets()=runBlocking<Unit> {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as ZApplication
        check(app.packageName=="com.zdownloader.app.candidate0965")
        check(!app.coordinator.running)
        val home=File(app.cacheDir,"native-copy-${UUID.randomUUID()}").apply {check(mkdirs())}
        val cases=JSONArray()
        val report=JSONObject().put("passed",false).put("cases",cases)
        fun execute(vararg args:String) {
            val session=FFmpegKit.executeWithArguments(arrayOf("-y","-nostdin","-v","error",*args))
            assertTrue(session.allLogsAsString,ReturnCode.isSuccess(session.returnCode))
        }
        fun packetHash(file:File,label:String):String {
            val hash=File(home,"$label.sha256")
            execute("-i",file.path,"-map","0:a:0","-c:a","copy","-f","hash","-hash","sha256",hash.path)
            return hash.readText().trim()
        }
        try {
            val video=File(home,"fixture.mp4")
            instrumentation.context.assets.open("status-original.mp4").use {input->
                video.outputStream().use {input.copyTo(it)}
            }
            val pcm=File(home,"tone.pcm")
            execute("-i",video.path,"-t","3","-vn","-ac","1","-ar","48000","-f","s16le",pcm.path)
            val mp3=File(home,"native.mp3")
            assertEquals(0,LameEncoder.encodeEx(pcm.path,mp3.path,128,48000,1,AtomicBoolean(false)))
            val m4a=File(home,"native.m4a")
            execute("-f","s16le","-ar","48000","-ac","1","-i",pcm.path,"-c:a","aac","-b:a","128k",m4a.path)
            val opus=File(home,"native.webm")
            execute("-f","s16le","-ar","48000","-ac","1","-i",pcm.path,"-c:a","opus","-strict","-2","-b:a","128k",opus.path)
            for((source,outputType,codec) in listOf(Triple(mp3,"mp3","mp3"),Triple(m4a,"m4a","aac"),Triple(opus,"opus","opus"))) {
                val info=FFprobeKit.getMediaInformation(source.path).mediaInformation
                val format=Format.parse(JSONObject().put("format_id",outputType).put("url","https://example.com/${source.name}")
                    .put("ext",source.extension).put("vcodec","none").put("acodec",codec).put("abr",128).put("language","ar"))
                val media=Media("https://example.com/audio","native-copy","Native copy","example.com",
                    null,null,info.duration.toDouble(),listOf(format))
                val selection=Selection.audio(media,outputType,format,0).copy(embedMetadata=false)
                assertFalse("A compatible source must not be re-encoded",selection.convertAudio)
                assertEquals("ar",selection.primary.language)
                val output=File(home,"copied.$outputType")
                val control=TransferControl()
                Processor().process(source,null,output,selection,control,info.duration.toDouble())
                val actual=FFprobeKit.getMediaInformation(output.path).mediaInformation
                assertEquals(codec,actual.streams.single {it.type=="audio"}.codec)
                assertEquals(info.duration.toDouble(),actual.duration.toDouble(),0.15)
                val before=packetHash(source,"input-$outputType")
                val after=packetHash(output,"output-$outputType")
                assertEquals("Encoded audio changed during $outputType copy",before,after)
                cases.put(JSONObject().put("output",outputType).put("codec",codec).put("encoded_packets_identical",true)
                    .put("selected_language","ar").put("duration_seconds",actual.duration.toDouble()))
            }
            report.put("passed",true)
        } finally {
            val run=InstrumentationRegistry.getArguments().getString("evidenceRun","manual")!!
            require(run.matches(Regex("[A-Za-z0-9-]{1,100}")))
            val evidence=File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
            File(evidence,"native-audio-copy.json").writeText(report.toString(2))
            check(!NativeWork.busy(home))
            check(home.deleteRecursively())
        }
    }
}
