package com.zdownloader.app

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.domain.videoOptions
import com.zdownloader.app.playback.MediaPlaybackService
import com.zdownloader.app.playback.PlaybackRuntime
import com.zdownloader.app.playback.PlayerActivity
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real network, production queue/publication, and application player for one owned job. */
class ProductionPipelineDeviceAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrument.targetContext.applicationContext as ZApplication
    private val connection get() = app.playback
    private data class OutputDigest(val bytes: Long, val sha256: String, val elapsedMs: Long)

    @Test fun realLargeDownloadIsPublishedAndPlaysThroughSeekAndEnd(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val run = requireNotNull(args.getString("evidenceRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val evidence = File(requireNotNull(app.getExternalFilesDir(null)), "pipeline-acceptance/$run")
        check(evidence.mkdirs()) { "refusing to reuse acceptance evidence directory" }
        val source = args.getString("probeSource")
            ?: "https://media.w3.org/2010/05/bunny/movie.mp4"
        val targetHeight = (args.getString("probeTargetHeight")?.toIntOrNull() ?: 480).coerceIn(110, 720)
        val minDurationMs = (args.getString("probeMinimumDurationMs")?.toLongOrNull() ?: 300_000L).coerceIn(45_000L, 300_000L)
        val researchScope = if (minDurationMs < 300_000L) "short-network-sanity-not-long-video-performance" else "long-video-performance"
        val report = JSONObject().put("source", source).put("evidence_run", run)
            .put("effective_target_height", targetHeight).put("probe_target_height", targetHeight)
            .put("effective_minimum_duration_ms", minDurationMs).put("probe_minimum_duration_ms", minDurationMs)
            .put("research_scope", researchScope)
        if (minDurationMs < 300_000L) report.put("cannot_prove_88min_performance", true)
        var jobId: String? = null
        var root: MainActivity? = null
        var failure: Throwable? = null
        var phase = "PREFLIGHT"
        try {
            withTimeout(900_000L) {
                check(app.repository.downloads.first().none { it.status !in setOf("COMPLETED", "CANCELLED", "FAILED", "PAUSED") }) {
                    "another queue job is active; do not run an unscoped queue test"
                }
                root = instrument.startActivitySync(Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                main { root!!.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                val started = SystemClock.elapsedRealtime()
                report.put("acquisition_start_elapsed_realtime", started)
                phase = "SOURCE_ANALYSIS"
                val media = withTimeout(60_000L) {
                    app.extractor.analyze(report.getString("source"), forceRefresh = true, enrichOptional = false)
                }
                report.put("analysis_ms", SystemClock.elapsedRealtime() - started)
                    .put("quality_option_count", media.videoOptions().size)
                val formats = media.videoOptions().filter { it.ext == "mp4" }
                val format = requireNotNull(formats.filter { it.height?.let { height -> height in 1..720 } == true }
                    .minByOrNull { kotlin.math.abs(requireNotNull(it.height) - targetHeight) }) {
                    "no suitable standard-definition format for the test phone"
                }
                val selection = Selection.video(media, format)
                report.put("selected_height", format.height).put("selected_container", selection.output)
                val id = app.repository.create(media, selection, "Pipeline acceptance $run")
                jobId = id
                report.put("job_id", id)
                phase = "TRANSFER_PROCESSING_PUBLICATION"
                val localDigest = AtomicReference<OutputDigest?>()
                val observedStates = linkedSetOf<String>()
                var largestTransferred = 0L
                var positiveSpeed = false
                val observer = launch(Dispatchers.IO) {
                    app.repository.downloads.collect { rows ->
                        rows.firstOrNull { it.id == id }?.let { row ->
                            observedStates += row.status
                            largestTransferred = maxOf(largestTransferred, row.bytes)
                            positiveSpeed = positiveSpeed || row.speed > 0
                            if (localDigest.get() == null && row.status in setOf("VALIDATING", "SAVING")) {
                                val output = File(app.storage.temporary(id), "output.${selection.output}")
                                if (output.isFile) localDigest.set(output.inputStream().use { digest(it) })
                            }
                        }
                    }
                }
                val runStarted = SystemClock.elapsedRealtime()
                report.put("run_start_elapsed_realtime", runStarted)
                val saved = try {
                    withTimeout(780_000L) { app.coordinator.run() }
                    report.put("run_elapsed_ms", SystemClock.elapsedRealtime() - runStarted)
                    report.put("queue_wall_ms", SystemClock.elapsedRealtime() - started - report.getLong("analysis_ms"))
                    val completed = requireNotNull(app.repository.dao.get(id))
                    report.put("final_status", completed.status).put("job_error", completed.error).put("output_size", completed.outputSize)
                    assertEquals("production job did not complete: ${completed.error}", "COMPLETED", completed.status)
                    // An open Linux file remains readable after unlink; allow
                    // the independent hash to finish before stopping its owner.
                    val hashWaitStarted = SystemClock.elapsedRealtime()
                    withTimeout(10_000L) { while (localDigest.get() == null) delay(25) }
                    report.put("post_queue_hash_wait_ms", SystemClock.elapsedRealtime() - hashWaitStarted)
                    completed
                } finally { observer.cancelAndJoin() }
                report.put("observed_states", JSONArray(observedStates.toList()))
                    .put("largest_observed_download_bytes", largestTransferred).put("positive_speed_observed", positiveSpeed)
                assertTrue("no real transfer progress was observed", largestTransferred > 0L && positiveSpeed)
                phase = "PUBLICATION_VERIFICATION"
                val beforePublication = requireNotNull(localDigest.get()) { "missed production output; no independent hash comparison possible" }
                val uri = Uri.parse(requireNotNull(saved.uri))
                verifyPublication(uri, requireNotNull(saved.outputSize))
                val readback = requireNotNull(app.contentResolver.openInputStream(uri)).use { digest(it) }
                report.put("local_output_sha256", beforePublication.sha256).put("published_sha256", readback.sha256)
                    .put("local_output_hash_ms", beforePublication.elapsedMs).put("independent_readback_ms", readback.elapsedMs)
                    .put("hash_observer_scope", "extra concurrent read during validation; its cost is included in wall time")
                assertEquals(beforePublication.bytes, readback.bytes)
                assertEquals(beforePublication.sha256, readback.sha256)
                assertEquals(saved.outputSize, readback.bytes)
                report.put("is_pending", 0).put("production_output_and_publication_hashes_match", true)
                val events = app.repository.dao.events(id)
                report.put("database_events", JSONArray(events.map { it.state }))
                assertTrue(events.any { it.state == "VALIDATING" })
                assertTrue(events.any { it.state == "SAVING" })
                phase = "DIAGNOSTIC_CORRELATION"
                report.put("diagnostics", awaitJobDiagnostics(id))
                val retentionStarted = SystemClock.elapsedRealtime()
                val preservedOutput = File(evidence, "published-output.mp4")
                requireNotNull(app.contentResolver.openInputStream(uri)).use { input ->
                    preservedOutput.outputStream().use { output -> input.copyTo(output) }
                }
                val preservedDigest = preservedOutput.inputStream().use { digest(it) }
                assertEquals(readback.bytes, preservedDigest.bytes)
                assertEquals(readback.sha256, preservedDigest.sha256)
                report.put("preserved_output_bytes", preservedDigest.bytes)
                    .put("preserved_output_sha256", preservedDigest.sha256)
                    .put("diagnostic_retention_elapsed_ms", SystemClock.elapsedRealtime() - retentionStarted)
                    .put("diagnostic_retention_scope", "diagnostic copy of published media preserved in test evidence directory prior to playback")
                phase = "PLAYBACK"
                verifyPlayback(uri, saved.title, evidence, report, minDurationMs)
                val completionElapsed = SystemClock.elapsedRealtime() - started
                report.put("end_to_end_including_player_ms", completionElapsed)
                    .put("completion_elapsed_ms", completionElapsed)
            }
        } catch (error: Throwable) {
            failure = error
            report.put("failure_phase", phase).put("failure_type", error.javaClass.simpleName)
                .put("failure_message", error.message?.take(300))
        } finally {
            withContext(NonCancellable) {
                val cleanupError = cleanup(jobId, root, report)
                if (cleanupError != null) {
                    if (failure == null) failure = cleanupError else failure?.addSuppressed(cleanupError)
                }
                report.put("passed", failure == null)
                File(evidence, "result.json").writeText(report.toString(2))
            }
        }
        failure?.let { throw AssertionError("production pipeline device acceptance failed", it) }
    }

    @Test fun replaysRetainedActualNetworkOutputThroughAppPlayer(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val run = requireNotNull(args.getString("evidenceRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val evidence = File(requireNotNull(app.getExternalFilesDir(null)), "pipeline-acceptance/$run")
        check(evidence.mkdirs()) { "refusing to reuse acceptance evidence directory" }
        val retainedFile = args.getString("retainedFile")?.let { File(it) }
            ?: File(requireNotNull(app.getExternalFilesDir(null)), "pipeline-acceptance/${args.getString("retainedRun") ?: "network-sanity-retry-8e1b87"}/published-output.mp4")
        val report = JSONObject().put("evidence_run", run)
            .put("scope", "offline replay of actually downloaded published copy; no new network or pipeline speedup")
            .put("playback_source", "file_uri")
            .put("retained_media_note", "retained file is exact copy of published media, but replay is FILE URI, not direct MediaStore playback")
            .put("retained_source_path", retainedFile.absolutePath)
        var root: MainActivity? = null
        var failure: Throwable? = null
        var phase = "PREFLIGHT"
        val playbackStarted = SystemClock.elapsedRealtime()
        try {
            withTimeout(180_000L) {
                phase = "VERIFY_RETAINED_BEFORE"
                check(retainedFile.isFile) { "retained published media missing: ${retainedFile.absolutePath}" }
                val before = retainedFile.inputStream().use { digest(it) }
                assertEquals(1781022L, before.bytes)
                assertEquals("16dfd2e70155204c6b0ff0556fb214edbdb4d347769b86726e67ade99e573ae3", before.sha256)
                report.put("before_bytes", before.bytes).put("before_sha256", before.sha256)
                phase = "LAUNCH_ACTIVITY"
                root = instrument.startActivitySync(Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                main { root!!.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                phase = "PLAYBACK"
                verifyPlayback(Uri.fromFile(retainedFile), "Offline replay $run", evidence, report, 45_000L)
            }
        } catch (error: Throwable) {
            failure = error
            report.put("failure_phase", phase).put("failure_type", error.javaClass.simpleName)
                .put("failure_message", error.message?.take(300))
        } finally {
            withContext(NonCancellable) {
                report.put("elapsed_actual_playback_ms", SystemClock.elapsedRealtime() - playbackStarted)
                val cleanupError = cleanup(null, root, report)
                if (cleanupError != null) {
                    if (failure == null) failure = cleanupError else failure?.addSuppressed(cleanupError)
                }
                var afterMatches = false
                try {
                    if (retainedFile.isFile) {
                        val after = retainedFile.inputStream().use { digest(it) }
                        report.put("after_bytes", after.bytes).put("after_sha256", after.sha256)
                        afterMatches = after.bytes == 1781022L &&
                            after.sha256 == "16dfd2e70155204c6b0ff0556fb214edbdb4d347769b86726e67ade99e573ae3"
                    }
                } catch (e: Throwable) {
                    report.put("after_hash_error", e.message?.take(300))
                }
                report.put("retained_hash_intact", afterMatches)
                report.put("passed", failure == null && afterMatches)
                File(evidence, "result.json").writeText(report.toString(2))
            }
        }
        failure?.let { throw AssertionError("offline replay through app player failed", it) }
        check(report.optBoolean("retained_hash_intact", false)) { "retained file hash did not hold after replay" }
    }

    private suspend fun cleanup(id: String?, root: MainActivity?, report: JSONObject): Throwable? {
        var firstFailure: Throwable? = null
        val failures = JSONArray()
        suspend fun attempt(name: String, action: suspend () -> Unit) {
            try { withTimeout(15_000L) { action() } }
            catch (error: Throwable) {
                failures.put(JSONObject().put("phase", name).put("type", error.javaClass.simpleName))
                if (firstFailure == null) firstFailure = error else firstFailure?.addSuppressed(error)
            }
        }
        attempt("playback_teardown") {
            main {
                connection.clear(); playerActivity()?.finish(); connection.detach(); connection.disconnect()
                app.stopService(Intent(app, MediaPlaybackService::class.java)); root?.finish()
            }
            while (PlaybackRuntime.active) delay(50)
        }
        if (id != null) {
            var ownedUri: String? = null
            attempt("stop_owned_job") { app.repository.cancel(id); app.coordinator.stopAndAwait(id) }
            attempt("delete_owned_publication") {
                val row = app.repository.dao.get(id)
                ownedUri = row?.uri
                if (row?.status == "COMPLETED") app.storage.deleteFile(row)
            }
            attempt("remove_owned_history_and_scratch") { app.repository.remove(id) }
            attempt("verify_owned_cleanup") {
                check(app.repository.dao.get(id) == null) { "owned job was not removed" }
                ownedUri?.let { uri ->
                    app.contentResolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns._ID), null, null, null).use {
                        check(it?.moveToFirst() != true) { "owned publication was not deleted" }
                    }
                }
            }
        }
        report.put("cleanup_failures", failures).put("owned_job_and_output_cleanup_completed", firstFailure == null)
        return firstFailure
    }

    private suspend fun digest(input: InputStream): OutputDigest {
        val started = SystemClock.elapsedRealtime()
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        var bytes = 0L
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
            bytes += count
        }
        return OutputDigest(bytes, hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) },
            SystemClock.elapsedRealtime() - started)
    }

    private fun verifyPublication(uri: Uri, expectedBytes: Long) {
        assertEquals("this acceptance requires actual MediaStore publication", "media", uri.authority)
        app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null).use { cursor ->
            check(cursor != null && cursor.moveToFirst()) { "published MediaStore item missing" }
            assertEquals(0, cursor.getInt(0))
        }
        app.contentResolver.openFileDescriptor(uri, "r").use { descriptor ->
            assertEquals(expectedBytes, requireNotNull(descriptor).statSize)
        }
    }

    private suspend fun awaitJobDiagnostics(id: String): JSONArray {
        var records = emptyList<JSONObject>()
        val requiredStages = setOf("transfer_completed", "processing_completed", "output_validated",
            "gallery_publication_completed", "video_pipeline_profiling", "mediastore_export_timing")
        withTimeout(10_000L) {
            while (true) {
                records = listOf("network-diagnostics.previous.jsonl", "network-diagnostics.jsonl")
                    .map { File(app.filesDir, it) }.filter { it.isFile }.flatMap { it.readLines() }
                    .filter { it.isNotBlank() && it.trimEnd().endsWith("}") }.map { JSONObject(it) }
                    .filter { it.optString("operation") == id }
                if (records.map { it.optString("stage") }.toSet().containsAll(requiredStages)) break
                delay(100)
            }
        }
        return JSONArray(records)
    }

    private suspend fun verifyPlayback(uri: Uri, title: String, evidence: File, report: JSONObject, minDurationMs: Long) {
        withContext(Dispatchers.Main) {
            connection.attach()
            connection.connect()
            connection.playUri(uri.toString(), title, video = true, open = true)
            requireNotNull(connection.player.value).apply {
                shuffleModeEnabled = false
                repeatMode = Player.REPEAT_MODE_OFF
                setPlaybackSpeed(1f)
            }
        }
        compose.waitUntil(30_000L) {
            try {
                compose.onAllNodesWithTag("video_player").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            } catch (e: IllegalStateException) {
                if (e.message?.contains("No compose hierarchies", ignoreCase = true) == true) false else throw e
            }
        }
        awaitMain {
            val state = connection.state.value
            check(state.error == null) { "application player reported error resource ${state.error}" }
            state.playing && state.position > 1_000L
        }
        val duration = main {
            val state = connection.state.value
            assertNull(state.error)
            assertTrue(state.tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected })
            assertTrue(state.tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSelected })
            state.duration
        }
        assertTrue("source must satisfy minimum duration", duration > minDurationMs)
        main { connection.seek(30_000L) }
        awaitMain { connection.state.value.position in 29_000L..40_000L && connection.state.value.playing }
        delay(1_000L)
        val frame = captureVideoSurface()
        try {
            val colors = IntArray(frame.width * frame.height)
            frame.getPixels(colors, 0, frame.width, 0, 0, frame.width, frame.height)
            val levels = colors.map { ((it shr 16 and 255) + (it shr 8 and 255) + (it and 255)) / 3 }
            assertTrue("video surface is blank", requireNotNull(levels.maxOrNull()) - requireNotNull(levels.minOrNull()) > 20)
            File(evidence, "actual-video-surface.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { frame.recycle() }
        val screenshot = requireNotNull(instrument.uiAutomation.takeScreenshot())
        try { File(evidence, "application-player.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { screenshot.recycle() }
        main { connection.seek(duration / 2) }
        awaitMain { kotlin.math.abs(connection.state.value.position - duration / 2) < 5_000L }
        main { connection.seek(duration - 2_000L); requireNotNull(connection.player.value).play() }
        awaitMain { connection.player.value?.playbackState == Player.STATE_ENDED }
        report.put("player", JSONObject().put("duration_ms", duration).put("selected_video_and_audio", true)
            .put("position_advanced", true).put("actual_surface_nonblank", true).put("midpoint_seek_passed", true)
            .put("ended_state", Player.STATE_ENDED).put("scope", "real app playback and seek/EOS, not uninterrupted full-duration viewing or human listening"))
    }

    private suspend fun captureVideoSurface(): Bitmap {
        awaitMain { playerActivity()?.window?.decorView?.let { findSurface(it) }?.let {
            when (it) { is SurfaceView -> it.holder.surface.isValid; is TextureView -> it.isAvailable; else -> false }
        } == true }
        val surface = main { requireNotNull(findSurface(requireNotNull(playerActivity()).window.decorView)) }
        if (surface is TextureView) return main { requireNotNull(surface.getBitmap(320, 180)) }
        check(surface is SurfaceView)
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val finished = CountDownLatch(1)
        var status = -1
        main { PixelCopy.request(surface, bitmap, { status = it; finished.countDown() }, android.os.Handler(android.os.Looper.getMainLooper())) }
        check(withContext(Dispatchers.IO) { finished.await(10, TimeUnit.SECONDS) } && status == PixelCopy.SUCCESS) {
            bitmap.recycle()
            "could not capture the actual video surface (PixelCopy=$status)"
        }
        return bitmap
    }

    private fun findSurface(view: View): View? {
        if (view is SurfaceView || view is TextureView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findSurface(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun playerActivity(): PlayerActivity? = listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED)
        .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
        .filterIsInstance<PlayerActivity>().firstOrNull()

    private suspend fun awaitMain(predicate: () -> Boolean) = withTimeout(30_000L) {
        while (!main(predicate)) delay(100)
    }

    private suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main) { block() }
}
