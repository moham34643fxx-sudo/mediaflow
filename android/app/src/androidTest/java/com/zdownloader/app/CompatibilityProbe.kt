package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.withTimeout

/** Separate investigation: records a failure honestly without counting it as website support. */
class CompatibilityProbe {
    /** Capture helper only: decode an externally recorded real cold start. */
    @Test fun captureRecordedStartupFrames() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val recording=File(context.getExternalFilesDir(null),"startup-native.mp4")
        assertTrue("Record the installed APK with tools/record_startup.py first",recording.isFile)
        val folder=File(context.getExternalFilesDir(null),"startup-frames").apply {mkdirs()}
        val retriever=android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(recording.path)
            for(millis in 0..1600 step 200) {
                val image=retriever.getFrameAtTime(millis*1000L,android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                assertNotNull(image)
                File(folder,"frame-$millis.png").outputStream().use {image!!.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                image?.recycle()
            }
            File(folder,"capture.json").writeText(JSONObject().put("source","Real ADB screenrecord of installed APK; frames decoded on Android").put("durationMs",retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)).put("requestedFrameStepMs",200).toString(2))
        } finally {retriever.release()}
    }
    @Test fun investigateActualYoutubeTransferAndProcessing() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val id="youtube-transfer-probe"
        val url="https://www.youtube.com/watch?v=aqz-KE-bpKQ"
        val report=JSONObject().put("source","YouTube / public Big Buck Bunny").put("url",url).put("runtime",JsBridge.version())
        var stage="extraction"
        try {
            withTimeout(180000) {
                val metadata=app.extractor.pythonCall("analyze",url,id)
                report.put("js_challenges",metadata.optJSONObject("js_challenges"))
                val media=Media.parse(metadata,url)
                report.put("extraction","passed").put("formats",media.formats.size)
                val video=media.formats.filter { it.video && it.height != null && it.height!!<=240 }.minByOrNull { it.height!! } ?: throw MediaException(ErrorCode.FORMAT_UNAVAILABLE)
                val selection=Selection.video(media,video)
                report.put("selected_height",video.height)
                val dir=app.storage.temporary(id)
                val primary=File(dir,"primary.${selection.primary.ext}")
                stage="transfer"
                app.transfer.stream(selection.primary,primary,id,TransferControl()) { _,_-> }
                val secondary=selection.secondary?.let { track->File(dir,"secondary.${track.ext}").also { app.transfer.stream(track,it,id,TransferControl()) { _,_-> } } }
                report.put("transfer","passed")
                stage="processing"
                val output=File(dir,"output.${selection.output}")
                Processor().process(primary,secondary,output,selection,TransferControl(),media.duration)
                report.put("processing","passed").put("output_bytes",output.length()).put("status","Verified download and validated output")
            }
        } catch(e:Exception) { report.put("status","Failed at $stage").put("error",(e as? MediaException)?.code?.name ?: e.javaClass.simpleName) }
        finally { app.extractor.cancel(id);app.storage.removeTemporary(id) }
        android.util.Log.i("ZCompatibility",report.toString())
        File(app.filesDir,"youtube-transfer-compatibility.json").writeText(report.toString(2))
        val run=InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "manual"
        File(app.getExternalFilesDir(null),"youtube-transfer-$run.json").writeText(report.toString(2))
        assertTrue(report.has("status"))
    }
    @Test fun investigateYoutubePublicExtractorTestVideo() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val result = try {withTimeout(95000) {app.extractor.pythonCall("analyze","https://www.youtube.com/watch?v=aqz-KE-bpKQ","youtube-probe")}} finally {app.extractor.cancel("youtube-probe")}
        val report = JSONObject().put("platform","YouTube").put("url","https://www.youtube.com/watch?v=aqz-KE-bpKQ").put("time",System.currentTimeMillis())
        if(result.has("error")) report.put("status","Unsupported for tested link/environment").put("error",result.optString("error")).put("diagnostic",result.optString("diagnostic")).put("js_challenges",result.optJSONObject("js_challenges"))
        else report.put("status","Partially supported: metadata only").put("formats",result.optJSONArray("formats")?.length() ?: 0).put("js_challenges",result.optJSONObject("js_challenges"))
        File(app.filesDir,"youtube-compatibility.json").writeText(report.toString(2))
        val run=InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "manual"
        File(app.getExternalFilesDir(null),"youtube-metadata-$run.json").writeText(report.toString(2))
        android.util.Log.i("ZCompatibility",report.toString())
        assertTrue(result.has("error") || result.has("formats"))
    }
}
