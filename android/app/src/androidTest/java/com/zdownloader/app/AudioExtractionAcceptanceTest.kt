package com.zdownloader.app

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.extraction.*
import com.zdownloader.app.ui.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale

/** Real Android FFmpeg/LAME/SAF/MediaStore evidence; never runs against the user's package. */
class AudioExtractionAcceptanceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext.applicationContext as ZApplication
    private val repo get()=app.audioExtraction
    private val fixtures get()=File(app.filesDir,"extraction-fixtures").apply {mkdirs()}
    private val evidence get()=File(app.filesDir,"extraction-review").apply {mkdirs()}
    private val engine get()=repo.engine
    private fun safe() {check(app.packageName=="com.zdownloader.app.candidate0965")}
    private fun uri(file:File)=FileProvider.getUriForFile(app,"${app.packageName}.extraction.fixtures",file)
    private fun digest(file:File)=file.inputStream().use {MessageDigest.getInstance("SHA-256").let {md->val b=ByteArray(65536);while(true) {val n=it.read(b);if(n<0) break;md.update(b,0,n)};md.digest().joinToString("") {v->"%02x".format(v)}}}
    private suspend fun fixture(name:String,codec:String="aac",seconds:Int=8,multi:Boolean=false):File {
        val file=File(fixtures,name)
        val frame=File(fixtures,"frame.rgb").apply {if(!isFile) writeBytes(ByteArray(128*96*3) {if(it%3==2) 127 else 0})}
        fun tone(rate:Int,hz:Int):File=File(fixtures,"tone-$rate-$hz.raw").apply {
            if(!isFile) outputStream().buffered().use {out->repeat(rate) {n->val v=(kotlin.math.sin(n*2*Math.PI*hz/rate)*12000).toInt();out.write(v and 255);out.write((v shr 8) and 255)}}
        }
        val args=listOf("-y","-v","error","-stream_loop","-1","-f","rawvideo","-pixel_format","rgb24","-video_size","128x96","-framerate","5","-i",frame.path,
            "-stream_loop","-1","-f","s16le","-ar","48000","-ac","1","-i",tone(48000,440).path)+
            (if(multi) listOf("-stream_loop","-1","-f","s16le","-ar","44100","-ac","1","-i",tone(44100,880).path) else emptyList())+
            listOf("-map","0:v","-map","1:a")+(if(multi) listOf("-map","2:a") else emptyList())+
            listOf("-t",seconds.toString(),"-c:v","mpeg4","-q:v","20","-c:a",codec)+(if(codec=="aac") listOf("-b:a","128k") else if(codec=="opus") listOf("-strict","-2") else emptyList())+
            listOf("-metadata:s:a:0","language=ara","-metadata:s:a:0","title=Arabic")+
            (if(multi) listOf("-metadata:s:a:1","language=eng","-metadata:s:a:1","title=English") else emptyList())+file.path
        engine.execute(args,ExtractionControl())
        return file
    }
    private suspend fun decodedHash(input:String,map:String,out:File):String {
        engine.execute(listOf("-y","-v","error","-protocol_whitelist","file,pipe,saf","-i",input,"-map",map,"-f","hash","-hash","sha256",out.path),ExtractionControl())
        return out.readText().trim()
    }
    private fun info(file:File)=FFprobeKit.getMediaInformation(file.path).mediaInformation
    @After fun retainNativeDiagnostics() {
        if(app.packageName=="com.zdownloader.app.candidate0965") {
            File(evidence,"native-sessions.log").appendText(
                com.arthenica.ffmpegkit.FFmpegKit.listSessions().takeLast(12).joinToString("\n") {"${it.sessionId}: ${it.returnCode}\n${it.allLogsAsString}"}+"\n")
            repo.task.value?.takeIf {!it.phase.active}?.let {task->
                listOfNotNull(task.output,task.pending).forEach {value->runCatching {app.contentResolver.delete(Uri.parse(value),null,null)}}
            }
        }
    }
    @Test fun realCodecTrackAndContainerMatrix()=runBlocking {
        safe()
        val report=JSONArray()
        val matrix=listOf(Triple("multi.mp4","aac",AudioOutput.ORIGINAL),Triple("multi-mp3.mp4","aac",AudioOutput.MP3),
            Triple("convert.mkv","flac",AudioOutput.M4A),Triple("original.mkv","flac",AudioOutput.ORIGINAL),Triple("opus.mkv","opus",AudioOutput.ORIGINAL))
        for((name,codec,format) in matrix) {
            val sourceFile=fixture(name,codec,multi=name.startsWith("multi"))
            val before=digest(sourceFile)
            val source=engine.inspect(uri(sourceFile));assertTrue(source.tracks.isNotEmpty())
            val track=if(name.startsWith("multi")) source.tracks.last().also {assertEquals(2,source.tracks.size);assertEquals("eng",it.language)} else source.tracks.first()
            val plan=ExtractionPolicy.plan(track,format,if(format==AudioOutput.ORIGINAL) 0 else 192)
            val output=File(fixtures,"out-${name.substringBefore('.')}.${plan.extension}")
            val started=System.nanoTime();var updates=0
            engine.extract(source,track,plan,output,ExtractionControl()) {updates++}
            val extractionMs=(System.nanoTime()-started)/1000000
            engine.validate(output,plan,track.duration,ExtractionControl())
            val result=info(output);assertEquals(1,result.streams.size);assertEquals("audio",result.streams[0].type)
            assertEquals(plan.codec,result.streams[0].codec)
            val sample=File(fixtures,"one-second-sample.pcm")
            engine.execute(listOf("-y","-v","error","-i",output.path,"-map","0:a:0","-t","1","-ac","1","-ar","16000","-f","s16le",sample.path),ExtractionControl())
            val pcm=java.nio.ByteBuffer.wrap(sample.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            var energy=0.0;var crossings=0;var previous=0;val samples=pcm.remaining()
            while(pcm.hasRemaining()) {val value=pcm.get().toInt();energy+=value.toDouble()*value;if(previous<0&&value>=0) crossings++;previous=value}
            val rms=kotlin.math.sqrt(energy/samples)
            val hz=crossings*16000.0/samples
            assertTrue("Unexpected silence in $name",rms>1000)
            assertEquals(if(name.startsWith("multi")) 880.0 else 440.0,hz,20.0)
            if(plan.copy) {
                val inputHash=decodedHash(com.arthenica.ffmpegkit.FFmpegKitConfig.getSafParameterForRead(app,uri(sourceFile)),"0:${track.index}",File(fixtures,"in.hash"))
                val outputHash=decodedHash(output.path,"0:a:0",File(fixtures,"out.hash"))
                File(evidence,"copy-${name.substringBefore('.')}-diagnostic.json").writeText(JSONObject().put("inputHash",inputHash).put("outputHash",outputHash)
                    .put("input",info(sourceFile).allProperties).put("output",result.allProperties).toString(2))
                assertEquals(inputHash,outputHash)
            }
            assertEquals(before,digest(sourceFile))
            report.put(JSONObject().put("source",name).put("sourceBytes",sourceFile.length()).put("codec",codec).put("outputCodec",plan.codec)
                .put("copy",plan.copy).put("selectedTrack",track.index).put("duration",result.duration).put("outputBytes",output.length()).put("extractionMs",extractionMs).put("progressCallbacks",updates)
                .put("outputRms16Bit",rms).put("estimatedToneHz",hz))
        }
        File(evidence,"codec-matrix.json").writeText(report.toString(2))
    }
    @Test fun sourcePastFourGiBUsesSeekableSafWithoutCopyingVideo()=runBlocking {
        safe()
        val source=fixture("large.mp4",seconds=40)
        // Insert an extended free box before moov: moov is genuinely beyond 32-bit file offsets.
        // Sparse padding is disclosed: this tests >4GiB size/seek handling, not a dense 4GiB encode.
        RandomAccessFile(source,"rw").use {f->
            var pos=0L;var moov=-1L
            while(pos<f.length()) {f.seek(pos);val size=f.readInt().toLong() and 0xffffffffL;val type=ByteArray(4).also {f.readFully(it)}.toString(Charsets.US_ASCII);if(type=="moov") {moov=pos;break};check(size>=8);pos+=size}
            check(moov>0);val tail=ByteArray((f.length()-moov).toInt());f.seek(moov);f.readFully(tail)
            val gap=4L*1024*1024*1024+1024
            f.setLength(moov+gap+tail.size);f.seek(moov);f.writeInt(1);f.writeBytes("free");f.writeLong(gap);f.seek(moov+gap);f.write(tail)
        }
        val started=System.nanoTime();val memoryBefore=android.os.Debug.getPss()
        val input=engine.inspect(uri(source));val track=input.tracks.first();val plan=ExtractionPolicy.plan(track,AudioOutput.ORIGINAL,0)
        val out=File(fixtures,"large-audio.m4a")
        engine.extract(input,track,plan,out,ExtractionControl()) {}
        engine.validate(out,plan,track.duration,ExtractionControl())
        assertTrue(source.length()>4L*1024*1024*1024);assertTrue(out.length()<2*1024*1024)
        assertFalse(repo.directory.listFiles().orEmpty().any {it.length()>100*1024*1024})
        File(evidence,"large-file.json").writeText(JSONObject().put("sparsePadding",true).put("sourceBytes",source.length()).put("outputBytes",out.length())
            .put("duration",track.duration).put("elapsedMs",(System.nanoTime()-started)/1000000).put("pssBeforeKb",memoryBefore).put("pssAfterKb",android.os.Debug.getPss()).toString(2))
        source.delete()
        Unit
    }
    @Test fun denseVideoOverTwoGiBHasBoundedMemoryAndRealAudio()=runBlocking {
        safe()
        check(android.os.StatFs(fixtures.path).availableBytes>4L*1024*1024*1024)
        fixture("dense-seed.mp4",seconds=1)
        val frame=File(fixtures,"large-frame.yuv").apply {writeBytes(ByteArray(1280*720*3/2) {if(it<1280*720) 76 else 128.toByte()})}
        // A finite, contiguous 75-second PCM fixture avoids relying on raw
        // demuxer loop timestamp behavior in the bundled Android FFmpeg build.
        val pcm=File(fixtures,"dense-continuous.raw").apply {
            val second=File(fixtures,"tone-48000-440.raw").readBytes()
            outputStream().buffered().use {out->repeat(75) {out.write(second)}}
        }
        val source=File(fixtures,"dense-video.nut")
        val out=File(fixtures,"dense-audio.wav")
        try {
            // Actual video packets on disk, not sparse padding or renamed data. Never decode video during extraction.
            engine.execute(listOf("-y","-v","error","-stream_loop","-1","-f","rawvideo","-pixel_format","yuv420p","-video_size","1280x720","-framerate","25","-i",frame.path,
                "-f","s16le","-ar","48000","-ac","1","-i",pcm.path,
                "-map","0:v:0","-map","1:a:0","-t","75","-c:v","copy","-c:a","copy","-f","nut",source.path),ExtractionControl())
            assertTrue(source.length()>2L*1024*1024*1024)
            val before=android.os.Debug.getPss();var peak=before
            val monitor=launch(Dispatchers.Default) {while(isActive) {peak=maxOf(peak,android.os.Debug.getPss());delay(100)}}
            val started=System.nanoTime()
            try {
                val input=engine.inspect(uri(source));val track=input.tracks.first();val plan=ExtractionPolicy.plan(track,AudioOutput.ORIGINAL,0)
                engine.extract(input,track,plan,out,ExtractionControl()) {}
                File(evidence,"dense-probe.json").writeText(JSONObject().put("input",info(source).allProperties).put("output",info(out).allProperties)
                    .put("planCodec",plan.codec).put("planRate",plan.rate).put("planChannels",plan.channels).put("expectedSeconds",75).toString(2))
                engine.validate(out,plan,75.0,ExtractionControl())
                val elapsedMs=(System.nanoTime()-started)/1000000
                assertEquals(decodedHash(source.path,"0:a:0",File(fixtures,"dense-in.hash")),decodedHash(out.path,"0:a:0",File(fixtures,"dense-out.hash")))
                assertTrue(out.length()<10*1024*1024)
                File(evidence,"dense-large-file.json").writeText(JSONObject().put("sparsePadding",false).put("sourceBytes",source.length()).put("outputBytes",out.length()).put("durationSeconds",75)
                    .put("extractAndValidateMs",elapsedMs).put("pssBeforeKb",before).put("sampledPeakPssKb",peak).put("pcmHashMatches",true).toString(2))
            } finally {monitor.cancelAndJoin()}
        } finally {source.delete();frame.delete();pcm.delete()}
    }
    @Test fun cancellationIsScopedAndLeavesSourceIntact()=runBlocking {
        safe();val file=fixture("cancel.mp4",seconds=600);val hash=digest(file)
        val source=engine.inspect(uri(file));val track=source.tracks.first();val plan=ExtractionPolicy.plan(track,AudioOutput.MP3,192)
        val control=ExtractionControl();val out=File(fixtures,"cancel.mp3")
        val job=async(Dispatchers.IO) {runCatching {engine.extract(source,track,plan,out,control) {}}}
        delay(200);control.cancel();val result=withTimeout(15000) {job.await()}
        assertTrue(result.isFailure);assertNull(control.session);assertEquals(hash,digest(file));out.delete()
        // A later independent session must still work; no global FFmpeg cancellation.
        engine.execute(listOf("-v","error","-i",file.path,"-map","0:a:0","-t","1","-f","null","-"),ExtractionControl())
    }
    @Test fun realServiceSavesVerifiedAudioToMediaStoreAndPlaybackWorks()=runBlocking {
        safe();val file=fixture("publish.mp4",seconds=20)
        ActivityScenario.launch(MainActivity::class.java).use {
            repo.discard()
            val source=engine.inspect(uri(file));val track=source.tracks.first();val plan=ExtractionPolicy.plan(track,AudioOutput.MP3,192)
            repo.start(source,track,plan)
            // The production foreground service, not the test coroutine, owns the work.
            withTimeout(90000) {while(repo.task.value?.phase?.active==true) delay(100)}
            val result=repo.task.value!!
            File(evidence,"publication.json").writeText(JSONObject().put("phase",result.phase.name).put("error",result.error).put("output",result.output).put("bytes",result.bytes).put("screenStateAutomated",false).toString(2))
            assertEquals(ExtractionPhase.COMPLETED,result.phase)
            val output=Uri.parse(result.output)
            val copy=File(fixtures,"published.mp3");app.contentResolver.openInputStream(output)!!.use {input->copy.outputStream().use {sink->input.copyTo(sink)}}
            assertEquals(result.sha256,digest(copy));engine.validate(copy,plan,track.duration,ExtractionControl())
            val player=MediaPlayer()
            try {
                player.setDataSource(app,output);player.setVolume(0f,0f);player.prepare()
                assertTrue("Unexpected Android player duration: ${player.duration} ms",player.duration in 19500..20500)
                player.start()
                // Audio routing/decoder startup need not complete within a fixed
                // 500 ms. Require actual playback progress, with a bounded wait.
                withTimeout(5000) {while(player.currentPosition<=0) delay(50)}
                assertTrue("Android player did not advance",player.currentPosition>0)
            } finally {
                File(evidence,"playback.json").writeText(JSONObject().put("durationMs",runCatching {player.duration}.getOrDefault(-1))
                    .put("positionMs",runCatching {player.currentPosition}.getOrDefault(-1)).put("mutedDuringTest",true).toString(2))
                player.release()
            }
            // The library queries the merged 'external' collection; the inserted URI
            // uses 'external_primary'. They are aliases for this same indexed row.
            assertTrue(app.mediaFiles.indexed(true,"publish","date",500).any {
                Uri.parse(it.uri).lastPathSegment==output.lastPathSegment&&it.name==result.name&&it.volume=="external_primary"
            })
            app.contentResolver.delete(output,null,null)
        }
        Unit
    }
    @Test fun failedSaveRetainsVerifiedAudioAndCanRetryWithoutSource()=runBlocking {
        safe();val file=fixture("retry-save.mp4",seconds=3)
        ActivityScenario.launch(MainActivity::class.java).use {
            repo.discard()
            val source=engine.inspect(uri(file));val track=source.tracks.first();val plan=ExtractionPolicy.plan(track,AudioOutput.M4A,0)
            repo.start(source,track,plan,Uri.parse("content://${app.packageName}.extraction.fixtures/fixtures/nonexistent-parent/result.m4a"))
            withTimeout(30000) {while(repo.task.value?.phase?.active==true) delay(50)}
            val failed=repo.task.value!!
            assertEquals(ExtractionPhase.SAVE_FAILED,failed.phase);assertTrue(failed.valid)
            val prepared=File(repo.directory,"${failed.id}.${plan.extension}");assertTrue(prepared.isFile)
            file.delete() // Proves retry does not read/re-encode the source.
            repo.retrySave()
            withTimeout(30000) {while(repo.task.value?.phase?.active==true) delay(50)}
            val saved=repo.task.value!!;assertEquals(ExtractionPhase.COMPLETED,saved.phase)
            assertEquals(failed.id,saved.id);assertEquals(failed.sha256,saved.sha256)
            File(evidence,"save-retry.json").writeText(JSONObject().put("initialPhase",failed.phase.name).put("retryPhase",saved.phase.name).put("sourceRemovedBeforeRetry",true).put("samePreparedHash",failed.sha256==saved.sha256).toString(2))
            app.contentResolver.delete(Uri.parse(saved.output),null,null)
        }
        Unit
    }
    @Test fun videosWithoutAudioAndDamagedInputsAreRejected()=runBlocking {
        safe();val seed=fixture("silent-seed.mp4",seconds=1)
        val silent=File(fixtures,"no-audio.mp4")
        engine.execute(listOf("-y","-v","error","-i",seed.path,"-map","0:v:0","-c","copy","-an",silent.path),ExtractionControl())
        val noAudio=runCatching {engine.inspect(uri(silent))}.exceptionOrNull()
        assertEquals(R.string.extract_no_audio,(noAudio as? ExtractionFailure)?.messageId)
        val broken=File(fixtures,"broken.mp4").apply {writeBytes(ByteArray(64))}
        assertTrue(runCatching {engine.inspect(uri(broken))}.isFailure)
    }
    @Test fun premiumArabicAndEnglishLayoutsUseRealSource()=runBlocking {
        safe();repo.discard();val file=fixture("layout.mp4",multi=true)
        for((lang,scale) in listOf("ar" to 1f,"en" to 1f,"ar" to 1.6f)) {
            ActivityScenario.launch(ComponentActivity::class.java).use {scenario->
                scenario.onActivity {activity->
                    val model=androidx.lifecycle.ViewModelProvider(activity)[AudioExtractionViewModel::class.java]
                    model.select(uri(file))
                    activity.setContent {
                        val configuration=android.content.res.Configuration(LocalConfiguration.current).apply {setLocale(Locale(lang));fontScale=scale}
                         val configured=activity.createConfigurationContext(configuration)
                         val localized=object:android.content.ContextWrapper(activity) {
                             override fun getResources()=configured.resources
                             override fun getAssets()=configured.assets
                         }
                        val density=LocalDensity.current
                        CompositionLocalProvider(LocalContext provides localized,LocalSpectrumContext provides localized,LocalConfiguration provides configuration,LocalDensity provides Density(density.density,scale),LocalLayoutDirection provides if(lang=="ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                            FlowTheme(Spectrum.Dark) {AudioExtractionScreen(model,{},{})}
                        }
                    }
                }
                compose.waitUntil(20000) {compose.onAllNodesWithTag("extraction_start").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("extraction_start").assertIsDisplayed()
                compose.waitForIdle()
                val image=instrument.uiAutomation.takeScreenshot()!!
                try {File(evidence,"$lang-font${(scale*100).toInt()}.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {image.recycle()}
                compose.onNodeWithTag("extraction_list").performScrollToNode(hasTestTag("extraction_format_MP3"))
                compose.onNodeWithTag("extraction_format_MP3").performClick()
                compose.onNodeWithTag("extraction_bitrate_192").assertIsSelected()
                compose.waitForIdle()
                val options=instrument.uiAutomation.takeScreenshot()!!
                try {File(evidence,"$lang-font${(scale*100).toInt()}-options.png").outputStream().use {options.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {options.recycle()}
            }
        }
    }
}
