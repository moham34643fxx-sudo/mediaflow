package com.zdownloader.app.data

import com.zdownloader.app.domain.FileNames
import org.json.JSONObject

/**
 * Structured metadata sidecar and asset package descriptor.
 *
 * Facilitates saving associated media components (media, subtitles, thumbnail,
 * and metadata JSON sidecar) in an organized, predictable structure.
 */
data class MediaPackageMetadata(
    val title: String,
    val sourceUrl: String,
    val sourcePlatform: String,
    val uploader: String?,
    val durationSeconds: Double?,
    val formatId: String,
    val resolution: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val containerExtension: String,
    val downloadedAtTimestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("source_url", sourceUrl)
        put("source_platform", sourcePlatform)
        uploader?.let { put("uploader", it) }
        durationSeconds?.let { put("duration_seconds", it) }
        put("format_id", formatId)
        resolution?.let { put("resolution", it) }
        videoCodec?.let { put("video_codec", it) }
        audioCodec?.let { put("audio_codec", it) }
        put("container_extension", containerExtension)
        put("downloaded_at", downloadedAtTimestamp)
        put("package_version", 1)
    }

    companion object {
        fun sidecarFilename(baseTitle: String): String =
            "${FileNames.sanitize(baseTitle)}.info.json"

        fun subtitleFilename(baseTitle: String, language: String, extension: String): String =
            "${FileNames.sanitize(baseTitle)}.$language.$extension"

        fun thumbnailFilename(baseTitle: String, extension: String): String =
            "${FileNames.sanitize(baseTitle)}.thumb.$extension"
    }
}
