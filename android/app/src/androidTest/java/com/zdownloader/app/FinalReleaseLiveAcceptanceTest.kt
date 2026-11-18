package com.zdownloader.app

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.UUID

class FinalReleaseLiveAcceptanceTest {

    @Test
    fun downloadsAndProcessesRealPublicVideo(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val probeSource = args.getString("probeSource")?.takeIf { it.isNotBlank() } ?: "https://www.w3schools.com/html/mov_bbb.mp4"
        val evidenceRun = args.getString("evidenceRun")?.takeIf { it.isNotBlank() } ?: "final-run"
        require(evidenceRun.matches(Regex("^[a-z0-9-]{1,80}$"))) { "Invalid evidenceRun: $evidenceRun" }

        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val reportFile = File(app.getExternalFilesDir(null), "final-live/$evidenceRun/result.json").apply { parentFile?.mkdirs() }
        val report = JSONObject().put("source_host", runCatching { URI(probeSource).host }.getOrNull() ?: "unknown").put("evidence_run", evidenceRun)

        val tempId = "live-acceptance-${UUID.randomUUID()}"
        val tempDir = app.storage.temporary(tempId)
        val control = TransferControl()

        var passed = false
        var failureCode: String? = null
        var failureClass: String? = null
        var ttfbMs: Long? = null
        var actualWidth: Int? = null
        var actualHeight: Int? = null
        var actualDuration: Double? = null

        try {
            withTimeout(240_000L) {
                val t0 = SystemClock.elapsedRealtime()
                val media = app.extractor.analyze(probeSource, forceRefresh = true, enrichOptional = false)
                report.put("elapsed_analysis_ms", SystemClock.elapsedRealtime() - t0)

                val capped = media.qualityGroups().filter { (it.resolution ?: 0) in 1..480 }.firstOrNull()?.recommended
                val chosen = capped ?: media.recommendedVideo() ?: media.formats.first { it.video && FormatAvailability.eligible(it) }
                val capApplied = capped != null && chosen == capped
                if (capApplied) verifiedQualityEdge(chosen)?.let { assertTrue("Cap 480 exceeded", it <= 480) }

                val selection = Selection.video(media, chosen)
                val audioExpected = selection.secondary != null || selection.primary.audio
                report.put("selected_format_id", selection.primary.id)
                    .put("selected_video_codec", selection.primary.videoCodec)
                    .put("selected_audio_codec", selection.secondary?.audioCodec ?: selection.primary.audioCodec)
                    .put("verified_width", selection.primary.width)
                    .put("verified_height", selection.primary.height)
                    .put("audio_expected", audioExpected)
                    .put("cap_applied", capApplied)

                val primaryFile = File(tempDir, "primary.${selection.primary.ext}")
                val tTransfer = SystemClock.elapsedRealtime()
                app.transfer.stream(selection.primary, primaryFile, "$tempId-p", control) { bytes, _ ->
                    if (ttfbMs == null && bytes > 0L) ttfbMs = SystemClock.elapsedRealtime() - tTransfer
                    if (bytes > 100L * 1024 * 1024) { control.stop(); throw MediaException(ErrorCode.BATCH_LIMIT_EXCEEDED) }
                }

                val secondaryFile = selection.secondary?.let { sec ->
                    val sFile = File(tempDir, "secondary.${sec.ext}")
                    val pBytes = primaryFile.length()
                    app.transfer.stream(sec, sFile, "$tempId-s", control) { bytes, _ ->
                        if (pBytes + bytes > 100L * 1024 * 1024) { control.stop(); throw MediaException(ErrorCode.BATCH_LIMIT_EXCEEDED) }
                    }
                    sFile
                }
                val transferBytes = primaryFile.length() + (secondaryFile?.length() ?: 0L)
                report.put("bytes_transferred", transferBytes).put("elapsed_transfer_ms", SystemClock.elapsedRealtime() - tTransfer)
                ttfbMs?.let { report.put("ttfb_ms", it) }

                assertTrue(primaryFile.isFile && primaryFile.length() > 0L)
                if (secondaryFile != null) assertTrue(secondaryFile.isFile && secondaryFile.length() > 0L)

                val tProcess = SystemClock.elapsedRealtime()
                val processed = File(tempDir, "processed.${selection.output}")
                Processor().process(primaryFile, secondaryFile, processed, selection, control, media.duration)
                report.put("elapsed_processing_ms", SystemClock.elapsedRealtime() - tProcess)
                assertTrue(processed.isFile && processed.length() > 0L)

                val info = FFprobeKit.getMediaInformation(processed.path).mediaInformation
                assertNotNull("FFprobe info missing", info)
                val duration = info?.duration?.toDoubleOrNull()
                assertNotNull("Missing duration", duration)
                assertTrue("Duration non-positive", duration!! > 0.0)
                actualDuration = duration

                val video = info.streams?.firstOrNull { it.type == "video" }
                assertNotNull("Missing video stream", video)
                val w = video?.width?.toInt()
                val h = video?.height?.toInt()
                assertNotNull("Missing video width", w); assertTrue(w!! > 0)
                assertNotNull("Missing video height", h); assertTrue(h!! > 0)
                actualWidth = w; actualHeight = h

                if (selection.primary.dimensionsVerified && selection.primary.width != null && selection.primary.height != null) {
                    assertEquals("Width mismatch", selection.primary.width, w)
                    assertEquals("Height mismatch", selection.primary.height, h)
                }

                if (audioExpected) {
                    assertTrue("Missing audio stream", info.streams?.any { it.type == "audio" } == true)
                }

                passed = true
            }
        } catch (t: Throwable) {
            failureClass = t.javaClass.simpleName
            failureCode = if (t is MediaException) t.code.name else failureClass
            throw t
        } finally {
            control.stop()
            report.put("passed", passed)
            if (!passed) {
                report.put("failure_code", failureCode ?: "UNKNOWN")
                report.put("exception_class", failureClass ?: "UNKNOWN")
            }
            actualWidth?.let { report.put("actual_width", it) }
            actualHeight?.let { report.put("actual_height", it) }
            actualDuration?.let { report.put("actual_duration", it) }
            reportFile.writeText(report.toString(2))

            if (withTimeoutOrNull(30_000L) { NativeWork.awaitIdle(tempDir); true } == true) tempDir.deleteRecursively()
            else NativeWork.afterIdle(tempDir) { tempDir.deleteRecursively() }
        }
    }
}
