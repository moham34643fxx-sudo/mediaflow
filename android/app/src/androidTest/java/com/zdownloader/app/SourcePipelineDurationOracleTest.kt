package com.zdownloader.app

import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.Media
import com.zdownloader.app.domain.Selection
import org.junit.Assert.*
import org.junit.Test

class SourcePipelineDurationOracleTest {

    @Test
    fun positivePlaylistsParseExactFractionalDurations() {
        val cases = listOf(
            """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:630
                #EXTINF:630.000,
                segment1.ts
                #EXTINF:4.634,
                segment2.ts
                #EXT-X-ENDLIST
            """.trimIndent() to 634.634,
            """
                #EXTM3U
                #EXTINF:312.456,
                stream_b.ts
                #EXT-X-ENDLIST
            """.trimIndent() to 312.456
        )

        for ((playlist, expected) in cases) {
            val parsed = SourcePipelineDurationOracle.parseMediaPlaylist(playlist)
            assertEquals(expected, parsed, 0.000001)
        }
    }

    @Test
    fun rejectedInvalidMalformedLiveMasterAndNonfinitePlaylists() {
        val invalidCases = mapOf(
            "liveMissingEndList" to "#EXTM3U\n#EXTINF:10.0,\nseg.ts",
            "masterPlaylistTag" to "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nleaf.m3u8\n#EXT-X-ENDLIST",
            "masterIframeStreamInfTag" to "#EXTM3U\n#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=800000,URI=\"iframe.m3u8\"\n#EXT-X-ENDLIST",
            "validSegmentThenMalformedExtinf" to "#EXTM3U\n#EXTINF:10.0,\nseg1.ts\n#EXTINFbogus\nseg2.ts\n#EXT-X-ENDLIST",
            "emptyNoSegments" to "#EXTM3U\n#EXT-X-ENDLIST",
            "badExtinfFormat" to "#EXTM3U\n#EXTINF:not_a_number,\nseg.ts\n#EXT-X-ENDLIST",
            "nanDuration" to "#EXTM3U\n#EXTINF:NaN,\nseg.ts\n#EXT-X-ENDLIST",
            "infinityDuration" to "#EXTM3U\n#EXTINF:Infinity,\nseg.ts\n#EXT-X-ENDLIST",
            "negativeDuration" to "#EXTM3U\n#EXTINF:-5.0,\nseg.ts\n#EXT-X-ENDLIST",
            "missingUri" to "#EXTM3U\n#EXTINF:10.0,\n#EXT-X-ENDLIST",
            "duplicateExtinf" to "#EXTM3U\n#EXTINF:10.0,\n#EXTINF:10.0,\nseg.ts\n#EXT-X-ENDLIST",
            "uriWithoutDuration" to "#EXTM3U\nseg_without_inf.ts\n#EXT-X-ENDLIST",
            "missingM3uHeader" to "#EXTINF:10.0,\nseg.ts\n#EXT-X-ENDLIST",
            "contentAfterEndList" to "#EXTM3U\n#EXTINF:10.0,\nseg1.ts\n#EXT-X-ENDLIST\nseg2.ts"
        )

        for ((caseName, playlist) in invalidCases) {
            try {
                SourcePipelineDurationOracle.parseMediaPlaylist(playlist)
                fail("Expected IllegalArgumentException for invalid case: $caseName")
            } catch (expected: IllegalArgumentException) {
                // Expected rejection
            }
        }
    }

    @Test
    fun nonHlsResolutionReturnsCatalogMetadataWithoutPayloadInspection() {
        val nonHlsFormat = Format(
            id = "direct-https",
            ext = "mp4",
            height = 720,
            fps = 30.0,
            videoCodec = "avc1.4d401f",
            audioCodec = "mp4a.40.2",
            bytes = 1000000L,
            bitrate = 1500.0,
            protocol = "https",
            payload = "not-json"
        )
        val media = Media(
            url = "https://example.com/video",
            id = "test-video",
            title = "Test",
            source = "source",
            uploader = null,
            thumbnail = null,
            duration = 120.0,
            formats = listOf(nonHlsFormat)
        )
        val selection = Selection(nonHlsFormat, duration = 120.0)

        val result = SourcePipelineDurationOracle.resolve(media, selection)
        assertNotNull(result)
        assertEquals("CATALOG_METADATA", result!!.kind)
        assertEquals(120.0, result.expectedDuration, 0.0)
    }
}
