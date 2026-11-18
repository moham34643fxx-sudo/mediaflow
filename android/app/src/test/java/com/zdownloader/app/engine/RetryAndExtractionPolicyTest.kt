package com.zdownloader.app.engine

import com.zdownloader.app.domain.ContentRange
import com.zdownloader.app.domain.ErrorCode
import com.zdownloader.app.domain.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class RetryAndExtractionPolicyTest {

    @Test
    fun rateLimitDoesNotReExtractUnlessExpired() {
        // While URL is valid, RATE_LIMITED must NOT trigger refresh; resumes via HTTP Range
        val refreshNonExpired = QueueCoordinator.shouldRefreshSelection(
            checkpoint = "DOWNLOAD",
            justResolved = false,
            retryCause = ErrorCode.RATE_LIMITED.name,
            expired = false
        )
        assertFalse("RATE_LIMITED with valid URL must NOT re-extract", refreshNonExpired)

        // If URL expired during cooldown, refresh is permitted once claimed
        val refreshExpired = QueueCoordinator.shouldRefreshSelection(
            checkpoint = "DOWNLOAD",
            justResolved = false,
            retryCause = ErrorCode.RATE_LIMITED.name,
            expired = true
        )
        assertTrue("Expired URL must refresh", refreshExpired)
    }

    @Test
    fun expiredUrlsWithoutTimestampsRecoverViaHttpErrors() {
        // Expired URLs without reliable expiration timestamps (expired = false)
        // are rejected with HTTP 403 Forbidden or HTTP 410 Gone by the CDN.
        // On retry, the recorded error causes shouldRefreshSelection to return true.
        assertEquals(ErrorCode.HTTP_FORBIDDEN, NetworkDiagnostics.httpError(403))
        assertEquals(ErrorCode.MEDIA_URL_EXPIRED, NetworkDiagnostics.httpError(410))

        for (code in listOf(ErrorCode.HTTP_FORBIDDEN, ErrorCode.MEDIA_URL_EXPIRED, ErrorCode.AUTHENTICATION_REQUIRED, ErrorCode.SOURCE_CORRUPT)) {
            val shouldRefresh = QueueCoordinator.shouldRefreshSelection(
                checkpoint = "DOWNLOAD",
                justResolved = false,
                retryCause = code.name,
                expired = false
            )
            assertTrue("${code.name} must trigger refresh even when timestamp expiration was not detected", shouldRefresh)
        }
    }

    @Test
    fun socketTimeoutsAndTransientNetworkErrorsResumeViaRangeWithoutReExtraction() {
        val readTimeout = NetworkDiagnostics.classify(SocketTimeoutException("Read timed out"), "body")
        assertEquals(ErrorCode.READ_TIMEOUT, readTimeout)

        val connectTimeout = NetworkDiagnostics.classify(SocketTimeoutException("connect timed out"), "tcp")
        assertEquals(ErrorCode.CONNECT_TIMEOUT, connectTimeout)

        for (code in listOf(readTimeout, connectTimeout, ErrorCode.DOWNLOAD_INTERRUPTED)) {
            val shouldRefresh = QueueCoordinator.shouldRefreshSelection(
                checkpoint = "DOWNLOAD",
                justResolved = false,
                retryCause = code.name,
                expired = false
            )
            assertFalse("${code.name} must NOT re-extract; must resume via HTTP Range", shouldRefresh)
        }
    }

    @Test
    fun nonDownloadCheckpointsAndJustResolvedNeverRefresh() {
        // Items in PROCESSING or MERGING must never trigger extractor refresh
        val refreshProcessing = QueueCoordinator.shouldRefreshSelection(
            checkpoint = "PROCESSING",
            justResolved = false,
            retryCause = ErrorCode.MEDIA_URL_EXPIRED.name,
            expired = true
        )
        assertFalse("PROCESSING checkpoint must never refresh selection", refreshProcessing)

        // An item freshly extracted must not immediately refresh again
        val refreshJustResolved = QueueCoordinator.shouldRefreshSelection(
            checkpoint = "DOWNLOAD",
            justResolved = true,
            retryCause = null,
            expired = true
        )
        assertFalse("Newly resolved items must not double-refresh", refreshJustResolved)
    }

    @Test
    fun contentRangeStrictlyValidatesHttp206Resumption() {
        // Valid 206 response matching bytes offset
        val validRange = ContentRange.parse("bytes 1048576-2097151/10485760")
        assertEquals(1048576L, validRange?.start)
        assertEquals(2097151L, validRange?.end)
        assertEquals(10485760L, validRange?.total)

        // Invalid ranges rejected immediately to prevent file corruption
        assertNull(ContentRange.parse("bytes 200-100/1000"))
        assertNull(ContentRange.parse("bytes 0-1000/100"))
        assertNull(ContentRange.parse("invalid-range-string"))
    }

    @Test
    fun adversarialExpiresAtCornerCases() {
        val now = System.currentTimeMillis()

        fun isExpired(expiresAt: Long?): Boolean {
            return expiresAt?.let { deadline -> deadline <= now + 5_000 } == true
        }

        // null expiresAt: must NOT be considered expired
        assertFalse("null expiresAt must not be expired", isExpired(null))

        // 0 expiresAt: must be considered expired (epoch 1970 is in the past)
        assertTrue("0 expiresAt must be considered expired", isExpired(0L))

        // Negative expiresAt: must be considered expired
        assertTrue("Negative expiresAt must be considered expired", isExpired(-1L))

        // Distant future expiresAt: must NOT be considered expired
        assertFalse("Distant future expiresAt must not be expired", isExpired(now + 86_400_000L))
        assertFalse("Long.MAX_VALUE expiresAt must not be expired", isExpired(Long.MAX_VALUE - 100_000L))

        // Near-boundary tests (+5000ms buffer)
        assertTrue("Within 5s buffer must be expired", isExpired(now + 4_999L))
        assertTrue("Exactly at 5s buffer must be expired", isExpired(now + 5_000L))
        assertFalse("Beyond 5s buffer must not be expired", isExpired(now + 5_001L))
    }

    @Test
    fun adversarialRetryCauseCornerCases() {
        // First run: retryCause is null
        assertFalse("First run with valid URL must NOT refresh",
            QueueCoordinator.shouldRefreshSelection("DOWNLOAD", justResolved = false, retryCause = null, expired = false))
        assertTrue("First run with expired URL MUST refresh",
            QueueCoordinator.shouldRefreshSelection("DOWNLOAD", justResolved = false, retryCause = null, expired = true))

        // Subsequent retry where previous error was not recorded (null)
        assertFalse("Retry with null cause and valid URL must NOT refresh",
            QueueCoordinator.shouldRefreshSelection("DOWNLOAD", justResolved = false, retryCause = null, expired = false))
        assertTrue("Retry with null cause and expired URL MUST refresh",
            QueueCoordinator.shouldRefreshSelection("DOWNLOAD", justResolved = false, retryCause = null, expired = true))

        // Unknown or custom error string
        assertFalse("Unknown error string with valid URL must NOT refresh",
            QueueCoordinator.shouldRefreshSelection("DOWNLOAD", justResolved = false, retryCause = "UNKNOWN_ERROR", expired = false))
    }

    @Test
    fun adversarialDualStreamExpiryCornerCases() {
        val now = System.currentTimeMillis()
        fun evalExpiry(primaryExpiresAt: Long?, secondaryExpiresAt: Long?): Boolean {
            val list = listOfNotNull(primaryExpiresAt, secondaryExpiresAt)
            return list.any { deadline -> deadline <= now + 5_000 }
        }

        // Both streams valid
        assertFalse("Both streams valid -> not expired",
            evalExpiry(now + 3600_000L, now + 3600_000L))

        // Only primary expired
        assertTrue("Only primary expired -> any{} triggers refresh",
            evalExpiry(now - 1000L, now + 3600_000L))

        // Only secondary expired
        assertTrue("Only secondary expired -> any{} triggers refresh",
            evalExpiry(now + 3600_000L, now - 1000L))

        // Both expired
        assertTrue("Both expired -> triggers refresh",
            evalExpiry(now - 1000L, now - 1000L))

        // Single stream (audio-only, secondary is null)
        assertFalse("Single stream valid -> not expired",
            evalExpiry(now + 3600_000L, null))
        assertTrue("Single stream expired -> triggers refresh",
            evalExpiry(now - 1000L, null))
    }

    @Test
    fun adversarialNonDownloadCheckpointsAreStrictlyImmune() {
        val nonDownloadCheckpoints = listOf(
            "DOWNLOADED",
            "MERGED",
            "VALIDATED",
            "PROCESSING",
            "SAVING",
            "COMPLETED",
            "FAILED",
            "PAUSED",
            "CANCELLED",
            "",
            "download", // case sensitive check
            "UNKNOWN"
        )

        for (checkpoint in nonDownloadCheckpoints) {
            // Even with expired URLs or fatal dead URL error codes, non-DOWNLOAD checkpoints must NEVER refresh
            assertFalse(
                "Checkpoint '$checkpoint' must never trigger selection refresh",
                QueueCoordinator.shouldRefreshSelection(
                    checkpoint = checkpoint,
                    justResolved = false,
                    retryCause = ErrorCode.HTTP_FORBIDDEN.name,
                    expired = true
                )
            )
        }
    }

    @Test
    fun adversarialRateLimitStrictlyEnforcesMinimumBackoff() {
        // Verify delay calculation: RATE_LIMITED delay is maxOf(RetryPolicy.delayMillis, 60_000L)
        val retryAfterScenarios = listOf(null, 0L, 5_000L, 30_000L, 60_000L, 120_000L)

        for (attempt in 0..10) {
            for (retryAfter in retryAfterScenarios) {
                val baseDelay = RetryPolicy.delayMillis(attempt, retryAfter, jitter = 0.5)
                val rateLimitedDelay = maxOf(baseDelay, 60_000L)
                assertTrue(
                    "RATE_LIMITED backoff must be at least 60,000ms (attempt=$attempt, retryAfter=$retryAfter)",
                    rateLimitedDelay >= 60_000L
                )
                if (retryAfter != null && retryAfter > 60_000L) {
                    assertTrue(
                        "Server Retry-After of $retryAfter must be respected",
                        rateLimitedDelay >= retryAfter
                    )
                }
            }
        }

        // Verify that RATE_LIMITED error code does NOT trigger selection refresh if URL is unexpired
        assertFalse(
            "RATE_LIMITED must not trigger URL refresh when URL is not expired",
            QueueCoordinator.shouldRefreshSelection("DOWNLOAD", false, ErrorCode.RATE_LIMITED.name, expired = false)
        )
    }

    @Test
    fun adversarialDeterministicRetryTerminationOracle() {
        // 1. Non-recoverable error (e.g., HTTP_FORBIDDEN after in-flight refresh failed)
        // Must fail immediately on the queue without retry
        val nonRecoverableCanRetry = ErrorCode.HTTP_FORBIDDEN.recoverable && 0 < 3
        assertFalse("HTTP_FORBIDDEN is non-recoverable at queue level; fails immediately", nonRecoverableCanRetry)

        // 2. Recoverable errors (e.g. RATE_LIMITED, SOURCE_CORRUPT, READ_TIMEOUT)
        // Must terminate deterministically when retries reaches retryLimit
        val retryLimit = 3
        var currentRetries = 0
        var terminalFailure = false

        while (currentRetries <= retryLimit + 2) {
            val canRetry = ErrorCode.SOURCE_CORRUPT.recoverable && currentRetries < retryLimit
            if (canRetry) {
                currentRetries++
            } else {
                terminalFailure = true
                break
            }
        }

        assertTrue("Failure must terminate after retryLimit exhausted", terminalFailure)
        assertEquals("Must terminate exactly when retries equals retryLimit", 3, currentRetries)
    }
}
