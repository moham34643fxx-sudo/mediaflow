package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class VideoQualityPipelineTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    @Test fun selected480Remains480ThroughPersistenceRemuxAndFullValidation()=runBlocking {
        val dir=File(context.cacheDir,"quality-${UUID.randomUUID()}").apply {mkdirs()}
        try {
            val source=File(dir,"480.mp4")
            val frame=File(dir,"frame.yuv").apply {writeBytes(ByteArray(854*480*3/2) {if(it<854*480) 76 else 128.toByte()})}
            val generated=FFmpegKit.executeWithArguments(arrayOf("-y","-v","error","-stream_loop","-1","-f","rawvideo","-pixel_format","yuv420p","-video_size","854x480","-framerate","12","-i",frame.path,"-t","2","-c:v","mpeg4",source.path))
            assertTrue(ReturnCode.isSuccess(generated.returnCode))
            fun format(id:String,w:Int,h:Int)=Format.parse(JSONObject().put("format_id",id).put("url","https://example.com/$id.mp4")
                .put("ext","mp4").put("width",w).put("height",h).put("vcodec","mpeg4").put("acodec","none"))
            val f480=format("real480",854,480)
            val media=Media("https://example.com/watch","id","Video","Fixture",null,null,2.0,listOf(format("720",1280,720),f480))
            val selected=Selection.parse(VideoPreferenceSelection.request(480).resolve(media).copy(output="mkv").json())
            assertEquals(f480,selected.primary)
            val output=File(dir,"output.mkv")
            Processor().process(source,null,output,selected,TransferControl(),2.0)
            val video=FFprobeKit.getMediaInformation(output.path).mediaInformation.streams.first {it.type=="video"}
            assertEquals(480,video.height.toInt());assertEquals(854,video.width.toInt());assertEquals("mpeg4",video.codec)
            assertNull(VideoPreferenceSelection.ready(media.copy(formats=media.formats.filter {it.id!="real480"}),480))
            try {Processor().validate(output,false,false,720,2.0,expectedWidth=1280);fail("Wrong quality accepted")}
            catch(error:MediaException) {assertEquals("height",error.validationCheck)}
        } finally {dir.deleteRecursively()}
    }
    @Test fun realUserAv1BeforeAfterBenchmarkRetainsEveryFrameAndAudio()=runBlocking {
        val input=File(context.getExternalFilesDir(null),"video-review/user-video.mp4")
        assertTrue("Runner must supply a copy of the user's reproduced video",input.isFile)
        val info=FFprobeKit.getMediaInformation(input.path).mediaInformation
        val video=info.streams.first {it.type=="video"}
        val expected=video.allProperties.getString("nb_frames").toLong()
        assertEquals("av1",video.codec);assertTrue(expected>7000)
        fun now()=android.os.SystemClock.elapsedRealtime()
        val before=now()
        val baseline=FFmpegKit.executeWithArguments(arrayOf("-nostdin","-v","error","-xerror","-err_detect","explode","-hwaccel","none",
            "-c:v","av1_mediacodec","-ndk_codec","1","-stats","-i",input.path,"-map","0:v:0?","-map","0:a:0?","-fps_mode","passthrough","-f","null","-"))
        val baselineMs=now()-before
        assertTrue(baseline.allLogsAsString,ReturnCode.isSuccess(baseline.returnCode))
        assertEquals(expected,baseline.getAllStatistics(1000).maxOf {it.videoFrameNumber}.toLong())
        val complete=now()
        Processor().validate(input,false,true,video.height.toInt(),info.duration.toDouble(),"mp4",expectedWidth=video.width.toInt(),expectedVideoCodec="av1")
        val fullMs=now()-complete
        val dir=File(context.filesDir,"quickshare-review").apply {mkdirs()}
        File(dir,"real-video-benchmark.json").writeText(JSONObject().put("inputBytes",input.length()).put("codec","av1")
            .put("frames",expected).put("baselineFullValidationMs",baselineMs).put("newFullValidationMs",fullMs)
            .put("width",video.width).put("height",video.height).put("fullVideoAndAudioValidated",true).toString(2))
        assertTrue("No measured speedup: $fullMs vs $baselineMs",fullMs<baselineMs)
    }
}
