package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real public media copied via USB: codec evidence only, never network evidence. */
class LocalMediaContractTest {
    @Test fun copyMergeTranscodeMp4AndRejectTruncation()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val fixtures=File(app.getExternalFilesDir(null),"codec-fixtures")
        val dir=app.storage.temporary("local-codec-contract")
        try {
            val audio=File(fixtures,"audio_en_2c_64k_opus.webm")
            val a=Format("opus","webm",null,null,"none","opus",audio.length(),64.0,"https","{}")
            val results=org.json.JSONArray()
            for((name,codec) in listOf("video_144p_108k_h264.mp4" to "h264","video_144p_96k_vp9.webm" to "vp9")) {
                val input=File(fixtures,name)
                assertTrue("Fetch host fixtures first",input.isFile)
                val v=Format(name,input.extension,144,25.0,codec,"none",input.length(),108.0,"https","{}",width=180)
                val selection=Selection(v,a,output="mp4",duration=60.0)
                val output=File(dir,"$codec.mp4")
                Processor().process(input,audio,output,selection,TransferControl(),60.0)
                val info=FFprobeKit.getMediaInformation(output.path).mediaInformation
                assertEquals("h264",info.streams.first {it.type=="video"}.codec)
                assertEquals("aac",info.streams.first {it.type=="audio"}.codec)
                assertEquals(144,info.streams.first {it.type=="video"}.height.toInt())
                val retriever=android.media.MediaMetadataRetriever()
                try {retriever.setDataSource(output.path);val frame=retriever.getFrameAtTime(1000000);assertNotNull(frame);frame?.recycle()} finally {retriever.release()}
                results.put(JSONObject().put("inputCodec",codec).put("output",info.allProperties).put("bytes",output.length()))
                val truncated=File(dir,"truncated.mp4").apply {writeBytes(output.readBytes().let {it.copyOf(it.size/3)})}
                try {Processor().validate(truncated,false,expectedDuration=60.0,expectedContainer="mp4");fail("Truncation accepted")} catch(e:MediaException) {assertEquals(ErrorCode.OUTPUT_VALIDATION_FAILED,e.code)}
            }
            File(app.getExternalFilesDir(null),"obsidian-local-codecs.json").writeText(JSONObject().put("fixtureAcquisition","host HTTPS + USB; not an Android network test").put("outputs",results).toString(2))
        } finally {app.storage.removeTemporary("local-codec-contract")}
    }
}
