package com.zdownloader.app.ui

import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.data.BatchSummary

internal enum class DownloadFilter(val key: String) {
    ALL("all"),
    ACTIVE("active"),
    WAITING("queued"),
    COMPLETED("done"),
    FAILED("failed"),
    CANCELLED("cancelled");

    companion object {
        fun fromKey(key: String): DownloadFilter = entries.firstOrNull { it.key == key } ?: ALL
    }
}

internal data class DownloadsOverviewMetrics(
    val activeCount: Int,
    val waitingCount: Int,
    val completedCount: Int,
    val failedCount: Int,
    val totalOutputSize: Long
)

internal data class ItemProgressPresentation(
    val progress: Float?,
    val percent: Int?,
    val validSpeed: Long?,
    val remainingSeconds: Long?
)

internal object DownloadsPresentation {

    fun canContinueQueue(downloads: List<DownloadEntity>, batches: List<BatchSummary>): Boolean = downloads.any { download ->
        download.status in setOf("QUEUED", "WAITING_FOR_NETWORK", "RETRYING", "RESUMING", "INTERRUPTED") &&
            (download.batchId == null || batches.any { it.batch.id == download.batchId && it.batch.state == "ACTIVE" })
    }

    val ACTIVE_STATUSES = setOf(
        "DOWNLOADING", "DOWNLOADING_VIDEO", "DOWNLOADING_AUDIO",
        "WAITING_FOR_PROCESSOR", "PROCESSING", "MERGING", "CONVERTING", "VALIDATING", "SAVING", "PAUSING"
    )

    val WAITING_STATUSES = setOf(
        "QUEUED", "WAITING_FOR_NETWORK", "RETRYING", "PAUSED", "INTERRUPTED", "RESUMING", "SCHEDULED"
    )

    fun matchesFilter(status: String, filter: DownloadFilter): Boolean = when (filter) {
        DownloadFilter.ALL -> true
        DownloadFilter.ACTIVE -> status in ACTIVE_STATUSES
        DownloadFilter.WAITING -> status in WAITING_STATUSES
        DownloadFilter.COMPLETED -> status == "COMPLETED"
        DownloadFilter.FAILED -> status == "FAILED"
        DownloadFilter.CANCELLED -> status == "CANCELLED"
    }

    fun matchesBatchFilter(batch: BatchSummary, filter: DownloadFilter): Boolean = when (filter) {
        DownloadFilter.ALL -> true
        DownloadFilter.ACTIVE -> batch.status == "ACTIVE" && batch.active > 0
        DownloadFilter.WAITING -> batch.status in setOf("ACTIVE", "PAUSED", "SCHEDULED") && batch.active == 0 && !batch.finished
        DownloadFilter.COMPLETED -> batch.status == "COMPLETED"
        DownloadFilter.FAILED -> batch.failed > 0
        DownloadFilter.CANCELLED -> batch.status == "CANCELLED" || batch.canRemoveCancelled
    }

    fun matchesSearch(entity: DownloadEntity, query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        return entity.title.contains(q, ignoreCase = true) ||
               entity.filename.contains(q, ignoreCase = true) ||
               entity.source.contains(q, ignoreCase = true)
    }

    fun matchesBatchSearch(batch: BatchSummary, query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        return batch.batch.title.contains(q, ignoreCase = true)
    }

    fun countMatching(downloads: List<DownloadEntity>, batches: List<BatchSummary>, filter: DownloadFilter): Int =
        downloads.count { it.batchId == null && matchesFilter(it.status, filter) } +
        batches.count { matchesBatchFilter(it, filter) }

    fun filterDownloads(
        downloads: List<DownloadEntity>,
        filter: DownloadFilter,
        searchQuery: String
    ): List<DownloadEntity> = downloads.filter { it.batchId == null && matchesFilter(it.status, filter) && matchesSearch(it, searchQuery) }

    fun filterBatches(
        batches: List<BatchSummary>,
        filter: DownloadFilter,
        searchQuery: String
    ): List<BatchSummary> = batches.filter { matchesBatchFilter(it, filter) && matchesBatchSearch(it, searchQuery) }

    fun computeOverviewMetrics(downloads: List<DownloadEntity>): DownloadsOverviewMetrics {
        var active = 0
        var waiting = 0
        var completed = 0
        var failed = 0
        var totalSize = 0L

        for (item in downloads) {
            when {
                item.status == "COMPLETED" -> {
                    completed++
                    totalSize += (item.outputSize ?: 0L)
                }
                item.status in ACTIVE_STATUSES -> active++
                item.status in WAITING_STATUSES -> waiting++
                item.status == "FAILED" -> failed++
            }
        }
        return DownloadsOverviewMetrics(
            activeCount = active,
            waitingCount = waiting,
            completedCount = completed,
            failedCount = failed,
            totalOutputSize = totalSize
        )
    }

    fun computeProgress(entity: DownloadEntity): ItemProgressPresentation {
        val isDownloading = entity.status.startsWith("DOWNLOADING")
        if (!isDownloading) {
            return ItemProgressPresentation(
                progress = null,
                percent = null,
                validSpeed = null,
                remainingSeconds = null
            )
        }

        val validSpeed = entity.speed.takeIf { it > 0 }
        val isExact = entity.sizeKind == "EXACT"
        val isEstimated = entity.sizeKind != "EXACT" && entity.sizeKind != "UNKNOWN"

        if (isExact) {
            val measurable = entity.total?.takeIf { it > 0 && it >= entity.bytes }
            val progress = measurable?.let {
                (entity.bytes.toFloat() / it).coerceIn(0f, 0.99f)
            }
            val percent = measurable?.let {
                (entity.bytes.toDouble() * 100 / it).coerceIn(0.0, 99.0).toInt()
            }
            val remainingSeconds = if (validSpeed != null && measurable != null && measurable > entity.bytes) {
                val rem = ((measurable - entity.bytes) / validSpeed)
                if (rem in 1..864000) rem else null
            } else null

            return ItemProgressPresentation(
                progress = progress,
                percent = percent,
                validSpeed = validSpeed,
                remainingSeconds = remainingSeconds
            )
        }

        if (isEstimated) {
            val estimatedTotal = entity.total?.takeIf { it > 0 }
            if (estimatedTotal != null && entity.bytes < estimatedTotal) {
                val progress = (entity.bytes.toFloat() / estimatedTotal).coerceIn(0f, 0.99f)
                val percent = (entity.bytes.toDouble() * 100 / estimatedTotal).coerceIn(0.0, 99.0).toInt()
                return ItemProgressPresentation(
                    progress = progress,
                    percent = percent,
                    validSpeed = validSpeed,
                    remainingSeconds = null
                )
            }
        }

        return ItemProgressPresentation(
            progress = null,
            percent = null,
            validSpeed = validSpeed,
            remainingSeconds = null
        )
    }
}
