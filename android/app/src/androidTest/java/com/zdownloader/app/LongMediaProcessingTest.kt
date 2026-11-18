package com.zdownloader.app

import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Isolated local processing/publication only. Never retries an installed user's job. */
class LongMediaProcessingTest {
    @Test fun shortAndLongAudioAndVideoKeepQualityAndPublishCorrectly():Unit=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        check(instrument.targetContext.packageName=="com.zdownloader.app.designreview")
        val app=instrument.targetContext.applicationContext as ZApplication
        val fixture=File(app.getExternalFilesDir(null),"video-review/long-audio.m4a")
        val videoFixture=File(app.getExternalFilesDir(null),"video-review/user-video.mp4")
        assertTrue(fixture.isFile);assertTrue(videoFixture.isFile)
        val directory=app.storage.temporary("long-processing-${UUID.randomUUID()}")
        val report=JSONArray();val owned=mutableListOf<String>()
        val reportFile=File(app.filesDir,"quickshare-review/long-media-processing.json").apply {parentFile!!.mkdirs()}
        val wake=app.getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"MediaFlow:long-media-test")
        wake.acquire(20*60_000L)
        fun ffmpeg(vararg args:String) {assertTrue(ReturnCode.isSuccess(FFmpegKit.executeWithArguments(args).returnCode))}
        try {
            val short=File(directory,"short.m4a")
            ffmpeg("-y","-v","error","-i",fixture.path,"-t","30","-map","0:a:0","-c","copy",short.path)
            val videoShort=File(directory,"short-video.mp4")
            ffmpeg("-y","-v","error","-i",videoFixture.path,"-t","15","-map","0:v:0","-map","0:a:0?","-c","copy",videoShort.path)
            val videoLong=File(directory,"long-video.mp4")
            ffmpeg("-y","-v","error","-stream_loop","3","-i",videoFixture.path,"-map","0:v:0","-map","0:a:0?","-c","copy",videoLong.path)
            for((name,input,convert) in listOf(Triple("short_mp3",short,true),Triple("long_mp3",fixture,true),
                Triple("long_original_audio",fixture,false),Triple("short_video",videoShort,false),Triple("long_video",videoLong,false))) {
                val info=checkNotNull(FFprobeKit.getMediaInformation(input.path).mediaInformation)
                val audio=info.streams.firstOrNull {it.type=="audio"}
                val video=info.streams.firstOrNull {it.type=="video"}
                val duration=info.duration.toDouble()
                val format=Format.parse(JSONObject().put("format_id",name).put("url","https://example.com/$name")
                    .put("ext",if(video==null) "m4a" else "mp4").put("protocol","https")
                    .put("vcodec",video?.codec ?: "none").put("acodec",audio?.codec ?: "none")
                    .put("width",video?.width).put("height",video?.height))
                val selection=Selection(format,audioOnly=video==null,output=if(convert) "mp3" else format.ext,convertAudio=convert,bitrate=192,duration=duration)
                assertFalse(selection.transcodeVideo)
                if(!convert) assertFalse(selection.transcodeAudio)
                val output=File(directory,"$name.${selection.output}")
                val began=SystemClock.elapsedRealtime();var validation=began
                val samples=java.util.concurrent.CopyOnWriteArrayList<AudioProcessingSample>()
                val observing=launch(Dispatchers.Default,start=CoroutineStart.UNDISPATCHED) {
                    AudioProcessingProgress.state.collect {it[directory.name]?.let(samples::add)}
                }
                val control=TransferControl().apply {onValidationReady={validation=SystemClock.elapsedRealtime()}}
                try {Processor().process(input,null,output,selection,control,duration,"Isolated media processing")}
                finally {observing.cancelAndJoin()}
                val validated=SystemClock.elapsedRealtime()
                assertFalse(AudioProcessingProgress.state.value.containsKey(directory.name))
                val positions=samples.filter {it.phase==AudioProcessingPhase.ENCODING&&it.positionMs>0}.map {it.positionMs}.distinct()
                if(convert) {
                    assertTrue("MP3 telemetry must advance before completion",positions.isNotEmpty())
                    if(name=="long_mp3") {
                        assertTrue("Statistics must be milliseconds, not seconds",positions.last()>duration*1000*.8)
                        assertTrue("Long encoding needs multiple real progress samples",positions.size>=3)
                        assertTrue("Statistics must not jump to the end immediately",positions.any {it<duration*1000*.8})
                    }
                } else assertTrue(samples.isEmpty())
                val result=checkNotNull(FFprobeKit.getMediaInformation(output.path).mediaInformation)
                val expectedAudio=if(convert) "mp3" else codecFamily(audio?.codec)
                assertEquals(expectedAudio,codecFamily(result.streams.firstOrNull {it.type=="audio"}?.codec))
                assertTrue(kotlin.math.abs(result.duration.toDouble()-duration)<maxOf(.5,duration*.03))
                if(video!=null) {
                    val track=result.streams.first {it.type=="video"}
                    assertEquals(video.width,track.width);assertEquals(video.height,track.height)
                    assertEquals(codecFamily(video.codec),codecFamily(track.codec))
                }
                if(convert) {
                    val track=result.streams.first {it.type=="audio"}.allProperties
                    assertEquals(44100,track.getString("sample_rate").toInt());assertEquals(2,track.getInt("channels"))
                    assertTrue(kotlin.math.abs(track.getString("bit_rate").toLong()-192000)<32000)
                }
                val id=UUID.randomUUID().toString();owned+=id
                val row=DownloadEntity(id,"https://example.com/long-processing","Isolated processing","Fixture",null,duration,selection.json(),
                    "MediaFlow processing check $id.${selection.output}",null,id,status="SAVING",checkpoint="VALIDATED",bytes=output.length(),relativeFolder="MediaFlow processing verification")
                app.repository.dao.insert(row)
                val publishAt=SystemClock.elapsedRealtime()
                val uri=app.storage.commit(row,output,selection,control)
                val committed=SystemClock.elapsedRealtime()
                assertEquals("COMPLETED",app.repository.dao.get(id)!!.status)
                val retriever=MediaMetadataRetriever()
                try {
                    retriever.setDataSource(app,uri);assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()>0)
                    if(video!=null) {
                        val frame=retriever.getFrameAtTime(1_000_000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        assertNotNull(frame);frame?.recycle()
                    }
                }
                finally {retriever.release()}
                assertTrue(directory.listFiles().orEmpty().none {it.extension in listOf("pcm","raw")})
                report.put(JSONObject().put("case",name).put("durationSeconds",duration).put("processingMs",validation-began)
                    .put("fullValidationMs",validated-validation).put("publicationMs",committed-publishAt)
                    .put("outputBytes",output.length()).put("audioCodec",expectedAudio).put("published",true)
                    .put("encodingProgressSamples",positions.size).put("firstPositionMs",positions.firstOrNull()).put("lastPositionMs",positions.lastOrNull()))
                reportFile.writeText(JSONObject().put("passed",false).put("cases",report).toString(2))
            }
            reportFile.writeText(JSONObject().put("passed",true).put("cases",report).toString(2))
        } finally {
            for(id in owned) app.repository.dao.get(id)?.let {row->
                if(row.status=="COMPLETED") app.storage.deleteFile(row)
                app.repository.dao.removeHistory(id)
            }
            NativeWork.awaitIdle(directory);directory.deleteRecursively()
            if(wake.isHeld) wake.release()
        }
    }
}
