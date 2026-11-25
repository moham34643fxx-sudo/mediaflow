package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID
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
    @Test fun captureTopCinemaHostTlsWithoutFollowingRedirects() = captureTopCinemaHostTls("topcinemaa.one")

    @Test fun captureTopCinemaRedirectHostTlsWithoutFollowingRedirects() = captureTopCinemaHostTls("topcinema.cam")

    private fun captureTopCinemaHostTls(host:String) = runBlocking<Unit> {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext
        val operation = "topcinema-head-${UUID.randomUUID()}"
        val request = JSONObject().put("url", "https://$host/").put("method", "HEAD")
            .put("headers", org.json.JSONArray()).put("timeout_ms", 15_000).put("operation", operation)
        val report = JSONObject().put("api", android.os.Build.VERSION.SDK_INT).put("host", host)
            .put("method", "HEAD").put("transport", "MetadataHttpBridge")
            .put("followsRedirects", false).put("episodeChecked", false)
        try {
            val response = MetadataHttpBridge().use { it.execute(request.toString(), null) }
            report.put("httpStatus", response.status).put("tls", "verified_http_response")
            if (response.status in 300..399) {
                val headers = org.json.JSONArray(response.headersJson)
                for (index in 0 until headers.length()) {
                    val pair = headers.getJSONArray(index)
                    if (!pair.getString(0).equals("Location", ignoreCase = true)) continue
                    val redirectHost = runCatching {
                        java.net.URI("https://$host/").resolve(pair.getString(1)).host
                    }.getOrNull()
                    report.put("redirectHost", redirectHost?.takeIf { it.matches(Regex("[A-Za-z0-9.-]{1,253}")) })
                    break
                }
            }
        } catch (error: MetadataTransportException) {
            report.put("tls", "no_verified_http_response").put("error", error.code)
                .put("exceptionClasses", org.json.JSONArray(error.safeCauseClassesJson))
            val failure = NetworkDiagnostics.exportSnapshot().asSequence().map { JSONObject(it) }
                .lastOrNull { it.optString("operation") == operation && it.optString("code") == error.code }
            report.put("stage", failure?.optString("stage") ?: "not_recorded")
        }
        val run = InstrumentationRegistry.getArguments().getString("evidenceRun", "manual")
        File(app.getExternalFilesDir(null), "topcinema-host-$run.json").writeText(report.toString(2))
        assertTrue("Host probe must record either an HTTP status or a transport outcome",
            report.has("httpStatus") || report.has("error"))
    }

    @Test fun captureTopCinemaEpisodeApi26FailureFacts() = runBlocking<Unit> {
        assertEquals("This diagnostic reproduction is scoped to API 26",26,android.os.Build.VERSION.SDK_INT)
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val args=InstrumentationRegistry.getArguments()
        val url=checkNotNull(args.getString("analysisUrl")) {"Provide the public TopCinema episode URL as analysisUrl"}
        val host=android.net.Uri.parse(url).host
        assertTrue("Probe host must be the public TopCinema fixture",host=="topcinemaa.one"||host=="www.topcinemaa.one")
        val id="topcinema-tls-${UUID.randomUUID()}"
        val probeStarted=System.currentTimeMillis()
        NetworkDiagnostics.tlsFailureEvidenceCaptureForTests=true
        val result=try {withTimeout(90_000) {app.extractor.pythonCall("analyze",url,id)}} finally {
            NetworkDiagnostics.tlsFailureEvidenceCaptureForTests=false
            app.extractor.cancel(id)
        }
        val safeClasses=Regex("[A-Za-z0-9_.$]{1,120}")
        val safeReason=Regex("[A-Z0-9_]{1,80}")
        val safeHost=Regex("(?i)^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
        val safeHash=Regex("[A-F0-9]{64}")
        val chain=org.json.JSONArray()
        result.optJSONArray("exception_chain")?.let {facts->
            for(index in 0 until facts.length()) {
                val fact=facts.optJSONObject(index) ?: continue
                val row=JSONObject().put("class",fact.optString("class").takeIf(safeClasses::matches) ?: "Other")
                fact.optString("ssl_reason").takeIf(safeReason::matches)?.let {row.put("ssl_reason",it)}
                chain.put(row)
            }
        }
        val connections=org.json.JSONArray()
        NetworkDiagnostics.exportSnapshot().forEach {raw->
            val event=runCatching {JSONObject(raw)}.getOrNull() ?: return@forEach
            if(event.optLong("time")<probeStarted||event.optString("stack")!="okhttp"||event.optString("code")!="TLS_FAILED") return@forEach
            val facts=event.optJSONObject("details") ?: return@forEach
            val endpoint=facts.optString("endpoint_host").takeIf(safeHost::matches) ?: return@forEach
            val connection=JSONObject().put("endpoint_host",endpoint)
            if(facts.has("certificate_path_index")&&!facts.isNull("certificate_path_index")) {
                val index=facts.optInt("certificate_path_index",Int.MIN_VALUE)
                if(index in -1..32) connection.put("certificate_path_index",index)
            }
            if(facts.has("certificate_path_length")&&!facts.isNull("certificate_path_length")) {
                val length=facts.optInt("certificate_path_length",Int.MIN_VALUE)
                if(length in 0..32) connection.put("certificate_path_length",length)
            }
            val certificates=org.json.JSONArray()
            facts.optJSONArray("certificates_sha256")?.let {rows->
                for(index in 0 until minOf(rows.length(),9)) {
                    val certificate=rows.optJSONObject(index) ?: continue
                    val pathIndex=certificate.optInt("path_index",Int.MIN_VALUE)
                    val fingerprint=certificate.optString("sha256")
                    if(pathIndex in 0..32&&safeHash.matches(fingerprint))
                        certificates.put(JSONObject().put("path_index",pathIndex).put("sha256",fingerprint))
                }
            }
            if(certificates.length()>0) connection.put("certificates_sha256",certificates)
            connections.put(connection)
        }
        val timings=JSONObject()
        result.optJSONObject("timings_ms")?.let {source->
            for(key in listOf("total","network_headers_inclusive","dns_inclusive","requests","redirects"))
                if(source.has(key)&&!source.isNull(key)) timings.put(key,source.opt(key))
        }
        val safeCode=Regex("[A-Z0-9_]{1,80}")
        val safeLabel=Regex("[A-Za-z0-9_.$]{1,120}")
        val report=JSONObject().put("api",android.os.Build.VERSION.SDK_INT)
            .put("error",result.optString("error","OK").takeIf(safeCode::matches) ?: "OTHER")
            .put("stage",result.optString("stage","complete").takeIf(Regex("[a-z_]{1,32}")::matches) ?: "other")
            .put("diagnostic",result.optString("diagnostic","none").takeIf(safeLabel::matches) ?: "Other")
            .put("exception_chain",chain).put("tls_connections",connections).put("timings_ms",timings)
        app.filesDir.resolve("$id.json").writeText(report.toString(2))
        android.util.Log.i("ZTlsProbe",report.toString())
        assertTrue("Analysis returned neither an error nor media formats",result.has("error")||result.has("formats"))
        if(report.optString("error")=="TLS_FAILED") {
            assertTrue("No per-request TLS endpoint was captured",connections.length()>0)
            assertTrue("No peer certificate fingerprints were captured",(0 until connections.length()).any {
                connections.getJSONObject(it).optJSONArray("certificates_sha256")?.length()?.let {count->count>0}==true
            })
        }
    }
}
