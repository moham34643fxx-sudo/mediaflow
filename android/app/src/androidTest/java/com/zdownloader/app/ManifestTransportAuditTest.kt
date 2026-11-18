package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Genuine local FFmpeg HLS/DASH manifests and segments; only HTTP delivery is substituted. */
class ManifestTransportAuditTest {
    @Test fun nativeHlsAssemblesRealSegmentsAndRejectsAMissingSegment()=verify("hls")
    @Test fun nativeDashMergesSeparateTracksAndRejectsAMissingSegment()=verify("dash")

    private fun verify(kind:String)=runBlocking<Unit> {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        check(!app.coordinator.running)
        val root=File(app.cacheDir,"manifest-audit-${UUID.randomUUID()}").apply {check(mkdirs())}
        val source=File(root,"source.mp4")
        instrument.context.assets.open("status-original.mp4").use {input->source.outputStream().use {input.copyTo(it)}}
        val serving=File(root,"serving").apply {check(mkdirs())}
        val manifest=File(serving,if(kind=="hls") "master.m3u8" else "index.mpd")
        val args=mutableListOf("-y","-v","error","-i",source.path,"-t","4","-map","0:v:0","-map","0:a:0","-c","copy")
        args+=if(kind=="hls") listOf("-f","hls","-hls_time","1","-hls_list_size","0","-hls_playlist_type","vod",
            "-master_pl_name","master.m3u8","-hls_segment_filename",File(serving,"segment-%03d.ts").path)
            else listOf("-f","dash","-seg_duration","1","-use_template","0","-use_timeline","0")
        args+=if(kind=="hls") File(serving,"index.m3u8").path else manifest.path
        val controls=mutableListOf<TransferControl>()
        val python=app.extractor.python()
        val fixture=python.getModule("types").callAttr("ModuleType","manifest_transport_audit")
        fixture.put("directory",serving.path);fixture.put("manifest_name",manifest.name)
        var installed=false
        try {
            assertTrue("Cannot generate real local manifest",ReturnCode.isSuccess(FFmpegKit.executeWithArguments(args.toTypedArray()).returnCode))
            assertTrue(manifest.isFile)
            python.builtins.callAttr("exec","""
import io, os, json
from urllib.parse import urlsplit
import zextractor as z
from yt_dlp.networking.common import Response
from yt_dlp.networking.exceptions import HTTPError
original_engine = z.yt_dlp.YoutubeDL
fail_name = None
missing_requests = 0
class FixtureEngine(original_engine):
    def urlopen(self, request):
        global missing_requests
        url = request if isinstance(request, str) else request.url
        parts = urlsplit(url)
        name = parts.path.removeprefix('/')
        if parts.scheme != 'https' or parts.hostname != 'manifest-audit.example' or name != os.path.basename(name):
            raise AssertionError('Unexpected network/path in local manifest fixture')
        path = os.path.join(directory, name)
        if name == fail_name or not os.path.isfile(path):
            missing_requests += 1
            raise HTTPError(Response(io.BytesIO(b''), url, {}, 404))
        if os.path.getsize(path) > 4 * 1024 * 1024:
            raise AssertionError('Fixture response is larger than its test memory budget')
        with open(path, 'rb') as stream:
            payload = stream.read()
        mime = 'application/dash+xml' if name.endswith('.mpd') else 'application/vnd.apple.mpegurl' if name.endswith('.m3u8') else 'application/octet-stream'
        return Response(io.BytesIO(payload), url, {'Content-Length': str(len(payload)), 'Content-Type': mime}, 200)
with FixtureEngine({'quiet': True, 'logger': z.QuietLogger(), 'cachedir': False,
    'retries': 0, 'extractor_retries': 0, 'remote_components': [], 'js_runtimes': {}}) as engine:
    extracted = engine.extract_info('https://manifest-audit.example/' + manifest_name, download=False)
    metadata = json.dumps(engine.sanitize_info(extracted))
z.yt_dlp.YoutubeDL = FixtureEngine
""".trimIndent(),fixture.get("__dict__"))
            installed=true
            val metadata=JSONObject(fixture.get("metadata").toString())
            val media=Media.parse(metadata,"https://manifest-audit.example/${manifest.name}")
            val selection=Selection.video(media,media.videoOptions().first(),"mp4")
            if(kind=="dash") assertNotNull("DASH must exercise real separate audio/video tracks",selection.secondary)
            val missing=serving.listFiles()!!.filter {if(kind=="hls") it.extension=="ts" else it.name.startsWith("chunk-")&&it.extension=="m4s"}.sortedBy {it.name}.first()
            for(failing in listOf(false,true)) {
                fixture.put("fail_name",if(failing) missing.name else null)
                val control=TransferControl().also {controls+=it}
                val prefix=if(failing) "missing" else "complete"
                val primary=File(root,"$prefix-primary.${selection.primary.ext}")
                val secondary=selection.secondary?.let {File(root,"$prefix-secondary.${it.ext}")}
                try {
                    withTimeout(90_000) {
                        Transfer(app.extractor).stream(selection.primary,primary,"manifest-audit-${UUID.randomUUID()}",control) {_,_->}
                        if(secondary!=null) Transfer(app.extractor).stream(selection.secondary!!,secondary,"manifest-audit-${UUID.randomUUID()}",control) {_,_->}
                        val output=File(root,"$prefix-output.mp4")
                        Processor().process(primary,secondary,output,selection,control,media.duration)
                        val streams=FFprobeKit.getMediaInformation(output.path).mediaInformation!!.streams
                        assertTrue(streams.any {it.type=="video"});assertTrue(streams.any {it.type=="audio"})
                    }
                    assertFalse("Missing media segment was reported as a successful output",failing)
                } catch(error:MediaException) {
                    if(!failing) throw error
                    assertTrue("The missing segment must actually have been requested",fixture.get("missing_requests")!!.toInt()>0)
                    assertFalse(File(root,"$prefix-output.mp4").exists())
                }
            }
        } finally {
            for(control in controls) control.stop()
            withContext(NonCancellable) {withTimeout(30_000) {NativeWork.awaitIdle(root)}}
            if(installed) python.builtins.callAttr("exec","z.yt_dlp.YoutubeDL = original_engine",fixture.get("__dict__"))
            root.deleteRecursively()
        }
    }
}
