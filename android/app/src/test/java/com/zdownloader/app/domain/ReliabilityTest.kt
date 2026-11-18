package com.zdownloader.app.domain

import org.junit.Assert.*
import org.junit.Test

class ReliabilityTest {
    @Test fun contentRangesRejectCorruptionAndOverflow() {
        assertEquals(ContentRange(65536,99999,100000),ContentRange.parse("bytes 65536-99999/100000"))
        assertEquals(ContentRange(0,9,null),ContentRange.parse("bytes 0-9/*"))
        listOf(null,"bytes 9-0/100","bytes 0-100/100","bytes 0-9223372036854775807/*","bytes 0-999999999999999999999/100","items 0-9/10").forEach { assertNull(it,ContentRange.parse(it)) }
    }
    @Test fun retryBackoffIsBoundedAndRespectsServerDelay() {
        assertEquals(5000,RetryPolicy.delayMillis(0,jitter=0.0))
        assertEquals(12500,RetryPolicy.delayMillis(1,jitter=1.0))
        assertEquals(60000,RetryPolicy.delayMillis(0,60000,jitter=0.0))
        assertEquals(200000,RetryPolicy.delayMillis(100,jitter=1.0))
        assertFalse(ErrorCode.HTTP_ERROR.recoverable)
        assertTrue(ErrorCode.SERVER_UNAVAILABLE.recoverable)
        assertFalse(ErrorCode.AUTHENTICATION_REQUIRED.recoverable)
    }
    @Test fun decoderAndMetadataFailuresAreNotTransportCorruptionRetries() {
        assertFalse(ErrorCode.DECODER_UNAVAILABLE.recoverable)
        assertFalse(ErrorCode.MEDIA_METADATA_MISMATCH.recoverable)
        assertFalse(ErrorCode.ENCODER_UNAVAILABLE.recoverable)
        assertFalse(ErrorCode.OUTPUT_VALIDATION_FAILED.recoverable)
        assertTrue(ErrorCode.SOURCE_CORRUPT.recoverable)
    }
    @Test fun unicodeNamesFitFilesystemAndKeepCompleteCodePoints() {
        val name = FileNames.sanitize("🎬".repeat(100))
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 200)
        assertEquals(0,name.length % 2)
        assertFalse(FileNames.sanitize("video\u202Etxt.mp4").contains('\u202E'))
        assertEquals("عنوان English 🎬",FileNames.sanitize("عنوان English 🎬"))
    }
    @Test fun codecAbsenceDoesNotInventAnotherTrack() {
        val audio = Format("a","m4a",null,null,null,"aac",100,192.0,"https","{}")
        assertFalse(audio.video)
        assertTrue(audio.audio)
        val video = audio.copy(videoCodec="h264",audioCodec=null)
        assertTrue(video.video)
        assertFalse(video.audio)
        val unknownAudio = audio.copy(videoCodec="none",audioCodec=null)
        assertTrue(unknownAudio.streamOnly)
    }
}
