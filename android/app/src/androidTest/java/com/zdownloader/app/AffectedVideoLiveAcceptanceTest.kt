package com.zdownloader.app

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.NativeWork
import com.zdownloader.app.engine.AnalysisTrace
import com.zdownloader.app.ui.QuickShareViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/** Real source, UI, durable queue, full production validation and published-byte checks.
 * Never launches, queries, or cleans the installed user's application/package.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AffectedVideoLiveAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()

    // Explicit Unit is essential: JUnit rejects a test inferred to return JSONObject.
    @Test fun real360SelectionPublishesTheActualSourceQuality(): Unit = runBlocking {
        acceptLiveVideo(selectedHeight = 360)
    }

    @Test fun real720DefaultsToMp4MergeCopyAndPreservesBothEncodedStreams(): Unit = runBlocking {
        acceptLiveVideo(selectedHeight = 720)
    }
    @Test fun unratedSdIsMeasuredAndReal360PublishesWithAudio():Unit=runBlocking {
        checkNotNull(InstrumentationRegistry.getArguments().getString("unratedSource")) {"unratedSource argument required"}
        acceptLiveVideo(selectedHeight=360,unratedSource=true)
    }

    private class Evidence(val report: JSONObject) {
        var check = "initialization"
        var status = "NOT_SUBMITTED"
        var error = "NONE"
        fun observe(row: DownloadEntity) {
            status = token(row.status)
            error = row.error?.let { ErrorCode.parse(it).name } ?: "NONE"
            report.put("status", status).put("errorCode", error)
        }
        fun expect(name: String, condition: Boolean) {
            check = name
            assertTrue("check=$name status=$status error=$error", condition)
        }
        fun failure(type: String): String = "check=$check status=$status error=$error type=${token(type)}"
    }

    private data class PacketHashes(val video: String, val audio: String) {
        fun json() = JSONObject().put("videoSha256", video).put("audioSha256", audio)
    }

    private suspend fun acceptLiveVideo(selectedHeight:Int,unratedSource:Boolean=false): Unit {
        val instrument = InstrumentationRegistry.getInstrumentation()
        // Guard before application access, cache clearing, Activity launch, or file creation.
        check(instrument.targetContext.packageName == REVIEW_PACKAGE) { "Review package required" }
        val app = instrument.targetContext.applicationContext as ZApplication
        check(app.packageName == REVIEW_PACKAGE) { "Review package required" }
        val source = InstrumentationRegistry.getArguments().getString(if(unratedSource) "unratedSource" else "probeSource")
        check(!source.isNullOrBlank()) { "probeSource argument required" }
        val beforeRows = app.repository.downloads.first()
        val before = beforeRows.map { it.id }.toSet()
        val reportFile = File(app.filesDir, "quickshare-review/${if(unratedSource) "live-facebook-unrated-360" else if (selectedHeight==360) "live-facebook-360" else "live-facebook-mp4-720"}.json")
        val evidence = Evidence(JSONObject().put("passed", false).put("package", app.packageName)
            .put("test", if (selectedHeight==360) "real360_selection" else "actual720_mp4_copy")
            .put("source", "instrumentation_probeSource_public_link")
            .put("timingScope", "Observed live acceptance run; source hashing overlaps validation, not a performance benchmark")
            .put("sdk", Build.VERSION.SDK_INT).put("externalAppSending", "not_tested"))
        val scratch = File(app.cacheDir, "live-video-review-${UUID.randomUUID()}").apply { mkdirs() }
        var model: QuickShareViewModel? = null
        var owned: String? = null
        var failure: AssertionError? = null
        val began = SystemClock.elapsedRealtime()
        try {
            evidence.expect("platform_supports_vp9_mp4_policy", Build.VERSION.SDK_INT >= 31)
            val normalized = SharedLinks.normalize(requireNotNull(source))
            val sourceKey = MediaIdentity.urlKey(normalized)
            evidence.expect("no_preexisting_request_for_source", beforeRows.none { it.url == normalized || it.urlKey == sourceKey })
            app.extractor.clearMetadataCache()
            AnalysisTrace.clear()
            withTimeout(420_000) {
                val intent = Intent(app, ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(Intent.EXTRA_TEXT, source)
                ActivityScenario.launch<ShareReceiverActivity>(intent).use { scenario ->
                    lateinit var viewModel: QuickShareViewModel
                    scenario.onActivity { viewModel = ViewModelProvider(it)[QuickShareViewModel::class.java] }
                    model = viewModel
                    evidence.report.put("traceId",viewModel.state.value.traceId)
                    var previewSelection: Selection? = null
                    val discoveryStarted = SystemClock.elapsedRealtime()
                    evidence.check = "base_catalogue_ready"
                    withTimeout(110_000) {
                        while (viewModel.state.value.media == null) {
                            val state = viewModel.state.value
                            evidence.expect("base_metadata_no_error", state.error == null)
                            delay(25)
                        }
                    }
                    val media = requireNotNull(viewModel.state.value.media)
                    evidence.expect("no_preexisting_media_duplicate", !app.repository.duplicateReview(media).found)
                    val edges = media.qualityGroups().mapNotNull { it.resolution }.toSet()
                    evidence.expect("requested_real_quality_exists", selectedHeight in edges)
                    if(!unratedSource) evidence.expect("facebook_real_catalogue", edges.containsAll(setOf(360, 540, 720, 1080)) && 480 !in edges)
                    evidence.report.put("availableEdges", JSONArray(edges.sorted()))
                        .put("metadataMs", SystemClock.elapsedRealtime() - discoveryStarted)
                        .put("sizesStillEnrichingAtSelection", viewModel.state.value.enrichingSizes)
                    evidence.check = "verified_quality_row_visible"
                    compose.waitUntil(30_000) {compose.onAllNodesWithTag("share_video_$selectedHeight").fetchSemanticsNodes().isNotEmpty()}
                    compose.onNodeWithTag("share_formats").performScrollToNode(hasTestTag("share_video_$selectedHeight"))
                    compose.onNodeWithTag("share_video_$selectedHeight").assertIsEnabled().performClick()
                    scenario.onActivity {
                        previewSelection = VideoPreferenceSelection.ready(viewModel.state.value.media,viewModel.state.value.qualityPreference)
                        val selected = previewSelection
                        evidence.expect("actual_quality_selected", selected != null && verifiedQualityEdge(selected.primary) == selectedHeight)
                        evidence.expect("actual_quality_default_mp4", selected?.output == "mp4")
                        evidence.expect("actual_quality_no_transcode", selected != null && !selected.transcodeVideo && !selected.transcodeAudio)
                        if(selectedHeight==720) evidence.expect("actual720_merge_copy", selected?.plan == ProcessingPlan.MERGE_COPY)
                    }
                    evidence.report.put("selectedRealHeight",selectedHeight)
                        .put("selectedRowTag","share_video_$selectedHeight")
                        .put("qualitySelectedAfterMetadata",true)
                        .put("metadataMs",SystemClock.elapsedRealtime() - discoveryStarted)
                    evidence.check = "download_pressed"
                    compose.onNodeWithTag("share_download").assertIsEnabled().performClick()
                    val submittedAt = SystemClock.elapsedRealtime()
                    withTimeout(20_000) {
                        while (owned == null) {
                            val state = viewModel.state.value
                            (state.persistedId ?: state.queuedId)?.let { id ->
                                evidence.expect("new_review_row_owned", id !in before)
                                owned = id
                            }
                            evidence.error = state.error?.name ?: "NONE"
                            evidence.expect("submission_no_error", state.error == null && state.duplicate == null)
                            if (owned == null) delay(10)
                        }
                    }
                    val id = requireNotNull(owned)
                    evidence.report.put("operation", id).put("persistMs", SystemClock.elapsedRealtime() - submittedAt)
                    val persisted = requireNotNull(app.repository.dao.get(id))
                    evidence.observe(persisted)
                    val persistedSelection=persisted.selection.takeIf {it.isNotBlank()}?.let {Selection.parse(it)}
                    evidence.expect("verified_selection_persisted",persistedSelection!=null&&
                        persistedSelection.primary.id==requireNotNull(previewSelection).primary.id&&
                        verifiedQualityEdge(persistedSelection.primary)==selectedHeight)
                    evidence.report.put("persistedRealSelection",true).put("persistedRequestPresent",persisted.request!=null)
                    var resolved: Selection? = null
                    var inputHashes: PacketHashes? = null
                    var inputBytes = 0L
                    var completed: DownloadEntity? = null
                    var publishedAt = 0L
                    while (completed == null) {
                        val row = requireNotNull(app.repository.dao.get(id))
                        evidence.observe(row)
                        evidence.expect("transfer_not_failed_or_cancelled", row.status !in setOf("FAILED", "CANCELLED", "INTERRUPTED", "PAUSED"))
                        if (resolved == null) evidence.expect("discovery_within110s", SystemClock.elapsedRealtime() - discoveryStarted <= 110_000)
                        if (row.selection.isNotBlank() && resolved == null) {
                            resolved = Selection.parse(row.selection)
                            evidence.expect("discovery_within110s", SystemClock.elapsedRealtime() - discoveryStarted <= 110_000)
                            evidence.report.put("resolvedAfterMs", SystemClock.elapsedRealtime() - discoveryStarted)
                        }
                        if (row.status == "VALIDATING" && inputHashes == null) {
                            val hashingStarted = SystemClock.elapsedRealtime()
                            val selection = Selection.parse(row.selection)
                            resolved = selection
                            val directory = File(app.filesDir, "transfers/$id")
                            // Reserve before reading either source; publication's cleanup defers
                            // until both hashes are captured. Do not awaitIdle with our lease held.
                            val lease = NativeWork.acquire(directory)
                            try {
                                val primary = File(directory, "primary.${selection.primary.ext}")
                                val audio = selection.secondary?.let { File(directory, "secondary.${it.ext}") } ?: primary
                                evidence.expect("downloaded_sources_retained", primary.isFile && audio.isFile && primary.length() > 0 && audio.length() > 0)
                                inputBytes = primary.length() + if (audio == primary) 0 else audio.length()
                                val videoHash = packetHash(primary, "0:v:0", File(scratch, "input-video.sha256"), evidence)
                                val audioHash = packetHash(audio, "0:a:0", File(scratch, "input-audio.sha256"), evidence)
                                inputHashes = PacketHashes(videoHash, audioHash)
                                evidence.report.put("inputHashes", inputHashes.json()).put("sourceBytes", inputBytes)
                                    .put("hashCaptureStage", "VALIDATING")
                                    .put("inputHashingMs", SystemClock.elapsedRealtime() - hashingStarted)
                                    .put("sourceHashingConcurrentWithValidation", true)
                            } finally { lease.release() }
                        }
                        if (row.status == "COMPLETED") {
                            evidence.expect("input_hashes_captured_before_cleanup", inputHashes != null)
                            completed = row
                            publishedAt = SystemClock.elapsedRealtime()
                        } else delay(20)
                    }
                    val finalRow = requireNotNull(completed)
                    evidence.observe(finalRow)
                    val selection = requireNotNull(resolved)
                    val finalSelection = Selection.parse(finalRow.selection)
                    val edge = verifiedQualityEdge(finalSelection.primary)
                    evidence.expect("verified_final_quality", edge != null && edge <= selectedHeight)
                    evidence.expect("expected_actual_quality", edge == selectedHeight)
                    evidence.expect("selected_dimensions_known", selection.primary.width != null && selection.primary.height != null)
                    evidence.expect("selection_identity_stable", finalSelection.primary.id == selection.primary.id &&
                        finalSelection.primary.width == selection.primary.width && finalSelection.primary.height == selection.primary.height &&
                        finalSelection.secondary?.id == selection.secondary?.id)
                    evidence.expect("preview_rendition_retained", previewSelection?.primary?.id == selection.primary.id &&
                        previewSelection?.primary?.width == selection.primary.width && previewSelection?.primary?.height == selection.primary.height)
                    evidence.expect("final_mp4_copy_policy", finalSelection.output == "mp4" && !finalSelection.transcodeVideo && !finalSelection.transcodeAudio)
                    val expectedCodec=if(unratedSource) "h264" else "vp9"
                    evidence.expect("source_codec", codecFamily(selection.primary.videoCodec) == expectedCodec)
                    if (selectedHeight==720) evidence.expect("final720_merge_copy", finalSelection.plan == ProcessingPlan.MERGE_COPY && finalSelection.secondary != null)
                    evidence.report.put("selectedFormatId", token(selection.primary.id)).put("audioFormatId", token((selection.secondary ?: selection.primary).id))
                        .put("selectedEdge", edge).put("sourceWidth", selection.primary.width).put("sourceHeight", selection.primary.height)
                        .put("plan", finalSelection.plan.name).put("transcodeVideo", finalSelection.transcodeVideo)
                        .put("transcodeAudio", finalSelection.transcodeAudio)
                    val published = File(scratch, "published.mp4")
                    val uri = Uri.parse(requireNotNull(finalRow.uri))
                    evidence.expect("published_content_uri", uri.scheme == "content")
                    evidence.check = "copy_published_bytes"
                    withContext(Dispatchers.IO) {
                        requireNotNull(app.contentResolver.openInputStream(uri)).use { input ->
                            published.outputStream().use { input.copyTo(it) }
                        }
                    }
                    evidence.expect("published_byte_count", published.length() > 0 && finalRow.outputSize == published.length())
                    val probe = withContext(Dispatchers.IO) { FFprobeKit.getMediaInformation(published.path).mediaInformation }
                    evidence.expect("published_probe_succeeded", probe != null)
                    val info = requireNotNull(probe)
                    val video = info.streams.firstOrNull { it.type == "video" }
                    val audio = info.streams.firstOrNull { it.type == "audio" }
                    evidence.expect("published_dimensions_match_selection", video?.width?.toInt() == selection.primary.width && video?.height?.toInt() == selection.primary.height)
                    evidence.expect("published_video_codec_retained", codecFamily(video?.codec) == expectedCodec)
                    evidence.expect("published_audio_present", audio != null && codecFamily(audio.codec) == codecFamily((selection.secondary ?: selection.primary).audioCodec))
                    evidence.expect("published_mp4_container", "mp4" in info.allProperties.optJSONObject("format")?.optString("format_name").orEmpty().split(','))
                    val expectedDuration = selection.duration ?: finalRow.duration
                    val duration = info.duration?.toDoubleOrNull()
                    evidence.expect("published_duration", expectedDuration != null && expectedDuration > 0 && duration != null && duration > 0 &&
                        kotlin.math.abs(duration - expectedDuration) <= maxOf(.5, expectedDuration * .03))
                    val outputHashes = PacketHashes(
                        packetHash(published, "0:v:0", File(scratch, "output-video.sha256"), evidence),
                        packetHash(published, "0:a:0", File(scratch, "output-audio.sha256"), evidence))
                    evidence.expect("video_encoded_packets_unchanged", inputHashes?.video == outputHashes.video)
                    evidence.expect("audio_encoded_packets_unchanged", inputHashes?.audio == outputHashes.audio)
                    evidence.report.put("outputHashes", outputHashes.json()).put("encodedPacketHashesMatch", true)
                        .put("width", video?.width).put("height", video?.height).put("videoCodec", video?.codec)
                        .put("audioCodec", audio?.codec).put("audioPresent", true).put("duration", duration)
                        .put("expectedDuration", expectedDuration).put("publishedBytes", published.length())
                        .put("sourceToPublishedByteRatio", published.length().toDouble() / inputBytes)
                        .put("transferThroughPublicationMs", publishedAt - submittedAt)
                    checkAndroidThumbnail(app, uri, evidence)
                    val timings = awaitTimings(app, id, evidence)
                    evidence.report.put("timings", timings)
                    captureAnalysisTimes(id,evidence)
                    evidence.expect("mux_diagnostic_video_copy", timings.getJSONObject("video_mux_timing").getBoolean("video_copy"))
                    evidence.expect("mux_diagnostic_elapsed", timings.getJSONObject("video_mux_timing").getLong("mux_ms") >= 0)
                    evidence.expect("production_full_validation_completed", timings.getJSONObject("video_validation_timing").getBoolean("validated"))
                    evidence.report.put("passed", true)
                }
            }
        } catch (error: Throwable) {
            // Never expose extractor payloads, signed URLs, FFmpeg logs, or raw
            // exception messages through instrumentation failures/evidence.
            if (error is MediaException) evidence.error = error.code.name
            evidence.report.put("failure", evidence.failure(error.javaClass.simpleName))
            failure = AssertionError(evidence.failure(error.javaClass.simpleName))
        } finally {
            withContext(NonCancellable) {
                try {
                    instrument.runOnMainSync { model?.cancelAnalysis() }
                    // SubmissionHandoff can finish after Activity teardown. Only
                    // adopt its exact ID, never sweep newly observed repository rows.
                    withTimeout(10_000) {
                        while (owned == null && model?.state?.value?.saving == true) delay(20)
                    }
                    if (owned == null) owned = (model?.state?.value?.persistedId ?: model?.state?.value?.queuedId)?.takeIf { it !in before }
                    owned?.let { id ->
                        check(id !in before) { "Cannot clean pre-existing row" }
                        if (app.repository.dao.get(id)?.status != "COMPLETED") app.repository.cancel(id)
                        withTimeout(30_000) { app.coordinator.stopAndAwait(id) }
                        withTimeout(30_000) { NativeWork.awaitIdle(File(app.filesDir, "transfers/$id")) }
                        app.repository.dao.get(id)?.takeIf { it.status == "COMPLETED" }?.let { app.storage.deleteFile(it) }
                        app.repository.remove(id)
                        evidence.expect("owned_row_removed", app.repository.dao.get(id) == null)
                        evidence.expect("owned_transfer_directory_removed", !File(app.filesDir, "transfers/$id").exists())
                    }
                    withTimeout(30_000) { NativeWork.awaitIdle(scratch) }
                    scratch.listFiles()?.forEach { it.delete() }
                    scratch.delete()
                    evidence.report.put("cleanupCompleted", true)
                } catch (error: Throwable) {
                    evidence.report.put("cleanupCompleted", false).put("cleanupFailureType", token(error.javaClass.simpleName))
                    if (failure == null) failure = AssertionError("Review cleanup failed: type=${token(error.javaClass.simpleName)}")
                } finally {
                    if (failure != null) evidence.report.put("passed", false)
                    evidence.report.put("testElapsedMs", SystemClock.elapsedRealtime() - began)
                    reportFile.parentFile?.mkdirs()
                    reportFile.writeText(evidence.report.toString(2))
                }
            }
        }
        failure?.let { throw it }
    }

    /** Hash encoded packet payloads, not decoded frames or container bytes. */
    private suspend fun packetHash(input: File, map: String, output: File, evidence: Evidence): String {
        evidence.check = "packet_hash_${map.replace(':', '_')}"
        val session = withTimeout(45_000) {
            suspendCancellableCoroutine<FFmpegSession> { continuation ->
                // Callback-owned leases survive coroutine cancellation/native shutdown.
                val leases = listOf(requireNotNull(input.parentFile), requireNotNull(output.parentFile)).distinct().map(NativeWork::acquire)
                try {
                    val native = FFmpegKit.executeWithArgumentsAsync(arrayOf("-nostdin", "-y", "-v", "error", "-xerror",
                        "-protocol_whitelist", "file,pipe", "-i", input.path, "-map", map, "-c", "copy",
                        "-f", "hash", "-hash", "sha256", output.path), { completed ->
                        leases.forEach { it.release() }
                        if (continuation.isActive) continuation.resume(completed)
                    })
                    continuation.invokeOnCancellation { FFmpegKit.cancel(native.sessionId) }
                } catch (error: Throwable) {
                    leases.forEach { it.release() }
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }
            }
        }
        evidence.expect("packet_hash_succeeded_${map.replace(':', '_')}", ReturnCode.isSuccess(session.returnCode))
        val hash = output.takeIf { it.isFile }?.readText()?.trim()
            ?.let { Regex("SHA256=([a-fA-F0-9]{64})").matchEntire(it)?.groupValues?.get(1)?.lowercase() }
        evidence.expect("packet_hash_valid_${map.replace(':', '_')}", hash != null)
        return requireNotNull(hash)
    }

    private suspend fun checkAndroidThumbnail(app: ZApplication, uri: Uri, evidence: Evidence) = withContext(Dispatchers.IO) {
        evidence.check = "android_content_uri_thumbnail"
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(app, uri)
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            evidence.expect("android_vp9_mp4_frame_available", frame != null)
            frame?.let {
                try {
                    evidence.expect("android_thumbnail_dimensions", it.width > 0 && it.height > 0)
                    evidence.report.put("androidCompatibility", JSONObject().put("contentUriOpened", true)
                        .put("frameAtUs", 1_000_000).put("frameOption", "CLOSEST_SYNC")
                        .put("thumbnailWidth", it.width).put("thumbnailHeight", it.height)
                        .put("retrieverDurationMs", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))
                        .put("playbackAndSeek", "not_tested").put("externalApps", "not_tested"))
                } finally { it.recycle() }
            }
        } finally { retriever.release() }
        Unit
    }

    private fun captureAnalysisTimes(id:String,evidence:Evidence) {
        val traces=JSONArray(AnalysisTrace.snapshot())
        val rows=(0 until traces.length()).map {traces.getJSONObject(it)}
        val ui=rows.firstOrNull {it.optString("id")==evidence.report.optString("traceId")}
        val queue=rows.firstOrNull {it.optString("id")==id}
        val extracts=rows.count {it.has("python_call_start")}
        evidence.report.put("analysisTraces",traces).put("baseExtractionCalls",extracts)
        evidence.expect("no_duplicate_base_extraction",extracts==1)
        fun elapsed(start:JSONObject?,from:String,end:JSONObject?,to:String):Long? =
            if(start?.has(from)==true&&end?.has(to)==true) end.getLong(to)-start.getLong(from) else null
        evidence.report.put("latencyMs",JSONObject()
            .put("intentToSheet",elapsed(ui,"intent_received",ui,"sheet_drawn"))
            .put("intentToDownloadPress",elapsed(ui,"intent_received",ui,"download_pressed"))
            .put("pressToFirstMediaByte",elapsed(ui,"download_pressed",queue,"first_live_bytes"))
            .put("intentToFirstMediaByte",elapsed(ui,"intent_received",queue,"first_live_bytes")))
    }
    private suspend fun awaitTimings(app: ZApplication, id: String, evidence: Evidence): JSONObject {
        evidence.check = "operation_timing_diagnostics"
        val result = JSONObject()
        val fields = mapOf("video_mux_timing" to listOf("queue_wait_ms", "inspection_ms", "mux_ms", "video_copy", "output_bytes"),
            "video_validation_timing" to listOf("validation_ms", "validated"))
        withTimeout(5_000) {
            while (fields.keys.any { !result.has(it) }) {
                withContext(Dispatchers.IO) {
                    for (name in listOf("network-diagnostics.previous.jsonl", "network-diagnostics.jsonl")) {
                        val file = File(app.filesDir, name)
                        if (!file.isFile) continue
                        runCatching { file.useLines { lines -> lines.forEach { line ->
                            val event = runCatching { JSONObject(line) }.getOrNull()
                            val stage = event?.optString("stage")
                            if (event?.optString("operation") == id && stage != null && fields.containsKey(stage)) {
                                val details = event?.optJSONObject("details")
                                if (details != null) {
                                    val safe = JSONObject()
                                    fields.getValue(requireNotNull(stage)).forEach { key ->
                                        val value = details.opt(key)
                                        if (value is Number || value is Boolean) safe.put(key, value)
                                    }
                                    result.put(stage, safe)
                                }
                            }
                        } } }
                    }
                }
                if (fields.keys.any { !result.has(it) }) delay(100)
            }
        }
        return result
    }

    companion object {
        private const val REVIEW_PACKAGE = "com.zdownloader.app.candidate0965"
        private fun token(value: String) = value.replace(Regex("[^A-Za-z0-9_.:+-]"), "_").take(160)
    }
}
