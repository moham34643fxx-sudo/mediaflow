package com.zdownloader.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ObsidianEngineTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
    private fun evidence(name:String,value:JSONObject) {File(app.getExternalFilesDir(null),name).writeText(value.toString(2))}
    @Test fun nativeLameEncodesEveryPresetAndAndroidDecodes() = runBlocking {
        assertEquals("3.100",LameEncoder.version())
        val dir=app.storage.temporary("obsidian-mp3")
        try {
            val source=File(dir,"source.m4a")
            val pcm=File(dir,"fixture.pcm")
            pcm.outputStream().buffered().use {out->repeat(44100*4) {i->val value=(kotlin.math.sin(i*2*Math.PI*1000/44100)*16000).toInt();repeat(2) {out.write(value and 255);out.write((value shr 8) and 255)}}}
            val fixture=FFmpegKit.executeWithArguments(arrayOf("-y","-f","s16le","-ar","44100","-ac","2","-i",pcm.path,"-c:a","aac",source.path))
            assertTrue(fixture.allLogsAsString,ReturnCode.isSuccess(fixture.returnCode))
            val format=Format("audio","m4a",null,null,"none","aac",source.length(),128.0,"https","{}")
            val rows=JSONArray()
            for(rate in listOf(0,128,192,256,320)) {
                val output=File(dir,"$rate.mp3")
                Processor().process(source,null,output,Selection(format,audioOnly=true,output="mp3",bitrate=rate,convertAudio=true),TransferControl(),4.0,"اختبار MP3")
                val info=FFprobeKit.getMediaInformation(output.path).mediaInformation
                assertEquals("mp3",info.streams.single {it.type=="audio"}.codec)
                val measured=info.bitrate.toLong()
                if(rate>0) assertTrue("$rate vs $measured",kotlin.math.abs(measured-rate*1000)<12000)
                val extractor=android.media.MediaExtractor()
                try {extractor.setDataSource(output.path);assertEquals("audio/mpeg",extractor.getTrackFormat(0).getString(android.media.MediaFormat.KEY_MIME))} finally {extractor.release()}
                val player=android.media.MediaPlayer()
                try {player.setDataSource(output.path);player.setVolume(0f,0f);player.prepare();player.start();withTimeout(6000) {while(player.currentPosition<=0) delay(100)};assertTrue("Android playback did not advance for $rate",player.currentPosition>0)} finally {player.release()}
                rows.put(JSONObject().put("preset",rate).put("bytes",output.length()).put("probe",info.allProperties))
            }
            evidence("obsidian-mp3.json",JSONObject().put("encoder",LameEncoder.version()).put("outputs",rows))
        } finally {app.storage.removeTemporary("obsidian-mp3")}
    }
    @Test fun bothNetworkStacksResolveAndTransfer() = runBlocking {
        val url="https://www.w3schools.com/html/mov_bbb.mp4"
        val media=app.extractor.analyze(url,forceRefresh=true)
        val dir=app.storage.temporary("obsidian-network")
        try {
            val kotlin=File(dir,"kotlin.mp4")
            app.transfer.stream(media.formats.first(),kotlin,"network-kotlin",TransferControl()) {_,_->}
            Processor().validate(kotlin,false,expectedContainer="mp4")
            val python=File(dir,"python.mp4")
            val cb=object:ProgressProxy {override fun progress(downloaded:String,total:String) {}}
            val result=JSONObject(app.extractor.python().getModule("zextractor").callAttr("download_stream",media.formats.first().payload,python.path,"network-python",cb).toString())
            assertFalse(result.toString(),result.has("error"))
            assertEquals(kotlin.length(),python.length())
            assertArrayEquals(java.security.MessageDigest.getInstance("SHA-256").digest(kotlin.readBytes()),java.security.MessageDigest.getInstance("SHA-256").digest(python.readBytes()))
            evidence("obsidian-network.json",JSONObject().put("kotlinBytes",kotlin.length()).put("pythonBytes",python.length()).put("identical",true))
        } finally {app.storage.removeTemporary("obsidian-network")}
    }
    @Test fun errorsRemainDistinctAcrossStacks() {
        assertEquals(ErrorCode.DNS_FAILED,NetworkDiagnostics.classify(java.net.UnknownHostException(),"dns"))
        assertEquals(ErrorCode.CONNECT_TIMEOUT,NetworkDiagnostics.classify(java.net.SocketTimeoutException(),"tcp"))
        assertEquals(ErrorCode.READ_TIMEOUT,NetworkDiagnostics.classify(java.net.SocketTimeoutException(),"body"))
        assertEquals(ErrorCode.TLS_FAILED,NetworkDiagnostics.classify(javax.net.ssl.SSLHandshakeException("test"),"tls"))
        val module=app.extractor.python().getModule("network_errors")
        val socket=app.extractor.python().getModule("socket")
        assertEquals("DNS_FAILED",module.callAttr("classify",socket.callAttr("gaierror",-2,"test")).asList()[0].toString())
    }
    @Test fun webmTracksBecomeRealMp4AndPublicAudioBecomesMp3() = runBlocking {
        val media=app.extractor.analyze("https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd",forceRefresh=true)
        val format=media.videoOptions().filter {it.ext=="webm"}.minBy {it.height ?: Int.MAX_VALUE}
        val selection=Selection.video(media,format,"mp4")
        val dir=app.storage.temporary("obsidian-conversion")
        try {
            val video=File(dir,"source.webm")
            app.transfer.stream(selection.primary,video,"obsidian-conversion",TransferControl()) {_,_->}
            val audio=selection.secondary?.let {f->File(dir,"audio.${f.ext}").also {app.transfer.stream(f,it,"obsidian-conversion-audio",TransferControl()) {_,_->}}}
            val mp4=File(dir,"converted.mp4")
            Processor().process(video,audio,mp4,selection,TransferControl(),media.duration)
            val info=FFprobeKit.getMediaInformation(mp4.path).mediaInformation
            assertTrue(info.allProperties.toString(),info.allProperties.getJSONObject("format").getString("format_name").split(',').contains("mp4"))
            assertEquals(format.height,info.streams.first {it.type=="video"}.height.toInt())
            assertEquals("aac",info.streams.first {it.type=="audio"}.codec)
            val retriever=android.media.MediaMetadataRetriever()
            try {retriever.setDataSource(mp4.path);val frame=retriever.getFrameAtTime(0);assertNotNull(frame);frame?.recycle()} finally {retriever.release()}
            val source=audio ?: video
            val audioSelection=Selection.audio(media,"mp3",selection.secondary ?: format).copy(bitrate=192)
            val mp3=File(dir,"public-audio.mp3")
            Processor().process(source,null,mp3,audioSelection,TransferControl(),media.duration,media.title)
            evidence("obsidian-conversion.json",JSONObject().put("mp4",info.allProperties).put("mp4Bytes",mp4.length()).put("mp3",FFprobeKit.getMediaInformation(mp3.path).mediaInformation.allProperties).put("mp3Bytes",mp3.length()))
        } finally {app.storage.removeTemporary("obsidian-conversion")}
    }
}
