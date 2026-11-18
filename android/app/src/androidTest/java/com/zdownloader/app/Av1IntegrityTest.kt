package com.zdownloader.app

import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

class Av1IntegrityTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as ZApplication
    private fun evidence(name:String,value:JSONObject) {
        val run=InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "manual"
        val dir=File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
        File(dir,"$name.json").writeText(value.toString(2))
    }
    private fun fixture(dir:File)=File(dir,"valid.mp4").also {file->
        instrumentation.context.assets.open("av1-validation.mp4").use {source->file.outputStream().use {source.copyTo(it)}}
    }
    @Test fun incorrectFrameCountCannotPassTheFastFullDecoder()=runBlocking {
        val id="av1-count-${UUID.randomUUID()}";val dir=app.storage.temporary(id)
        try {
            val source=fixture(dir)
            try {Av1FullDecoder.decode(source,15,TransferControl());fail("Incorrect frame count accepted")}
            catch(error:MediaException) {assertEquals("decoded_frame_count",error.validationCheck)}
            assertFalse(NativeWork.busy(dir))
        } finally {app.storage.removeTemporary(id)}
    }
    @Test fun cancelledFastValidationReleasesItsNativeFiles()=runBlocking {
        val id="av1-cancel-${UUID.randomUUID()}";val dir=app.storage.temporary(id)
        try {
            val source=fixture(dir);val control=TransferControl().apply {stop()}
            try {Av1FullDecoder.decode(source,16,control);fail("Cancellation ignored")}
            catch(expected:kotlinx.coroutines.CancellationException) { }
            assertFalse(NativeWork.busy(dir));assertTrue(source.isFile)
        } finally {app.storage.removeTemporary(id)}
    }
    @Test fun completeAv1AndAudioDecodeSucceedsWithoutChangingCodecOrPixels()=runBlocking {
        val id="av1-valid-${UUID.randomUUID()}";val dir=app.storage.temporary(id)
        try {
            val source=fixture(dir)
            Processor().validate(source,false,true,96,2.0,"mp4",expectedWidth=128,expectedFps=8.0,expectedVideoCodec="av1")
            val streams=FFprobeKit.getMediaInformation(source.path).mediaInformation.streams
            assertEquals("av1",streams.first {it.type=="video"}.codec)
            assertEquals("aac",streams.first {it.type=="audio"}.codec)
            evidence("av1-valid-complete",JSONObject().put("strict_decode",true).put("expected_frames",16).put("width",128).put("height",96).put("codec","av1"))
        } finally {app.storage.removeTemporary(id)}
    }
    @Test fun av1WithoutContainerFrameCountUsesTheStrictDecoderAdapter()=runBlocking {
        val id="av1-unindexed-${UUID.randomUUID()}";val dir=app.storage.temporary(id)
        try {
            val source=fixture(dir);val unindexed=File(dir,"unindexed.mkv")
            val copied=FFmpegKit.executeWithArguments(arrayOf("-y","-v","error","-i",source.path,"-map","0","-c","copy",unindexed.path))
            assertTrue(copied.allLogsAsString,ReturnCode.isSuccess(copied.returnCode))
            val stream=FFprobeKit.getMediaInformation(unindexed.path).mediaInformation.streams.first {it.type=="video"}
            assertNull("The regression must exercise the unknown-frame-count path",stream.allProperties.optString("nb_frames").toLongOrNull())
            Processor().validate(unindexed,false,true,96,expectedWidth=128,expectedVideoCodec="av1")
            assertFalse(NativeWork.busy(dir))
        } finally {app.storage.removeTemporary(id)}
    }
    @Test fun lateAv1PacketDamageIsRejectedEvenWhenFirstFrameStillDecodes()=runBlocking {
        val id="av1-late-damage-${UUID.randomUUID()}";val dir=app.storage.temporary(id)
        try {
            val source=fixture(dir)
            val damaged=File(dir,"late-damage.mp4");source.copyTo(damaged)
            val packets=FFprobeKit.executeWithArguments(arrayOf("-v","error","-select_streams","v:0","-show_packets",
                "-show_entries","packet=pos,size,pts_time","-of","json",source.path))
            val list=JSONObject(packets.output).getJSONArray("packets")
            assertEquals(16,list.length())
            RandomAccessFile(damaged,"rw").use {file->
                for(index in 12 until list.length()) {
                    val packet=list.getJSONObject(index)
                    file.seek(packet.getLong("pos"));file.write(ByteArray(packet.getInt("size")) {0xff.toByte()})
                }
            }
            val retriever=MediaMetadataRetriever()
            try {
                retriever.setDataSource(damaged.path)
                val frame=retriever.getFrameAtTime(0)
                assertNotNull("The fixture must retain a decodable first frame",frame);frame?.recycle()
            } finally {retriever.release()}
            try {
                Processor().validate(damaged,false,true,96,2.0,"mp4",expectedWidth=128,expectedFps=8.0,expectedVideoCodec="av1")
                fail("Late corruption was accepted")
            } catch(e:MediaException) {assertEquals(ErrorCode.OUTPUT_VALIDATION_FAILED,e.code)}
            evidence("av1-late-damage-rejected",JSONObject().put("first_frame_decodable",true).put("damaged_video_packets",4)
                .put("complete_validation_rejected",true).put("required_frame_count",16))
        } finally {app.storage.removeTemporary(id)}
    }
}
