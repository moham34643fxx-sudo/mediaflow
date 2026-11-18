package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.domain.ErrorCode
import com.zdownloader.app.domain.MediaException
import com.zdownloader.app.engine.Processor
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

class VideoFastValidationAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as ZApplication

    private fun fixture(target: File): File {
        instrumentation.context.assets.open("status-original.mp4").use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }

    @Test
    fun validH264VideoPassesFastValidationInSubSecond() = runBlocking {
        val id = "valid-h264-${UUID.randomUUID()}"
        val dir = app.storage.temporary(id)
        try {
            val file = fixture(File(dir, "valid.mp4"))
            val info = FFprobeKit.getMediaInformation(file.path).mediaInformation
            assertNotNull(info)
            val duration = info.duration.toDouble()
            val start = android.os.SystemClock.elapsedRealtime()
            Processor().validate(
                file = file,
                audio = false,
                requireAudio = true,
                expectedHeight = null,
                expectedDuration = duration,
                expectedContainer = "mp4",
                control = TransferControl(),
                expectedVideoCodec = "h264"
            )
            val elapsedMs = android.os.SystemClock.elapsedRealtime() - start
            assertTrue("Fast validation must complete in sub-second (took $elapsedMs ms)", elapsedMs < 2000L)
            assertTrue(file.length() > 0)
        } finally {
            app.storage.removeTemporary(id)
        }
    }

    @Test
    fun unsampledPayloadDamageDocumentsBoundedValidationCoverage() = runBlocking {
        val id = "damaged-payload-${UUID.randomUUID()}"
        val dir = app.storage.temporary(id)
        try {
            val file = fixture(File(dir, "damaged.mp4"))
            val probe = FFprobeKit.executeWithArguments(arrayOf("-v", "error", "-select_streams", "v:0",
                "-show_entries", "packet=pos,size", "-of", "json", file.path))
            assertTrue("Fixture packet inspection failed", ReturnCode.isSuccess(probe.returnCode))
            val packets = org.json.JSONObject(probe.output).getJSONArray("packets")
            val packet = packets.getJSONObject(packets.length() / 4)
            val packetSize = packet.getInt("size")
            assertTrue("Fixture packet must contain compressed payload", packetSize > 64)
            damageSliceHeader(file, packet.getLong("pos"), packetSize)
            assertNotNull("Container metadata must remain readable", FFprobeKit.getMediaInformation(file.path).mediaInformation)
            val demux = FFmpegKit.executeWithArguments(arrayOf("-nostdin", "-v", "error", "-xerror",
                "-err_detect", "explode", "-i", file.path, "-map", "0:v:0", "-map", "0:a:0?",
                "-c", "copy", "-f", "null", "-"))
            assertTrue("Fixture must demonstrate corruption missed by stream-copy", ReturnCode.isSuccess(demux.returnCode))
            val strict = FFmpegKit.executeWithArguments(arrayOf("-nostdin", "-v", "error", "-xerror",
                "-err_detect", "explode", "-hwaccel", "none", "-i", file.path,
                "-map", "0:v:0", "-map", "0:a:0?", "-max_error_rate", "0", "-f", "null", "-"))
            assertFalse("The fixture must contain genuine compressed-payload damage", ReturnCode.isSuccess(strict.returnCode))
            // Bounded windows may miss this payload; strict demux is not exhaustive decoding.
            val detected = try {
                Processor().validate(file, audio = false, requireAudio = true, expectedContainer = "mp4")
                false
            } catch (error: MediaException) {
                assertEquals(ErrorCode.OUTPUT_VALIDATION_FAILED, error.code)
                true
            }
            val evidence = File(app.getExternalFilesDir(null), "advanced-audit/payload-corruption.json")
            evidence.parentFile!!.mkdirs()
            evidence.writeText(org.json.JSONObject().put("packet_position", packet.getLong("pos"))
                .put("packet_size", packetSize).put("copy_code", demux.returnCode?.value)
                .put("copy_logs", demux.allLogsAsString).put("strict_code", strict.returnCode?.value)
                .put("strict_logs", strict.allLogsAsString).put("bounded_validation_detected", detected)
                .put("policy", "strict demux plus bounded decoder windows; unsampled damage is not guaranteed detectable").toString(2))
        } finally {
            app.storage.removeTemporary(id)
        }
    }

    private fun damageSliceHeader(file: File, position: Long, size: Int) {
        RandomAccessFile(file, "rw").use { packet ->
            var offset = position
            while (offset + 5 < position + size) {
                packet.seek(offset)
                val nalSize = packet.readInt()
                check(nalSize > 1 && offset + 4 + nalSize <= position + size)
                val nalType = packet.readUnsignedByte() and 31
                if (nalType == 1 || nalType == 5) {
                    // An all-zero slice header has no terminating Exp-Golomb
                    // code. Preserve NAL lengths so container-only checks pass.
                    packet.write(ByteArray(nalSize - 1))
                    return
                }
                offset += 4 + nalSize
            }
        }
        error("Fixture packet contains no H.264 slice")
    }

    @Test
    fun truncatedVideoFailsValidation() = runBlocking {
        val id = "truncated-${UUID.randomUUID()}"
        val dir = app.storage.temporary(id)
        try {
            val file = fixture(File(dir, "truncated.mp4"))
            val originalSize = file.length()
            // Truncate to 60% of original size (cutting off container packets/tail)
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength((originalSize * 0.6).toLong())
            }
            try {
                Processor().validate(
                    file = file,
                    audio = false,
                    requireAudio = true,
                    expectedContainer = "mp4",
                    control = TransferControl()
                )
                fail("Truncated video must not pass validation!")
            } catch (e: MediaException) {
                assertEquals(ErrorCode.OUTPUT_VALIDATION_FAILED, e.code)
            }
        } finally {
            app.storage.removeTemporary(id)
        }
    }

    @Test
    fun corruptedContainerHeaderFailsValidation() = runBlocking {
        val id = "corrupt-header-${UUID.randomUUID()}"
        val dir = app.storage.temporary(id)
        try {
            val file = fixture(File(dir, "corrupt_header.mp4"))
            // Corrupt container header bytes
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(8)
                raf.write(ByteArray(64) { 0xFF.toByte() })
            }
            try {
                Processor().validate(
                    file = file,
                    audio = false,
                    requireAudio = true,
                    expectedContainer = "mp4",
                    control = TransferControl()
                )
                fail("Corrupted container header must not pass validation!")
            } catch (e: MediaException) {
                assertEquals(ErrorCode.OUTPUT_VALIDATION_FAILED, e.code)
            }
        } finally {
            app.storage.removeTemporary(id)
        }
    }
}
