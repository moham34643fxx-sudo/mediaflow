package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.*
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ObsidianFixesTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
    @Test fun progressDoesNotEmitQueueWorkForThousandHistoryRows()=runBlocking {
        val db=androidx.room.Room.inMemoryDatabaseBuilder(app,com.zdownloader.app.data.ZDatabase::class.java).build()
        try {
            val dao=db.downloads()
            repeat(1000) {i->dao.insert(com.zdownloader.app.data.DownloadEntity("fixture-$i","https://example.com/fixture","History $i","Test",null,null,"{}","fixture.mp4",null,"fixture-$i",status=if(i==0) "DOWNLOADING" else "COMPLETED"))}
            val emissions=java.util.concurrent.atomic.AtomicInteger()
            val ready=CompletableDeferred<Unit>()
            val observer=launch {dao.queueSignals().distinctUntilChanged().collect {assertEquals(1,it.size);emissions.incrementAndGet();ready.complete(Unit)}}
            ready.await()
            val start=android.os.SystemClock.elapsedRealtime()
            repeat(20) {dao.progress("fixture-0",it.toLong(),100L,1)}
            delay(200)
            observer.cancelAndJoin()
            assertEquals(1,emissions.get())
            assertEquals(19L,dao.get("fixture-0")!!.bytes)
            File(app.getExternalFilesDir(null),"obsidian-progress-db.json").writeText(org.json.JSONObject().put("historyRows",1000).put("progressWrites",20).put("queueEmissions",emissions.get()).put("elapsedIncludingSettleMs",android.os.SystemClock.elapsedRealtime()-start).put("instantaneousPssKb",android.os.Debug.getPss()).toString(2))
        } finally {db.close()}
    }
    @Test fun proxyRedirectPolicyUsesEachTargetAndDirectDoesNotFallback() {
        val module=app.extractor.python().getModule("zextractor")
        val script="""
import types, urllib.request
from yt_dlp.networking._urllib import ProxyHandler, UrllibRH
from yt_dlp.networking import Request
import logging
original = AndroidNetwork
try:
    routes = {'https://a.example/': 'http://192.168.1.2:8080', 'https://b.example/': '', 'https://c.example/': 'http://192.168.1.3:8081'}
    AndroidNetwork = types.SimpleNamespace(proxyFor=lambda url: routes[url])
    handler = ProxyHandler({'all': 'http://stale.example:9000'})
    for url, proxy in routes.items():
        request = urllib.request.Request(url)
        system_proxy_open(handler, request)
        assert request.host == (proxy.split('://')[1] if proxy else 'b.example')
    engine = UrllibRH(proxies={'all': 'http://stale.example:9000'}, logger=logging.getLogger('test'))
    try:
        assert engine._get_proxies(Request('https://b.example/', proxies={'all': None})) == {'all': None}
    finally: engine.close()
finally:
    AndroidNetwork = original
    _current.proxy_host = None
""".trimIndent()
        app.extractor.python().builtins.callAttr("exec",script,module.get("__dict__"))
    }
    @Test fun monoStereo48kAndResamplingAreMeasuredAndPlayed()=runBlocking {
        val dir=app.storage.temporary("mp3-matrix")
        try {
            for((rate,channels) in listOf(44100 to 1,44100 to 2,48000 to 1,48000 to 2,22050 to 1,96000 to 2,48000 to 6)) {
                val pcm=File(dir,"source.pcm")
                pcm.outputStream().buffered().use {out->repeat(rate*2) {i->val sample=(kotlin.math.sin(i*2*Math.PI*440/rate)*12000).toInt();repeat(channels) {out.write(sample and 255);out.write(sample shr 8 and 255)}}}
                val source=File(dir,"source.wav")
                val fixture=FFmpegKit.executeWithArguments(arrayOf("-y","-f","s16le","-ar",rate.toString(),"-ac",channels.toString(),"-i",pcm.path,source.path))
                assertTrue(fixture.allLogsAsString,ReturnCode.isSuccess(fixture.returnCode))
                val f=Format("source","wav",null,null,"none","pcm_s16le",source.length(),null,"https","{}")
                val output=File(dir,"$rate-$channels.mp3")
                Processor().process(source,null,output,Selection(f,audioOnly=true,output="mp3",convertAudio=true,bitrate=192),TransferControl(),2.0,"اختبار الصوت")
                val info=FFprobeKit.getMediaInformation(output.path).mediaInformation
                val audio=info.streams.single {it.type=="audio"}.allProperties
                assertEquals(if(channels==1) 1 else 2,audio.getInt("channels"))
                assertEquals(if(rate==48000) 48000 else 44100,audio.getInt("sample_rate"))
                assertTrue(kotlin.math.abs(audio.getLong("bit_rate")-192000)<4000)
                val player=android.media.MediaPlayer()
                try {player.setDataSource(output.path);player.setVolume(0f,0f);player.prepare();player.start();withTimeout(6000) {while(player.currentPosition<100) delay(25)};assertTrue(player.currentPosition>=100)} finally {player.release()}
            }
        } finally {app.storage.removeTemporary("mp3-matrix")}
    }
    @Test fun malformedAndCancelledNativeInputsLeaveNoPartialOutput() {
        val dir=app.storage.temporary("lame-guards")
        try {
            val input=File(dir,"odd.pcm").apply {writeBytes(byteArrayOf(1,2,3))}
            val output=File(dir,"bad.mp3")
            assertTrue(LameEncoder.encodeEx(input.path,output.path,192,48000,2,java.util.concurrent.atomic.AtomicBoolean(false))!=0)
            assertFalse(output.exists())
            input.writeBytes(ByteArray(48000*4))
            assertEquals(-2,LameEncoder.encodeEx(input.path,output.path,192,48000,2,java.util.concurrent.atomic.AtomicBoolean(true)))
            assertFalse(output.exists())
        } finally {runBlocking {app.storage.removeTemporary("lame-guards")}}
    }
    @Test fun originalMp3PreservesSourceBitrateWithoutReencoding()=runBlocking {
        val dir=app.storage.temporary("mp3-original")
        try {
            val pcm=File(dir,"source.pcm").apply {writeBytes(ByteArray(44100*2))}
            val wav=File(dir,"source.wav")
            assertTrue(ReturnCode.isSuccess(FFmpegKit.executeWithArguments(arrayOf("-y","-f","s16le","-ar","44100","-ac","1","-i",pcm.path,wav.path)).returnCode))
            val wavFormat=Format("wav","wav",null,null,"none","pcm_s16le",wav.length(),null,"https","{}")
            val encoded=File(dir,"source.mp3")
            Processor().process(wav,null,encoded,Selection(wavFormat,audioOnly=true,output="mp3",convertAudio=true,bitrate=320),TransferControl(),1.0)
            val payload=org.json.JSONObject().put("format_id","mp3").put("url","https://example.com/audio.mp3").put("ext","mp3")
                .put("protocol","https").put("vcodec","none").put("acodec","mp3").put("filesize",encoded.length()).put("abr",320.0)
            val mp3Format=Format.parse(payload)
            val media=Media("https://example.com/audio","audio","Audio","Fixture",null,null,1.0,listOf(mp3Format))
            val original=Selection.audio(media,"original",mp3Format)
            val copied=File(dir,"copied.mp3")
            Processor().process(encoded,null,copied,original,TransferControl(),1.0)
            val stream=FFprobeKit.getMediaInformation(copied.path).mediaInformation.streams.single {it.type=="audio"}
            assertEquals("mp3",stream.codec)
            assertTrue(stream.allProperties.getLong("bit_rate")>=300000)
        } finally {app.storage.removeTemporary("mp3-original")}
    }
}
