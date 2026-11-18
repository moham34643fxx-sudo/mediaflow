package com.zdownloader.app.ui

import com.zdownloader.app.data.BatchSummary
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.data.PlaylistBatch
import org.junit.Assert.*
import org.junit.Test

class DownloadsPresentationTest {

    @Test fun continueQueueRequiresPendingWorkAndRespectsPlaylistPause() {
        assertFalse(DownloadsPresentation.canContinueQueue(emptyList(),emptyList()))
        for(status in listOf("COMPLETED","CANCELLED","FAILED","PAUSED","SCHEDULED","DOWNLOADING","PROCESSING")) {
            assertFalse(status,DownloadsPresentation.canContinueQueue(listOf(entity(status=status)),emptyList()))
        }
        for(status in listOf("QUEUED","WAITING_FOR_NETWORK","RETRYING","RESUMING","INTERRUPTED")) {
            assertTrue(status,DownloadsPresentation.canContinueQueue(listOf(entity(status=status)),emptyList()))
            assertTrue(status,DownloadsPresentation.canContinueQueue(listOf(entity(status=status,batchId="b1")),listOf(batch())))
            assertFalse(status,DownloadsPresentation.canContinueQueue(listOf(entity(status=status,batchId="b1")),listOf(batch(state="PAUSED"))))
        }
    }

    private fun entity(
        id: String = "id1",
        title: String = "Big Buck Bunny",
        filename: String = "bbb.mp4",
        source: String = "w3schools.com",
        status: String = "DOWNLOADING",
        bytes: Long = 5000L,
        total: Long? = 10000L,
        speed: Long = 1000L,
        outputSize: Long? = null,
        sizeKind: String = "EXACT",
        batchId: String? = null
    ): DownloadEntity = DownloadEntity(
        id = id,
        url = "https://example.com/video.mp4",
        title = title,
        source = source,
        thumbnail = null,
        duration = 60.0,
        selection = "{}",
        filename = filename,
        destination = null,
        fingerprint = "fp_$id",
        status = status,
        bytes = bytes,
        total = total,
        speed = speed,
        outputSize = outputSize,
        sizeKind = sizeKind,
        batchId = batchId
    )

    private fun batch(
        id: String = "b1",
        title: String = "Playlist",
        state: String = "ACTIVE",
        selected: Int = 5,
        completed: Int = 0,
        failed: Int = 0,
        cancelled: Int = 0,
        paused: Int = 0,
        active: Int = 0,
        scheduled: Int = 0
    ): BatchSummary = BatchSummary(
        batch = PlaylistBatch(
            id = id,
            submissionKey = "sub_$id",
            url = "https://example.com/playlist",
            title = title,
            policy = "{}",
            createdAt = 1000L,
            state = state
        ),
        selected = selected,
        completed = completed,
        failed = failed,
        cancelled = cancelled,
        paused = paused,
        active = active,
        scheduled = scheduled,
        scheduledAt = 0L,
        scheduleNetwork = "ANY",
        requiresCharging = false,
        transferred = 0L,
        expectedSum = 0.0,
        knownSizes = 0,
        estimatedSizes = 0,
        networkDone = 0,
        currentTitle = null,
        currentState = null
    )

    @Test fun allFailedPlaylistRemainsFailedAndRetryableRatherThanCompleted() {
        val failedBatch=batch(selected=3,failed=3)
        assertEquals("FAILED",failedBatch.status)
        assertTrue(failedBatch.finished)
        assertTrue(DownloadsPresentation.matchesBatchFilter(failedBatch,DownloadFilter.FAILED))
        assertFalse(DownloadsPresentation.matchesBatchFilter(failedBatch,DownloadFilter.COMPLETED))
        assertFalse(failedBatch.progress.successful)
        assertEquals("PARTIAL",batch(selected=3,completed=2,failed=1).status)
    }

    @Test
    fun testMatchesFilterCategorizesStatusesCorrectly() {
        assertTrue(DownloadsPresentation.matchesFilter("DOWNLOADING", DownloadFilter.ALL))
        assertTrue(DownloadsPresentation.matchesFilter("DOWNLOADING", DownloadFilter.ACTIVE))
        assertFalse(DownloadsPresentation.matchesFilter("QUEUED", DownloadFilter.ACTIVE))

        assertTrue(DownloadsPresentation.matchesFilter("QUEUED", DownloadFilter.WAITING))
        assertTrue(DownloadsPresentation.matchesFilter("SCHEDULED", DownloadFilter.WAITING))
        assertFalse(DownloadsPresentation.matchesFilter("DOWNLOADING", DownloadFilter.WAITING))

        assertTrue(DownloadsPresentation.matchesFilter("COMPLETED", DownloadFilter.COMPLETED))
        assertTrue(DownloadsPresentation.matchesFilter("FAILED", DownloadFilter.FAILED))
        assertTrue(DownloadsPresentation.matchesFilter("CANCELLED", DownloadFilter.CANCELLED))
    }

    @Test
    fun testBatchCancelledVsCompletedAndActiveVsWaitingDistinction() {
        // Fully cancelled batch (selected 5, cancelled 5, state CANCELLED) derives CANCELLED
        val fullyCancelledBatch = batch(state = "CANCELLED", selected = 5, cancelled = 5)
        assertEquals("CANCELLED", fullyCancelledBatch.status)
        assertTrue(DownloadsPresentation.matchesBatchFilter(fullyCancelledBatch, DownloadFilter.CANCELLED))
        assertFalse(
            "Fully cancelled batch must NOT be classified as COMPLETED",
            DownloadsPresentation.matchesBatchFilter(fullyCancelledBatch, DownloadFilter.COMPLETED)
        )

        // Partially cancelled batch (selected 5, completed 3, cancelled 2, state CANCELLED) derives CANCELLED
        val partialCancelledBatch = batch(state = "CANCELLED", selected = 5, completed = 3, cancelled = 2)
        assertEquals("CANCELLED", partialCancelledBatch.status)
        assertFalse(
            "Partially cancelled batch must NOT be classified as COMPLETED",
            DownloadsPresentation.matchesBatchFilter(partialCancelledBatch, DownloadFilter.COMPLETED)
        )
        assertTrue(DownloadsPresentation.matchesBatchFilter(partialCancelledBatch, DownloadFilter.CANCELLED))

        // Fully completed batch (selected 5, completed 5, active 0, state ACTIVE) derives COMPLETED
        val completedBatch = batch(state = "ACTIVE", selected = 5, completed = 5, active = 0)
        assertEquals("COMPLETED", completedBatch.status)
        assertTrue(DownloadsPresentation.matchesBatchFilter(completedBatch, DownloadFilter.COMPLETED))
        assertFalse(DownloadsPresentation.matchesBatchFilter(completedBatch, DownloadFilter.CANCELLED))

        // Actively-running batch (selected 5, active 2, state ACTIVE) derives ACTIVE
        val activeBatch = batch(state = "ACTIVE", selected = 5, active = 2)
        assertEquals("ACTIVE", activeBatch.status)
        assertTrue(DownloadsPresentation.matchesBatchFilter(activeBatch, DownloadFilter.ACTIVE))
        assertFalse(DownloadsPresentation.matchesBatchFilter(activeBatch, DownloadFilter.WAITING))

        // Queued-only batch (selected 5, active 0, other terminal counts 0, state ACTIVE) derives ACTIVE
        val queuedBatch = batch(state = "ACTIVE", selected = 5, active = 0)
        assertEquals("ACTIVE", queuedBatch.status)
        assertFalse(DownloadsPresentation.matchesBatchFilter(queuedBatch, DownloadFilter.ACTIVE))
        assertTrue(DownloadsPresentation.matchesBatchFilter(queuedBatch, DownloadFilter.WAITING))
    }

    @Test
    fun testMixedPlaylistAndSingleFilterCounts() {
        val singles = listOf(
            entity(id = "s1", status = "DOWNLOADING", batchId = null),
            entity(id = "s2", status = "COMPLETED", batchId = null),
            // Child item belonging to a playlist must not be double-counted as an independent row
            entity(id = "c1", status = "DOWNLOADING", batchId = "b1")
        )
        val batches = listOf(
            batch(id = "b1", state = "ACTIVE", selected = 5, active = 1),
            batch(id = "b2", state = "ACTIVE", selected = 5, completed = 5, active = 0)
        )

        // Active: 1 visible single (s1) + 1 active batch (b1) = 2 (child c1 ignored)
        assertEquals(2, DownloadsPresentation.countMatching(singles, batches, DownloadFilter.ACTIVE))
        // Completed: 1 visible single (s2) + 1 completed batch (b2) = 2
        assertEquals(2, DownloadsPresentation.countMatching(singles, batches, DownloadFilter.COMPLETED))
        // All: 2 visible singles + 2 batches = 4
        assertEquals(4, DownloadsPresentation.countMatching(singles, batches, DownloadFilter.ALL))
    }

    @Test
    fun testSearchMatchesTitleFilenameSourceAndTrimBlankContract() {
        val item = entity(title = "Documentary 2026", filename = "doc_nature.mp4", source = "nature.org")

        // Case-insensitive title matching
        assertTrue(DownloadsPresentation.matchesSearch(item, "doc"))
        assertTrue(DownloadsPresentation.matchesSearch(item, "DOCUMENTARY"))

        // Case-insensitive filename matching
        assertTrue(DownloadsPresentation.matchesSearch(item, "nature"))
        assertTrue(DownloadsPresentation.matchesSearch(item, "DOC_NATURE"))

        // Case-insensitive source matching
        assertTrue(DownloadsPresentation.matchesSearch(item, "nature.org"))
        assertTrue(DownloadsPresentation.matchesSearch(item, "NATURE.ORG"))

        // Leading/trailing whitespace trimmed
        assertTrue(DownloadsPresentation.matchesSearch(item, "   nature   "))

        // Blank queries return true (contract: do not filter out rows)
        assertTrue(DownloadsPresentation.matchesSearch(item, ""))
        assertTrue(DownloadsPresentation.matchesSearch(item, "   "))

        // Non-matching query returns false
        assertFalse(DownloadsPresentation.matchesSearch(item, "sports"))
    }

    @Test
    fun testProgressMeasurableExactComputesValidProgressAndEta() {
        val item = entity(status = "DOWNLOADING", bytes = 4000L, total = 10000L, speed = 2000L, sizeKind = "EXACT")
        val progress = DownloadsPresentation.computeProgress(item)

        assertNotNull(progress.progress)
        assertEquals(0.4f, progress.progress!!, 0.001f)
        assertEquals(40, progress.percent)
        assertEquals(2000L, progress.validSpeed)
        assertEquals(3L, progress.remainingSeconds)
    }

    @Test
    fun testProgressEstimatedTotalSuppressesEtaAndBecomesIndeterminateAtLimit() {
        // Before estimate: percent & progress shown, but ETA strictly suppressed
        val underEstimate = entity(status = "DOWNLOADING", bytes = 4000L, total = 10000L, speed = 2000L, sizeKind = "ESTIMATED")
        val progressUnder = DownloadsPresentation.computeProgress(underEstimate)

        assertNotNull(progressUnder.progress)
        assertEquals(40, progressUnder.percent)
        assertEquals(2000L, progressUnder.validSpeed)
        assertNull("ETA must be suppressed for estimated totals", progressUnder.remainingSeconds)

        // At or after estimate: indeterminate
        val overEstimate = entity(status = "DOWNLOADING", bytes = 10500L, total = 10000L, speed = 2000L, sizeKind = "ESTIMATED")
        val progressOver = DownloadsPresentation.computeProgress(overEstimate)

        assertNull("Progress must be indeterminate when exceeding estimated total", progressOver.progress)
        assertNull(progressOver.percent)
        assertEquals(2000L, progressOver.validSpeed)
        assertNull(progressOver.remainingSeconds)
    }

    @Test
    fun testProgressUnknownTotalSuppressesProgressAndEtaPreservesSpeed() {
        val item = entity(status = "DOWNLOADING", bytes = 4000L, total = null, speed = 1500L, sizeKind = "UNKNOWN")
        val progress = DownloadsPresentation.computeProgress(item)

        assertNull(progress.progress)
        assertNull(progress.percent)
        assertEquals(1500L, progress.validSpeed)
        assertNull(progress.remainingSeconds)
    }

    @Test
    fun testProgressGuardsNegativeOrZeroSpeedAndNonDownloadingStatuses() {
        // Zero or negative speed must yield null validSpeed and null remainingSeconds
        listOf(0L, -500L).forEach { spd ->
            val res = DownloadsPresentation.computeProgress(
                entity(status = "DOWNLOADING", bytes = 4000L, total = 10000L, speed = spd, sizeKind = "EXACT")
            )
            assertNotNull(res.progress)
            assertNull("Zero or negative speed must yield null validSpeed", res.validSpeed)
            assertNull("Zero or negative speed must yield null remainingSeconds", res.remainingSeconds)
        }

        // Non-downloading processing & waiting states must yield null progress, percent, speed, and ETA
        listOf("PROCESSING", "WAITING_FOR_PROCESSOR", "MERGING", "CONVERTING", "SAVING", "PAUSED", "QUEUED", "COMPLETED").forEach { st ->
            val res = DownloadsPresentation.computeProgress(
                entity(status = st, bytes = 4000L, total = 10000L, speed = 2000L, sizeKind = "EXACT")
            )
            assertNull("Non-downloading state $st must yield null progress", res.progress)
            assertNull("Non-downloading state $st must yield null percent", res.percent)
            assertNull("Non-downloading state $st must yield null validSpeed", res.validSpeed)
            assertNull("Non-downloading state $st must yield null remainingSeconds", res.remainingSeconds)
        }
    }
}
