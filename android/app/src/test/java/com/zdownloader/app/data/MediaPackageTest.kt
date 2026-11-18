package com.zdownloader.app.data

import org.junit.Assert.*
import org.junit.Test

class MediaPackageTest {

    @Test
    fun metadataSidecarGeneratesValidStructuredJson() {
        val meta = MediaPackageMetadata(
            title = "Nature Documentary 4K",
            sourceUrl = "https://example.com/watch?v=123",
            sourcePlatform = "example.com",
            uploader = "NatureChannel",
            durationSeconds = 360.5,
            formatId = "1080p_hevc",
            resolution = "1920x1080",
            videoCodec = "hevc",
            audioCodec = "aac",
            containerExtension = "mp4",
            downloadedAtTimestamp = 1700000000000L
        )

        val json = meta.toJson()
        assertEquals("Nature Documentary 4K", json.getString("title"))
        assertEquals("https://example.com/watch?v=123", json.getString("source_url"))
        assertEquals("NatureChannel", json.getString("uploader"))
        assertEquals(360.5, json.getDouble("duration_seconds"), 0.001)
        assertEquals("hevc", json.getString("video_codec"))
        assertEquals("mp4", json.getString("container_extension"))
        assertEquals(1, json.getInt("package_version"))
    }

    @Test
    fun assetFilenamesFollowSanitizedStandardConventions() {
        val title = "My:Awesome / Video? *Test*"
        assertEquals("My_Awesome _ Video_ _Test_.info.json", MediaPackageMetadata.sidecarFilename(title))
        assertEquals("My_Awesome _ Video_ _Test_.en.srt", MediaPackageMetadata.subtitleFilename(title, "en", "srt"))
        assertEquals("My_Awesome _ Video_ _Test_.thumb.jpg", MediaPackageMetadata.thumbnailFilename(title, "jpg"))
    }
}
