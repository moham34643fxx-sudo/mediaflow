package com.zdownloader.app.engine

import org.junit.Assert.*
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import java.io.IOException

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
    fun facebookHtmlReadsEnforceDeclaredAndStreamingByteLimits() {
        val mediaType = "text/html; charset=utf-8".toMediaType()
        assertEquals("12345", FacebookScraper.readBoundedHtml("12345".toByteArray().toResponseBody(mediaType), 5))
        assertTrue(runCatching {
            FacebookScraper.readBoundedHtml("123456".toByteArray().toResponseBody(mediaType), 5)
        }.exceptionOrNull() is IOException)

        val unknownLengthBody = object : ResponseBody() {
            private val content = Buffer().writeUtf8("123456")
            override fun contentType() = mediaType
            override fun contentLength() = -1L
            override fun source(): BufferedSource = content
        }
        val error = runCatching { FacebookScraper.readBoundedHtml(unknownLengthBody, 5) }.exceptionOrNull()
        assertTrue(error is IOException)
    }

    @Test
    fun facebookSessionCookiesAreLookedUpPerHttpsFacebookHost() {
        val lookups = mutableListOf<String>()
        val jar = FacebookSessionCookieJar { url ->
            lookups += url.toString()
            "c_user=${url.host}; xs=session"
        }
        val mobile = "https://m.facebook.com/watch/".toHttpUrl()
        val mobileCookies = jar.loadForRequest(mobile)
        assertEquals(2, mobileCookies.size)
        assertTrue(mobileCookies.all { it.matches(mobile) })

        val www = "https://www.facebook.com/watch/".toHttpUrl()
        val wwwCookies = jar.loadForRequest(www)
        assertEquals("www.facebook.com", wwwCookies.first().value)
        assertTrue(wwwCookies.all { it.matches(www) })
        assertFalse(mobileCookies.any { it.matches(www) })

        val attacker = "https://facebook.com.attacker.example/".toHttpUrl()
        assertTrue(jar.loadForRequest(attacker).isEmpty())
        assertTrue(jar.loadForRequest("https://attacker.facebook.com/".toHttpUrl()).isEmpty())
        assertEquals(2, lookups.size)
        assertTrue(FacebookSessionManager.isFacebookCookieUrl("https://facebook.com/video"))
        assertTrue(FacebookSessionManager.isFacebookCookieUrl("https://facebook.com:443/video"))
        assertFalse(FacebookSessionManager.isFacebookCookieUrl("http://m.facebook.com/video"))
        assertFalse(FacebookSessionManager.isFacebookCookieUrl("https://facebook.com:444/video"))
        assertFalse(FacebookSessionManager.isFacebookCookieUrl("https://user@facebook.com/video"))
        assertFalse(FacebookSessionManager.isFacebookCookieUrl("https://facebook.com.attacker.example/video"))
        assertFalse(FacebookSessionManager.isFacebookCookieUrl("https://attacker.facebook.com/video"))
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
        for(progressive in listOf(hdFmt, sdFmt)) {
            assertTrue(progressive.isNull("width"))
            assertTrue(progressive.isNull("height"))
            assertTrue(progressive.isNull("mediaflow_source_quality_label"))
        }
    }

    @Test
    fun missingDimensionsAndProgressiveBitrateAreNotInvented() {
        val html = """
            <MPD mediaPresentationDuration="PT30S">
                <Representation id="1" codecs="avc1" height="360" FBQualityLabel="480p">
                    <BaseURL>https://video.xx.fbcdn.net/v/partial.mp4</BaseURL>
                </Representation>
                <Representation id="2" codecs="avc1">
                    <BaseURL>https://video.xx.fbcdn.net/v/unrated.mp4</BaseURL>
                </Representation>
            </MPD>
            <script>{"browser_native_hd_url":"https://video.xx.fbcdn.net/v/hd.mp4"}</script>
        """.trimIndent()
        val formats = checkNotNull(FacebookScraper.parseHtml(html, "https://www.facebook.com/reel/12345", "test"))
            .getJSONArray("formats")
        val byId = (0 until formats.length()).map(formats::getJSONObject).associateBy { it.getString("format_id") }
        val partial = byId.getValue("dash_480p_1")
        assertEquals(360, partial.getInt("height"))
        assertTrue(partial.isNull("width"))
        assertEquals("480p", partial.getString("mediaflow_source_quality_label"))
        val unrated = byId.getValue("dash_video_2")
        assertTrue(unrated.isNull("height"))
        assertTrue(unrated.isNull("mediaflow_source_quality_label"))
        val hd = byId.getValue("hd")
        assertTrue(hd.isNull("filesize_approx"))
        assertTrue(hd.isNull("tbr"))
    }

    @Test
    fun ignoresDashDocumentTypeWithoutDroppingTheProgressiveSource() {
        val manifest = org.json.JSONObject.quote(
            """<!DOCTYPE MPD><MPD><Representation id="1" codecs="avc1">
                <BaseURL>https://video.xx.fbcdn.net/v/dash.mp4</BaseURL>
                </Representation></MPD>"""
        )
        val html = """<script>{"dash_manifest":$manifest,
            "browser_native_sd_url":"https://video.xx.fbcdn.net/v/sd.mp4"}</script>"""
        val formats = checkNotNull(FacebookScraper.parseHtml(html, "https://www.facebook.com/reel/12345", "test"))
            .getJSONArray("formats")
        assertEquals(1, formats.length())
        assertEquals("sd", formats.getJSONObject(0).getString("format_id"))
    }

    @Test
    fun parseHtmlExtractsReorderedFacebookMetadataAndResolvesImageUrl() {
        val html = """
            <meta content="عنوان الفيديو &amp; المزيد" property="og:title">
            <meta content="//scontent.xx.fbcdn.net/v/cover.jpg?token=a&amp;edge=1" property="og:image">
            <script>var data = {"browser_native_sd_url":"https://video.xx.fbcdn.net/v/video.mp4"};</script>
        """.trimIndent()

        val parsed = FacebookScraper.parseHtml(
            html,
            "https://www.facebook.com/share/r/123456789/",
            "test_origin"
        )

        assertNotNull(parsed)
        assertEquals("عنوان الفيديو & المزيد", parsed!!.getString("title"))
        assertEquals(
            "https://scontent.xx.fbcdn.net/v/cover.jpg?token=a&edge=1",
            parsed.getString("thumbnail")
        )
    }

    @Test
    fun parseHtmlRejectsNonFacebookOrInsecureThumbnailUrls() {
        val html = """
            <meta property="og:title" content="Public title">
            <meta property="og:image" content="http://127.0.0.1/private.jpg">
            <script>var data = {"browser_native_sd_url":"https://video.xx.fbcdn.net/v/video.mp4"};</script>
        """.trimIndent()

        val parsed = FacebookScraper.parseHtml(
            html,
            "https://www.facebook.com/reel/123456789",
            "test_origin"
        )

        assertNotNull(parsed)
        assertEquals("Public title", parsed!!.getString("title"))
        assertTrue(parsed.isNull("thumbnail"))
    }

    @Test
    fun parseHtmlSkipsGenericTitleAndUnsafePreferredImage() {
        val html = """
            <meta property="og:title" content="Facebook">
            <meta name="twitter:title" content="Real title">
            <meta property="og:image:secure_url" content="http://127.0.0.1/private.jpg">
            <meta property="og:image" content="https://scontent.xx.fbcdn.net/cover.jpg">
            <script>var data = {"browser_native_sd_url":"https://video.xx.fbcdn.net/v/video.mp4"};</script>
        """.trimIndent()

        val parsed = FacebookScraper.parseHtml(
            html,
            "https://www.facebook.com/reel/123456789",
            "test_origin"
        )

        assertNotNull(parsed)
        assertEquals("Real title", parsed!!.getString("title"))
        assertEquals("https://scontent.xx.fbcdn.net/cover.jpg", parsed.getString("thumbnail"))
    }

    @Test
    fun parsePageMetadataReadsTitleAndThumbnailWithoutVideoFormats() {
        val html = """
            <title>يوميات بحر بندر &amp; المزيد | Facebook</title>
            <meta content="//scontent.xx.fbcdn.net/v/cover.jpg?token=a&amp;edge=1" property="og:image">
        """.trimIndent()

        val parsed = FacebookScraper.parsePageMetadata(
            html,
            "https://www.facebook.com/reel/123456789/"
        )

        assertNotNull(parsed)
        assertEquals("يوميات بحر بندر & المزيد", parsed!!.getString("title"))
        assertEquals(
            "https://scontent.xx.fbcdn.net/v/cover.jpg?token=a&edge=1",
            parsed.getString("thumbnail")
        )
    }

    @Test
    fun mergeFallbackMetadataReplacesOnlyGenericOrMissingFields() {
        val primary = org.json.JSONObject()
            .put("title", "Facebook Video")
            .put("thumbnail", "")
            .put("formats", org.json.JSONArray().put(org.json.JSONObject().put("format_id", "embed_720")))
        val metadata = org.json.JSONObject()
            .put("title", "Public Reel title")
            .put("thumbnail", "https://scontent.xx.fbcdn.net/cover.jpg")

        val merged = FacebookScraper.mergeFallbackMetadata(primary, metadata)

        assertEquals("Public Reel title", merged.getString("title"))
        assertEquals("https://scontent.xx.fbcdn.net/cover.jpg", merged.getString("thumbnail"))
        assertEquals("embed_720", merged.getJSONArray("formats").getJSONObject(0).getString("format_id"))
    }

    @Test
    fun mergeFallbackMetadataPreservesUsefulExistingMetadata() {
        val primary = org.json.JSONObject()
            .put("title", "Existing accurate title")
            .put("thumbnail", "https://scontent.xx.fbcdn.net/existing.jpg")
        val metadata = org.json.JSONObject()
            .put("title", "Replacement title")
            .put("thumbnail", "https://scontent.xx.fbcdn.net/replacement.jpg")

        val merged = FacebookScraper.mergeFallbackMetadata(primary, metadata)

        assertEquals("Existing accurate title", merged.getString("title"))
        assertEquals("https://scontent.xx.fbcdn.net/existing.jpg", merged.getString("thumbnail"))
    }
}
