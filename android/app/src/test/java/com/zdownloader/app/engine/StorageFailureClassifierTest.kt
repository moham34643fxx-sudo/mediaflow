package com.zdownloader.app.engine

import com.zdownloader.app.domain.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class StorageFailureClassifierTest {
    @Test fun nestedNoSpaceAndQuotaFailuresAreNotReportedAsGenericWriteErrors() {
        for (cause in listOf(
            IOException("open failed: ENOSPC (No space left on device)"),
            IOException("write failed: EDQUOT (Disk quota exceeded)")
        )) {
            assertEquals(ErrorCode.INSUFFICIENT_STORAGE,
                StorageFailureClassifier.classify(IOException("provider write failed", cause)))
        }
    }

    @Test fun nestedPermissionFailuresAreDistinctFromUnavailableDestinations() {
        assertEquals(ErrorCode.STORAGE_PERMISSION_ERROR,
            StorageFailureClassifier.classify(IOException("provider failure", SecurityException("permission denied"))))
        assertEquals(ErrorCode.STORAGE_PERMISSION_ERROR,
            StorageFailureClassifier.classify(IOException("open failed: EACCES (Permission denied)")))
        assertEquals(ErrorCode.STORAGE_DESTINATION_UNAVAILABLE,
            StorageFailureClassifier.classify(IOException("provider failed", FileNotFoundException("document disappeared"))))
    }

    @Test fun unexpectedIoRemainsAnUnexpectedWriteFailureAndDiagnosticsExcludeMessages() {
        val error = IOException("private uri content://provider/tree/token")
        assertNull(StorageFailureClassifier.classify(error))
        assertNull(StorageFailureClassifier.classify(FileNotFoundException("open failed: EIO (I/O error)")))
        assertEquals("IOException", StorageFailureClassifier.safeType(error))
        assertEquals("IOException", StorageFailureClassifier.safeType(StorageFailureClassifier.terminalCause(error)))
    }

    @Test fun publicationFailuresKeepCapacityPermissionDestinationAndWriteErrorsDistinct() {
        val noSpace=IOException("provider operation failed",IOException("ENOSPC"))
        val denied=IOException("provider operation failed",SecurityException("private uri content://provider/token"))
        val missing=FileNotFoundException("destination unavailable")
        val unexpected=IOException("EIO at content://provider/private-token")

        assertEquals(ErrorCode.INSUFFICIENT_STORAGE,StorageFailureClassifier.classify(noSpace))
        assertEquals(ErrorCode.STORAGE_PERMISSION_ERROR,StorageFailureClassifier.classify(denied))
        assertEquals(ErrorCode.STORAGE_DESTINATION_UNAVAILABLE,StorageFailureClassifier.classify(missing))
        assertNull(StorageFailureClassifier.classify(unexpected))
        assertEquals("IOException",StorageFailureClassifier.safeType(unexpected))
        assertFalse(StorageFailureClassifier.safeType(unexpected).contains("private-token"))
    }

    @Test fun nativeMp3ErrorsSeparateEncoderInputFromOutputStorageFailures() {
        assertEquals(ErrorCode.INSUFFICIENT_STORAGE, NativeMp3EncoderFailure.code(-3))
        assertEquals(ErrorCode.FILE_WRITE_FAILED, NativeMp3EncoderFailure.code(-4))
        assertEquals(ErrorCode.CONVERSION_FAILED, NativeMp3EncoderFailure.code(-5))
        assertEquals(ErrorCode.STORAGE_PERMISSION_ERROR, NativeMp3EncoderFailure.code(-6))
        assertEquals(ErrorCode.STORAGE_DESTINATION_UNAVAILABLE, NativeMp3EncoderFailure.code(-7))
    }
}
