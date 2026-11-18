package com.zdownloader.app

import android.content.ContentUris
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.engine.Processor
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class LongVideoRealBenchmarkAndPlaybackTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as ZApplication

    @Test
    fun benchmarkLongVideoMuxValidationAndMediaStoreExport() {
        runBlocking {
        val sourceFile = File(app.getExternalFilesDir(null), "advanced-audit/movie.webm").let {
            if (it.exists() && it.length() > 0) it else File("/sdcard/Movies/MediaFlow/A.Minutes.Silence.2016.1080i.HDTV.DD2.0.h264.HuN-nIk - MediaFlow.webm")
        }
        assertTrue("Source long movie file must exist at ${sourceFile.path}", sourceFile.exists())
        val sourceLength = sourceFile.length()
        assertTrue("Source file size must be > 300MB (actual: $sourceLength)", sourceLength > 300_000_000L)

        // 1. Probe source file
        val probeSession = FFprobeKit.getMediaInformation(sourceFile.path)
        android.util.Log.e("PROBE_FAIL", "code=${probeSession.returnCode}, output=${probeSession.output}, err=${probeSession.failStackTrace}")
        val probe = probeSession.mediaInformation
        assertNotNull("FFprobe must read source media information: ${probeSession.output} / ${probeSession.failStackTrace}", probe)
        val durationSec = probe.duration?.toDoubleOrNull() ?: 5318.0
        val videoStream = requireNotNull(probe.streams.firstOrNull { it.type == "video" }) { "Source must have video stream" }
        val audioStream = requireNotNull(probe.streams.firstOrNull { it.type == "audio" }) { "Source must have audio stream" }

        val id = "real-benchmark-${UUID.randomUUID()}"
        val tempDir = app.storage.temporary(id)
        val mp4Output = File(tempDir, "benchmark_output.mp4")

        val evidence = JSONObject()
        evidence.put("source_path", sourceFile.path)
        evidence.put("source_bytes", sourceLength)
        evidence.put("source_duration_sec", durationSec)
        evidence.put("video_codec", videoStream.codec)
        evidence.put("audio_codec", audioStream.codec)
        evidence.put("width", videoStream.width)
        evidence.put("height", videoStream.height)

        try {
            // Step 1: Remux to MP4 container
            // If audio in webm is opus/vorbis, convert audio to aac for mp4 container compliance, copying video stream directly (-c:v copy)
            val muxStart = SystemClock.elapsedRealtime()
            val muxCmd = if (audioStream.codec == "aac") {
                arrayOf("-y", "-v", "error", "-i", sourceFile.path, "-map", "0:v:0", "-map", "0:a:0", "-c", "copy", "-movflags", "+faststart", mp4Output.path)
            } else {
                arrayOf("-y", "-v", "error", "-i", sourceFile.path, "-map", "0:v:0", "-map", "0:a:0", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", mp4Output.path)
            }
            val muxSession = FFmpegKit.executeWithArguments(muxCmd)
            val muxDurationMs = SystemClock.elapsedRealtime() - muxStart
            assertTrue("Muxing must succeed: ${muxSession.failStackTrace}", ReturnCode.isSuccess(muxSession.returnCode))
            assertTrue("MP4 output must exist and be > 300MB", mp4Output.exists() && mp4Output.length() > 300_000_000L)
            val mp4Bytes = mp4Output.length()
            evidence.put("mux_duration_ms", muxDurationMs)
            evidence.put("mux_output_bytes", mp4Bytes)

            // Step 2: Fast Validation
            val valStart = SystemClock.elapsedRealtime()
            Processor().validate(
                file = mp4Output,
                audio = false,
                requireAudio = true,
                expectedHeight = (videoStream.height as? Number)?.toInt(),
                expectedDuration = durationSec,
                expectedContainer = "mp4",
                control = TransferControl(),
                expectedVideoCodec = "h264"
            )
            val valDurationMs = SystemClock.elapsedRealtime() - valStart
            evidence.put("validation_duration_ms", valDurationMs)

            // Fast validation on 88-minute movie must complete in less than 2.0s
            assertTrue("Fast validation must complete in < 2000ms (actual: ${valDurationMs}ms)", valDurationMs < 2000L)

            // Step 3: MediaStore Export
            val primaryFormat = Format.parse(
                JSONObject()
                    .put("format_id", "22")
                    .put("ext", "mp4")
                    .put("vcodec", "avc1.640028")
                    .put("acodec", "mp4a.40.2")
                    .put("width", videoStream.width)
                    .put("height", videoStream.height)
            )
            val selection = Selection(primary = primaryFormat, output = "mp4", audioOnly = false)
            val entity = DownloadEntity(
                id = id,
                url = "https://example.com/movie",
                title = "A Minutes Silence 2016 HDTV Benchmark",
                source = "Benchmark",
                thumbnail = null,
                duration = durationSec,
                selection = "mp4",
                filename = "A Minutes Silence 2016 HDTV Benchmark.mp4",
                destination = null,
                fingerprint = "benchmark-$id"
            )
            val exportStart = SystemClock.elapsedRealtime()
            val mediaStoreUri = app.storage.commit(entity, mp4Output, selection, TransferControl())
            val exportDurationMs = SystemClock.elapsedRealtime() - exportStart
            evidence.put("mediastore_export_duration_ms", exportDurationMs)
            evidence.put("mediastore_uri", mediaStoreUri.toString())

            val totalPostProcessingMs = muxDurationMs + valDurationMs + exportDurationMs
            evidence.put("total_post_processing_ms", totalPostProcessingMs)

            // Step 4: Playback and Seeking Verification on Phone
            val seekArray = JSONArray()
            val seekPointsSec = listOf(0L, 600L, 1800L, 2700L, 3600L, 1200L, 5000L, 5300L)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(app, mediaStoreUri, null)
                val trackCount = extractor.trackCount
                assertTrue("Exported file must have >= 2 tracks (actual: $trackCount)", trackCount >= 2)
                var vTrack = -1
                var aTrack = -1
                for (i in 0 until trackCount) {
                    val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("video/")) vTrack = i
                    if (mime.startsWith("audio/")) aTrack = i
                }
                assertTrue("Video track must be found", vTrack >= 0)
                assertTrue("Audio track must be found", aTrack >= 0)

                for (targetSec in seekPointsSec) {
                    val targetUs = targetSec * 1_000_000L
                    extractor.unselectTrack(aTrack)
                    extractor.selectTrack(vTrack)
                    extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val vPtsUs = extractor.sampleTime
                    val vSize = extractor.sampleSize
                    assertTrue("Video sample must exist at seek target $targetSec s (pts: $vPtsUs)", vSize > 0 && vPtsUs >= 0)

                    extractor.unselectTrack(vTrack)
                    extractor.selectTrack(aTrack)
                    extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val aPtsUs = extractor.sampleTime
                    val aSize = extractor.sampleSize
                    assertTrue("Audio sample must exist at seek target $targetSec s (pts: $aPtsUs)", aSize > 0 && aPtsUs >= 0)

                    val pointInfo = JSONObject()
                        .put("target_sec", targetSec)
                        .put("video_pts_sec", vPtsUs / 1_000_000.0)
                        .put("audio_pts_sec", aPtsUs / 1_000_000.0)
                        .put("video_sample_bytes", vSize)
                        .put("audio_sample_bytes", aSize)
                    seekArray.put(pointInfo)
                }
            } finally {
                extractor.release()
            }
            evidence.put("seek_verification", seekArray)

            // Step 5: Frame Decoding at Key Points
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(app, mediaStoreUri)
                val fStart = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: retriever.getFrameAtTime(0)
                assertNotNull("Frame at t=0 must be decodable", fStart)
                fStart?.recycle()

                val fMid = retriever.getFrameAtTime(2700_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: retriever.getFrameAtTime(2700_000_000L)
                assertNotNull("Frame at midpoint t=2700s must be decodable", fMid)
                fMid?.recycle()

                val fEnd = retriever.getFrameAtTime(5200_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: retriever.getFrameAtTime(5200_000_000L)
                assertNotNull("Frame near end t=5200s must be decodable", fEnd)
                fEnd?.recycle()
                evidence.put("frames_decodable", true)
            } finally {
                retriever.release()
            }

            // Step 6: Verify MediaStore Query & Commit
            val resolver = app.contentResolver
            val cursor = resolver.query(mediaStoreUri, arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.IS_PENDING else MediaStore.MediaColumns._ID,
                MediaStore.Video.Media.DURATION
            ), null, null, null)
            assertNotNull("MediaStore query must return cursor", cursor)
            cursor?.use { c ->
                assertTrue("MediaStore record must exist", c.moveToFirst())
                val displayName = c.getString(0)
                val size = c.getLong(1)
                val pending = if (Build.VERSION.SDK_INT >= 29) c.getInt(2) else 0
                val dur = c.getLong(3)
                evidence.put("mediastore_display_name", displayName)
                evidence.put("mediastore_size", size)
                evidence.put("mediastore_pending", pending)
                evidence.put("mediastore_duration_ms", dur)
                assertEquals("File in MediaStore must have IS_PENDING == 0", 0, pending)
                assertEquals("File in MediaStore size must match output bytes", mp4Bytes, size)
            }

            // Write evidence file to external storage
            val evidenceDir = File(app.getExternalFilesDir(null), "advanced-audit").apply { mkdirs() }
            val evidenceFile = File(evidenceDir, "real_long_video_benchmark.json")
            evidenceFile.writeText(evidence.toString(2))
            android.util.Log.i("REAL_BENCHMARK", "SUCCESS: $evidence")

            // Clean up created media store entry from test
            runCatching { resolver.delete(mediaStoreUri, null, null) }
        } finally {
            app.storage.removeTemporary(id)
        }
        }
    }
}
