package com.zdownloader.app.engine

import com.zdownloader.app.domain.ErrorCode
import com.zdownloader.app.domain.MediaException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

class FastIntegrityAuditorTest {

    @Test
    fun sampledAv1HeadersRejectForbiddenAndReservedBitsWithoutDecodingFrames() {
        // 2026-11-14: HONOR emitted a valid first frame despite later 0xff
        // packets inside the bounded window. These bits are invalid for any OBU.
        for (valid in listOf(0x12, 0x32, 0x36)) {
            assertEquals(true, FastIntegrityAuditor.validAv1ObuHeader(valid.toByte()))
            assertEquals(false, FastIntegrityAuditor.validAv1ObuHeader((valid or 0x80).toByte()))
            assertEquals(false, FastIntegrityAuditor.validAv1ObuHeader((valid or 1).toByte()))
        }
        assertEquals(false, FastIntegrityAuditor.validAv1ObuHeader(0xff.toByte()))
    }

    @Test
    fun onlyDecodedPayloadCountsAsWindowEvidence() {
        val eos = android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM
        val config = android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG
        assertEquals(false, FastIntegrityAuditor.isDecodedOutput(0, eos))
        assertEquals(false, FastIntegrityAuditor.isDecodedOutput(0, 0))
        assertEquals(false, FastIntegrityAuditor.isDecodedOutput(128, config))
        assertEquals(false, FastIntegrityAuditor.isDecodedOutput(128, config or eos))
        assertEquals(true, FastIntegrityAuditor.isDecodedOutput(128, 0))
        assertEquals(true, FastIntegrityAuditor.isDecodedOutput(128, eos))
    }

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun rejectsFileTooSmall() {
        val file = tempDir.newFile("small.mp4")
        file.writeBytes(ByteArray(20))
        try {
            FastIntegrityAuditor.audit(file, audio = false, requireAudio = false)
            fail("Expected MediaException for file < 64 bytes")
        } catch (e: MediaException) {
            assertEquals(ErrorCode.FILE_WRITE_FAILED, e.code)
        }
    }

    @Test
    fun rejectsFileWithMissingMoov() {
        val file = tempDir.newFile("no_moov.mp4")
        DataOutputStream(FileOutputStream(file)).use { out ->
            // ftyp
            out.writeInt(16)
            out.write("ftyp".toByteArray(Charsets.ISO_8859_1))
            out.write("isom".toByteArray(Charsets.ISO_8859_1))
            out.writeInt(512)
            // free
            out.writeInt(60)
            out.write("free".toByteArray(Charsets.ISO_8859_1))
            out.write(ByteArray(52))
        }
        try {
            FastIntegrityAuditor.audit(file, audio = false, requireAudio = false)
            fail("Expected MediaException for missing moov atom")
        } catch (e: MediaException) {
            assertEquals(ErrorCode.OUTPUT_VALIDATION_FAILED, e.code)
            assertEquals("missing_moov_atom", e.validationCheck)
        }
    }

    @Test
    fun rejectsFileWithTruncatedAtom() {
        val file = tempDir.newFile("truncated.mp4")
        DataOutputStream(FileOutputStream(file)).use { out ->
            // ftyp
            out.writeInt(16)
            out.write("ftyp".toByteArray(Charsets.ISO_8859_1))
            out.write("isom".toByteArray(Charsets.ISO_8859_1))
            out.writeInt(512)
            // corrupt moov declaring 100,000 bytes but only 8 bytes exist
            out.writeInt(100000)
            out.write("moov".toByteArray(Charsets.ISO_8859_1))
            out.write(ByteArray(50))
        }
        try {
            FastIntegrityAuditor.audit(file, audio = false, requireAudio = false)
            fail("Expected MediaException for truncated atom")
        } catch (e: MediaException) {
            assertEquals(ErrorCode.OUTPUT_VALIDATION_FAILED, e.code)
            assertEquals("truncated_atom_moov", e.validationCheck)
        }
    }

    @Test
    fun rejectsFileWithNearBoundaryTruncatedAtom() {
        val file = tempDir.newFile("boundary_truncated.mp4")
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeInt(16)
            out.write("ftyp".toByteArray(Charsets.ISO_8859_1))
            out.write("isom".toByteArray(Charsets.ISO_8859_1))
            out.writeInt(512)
            out.writeInt(64)
            out.write("free".toByteArray(Charsets.ISO_8859_1))
            out.write(ByteArray(56))
            // moov declares 20 bytes, but only 16 bytes written (truncated by 4 bytes)
            out.writeInt(20)
            out.write("moov".toByteArray(Charsets.ISO_8859_1))
            out.write(ByteArray(8))
        }
        try {
            FastIntegrityAuditor.audit(file, audio = false, requireAudio = false)
            fail("Expected MediaException for atom truncated within 8 bytes of EOF")
        } catch (e: MediaException) {
            assertEquals(ErrorCode.OUTPUT_VALIDATION_FAILED, e.code)
            assertEquals("truncated_atom_moov", e.validationCheck)
        }
    }

    @Test
    fun rejectsFileWithTrailingDanglingBytes() {
        val file = tempDir.newFile("dangling.mp4")
        DataOutputStream(FileOutputStream(file)).use { out ->
            // ftyp
            out.writeInt(16)
            out.write("ftyp".toByteArray(Charsets.ISO_8859_1))
            out.write("isom".toByteArray(Charsets.ISO_8859_1))
            out.writeInt(512)
            // moov
            out.writeInt(16)
            out.write("moov".toByteArray(Charsets.ISO_8859_1))
            out.write(ByteArray(8))
            // mdat
            out.writeInt(32)
            out.write("mdat".toByteArray(Charsets.ISO_8859_1))
            out.write(ByteArray(24))
            // 4 dangling incomplete bytes
            out.write(byteArrayOf(0x00, 0x00, 0x00, 0x05))
        }
        try {
            FastIntegrityAuditor.audit(file, audio = false, requireAudio = false)
            fail("Expected MediaException for trailing dangling bytes")
        } catch (e: MediaException) {
            assertEquals(ErrorCode.OUTPUT_VALIDATION_FAILED, e.code)
            assertEquals("truncated_trailing_bytes", e.validationCheck)
        }
    }

    @Test
    fun auditMp4AtomsAcceptsValidContainer() {
        val file = tempDir.newFile("valid_structure.mp4")
        DataOutputStream(FileOutputStream(file)).use { out ->
            // ftyp
            out.writeInt(16)
            out.write("ftyp".toByteArray(Charsets.ISO_8859_1))
            out.write("isom".toByteArray(Charsets.ISO_8859_1))
            out.writeInt(512)
            // moov
            out.writeInt(16)
            out.write("moov".toByteArray(Charsets.ISO_8859_1))
            out.write(ByteArray(8))
            // mdat
            out.writeInt(32)
            out.write("mdat".toByteArray(Charsets.ISO_8859_1))
            out.write(ByteArray(24))
        }
        // auditMp4Atoms should complete cleanly without throwing
        FastIntegrityAuditor.auditMp4Atoms(file)
    }

    @Test
    fun rejectsExtendedBoxWithInsufficientHeader() {
        val file = tempDir.newFile("corrupt_ext_header.mp4")
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeInt(16)
            out.write("ftyp".toByteArray(Charsets.ISO_8859_1))
            out.write("isom".toByteArray(Charsets.ISO_8859_1))
            out.writeInt(512)
            // free box (48 bytes) to ensure file >= 64 bytes
            out.writeInt(48)
            out.write("free".toByteArray(Charsets.ISO_8859_1))
            out.write(ByteArray(40))
            // size=1 (extended) but only 4 bytes follow (needs 8 bytes for 64-bit size)
            out.writeInt(1)
            out.write("mdat".toByteArray(Charsets.ISO_8859_1))
            out.writeInt(100) // only 4 bytes instead of 8
        }
        try {
            FastIntegrityAuditor.audit(file, audio = false, requireAudio = false)
            fail("Expected MediaException for corrupt extended box header")
        } catch (e: MediaException) {
            assertEquals(ErrorCode.OUTPUT_VALIDATION_FAILED, e.code)
            assertEquals("corrupt_extended_box", e.validationCheck)
        }
    }

    @Test
    fun rejectsExtendedBoxWithInvalidSmallBoxSize() {
        val file = tempDir.newFile("small_ext_box.mp4")
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeInt(16)
            out.write("ftyp".toByteArray(Charsets.ISO_8859_1))
            out.write("isom".toByteArray(Charsets.ISO_8859_1))
            out.writeInt(512)
            // size=1 (extended) but boxSize is 10 (less than minimum 16 bytes for 64-bit box)
            out.writeInt(1)
            out.write("mdat".toByteArray(Charsets.ISO_8859_1))
            out.writeLong(10L)
            out.write(ByteArray(50))
        }
        try {
            FastIntegrityAuditor.audit(file, audio = false, requireAudio = false)
            fail("Expected MediaException for extended box < 16 bytes")
        } catch (e: MediaException) {
            assertEquals(ErrorCode.OUTPUT_VALIDATION_FAILED, e.code)
            assertEquals("truncated_atom_mdat", e.validationCheck)
        }
    }

    @Test
    fun rejectsExtendedBoxExceedingFileLength() {
        val file = tempDir.newFile("overflow_ext_box.mp4")
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeInt(16)
            out.write("ftyp".toByteArray(Charsets.ISO_8859_1))
            out.write("isom".toByteArray(Charsets.ISO_8859_1))
            out.writeInt(512)
            out.writeInt(1)
            out.write("mdat".toByteArray(Charsets.ISO_8859_1))
            out.writeLong(10_000_000L) // exceeds file size
            out.write(ByteArray(64))
        }
        try {
            FastIntegrityAuditor.audit(file, audio = false, requireAudio = false)
            fail("Expected MediaException for extended box exceeding file length")
        } catch (e: MediaException) {
            assertEquals(ErrorCode.OUTPUT_VALIDATION_FAILED, e.code)
            assertEquals("truncated_atom_mdat", e.validationCheck)
        }
    }

    @Test
    fun testDecoderStatusEnumValues() {
        assertEquals(true, FastIntegrityAuditor.DecoderStatus.PASS.isSuccess)
        assertEquals(false, FastIntegrityAuditor.DecoderStatus.FAIL.isSuccess)
        assertEquals(false, FastIntegrityAuditor.DecoderStatus.INCONCLUSIVE.isSuccess)
        assertEquals(false, FastIntegrityAuditor.DecoderStatus.UNSUPPORTED.isSuccess)
    }

    @Test
    fun testWindowResultDataClass() {
        val resPass = FastIntegrityAuditor.WindowResult(
            status = FastIntegrityAuditor.DecoderStatus.PASS,
            initialTimeUs = 1000L,
            framesProduced = 2,
            samplesFed = 5
        )
        assertEquals(FastIntegrityAuditor.DecoderStatus.PASS, resPass.status)
        assertEquals(1000L, resPass.initialTimeUs)
        assertEquals(2, resPass.framesProduced)
        assertEquals(5, resPass.samplesFed)

        val resFail = FastIntegrityAuditor.WindowResult(
            status = FastIntegrityAuditor.DecoderStatus.FAIL,
            reason = "corrupt_sample"
        )
        assertEquals(FastIntegrityAuditor.DecoderStatus.FAIL, resFail.status)
        assertEquals("corrupt_sample", resFail.reason)
    }

    @Test
    fun testValidContainerThrowsProbeExceptionOnHostWithoutMockedMediaExtractor() {
        val file = tempDir.newFile("valid_structure_with_moov.mp4")
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeInt(16)
            out.write("ftyp".toByteArray(Charsets.ISO_8859_1))
            out.write("isom".toByteArray(Charsets.ISO_8859_1))
            out.writeInt(512)
            out.writeInt(16)
            out.write("moov".toByteArray(Charsets.ISO_8859_1))
            out.write(ByteArray(8))
            out.writeInt(32)
            out.write("mdat".toByteArray(Charsets.ISO_8859_1))
            out.write(ByteArray(24))
        }
        try {
            FastIntegrityAuditor.audit(file, audio = false, requireAudio = false)
            fail("Expected probe exception on unmocked host JVM")
        } catch (e: MediaException) {
            assertEquals(ErrorCode.OUTPUT_VALIDATION_FAILED, e.code)
            assert(e.validationCheck?.startsWith("probe_") == true)
        }
    }
}
