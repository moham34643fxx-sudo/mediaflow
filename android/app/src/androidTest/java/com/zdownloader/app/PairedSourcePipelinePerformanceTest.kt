package com.zdownloader.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Opt-in paired real-source measurement with preflight HEAD verification and unchanged Coordinator. */
class PairedSourcePipelinePerformanceTest {
    private data class Digest(val bytes: Long, val sha256: String)
    private fun digest(input: InputStream): Digest = input.use {
        val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(1024 * 1024); var bytes = 0L
        while (true) { val n = it.read(buffer); if (n < 0) break; hash.update(buffer, 0, n); bytes += n }
        Digest(bytes, hash.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 255) })
    }

    @Test fun pairedSourceCompletesWithinVerifiedByteBudget(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as ZApplication
        val args = InstrumentationRegistry.getArguments()
        val run = requireNotNull(args.getString("evidenceRun")) { "evidenceRun argument is required" }
        require(run.matches(Regex("^[A-Za-z0-9_-]{1,48}$"))) { "evidenceRun must be 1..48 safe characters" }
        val probeSource = requireNotNull(args.getString("probeSource")) { "probeSource argument is required" }
        UrlPolicy.normalize(probeSource)
        val formatId = requireNotNull(args.getString("formatId")) { "formatId argument is required" }
        val expectedDurationSeconds = requireNotNull(args.getString("expectedDurationSeconds")?.toDoubleOrNull()) { "expectedDurationSeconds is required" }
        require(expectedDurationSeconds.isFinite() && expectedDurationSeconds in 0.001..1800.0) { "expectedDurationSeconds must be in (0, 1800]" }
        val controlledStudy = args.getString("controlledPipelineStudy") == "true"
        val audioSourceMode=args.getString("audioSourceMode") ?: "fixed_opus"
        require(audioSourceMode in setOf("fixed_opus","automatic_mp4")) {"Invalid audioSourceMode"}
        val externalDir = requireNotNull(app.getExternalFilesDir(null)) { "External files directory is unavailable" }
        val directory = File(externalDir, "source-paired-performance/$run")
        check(!directory.exists() && directory.mkdirs()) { "Collision refusal: directory already exists or cannot be created" }
        val resultFile = File(directory, "result.json")
        val maxBytes = 96L * 1024 * 1024; val headRecords = JSONArray()
        val report = JSONObject().put("source", probeSource).put("format_id", formatId).put("run", run)
            .put("audio_source_mode",audioSourceMode)
            .put("scope", "real paired source measurement through unchanged production coordinator with secure preflight HEAD size reconstruction")
            .put("media_byte_limit", maxBytes).put("head_records", headRecords).put("passed", false).put("phase", "PREFLIGHT")
        if (controlledStudy) {
            report.put("controlled_pipeline_study", true)
                .put("network_reference_kind", "passive_app_uid_payload_and_interface_counters")
                .put("network_rate_samples", JSONArray())
        }
        (args.getString("comparisonArm") ?: args.getString("candidateArm"))?.let { report.put("arm", it) }
        resultFile.writeText(report.toString(2))
        var phase = "PREFLIGHT"
        fun persist(p: String) { phase = p; report.put("phase", phase); runCatching { resultFile.writeText(report.toString(2)) } }
        val power = app.getSystemService(android.os.PowerManager::class.java)
        fun conditions() = JSONObject().put("thermal_status", if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1)
            .put("power_save", power.isPowerSaveMode).put("interactive", power.isInteractive)
            .put("uid_rx_bytes", android.net.TrafficStats.getUidRxBytes(app.applicationInfo.uid))
        val versionCode = if (Build.VERSION.SDK_INT >= 28) app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
            else @Suppress("DEPRECATION") app.packageManager.getPackageInfo(app.packageName, 0).versionCode.toLong()
        report.put("installed_version_code", versionCode).put("conditions_before", conditions())
        var activity: MainActivity? = null; var id: String? = null; var observer: Job? = null; var primaryError: Throwable? = null
        val rateSamples = ConcurrentLinkedQueue<JSONObject>()
        var lastSampleMs = 0L; var lastSampleStatus: String? = null
        try {
            check(app.repository.downloads.first().none { it.status !in setOf("COMPLETED", "FAILED", "CANCELLED", "PAUSED") })
            assertFalse(app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            activity = instrument.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            withContext(Dispatchers.Main) { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            app.extractor.clearMetadataCache(); AnalysisTrace.clear()
            val began = SystemClock.elapsedRealtime()
            persist("SOURCE_ANALYSIS")
            val media = withTimeout(100_000) { app.extractor.analyze(probeSource, "paired-$run", forceRefresh = true, enrichOptional = false) }
            val analysisMs = SystemClock.elapsedRealtime() - began
            report.put("analysis_ms", analysisMs).put("title", media.title).put("extracted_duration", media.duration)
            val videoFormat = media.formats.firstOrNull { it.id == formatId } ?: error("Requested formatId $formatId not found in catalog")
            require(videoFormat.video && !videoFormat.audio) { "Format $formatId must be video-only" }
            require(codecFamily(videoFormat.videoCodec) == "vp9") { "Format $formatId must be VP9" }
            val candidateSelection = Selection.video(media, videoFormat, "mp4")
            // Keep the historical Opus workload fixed; automatic AAC is a separate source-selection study.
            val audioFormat = if(audioSourceMode=="fixed_opus") media.audioOptions()
                .filter {it.streamOnly&&codecFamily(it.audioCodec)=="opus"}
                .maxWithOrNull(compareBy<Format> {it.languagePreference ?: 0.0}.thenBy {it.audioBitrate ?: it.bitrate ?: 0.0})
                ?: error("Opus reference track unavailable")
                else requireNotNull(candidateSelection.secondary) { "Selection must have secondary audio" }
            report.put("audio_candidates",JSONArray(media.audioOptions().filter {it.streamOnly}.map {f->JSONObject()
                .put("id",f.id).put("codec",f.audioCodec).put("language",f.language)
                .put("language_preference",f.languagePreference).put("channels",f.audioChannels)
                .put("sample_rate",f.sampleRate).put("bitrate_kbps",f.audioBitrate ?: f.bitrate)}))
            require(codecFamily(audioFormat.audioCodec) == if(audioSourceMode=="fixed_opus") "opus" else "aac") { "Unexpected source audio codec" }
            require(!candidateSelection.transcodeVideo) { "VP9 must be copy-compatible with MP4 output" }
            persist("HEAD_SIZE_VERIFICATION")
            val headClient = Transfer.networkClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
            val verifiedSizes = mutableMapOf<String, Long>(); val headStart = SystemClock.elapsedRealtime()
            for (format in listOf(videoFormat, audioFormat)) {
                require(format.protocol in listOf("http", "https")) { "Unsupported protocol ${format.protocol} for format ${format.id}" }
                val payload = JSONObject(format.payload); val cookies = payload.optString("cookies")
                require(cookies.isBlank() || cookies == "null") { "Cookies present in format ${format.id} payload" }
                require(payload.optJSONArray("fragments")?.length() ?: 0 == 0) { "Fragments present in format ${format.id} payload" }
                payload.optJSONObject("http_headers")?.let { headers ->
                    for (k in headers.keys()) {
                        val lower = k.lowercase()
                        require(lower != "cookie" && lower != "authorization") { "Forbidden credential header $k in format ${format.id}" }
                    }
                }
                val rawUrl = payload.getString("url"); val url = UrlPolicy.normalize(rawUrl)
                val req = Request.Builder().url(url).head().build()
                val resp = withContext(Dispatchers.IO) { headClient.newCall(req).execute() }
                resp.use { r ->
                    require(r.isSuccessful) { "HEAD failed with HTTP ${r.code} for format ${format.id}" }
                    val len = r.header("Content-Length")?.toLongOrNull() ?: error("Missing Content-Length for format ${format.id}")
                    require(len > 0L) { "Non-positive Content-Length $len for format ${format.id}" }
                    require(len <= 64L * 1024 * 1024) { "Format ${format.id} verified size $len exceeds 64 MiB limit" }
                    verifiedSizes[format.id] = len
                    headRecords.put(JSONObject().put("format_id", format.id).put("url", url).put("status", r.code)
                        .put("etag", r.header("ETag")?.takeUnless { it.startsWith("W/") })
                        .put("last_modified", r.header("Last-Modified")).put("verified_bytes", len))
                    persist("HEAD_SIZE_VERIFICATION")
                }
            }
            val headDurationMs = SystemClock.elapsedRealtime() - headStart
            val totalHeadBytes = verifiedSizes.values.sum()
            require(totalHeadBytes <= maxBytes) { "Total HEAD bytes $totalHeadBytes exceeds 96 MiB budget" }
            val maxAllowedProgress = minOf(maxBytes, totalHeadBytes)
            val reconstructedVideo = Format.parse(JSONObject(videoFormat.payload).put("filesize", verifiedSizes.getValue(videoFormat.id)))
            val reconstructedAudio = Format.parse(JSONObject(audioFormat.payload).put("filesize", verifiedSizes.getValue(audioFormat.id)))
            assertEquals(videoFormat.id, reconstructedVideo.id); assertEquals(videoFormat.width, reconstructedVideo.width)
            assertEquals(videoFormat.height, reconstructedVideo.height); assertEquals(videoFormat.videoCodec, reconstructedVideo.videoCodec)
            assertEquals(audioFormat.id, reconstructedAudio.id); assertEquals(audioFormat.audioCodec, reconstructedAudio.audioCodec)
            val updatedFormats = media.formats.map { f ->
                when (f.id) { reconstructedVideo.id -> reconstructedVideo; reconstructedAudio.id -> reconstructedAudio; else -> f }
            }
            val updatedMedia = media.copy(formats = updatedFormats, duration = expectedDurationSeconds)
            val selection = Selection(reconstructedVideo, reconstructedAudio, audioOnly = false, output = "mp4", duration = expectedDurationSeconds, embedMetadata = false)
            assertEquals(audioSourceMode=="fixed_opus",selection.transcodeAudio)
            report.put("audio_transcode_requested",selection.transcodeAudio)
            assertEquals(totalHeadBytes, selection.transferSize?.bytes); assertFalse(selection.transferSize?.estimated ?: true)
            report.put("selected_ids", JSONObject().put("video", videoFormat.id).put("audio", audioFormat.id))
                .put("expected_duration_seconds", expectedDurationSeconds).put("total_head_bytes", totalHeadBytes)
                .put("max_allowed_progress", maxAllowedProgress)
            persist("QUEUE_ENQUEUE")
            val job = app.repository.create(updatedMedia, selection, "Paired source performance $run"); id = job
            report.put("job_id", job)
            persist("COORDINATOR_EXECUTION")
            val localHash = AtomicReference<Digest?>(); val largestBytes = AtomicLong(); val firstProgressMs = AtomicLong(-1)
            val states = linkedSetOf<String>(); val queueStart = SystemClock.elapsedRealtime()
            observer = launch(Dispatchers.IO) {
                app.repository.downloads.collect { rows -> rows.firstOrNull { it.id == job }?.let { row ->
                    states += row.status; largestBytes.accumulateAndGet(row.bytes, ::maxOf)
                    if (controlledStudy) {
                        val now = SystemClock.elapsedRealtime()
                        if (row.status != lastSampleStatus || (now - lastSampleMs) >= 500L) {
                            lastSampleMs = now; lastSampleStatus = row.status
                            rateSamples.add(JSONObject()
                                .put("elapsed_realtime_ms", now)
                                .put("status", row.status)
                                .put("payload_bytes", row.bytes)
                                .put("reported_speed_Bps", row.speed)
                                .put("uid_rx_bytes", android.net.TrafficStats.getUidRxBytes(app.applicationInfo.uid))
                                .put("cpu_time_ms", android.os.Process.getElapsedCpuTime())
                                .put("thermal_status", if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1))
                        }
                    }
                    if (row.bytes > maxAllowedProgress) {
                        app.repository.cancel(job); app.coordinator.stopAndAwait(job); error("Byte budget exceeded: observed ${row.bytes} > $maxAllowedProgress")
                    }
                    if (row.bytes > 0) firstProgressMs.compareAndSet(-1, SystemClock.elapsedRealtime() - queueStart)
                    if (localHash.get() == null && row.status in setOf("VALIDATING", "SAVING")) {
                        val output = File(app.storage.temporary(job), "output.${selection.output}")
                        if (output.isFile) {
                            if(audioSourceMode=="automatic_mp4") {
                                val sourceAudio=File(app.storage.temporary(job),"secondary.${reconstructedAudio.ext}")
                                val lease=NativeWork.acquire(sourceAudio.parentFile!!)
                                try {sourceAudio.copyTo(File(directory,"source-audio.${reconstructedAudio.ext}"))}
                                finally {lease.release()}
                            }
                            localHash.set(digest(output.inputStream()))
                        }
                    }
                } }
            }
            withTimeout(780_000) { app.coordinator.run() }
            val queueEnd = SystemClock.elapsedRealtime()
            val completed = requireNotNull(app.repository.dao.get(job)) { "Completed download entity missing from repository" }
            assertEquals(completed.error, "COMPLETED", completed.status)
            withTimeout(10_000) { while (localHash.get() == null) delay(25) }
            observer.cancelAndJoin(); observer = null
            if (controlledStudy) report.put("network_rate_samples", JSONArray(rateSamples.toList()))
            persist("PUBLICATION_VERIFICATION")
            val uri = Uri.parse(requireNotNull(completed.uri))
            app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals(completed.outputSize, it.getLong(1)); assertTrue(it.getLong(1) > 0L)
            }
            val readbackStart = SystemClock.elapsedRealtime()
            val published = digest(requireNotNull(app.contentResolver.openInputStream(uri)))
            val readbackDuration = SystemClock.elapsedRealtime() - readbackStart
            assertEquals(requireNotNull(localHash.get()), published)
            val retained = File(directory, "published-output.${selection.output}")
            requireNotNull(app.contentResolver.openInputStream(uri)).use { input -> retained.outputStream().use { input.copyTo(it) } }
            assertEquals(published, digest(retained.inputStream()))
            val info = requireNotNull(FFprobeKit.getMediaInformation(retained.path).mediaInformation)
            val video = info.streams.first { it.type == "video" }; val audio = info.streams.first { it.type == "audio" }
            assertEquals(selection.primary.width, video.width.toInt()); assertEquals(selection.primary.height, video.height.toInt())
            assertEquals("vp9", codecFamily(video.codec)); assertEquals("aac", codecFamily(audio.codec))
            val actualDuration = info.duration.toDouble()
            assertEquals(expectedDurationSeconds, actualDuration, 0.25)
            var records = emptyList<JSONObject>()
            withTimeout(10_000) {
                while (true) {
                    records = listOf("network-diagnostics.previous.jsonl", "network-diagnostics.jsonl").flatMap { name ->
                        File(app.filesDir, name).takeIf { it.isFile }?.readLines().orEmpty()
                    }.mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }.filter { it.optString("operation") == job }
                    if (records.any { it.optString("stage") in setOf("video_pipeline_profiling", "video_validation_timing", "validated_mp4") }) break
                    delay(100)
                }
            }
            val valRecord = records.firstOrNull { it.optString("stage") == "video_validation_timing" }
            val strictAValidated = valRecord?.optJSONObject("details")?.optBoolean("validated") == true || records.any { it.optString("stage") == "validated_mp4" }
            assertTrue("Strict A validation gate must record actual validated=true", strictAValidated)
            val muxTiming = records.firstOrNull { it.optString("stage") == "video_mux_timing" }?.optJSONObject("details")
            val valTiming = valRecord?.optJSONObject("details")
            report.put("observed_metadata_unknown", media.duration == null && videoFormat.bytes == null && audioFormat.bytes == null)
                .put("host_oracle_duration_seconds", expectedDurationSeconds).put("actual_duration_seconds", actualDuration)
                .put("selected_codecs", JSONObject().put("video", videoFormat.videoCodec).put("audio", audioFormat.audioCodec))
                .put("actual_codecs", JSONObject().put("video", video.codec).put("audio", audio.codec))
                .put("head_preflight", JSONObject().put("records", headRecords).put("total_bytes", totalHeadBytes))
                .put("queue_wall_ms", queueEnd - queueStart).put("source_to_verified_publication_ms", SystemClock.elapsedRealtime() - began)
                .put("independent_readback_ms", readbackDuration).put("first_progress_ms", firstProgressMs.get())
                .put("largest_observed_transfer_bytes", largestBytes.get()).put("output_bytes", published.bytes).put("output_sha256", published.sha256)
                .put("is_pending", 0).put("local_vs_published_sha_match", true).put("final_status", completed.status)
                .put("observed_states", JSONArray(states.toList())).put("strict_a_diagnostic_validated", strictAValidated)
                .put("timing_breakdown", JSONObject().put("analysis_ms", analysisMs).put("head_ms", headDurationMs)
                    .put("mux_ms", muxTiming?.optLong("mux_ms", -1)).put("validation_ms", valTiming?.optLong("validation_ms", -1))
                    .put("readback_ms", readbackDuration))
                .put("diagnostics", JSONArray(records)).put("events", JSONArray(app.repository.dao.events(job).map { it.state }))
                .put("conditions_after", conditions()).put("passed", true)
            if (controlledStudy) {
                report.put("media_pipeline_passed", true)
                if (rateSamples.size < 2) {
                    report.put("passed", false).put("study_error", "Insufficient rate samples: ${rateSamples.size} < 2")
                    error("Controlled pipeline study failed: Insufficient rate samples (${rateSamples.size} < 2)")
                }
            }
        } catch (error: Throwable) {
            primaryError = error
            report.put("passed", false).put("failure_phase", phase).put("error", "${error.javaClass.simpleName}:${error.message}")
            report.put("conditions_after", conditions())
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    observer?.cancelAndJoin(); observer = null
                    if (controlledStudy) report.put("network_rate_samples", JSONArray(rateSamples.toList()))
                    id?.let { job ->
                        app.coordinator.stopAndAwait(job)
                        var cleanupUnconfirmed = false
                        val tempDir = runCatching { app.storage.temporary(job) }.getOrNull()
                        if (tempDir?.isDirectory == true) {
                            val idle = withTimeoutOrNull(10_000) { NativeWork.awaitIdle(tempDir) }
                            if (idle == null) cleanupUnconfirmed = true
                        }
                        if (cleanupUnconfirmed) {
                            report.put("cleanup_unconfirmed", true).put("passed", false)
                        } else {
                            app.repository.dao.get(job)?.takeIf { it.status == "COMPLETED" }?.let { app.storage.deleteFile(it) }
                            app.repository.cancel(job); app.repository.remove(job); app.storage.removeTemporary(job)
                        }
                    }
                } catch (cleanupError: Throwable) {
                    report.put("cleanup_error", "${cleanupError.javaClass.simpleName}:${cleanupError.message}").put("passed", false)
                    if (primaryError != null) primaryError.addSuppressed(cleanupError) else throw cleanupError
                } finally {
                    try { withContext(Dispatchers.Main) { activity?.finish() } } catch (e: Throwable) { primaryError?.addSuppressed(e) }
                    report.put("analysis_trace", JSONArray(AnalysisTrace.snapshot()))
                    runCatching { resultFile.writeText(report.toString(2)) }
                }
            }
        }
    }
}
