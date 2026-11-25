package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.AnalysisTrace
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Diagnostic matrix: blocked/unsupported public sources are outcomes, not hidden skips.
 * Timings measure a validated selectable model, not a drawn Compose frame. */
class UniversalAnalysisTest {
    @Test fun directFastPathComparedWithUpstream() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val args=InstrumentationRegistry.getArguments()
        val rows=JSONArray()
        for((kind,url) in linkedMapOf("mp4" to "https://www.w3schools.com/html/movie.mp4",
            "webm" to "https://storage.googleapis.com/shaka-demo-assets/angel-one/video_144p_96k_vp9.webm")) {
        if(args.getString("directKind")?.let {it!=kind}==true) continue
        repeat(args.getString("directTrials")?.toInt() ?: 3) {trial->
            val modes=args.getString("directForce")?.let {listOf(it.toBoolean())}
                ?: if(trial%2==0) listOf(false,true) else listOf(true,false)
            for(force in modes) {
                app.extractor.clearMetadataCache()
                AnalysisTrace.clear()
                val runtimeStarted=com.chaquo.python.Python.isStarted()
                val start=android.os.SystemClock.elapsedRealtime()
                val media=app.extractor.analyze(url,forceRefresh=true,forceExtractor=force,enrichOptional=false)
                val plan=Selection.video(media,media.qualityGroups().first().recommended)
                assertTrue(plan.primary.transportValid);assertEquals(kind,plan.output)
                val usedFastPath=plan.primary.id=="direct"
                if(usedFastPath) {assertNotNull(plan.primary.width);assertNotNull(media.duration)}
                assertEquals(url,JSONObject(plan.primary.payload).getString("url"))
                rows.put(JSONObject().put("kind",kind).put("trial",trial).put("force_upstream",force)
                    .put("elapsed_ms",android.os.SystemClock.elapsedRealtime()-start)
                    .put("used_fast_path",usedFastPath)
                    .put("python_started_before",runtimeStarted).put("analysis_trace",JSONArray(AnalysisTrace.snapshot()))
                    .put("format_count",media.formats.size).put("width",plan.primary.width).put("height",plan.primary.height)
                    .put("video_codec",plan.primary.videoCodec).put("audio_codec",plan.primary.audioCodec))
            }
        }
        }
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${args.getString("evidenceRun","manual")}").apply {mkdirs()}
        File(folder,"direct-fast-path-comparison.json").writeText(rows.toString(2))
    }
    @Test fun publicSourceFailureDiagnostics() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val args=InstrumentationRegistry.getArguments()
        val runtime=app.extractor.python()
        val globals=runtime.getModule("builtins").callAttr("dict")
        runtime.getModule("builtins").callAttr("exec", """
import zextractor, json, re
_zd_original_classify = zextractor.classify
_zd_failures = []
def _zd_record(exc, *args):
    message = re.sub(r'https?://[^\s]+', '[url]', str(exc))[:1200]
    _zd_failures.append({'type': type(exc).__name__, 'message': message})
    return _zd_original_classify(exc, *args)
zextractor.classify = _zd_record
""".trimIndent(),globals)
        val outcomes=JSONArray()
        try {
            for(url in listOf("https://www.youtube.com/watch?v=BaW_jenozKc","https://www.youtube.com/watch?v=jNQXAC9IVRw",
                "https://vimeo.com/76979871","https://www.reddit.com/r/videos/comments/6rrwyj/")) {
                val result=app.extractor.pythonCall("analyze",url,UUID.randomUUID().toString())
                outcomes.put(JSONObject().put("url",url).put("error",result.optString("error"))
                    .put("stages",result.optJSONObject("timings_ms")))
            }
            runtime.getModule("builtins").callAttr("exec","_zd_failures_json = json.dumps(_zd_failures)",globals)
            val failures=globals.callAttr("__getitem__","_zd_failures_json")
            val folder=File(app.getExternalFilesDir(null),"advanced-audit/${args.getString("evidenceRun","manual")}").apply {mkdirs()}
            File(folder,"public-source-diagnostics.json").writeText(JSONObject().put("outcomes",outcomes)
                .put("diagnostics",JSONArray(failures.toString())).toString(2))
        } finally {
            runtime.getModule("builtins").callAttr("exec","zextractor.classify = _zd_original_classify",globals)
        }
    }
    @Test fun uncachedSourceMatrix() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as ZApplication
        val args=InstrumentationRegistry.getArguments()
        val phase=args.getString("universalPhase","after")
        val sources=linkedMapOf(
            "hls" to "https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8",
            "dash" to "https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd",
            "html5" to "https://www.w3schools.com/html/html5_video.asp",
            "youtube" to "https://www.youtube.com/watch?v=jNQXAC9IVRw",
            "vimeo" to "https://vimeo.com/76979871",
            "facebook" to "https://www.facebook.com/reel/1102243642315985/")
        if(args.getString("instantMatrix")=="true") {
            sources.clear()
            sources.putAll(linkedMapOf(
                "youtube" to "https://www.youtube.com/watch?v=jNQXAC9IVRw",
                "facebook" to "https://www.facebook.com/reel/1102243642315985/",
                "instagram" to "https://www.instagram.com/reel/Chunk8-jurw/",
                "tiktok" to "https://www.tiktok.com/@scout2015/video/6718335390845095173",
                "direct-mp4" to "https://www.w3schools.com/html/mov_bbb.mp4",
                "hls" to "https://storage.googleapis.com/shaka-demo-assets/angel-one-hls/hls.m3u8",
                "dash-segmented" to "https://dash.akamaized.net/envivio/EnvivioDash3/manifest.mpd",
                "verified-720" to "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4",
                "verified-1080" to "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_1MB.mp4",
                "portrait-candidate" to "https://videos.pexels.com/video-files/5995101/5995101-hd_1080_1920_25fps.mp4",
                "unavailable" to "https://mediaflow-unavailable.invalid/video.mp4"))
        }
        if(args.getString("universalBroad")=="true") sources.putAll(linkedMapOf(
            "direct-mp4" to "https://www.w3schools.com/html/movie.mp4",
            "direct-webm" to "https://dl8.webmfiles.org/big-buck-bunny_trailer.webm",
            "direct-webm-w3c" to "https://media.w3.org/2010/05/bunny/trailer.webm",
            "direct-webm-gcs" to "https://storage.googleapis.com/shaka-demo-assets/angel-one/video_144p_96k_vp9.webm",
            "youtube-shorts" to "https://www.youtube.com/shorts/jNQXAC9IVRw",
            "youtube-unavailable-fixture" to "https://www.youtube.com/watch?v=BaW_jenozKc",
            "instagram" to "https://www.instagram.com/reel/Chunk8-jurw/",
            "tiktok" to "https://www.tiktok.com/@scout2015/video/6718335390845095173",
            "snapchat" to "https://www.snapchat.com/spotlight/W7_EDlXWTBiXAEEniNoMPwAAYYWtidGhudGZpAX1TKn0JAX1TKnXJAAAAAA",
            "x-twitter" to "https://twitter.com/starwars/status/665052190608723968",
            "reddit" to "https://www.reddit.com/r/videos/comments/6rrwyj/",
            "dailymotion" to "https://www.dailymotion.com/video/x5kesuj",
            "pinterest" to "https://www.pinterest.com/pin/664281013778109217/"))
        val rows=JSONArray()
        for((category,url) in sources) {
            app.extractor.clearMetadataCache();AnalysisTrace.clear()
            val id=UUID.randomUUID().toString()
            val row=JSONObject().put("category",category).put("url",url).put("phase",phase)
                .put("cache_bypassed",true).put("background_download","not_attempted")
                .put("output_validation","not_attempted").put("gallery_publication","not_attempted")
            val start=android.os.SystemClock.elapsedRealtime()
            try {
                UrlPolicy.normalize(url);row.put("url_accepted",true)
                val media=withTimeout(40_000) {
                    app.extractor.analyze(url,id,forceRefresh=true,enrichOptional=false) { discovered ->
                        val selectable=discovered.qualityGroups().isNotEmpty() || discovered.audioOptions().isNotEmpty()
                        if(selectable && !row.has("first_selectable_ms")) row.put("first_selectable_ms",android.os.SystemClock.elapsedRealtime()-start)
                    }
                }
                val choices=media.qualityGroups()
                val plan=choices.firstOrNull()?.recommended?.let {Selection.video(media,it)}
                    ?: Selection.audio(media,"original")
                assertTrue(plan.primary.transportValid)
                row.put("media_identified",true).put("source",media.source).put("media_id",media.id)
                    .put("format_count",media.formats.size).put("quality_labels",JSONArray(choices.map {it.resolution}))
                    .put("formats",JSONArray(media.formats.map {f->JSONObject().put("id",f.id).put("width",f.width)
                        .put("height",f.height).put("quality_label",qualityLabel(f)).put("protocol",f.protocol)
                        .put("video_codec",f.videoCodec).put("audio_codec",f.audioCodec).put("known_size",f.bytes!=null)}))
                    .put("download_plan_valid",true).put("separate_audio",plan.secondary!=null).put("outcome","resolved")
            } catch(error:Exception) {
                row.put("outcome","unresolved").put("error",if(error is MediaException) error.code.name else error.javaClass.simpleName)
            }
            row.put("full_analysis_ms",android.os.SystemClock.elapsedRealtime()-start)
            val trace=JSONArray(AnalysisTrace.snapshot())
            row.put("trace",(0 until trace.length()).map {trace.getJSONObject(it)}.firstOrNull {it.optString("id")==id})
            rows.put(row)
            val folder=File(app.getExternalFilesDir(null),"advanced-audit/${args.getString("evidenceRun","manual")}").apply {mkdirs()}
            File(folder,"universal-analysis-$phase.json").writeText(rows.toString(2))
        }
        assertEquals(sources.size,rows.length())
    }
}
