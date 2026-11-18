package com.zdownloader.app.engine

import org.junit.Assert.*
import org.junit.Test

class FacebookScraperTest {

    @Test
    fun isFacebookUrlIdentifiesSupportedHosts() {
        assertTrue(FacebookScraper.isFacebookUrl("https://www.facebook.com/reel/1441356070769297"))
        assertTrue(FacebookScraper.isFacebookUrl("https://m.facebook.com/share/r/1C1sKqGS6D/"))
        assertTrue(FacebookScraper.isFacebookUrl("https://fb.watch/m6Wq_3mD4e/"))
        assertTrue(FacebookScraper.isFacebookUrl("https://web.facebook.com/watch/?v=12345"))
        assertFalse(FacebookScraper.isFacebookUrl("https://www.instagram.com/reel/123"))
        assertFalse(FacebookScraper.isFacebookUrl("https://www.tiktok.com/@user/video/123"))
        assertFalse(FacebookScraper.isFacebookUrl("https://youtube.com/watch?v=123"))
    }

    @Test
    fun cleanUrlStripsTrackingParameters() {
        val dirty = "https://www.facebook.com/reel/1441356070769297?mibextid=wwXIfr&rdid=123&share_url=abc&ref=share"
        val cleaned = FacebookScraper.cleanUrl(dirty)
        assertEquals("https://www.facebook.com/reel/1441356070769297", cleaned)

        val withVideoId = "https://www.facebook.com/watch/?v=123456&mibextid=xyz&ref=feed"
        val cleanedVideo = FacebookScraper.cleanUrl(withVideoId)
        assertEquals("https://www.facebook.com/watch/?v=123456", cleanedVideo)
    }

    @Test
    fun unescapeHandlesEscapedSlashesAndAmpersands() {
        val raw = "https:\\/\\/video.xx.fbcdn.net\\/v\\/t2?bytestart=0\\u0026byteend=1000\\u0026efg=xyz"
        val unescaped = FacebookScraper.unescape(raw)
        assertEquals("https://video.xx.fbcdn.net/v/t2?bytestart=0&byteend=1000&efg=xyz", unescaped)
    }

    @Test
    fun unescapeHandlesUnicodeCharacters() {
        val raw = "Video \\u0641\\u064a\\u062f\\u064a\\u0648"
        val unescaped = FacebookScraper.unescape(raw)
        assertEquals("Video فيديو", unescaped)
    }

    @Test
    fun parseHtmlExtractsDashManifestMultipleRepresentationsAndAudio() {
        val dashManifestXml = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" mediaPresentationDuration="PT30S">
              <Period>
                <AdaptationSet mimeType="video/mp4">
                  <Representation id="1001" bandwidth="2500000" codecs="avc1.64001f" width="720" height="1280" FBQualityLabel="720p">
                    <BaseURL>https://video.xx.fbcdn.net/v/dash_720.mp4</BaseURL>
                  </Representation>
                  <Representation id="1002" bandwidth="1500000" codecs="avc1.4d401f" width="720" height="1280" FBQualityLabel="540p">
                    <BaseURL>https://video.xx.fbcdn.net/v/dash_540.mp4</BaseURL>
                  </Representation>
                  <Representation id="1003" bandwidth="1000000" codecs="avc1.4d401f" width="720" height="1280" FBQualityLabel="480p">
                    <BaseURL>https://video.xx.fbcdn.net/v/dash_480.mp4</BaseURL>
                  </Representation>
                  <Representation id="1004" bandwidth="600000" codecs="avc1.42c01e" width="720" height="1280" FBQualityLabel="360p">
                    <BaseURL>https://video.xx.fbcdn.net/v/dash_360.mp4</BaseURL>
                  </Representation>
                  <Representation id="1005" bandwidth="300000" codecs="avc1.42c01e" width="720" height="1280" FBQualityLabel="240p">
                    <BaseURL>https://video.xx.fbcdn.net/v/dash_240.mp4</BaseURL>
                  </Representation>
                </AdaptationSet>
                <AdaptationSet mimeType="audio/mp4">
                  <Representation id="2001" bandwidth="128000" codecs="mp4a.40.2" audioSamplingRate="44100">
                    <BaseURL>https://video.xx.fbcdn.net/v/dash_audio.m4a</BaseURL>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        // Construct HTML containing dash_manifest as JSON escaped string
        val escapedDash = org.json.JSONObject.quote(dashManifestXml)
        val html = """
            <html>
              <head><title>Facebook Reel Test</title></head>
              <body>
                <script>
                  var data = {
                    "browser_native_hd_url": "https://video.xx.fbcdn.net/v/hd.mp4?bitrate=2500000",
                    "browser_native_sd_url": "https://video.xx.fbcdn.net/v/sd.mp4?bitrate=800000",
                    "dash_manifest": $escapedDash
                  };
                </script>
              </body>
            </html>
        """.trimIndent()

        val parsed = FacebookScraper.parseHtml(html, "https://www.facebook.com/reel/123456789", "test_origin")
        assertNotNull(parsed)
        assertEquals(30.0, parsed!!.getDouble("duration"), 0.001)
        val formats = parsed.getJSONArray("formats")
        assertTrue("Expected at least 7 formats (5 DASH video, 1 DASH audio, 2 progressive fallback)", formats.length() >= 7)

        val formatIds = (0 until formats.length()).map { formats.getJSONObject(it).getString("format_id") }
        assertTrue(formatIds.any { it.startsWith("dash_720p_") })
        assertTrue(formatIds.any { it.startsWith("dash_540p_") })
        assertTrue(formatIds.any { it.startsWith("dash_480p_") })
        assertTrue(formatIds.any { it.startsWith("dash_360p_") })
        assertTrue(formatIds.any { it.startsWith("dash_240p_") })
        assertTrue(formatIds.any { it.startsWith("dash_audio_") })
        assertTrue(formatIds.contains("hd"))
        assertTrue(formatIds.contains("sd"))

        // Check audio format properties
        val audioFmt = (0 until formats.length()).map { formats.getJSONObject(it) }.first { it.getString("format_id").startsWith("dash_audio_") }
        assertEquals("none", audioFmt.getString("vcodec"))
        assertEquals("aac", audioFmt.getString("acodec"))
        assertEquals("m4a", audioFmt.getString("ext"))

        // Check 720p format dimensions
        val fmt720 = (0 until formats.length()).map { formats.getJSONObject(it) }.first { it.getString("format_id").startsWith("dash_720p_") }
        assertEquals(720, fmt720.getInt("width"))
        assertEquals(1280, fmt720.getInt("height"))
        assertEquals("measured_header", fmt720.getString("mediaflow_dimensions"))

        // Check 480p format dimensions (preserves container dimensions while retaining source quality label)
        val fmt480 = (0 until formats.length()).map { formats.getJSONObject(it) }.first { it.getString("format_id").startsWith("dash_480p_") }
        assertEquals(720, fmt480.getInt("width"))
        assertEquals(1280, fmt480.getInt("height"))
        assertEquals("480p", fmt480.getString("mediaflow_source_quality_label"))
        assertEquals("measured_header", fmt480.getString("mediaflow_dimensions"))

        // Check progressive format sizes and bitrates
        val hdFmt = (0 until formats.length()).map { formats.getJSONObject(it) }.first { it.getString("format_id") == "hd" }
        assertEquals(2500.0, hdFmt.getDouble("tbr"), 0.001)
        assertTrue(hdFmt.has("filesize_approx"))
        assertTrue(hdFmt.getLong("filesize_approx") > 0)

        val sdFmt = (0 until formats.length()).map { formats.getJSONObject(it) }.first { it.getString("format_id") == "sd" }
        assertEquals(800.0, sdFmt.getDouble("tbr"), 0.001)
        assertTrue(sdFmt.has("filesize_approx"))
        assertTrue(sdFmt.getLong("filesize_approx") > 0)
    }
}
