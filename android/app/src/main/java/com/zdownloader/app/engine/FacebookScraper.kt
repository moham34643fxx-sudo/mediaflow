package com.zdownloader.app.engine

import com.zdownloader.app.domain.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Resilient, multi-tiered Facebook media scraper.
 * Extracts direct progressive MP4 video streams without requiring any cookies or user login.
 *
 * Tiers:
 * 1. Canonical URL resolution & query parameter stripping.
 * 2. Facebook Embed Player (plugins/video.php) with Desktop browser headers.
 * 3. Mobile Web (m.facebook.com) with standard Mobile browser headers.
 * 4. Bot Crawler (facebookexternalhit/1.1) for Open Graph preview tags.
 */
object FacebookScraper {
    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val BOT_UA = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"

    private val httpClient: OkHttpClient by lazy {
        Transfer.networkClient.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val noRedirectClient: OkHttpClient by lazy {
        Transfer.networkClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    fun isFacebookUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val trimmed = url.trim()
        val host = runCatching { URI(trimmed).host?.lowercase(Locale.ROOT) }.getOrNull()
            ?: runCatching { java.net.URL(trimmed).host.lowercase(Locale.ROOT) }.getOrNull()
            ?: return false
        return host in setOf("facebook.com", "www.facebook.com", "m.facebook.com", "web.facebook.com", "fb.watch") ||
                host.endsWith(".facebook.com")
    }

    fun cleanUrl(url: String): String {
        return try {
            val uri = URI(url)
            val allowed = setOf("v", "id", "story_fbid")
            val cleanQuery = uri.rawQuery?.split('&')?.filter { part ->
                val key = part.substringBefore('=').lowercase(Locale.ROOT)
                key in allowed
            }?.joinToString("&")?.takeIf { it.isNotBlank() }
            URI(uri.scheme ?: "https", uri.authority, uri.path, cleanQuery, null).toString()
        } catch (_: Exception) {
            url
        }
    }

    suspend fun resolveCanonicalUrl(url: String): String = withContext(Dispatchers.IO) {
        val uri = runCatching { URI(url) }.getOrNull() ?: return@withContext url
        val path = uri.path ?: ""
        val isShare = path.contains("/share/") || uri.host?.contains("fb.watch") == true
        if (!isShare) return@withContext cleanUrl(url)

        // Step 1: Follow HTTP 301/302 redirects with Desktop UA (up to 5 hops)
        var currentUrl = url
        for (hop in 0 until 5) {
            try {
                val req = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Sec-Fetch-Mode", "navigate")
                    .build()
                val (statusCode, location) = noRedirectClient.newCall(req).execute().use { response ->
                    response.code to response.header("Location")
                }
                if (statusCode in listOf(301, 302, 303, 307, 308) && !location.isNullOrBlank()) {
                    val nextUrl = if (location.startsWith("http")) location else "https://www.facebook.com$location"
                    currentUrl = nextUrl
                    if (nextUrl.contains("/reel/") || nextUrl.contains("/videos/") || nextUrl.contains("/watch/")) {
                        return@withContext cleanUrl(nextUrl)
                    }
                } else {
                    break
                }
            } catch (_: Exception) {
                break
            }
        }
        if (currentUrl != url) {
            val cleaned = cleanUrl(currentUrl)
            if (cleaned.contains("/reel/") || cleaned.contains("/videos/") || cleaned.contains("/watch/")) {
                return@withContext cleaned
            }
        }

        // Step 2: Query mobile endpoint with Mobile UA to read canonical tag or reel ID
        try {
            val mUrl = "https://m.facebook.com" + uri.path + (uri.query?.let { "?$it" } ?: "")
            val req = Request.Builder()
                .url(mUrl)
                .header("User-Agent", MOBILE_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-Fetch-Mode", "navigate")
                .build()
            httpClient.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string().orEmpty()
                    val canonicalMatch = Pattern.compile("<link\\s+rel=[\"']canonical[\"']\\s+href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
                    if (canonicalMatch.find()) {
                        val c = canonicalMatch.group(1)
                        if (!c.isNullOrBlank() && c != "https://www.facebook.com/") {
                            return@withContext cleanUrl(c)
                        }
                    }
                    val reelMatch = Pattern.compile("/reel/(\\d+)").matcher(html)
                    if (reelMatch.find()) {
                        return@withContext "https://www.facebook.com/reel/${reelMatch.group(1)}/"
                    }
                }
            }
        } catch (_: Exception) { }

        cleanUrl(currentUrl)
    }

    fun unescape(input: String): String {
        if (input.isBlank()) return ""
        val s = input.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 5 < s.length && s[i + 1] == 'u') {
                val hex = s.substring(i + 2, i + 6)
                val code = hex.toIntOrNull(16)
                if (code != null) {
                    sb.append(code.toChar())
                    i += 6
                    continue
                }
            }
            sb.append(s[i])
            i++
        }
        return sb.toString()
    }

    private fun cleanHtmlText(raw: String): String {
        return runCatching {
            android.text.Html.fromHtml(raw, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
        }.getOrElse {
            unescape(raw).replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").trim()
        }
    }

    private suspend fun extractFromEmbed(canonicalUrl: String): JSONObject? = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(canonicalUrl, "UTF-8")
        val embedUrl = "https://www.facebook.com/plugins/video.php?href=$encoded"
        val req = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .build()
        runCatching {
            httpClient.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string().orEmpty()
                    parseHtml(html, canonicalUrl, "embed")
                } else null
            }
        }.getOrNull()
    }

    private suspend fun extractFromMobile(canonicalUrl: String): JSONObject? = withContext(Dispatchers.IO) {
        val uri = URI(canonicalUrl)
        val mUrl = "https://m.facebook.com" + uri.path + (uri.query?.let { "?$it" } ?: "")
        val reqBuilder = Request.Builder()
            .url(mUrl)
            .header("User-Agent", MOBILE_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Mode", "navigate")
        if (FacebookSessionManager.hasActiveSession()) {
            val cookies = FacebookSessionManager.getFacebookCookies()
            if (cookies.isNotBlank()) {
                reqBuilder.header("Cookie", cookies)
            }
        }
        val req = reqBuilder.build()
        runCatching {
            httpClient.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string().orEmpty()
                    parseHtml(html, canonicalUrl, "mobile")
                } else null
            }
        }.getOrNull()
    }

    private suspend fun extractFromBot(canonicalUrl: String): JSONObject? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(canonicalUrl)
            .header("User-Agent", BOT_UA)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        runCatching {
            httpClient.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string().orEmpty()
                    parseHtml(html, canonicalUrl, "bot")
                } else null
            }
        }.getOrNull()
    }

    internal fun parseHtml(html: String, canonicalUrl: String, origin: String): JSONObject? {
        if (html.isBlank()) return null

        var hdUrl: String? = null
        val hdPatterns = listOf(
            Pattern.compile("\"hd_src\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("hd_src\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"playable_url_quality_hd\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"browser_native_hd_url\"\\s*:\\s*\"([^\"]+)\"")
        )
        for (pat in hdPatterns) {
            val m = pat.matcher(html)
            if (m.find()) {
                val candidate = unescape(m.group(1).orEmpty())
                if (candidate.startsWith("http")) {
                    hdUrl = candidate
                    break
                }
            }
        }

        var sdUrl: String? = null
        val sdPatterns = listOf(
            Pattern.compile("\"sd_src\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("sd_src\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"playable_url\"\\s*:\\s*\"([^\"]+)\""),
            Pattern.compile("\"browser_native_sd_url\"\\s*:\\s*\"([^\"]+)\"")
        )
        for (pat in sdPatterns) {
            val m = pat.matcher(html)
            if (m.find()) {
                val candidate = unescape(m.group(1).orEmpty())
                if (candidate.startsWith("http")) {
                    sdUrl = candidate
                    break
                }
            }
        }

        if (hdUrl == null && sdUrl == null) {
            val ogMatch = Pattern.compile("<meta\\s+property=[\"']og:video(?::secure_url)?[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
            if (ogMatch.find()) {
                val candidate = unescape(ogMatch.group(1).orEmpty())
                if (candidate.startsWith("http")) {
                    sdUrl = candidate
                }
            }
        }

        // Try extracting dash_manifest JSON string or raw XML without recursive regex (prevents StackOverflow)
        var dashXmlString: String? = null
        try {
            dashXmlString = extractJsonStringAfterKey(html, "dash_manifest")
            if (dashXmlString.isNullOrBlank() && html.contains("<MPD")) {
                val mpdStart = html.indexOf("<MPD")
                val mpdEnd = html.indexOf("</MPD>", mpdStart)
                if (mpdStart >= 0 && mpdEnd > mpdStart) {
                    dashXmlString = unescape(html.substring(mpdStart, mpdEnd + 6))
                }
            }
        } catch (_: Exception) { }

        if (hdUrl == null && sdUrl == null && dashXmlString.isNullOrBlank()) return null

        var duration: Double? = null
        if (!dashXmlString.isNullOrBlank()) {
            duration = ManifestMetadata.dashDuration(dashXmlString)
        }
        if (duration == null) {
            val msMatch = Pattern.compile("\"playable_duration_in_ms\"\\s*:\\s*(\\d+)").matcher(html)
            if (msMatch.find()) {
                duration = msMatch.group(1)?.toDoubleOrNull()?.let { it / 1000.0 }
            }
        }
        if (duration == null) {
            val secMatch = Pattern.compile("\"(?:playable_duration(?:_in_sec)?|duration_in_sec)\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(html)
            if (secMatch.find()) {
                duration = secMatch.group(1)?.toDoubleOrNull()
            }
        }
        if (duration == null) {
            val metaMatch = Pattern.compile("<meta\\s+property=[\"'](?:og:)?video:duration[\"']\\s+content=[\"']([0-9]+(?:\\.[0-9]+)?)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
            if (metaMatch.find()) {
                duration = metaMatch.group(1)?.toDoubleOrNull()
            }
        }
        if (duration == null) {
            val efgMatch = Pattern.compile("\"duration_s\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(html)
            if (efgMatch.find()) {
                duration = efgMatch.group(1)?.toDoubleOrNull()
            }
        }

        var title = "Facebook Video"
        val titleMatch = Pattern.compile("<meta\\s+property=[\"']og:title[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (titleMatch.find()) {
            val t = cleanHtmlText(titleMatch.group(1).orEmpty())
            if (t.isNotBlank()) title = t
        } else {
            val tagMatch = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE).matcher(html)
            if (tagMatch.find()) {
                val t = cleanHtmlText(tagMatch.group(1).orEmpty())
                if (t.isNotBlank()) title = t
            }
        }

        var thumbnail: String? = null
        val thumbMatch = Pattern.compile("<meta\\s+property=[\"']og:image[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(html)
        if (thumbMatch.find()) {
            val th = unescape(thumbMatch.group(1).orEmpty())
            if (th.startsWith("http")) thumbnail = th
        }

        val videoIdMatch = Pattern.compile("(?:reel/|watch/\\?v=|videos/|v=)(\\d+)").matcher(canonicalUrl)
        val videoId = if (videoIdMatch.find()) videoIdMatch.group(1) else canonicalUrl.hashCode().toString()

        val formats = JSONArray()
        val seenFormatIds = mutableSetOf<String>()
        var defaultPortrait = true
        var maxManifestHdLabel = "720p"
        var maxManifestSdLabel = "360p"

        if (!dashXmlString.isNullOrBlank()) {
            try {
                val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                factory.isNamespaceAware = false
                val builder = factory.newDocumentBuilder()
                val doc = builder.parse(java.io.ByteArrayInputStream(dashXmlString.toByteArray(Charsets.UTF_8)))
                val repList = doc.getElementsByTagName("Representation")

                for (i in 0 until repList.length) {
                    val rep = repList.item(i) as? org.w3c.dom.Element ?: continue
                    val id = rep.getAttribute("id")
                    if (id.isBlank()) continue
                    val mimeType = rep.getAttribute("mimeType").lowercase(Locale.ROOT)
                    val codecs = rep.getAttribute("codecs")
                    val bandwidth = rep.getAttribute("bandwidth").toDoubleOrNull() ?: 0.0
                    val qualityLabel = rep.getAttribute("FBQualityLabel").trim()
                    val widthAttr = rep.getAttribute("width").toIntOrNull()
                    val heightAttr = rep.getAttribute("height").toIntOrNull()

                    val baseUrlNodes = rep.getElementsByTagName("BaseURL")
                    val rawBaseUrl = if (baseUrlNodes.length > 0) baseUrlNodes.item(0).textContent?.trim().orEmpty() else ""
                    val baseUrl = unescape(rawBaseUrl)
                    if (baseUrl.isBlank() || !baseUrl.startsWith("http")) continue

                    val isAudio = mimeType.contains("audio") || codecs.startsWith("mp4a") || mimeType == "audio/mp4"
                    if (isAudio) {
                        val fmtId = "dash_audio_$id"
                        if (seenFormatIds.add(fmtId)) {
                            val audioObj = JSONObject()
                                .put("format_id", fmtId)
                                .put("url", baseUrl)
                                .put("ext", "m4a")
                                .put("audio_ext", "m4a")
                                .put("vcodec", "none")
                                .put("acodec", "aac")
                                .put("abr", if (bandwidth > 0) bandwidth / 1000.0 else 128.0)
                                .put("tbr", if (bandwidth > 0) bandwidth / 1000.0 else 128.0)
                                .put("bitrate", if (bandwidth > 0) bandwidth / 1000.0 else 128.0)
                                .put("protocol", "https")
                                .put("mediaflow_source_quality_label", "Audio")
                                .put("http_headers", JSONObject().put("user-agent", DESKTOP_UA))
                            val samplingRate = rep.getAttribute("audioSamplingRate").toIntOrNull()
                            if (samplingRate != null) audioObj.put("asr", samplingRate)
                            formats.put(audioObj)
                        }
                    } else {
                        val labelTarget = if (qualityLabel.isNotBlank() && qualityLabel.endsWith("p")) {
                            qualityLabel.dropLast(1).toIntOrNull()
                        } else null

                        val isPortrait = if (widthAttr != null && heightAttr != null) heightAttr > widthAttr else true
                        defaultPortrait = isPortrait
                        val verifiedEdge = if (widthAttr != null && heightAttr != null) minOf(widthAttr, heightAttr) else (heightAttr ?: 360)
                        val assignedLabel = if (qualityLabel.isNotBlank()) qualityLabel else "${verifiedEdge}p"

                        val edgeNum = verifiedEdge
                        if (edgeNum >= 720 && edgeNum > (maxManifestHdLabel.dropLast(1).toIntOrNull() ?: 0)) {
                            maxManifestHdLabel = assignedLabel
                        } else if (edgeNum in 240..540 && edgeNum > (maxManifestSdLabel.dropLast(1).toIntOrNull() ?: 0)) {
                            maxManifestSdLabel = assignedLabel
                        }

                        val effectiveW = widthAttr ?: (if (isPortrait) verifiedEdge else (verifiedEdge * 16) / 9)
                        val effectiveH = heightAttr ?: (if (isPortrait) (verifiedEdge * 16) / 9 else verifiedEdge)

                        val fmtId = "dash_${assignedLabel}_$id"
                        if (seenFormatIds.add(fmtId)) {
                            val dimensionsStatus = if (widthAttr != null || heightAttr != null) "measured_header" else "unverified_label"
                            val videoObj = JSONObject()
                                .put("format_id", fmtId)
                                .put("url", baseUrl)
                                .put("ext", "mp4")
                                .put("video_ext", "mp4")
                                .put("vcodec", if (codecs.isNotBlank()) codecs else "h264")
                                .put("acodec", "none")
                                .put("width", effectiveW)
                                .put("height", effectiveH)
                                .put("vbr", if (bandwidth > 0) bandwidth / 1000.0 else 0.0)
                                .put("tbr", if (bandwidth > 0) bandwidth / 1000.0 else 0.0)
                                .put("bitrate", if (bandwidth > 0) bandwidth / 1000.0 else 0.0)
                                .put("protocol", "https")
                                .put("mediaflow_source_quality_label", assignedLabel)
                                .put("mediaflow_dimensions", dimensionsStatus)
                                .put("http_headers", JSONObject().put("user-agent", DESKTOP_UA))
                            formats.put(videoObj)
                        }
                    }
                    }
                } catch (_: Exception) { }
            }

            if (!hdUrl.isNullOrBlank() && seenFormatIds.add("hd")) {
                val hdHeight = maxManifestHdLabel.dropLast(1).toIntOrNull() ?: 720
                val effectiveW = if (defaultPortrait) hdHeight else (hdHeight * 16) / 9
                val effectiveH = if (defaultPortrait) (hdHeight * 16) / 9 else hdHeight
                val hdBitrate = Regex("[?&]bitrate=(\\d+)").find(hdUrl)?.groupValues?.get(1)?.toDoubleOrNull()?.div(1000.0) ?: 2000.0
                val hdApproxSize = if (duration != null && duration > 0 && hdBitrate > 0) ((duration * hdBitrate * 1000) / 8).toLong() else null
                val hdObj = JSONObject()
                    .put("format_id", "hd")
                    .put("url", hdUrl)
                    .put("ext", "mp4")
                    .put("video_ext", "mp4")
                    .put("audio_ext", "m4a")
                    .put("vcodec", "h264")
                    .put("acodec", "aac")
                    .put("width", effectiveW)
                    .put("height", effectiveH)
                    .put("bitrate", hdBitrate)
                    .put("tbr", hdBitrate)
                    .put("protocol", "https")
                    .put("mediaflow_source_quality_label", maxManifestHdLabel)
                    .put("mediaflow_dimensions", "unverified_label")
                    .put("http_headers", JSONObject().put("user-agent", DESKTOP_UA))
                if (hdApproxSize != null && hdApproxSize > 0) hdObj.put("filesize_approx", hdApproxSize)
                formats.put(hdObj)
            }

            if (!sdUrl.isNullOrBlank() && seenFormatIds.add("sd")) {
                val sdHeight = maxManifestSdLabel.dropLast(1).toIntOrNull() ?: 360
                val effectiveW = if (defaultPortrait) sdHeight else (sdHeight * 16) / 9
                val effectiveH = if (defaultPortrait) (sdHeight * 16) / 9 else sdHeight
                val sdBitrate = Regex("[?&]bitrate=(\\d+)").find(sdUrl)?.groupValues?.get(1)?.toDoubleOrNull()?.div(1000.0) ?: 800.0
                val sdApproxSize = if (duration != null && duration > 0 && sdBitrate > 0) ((duration * sdBitrate * 1000) / 8).toLong() else null
                val sdObj = JSONObject()
                    .put("format_id", "sd")
                    .put("url", sdUrl)
                    .put("ext", "mp4")
                    .put("video_ext", "mp4")
                    .put("audio_ext", "m4a")
                    .put("vcodec", "h264")
                    .put("acodec", "aac")
                    .put("width", effectiveW)
                    .put("height", effectiveH)
                    .put("bitrate", sdBitrate)
                    .put("tbr", sdBitrate)
                    .put("protocol", "https")
                    .put("mediaflow_source_quality_label", maxManifestSdLabel)
                    .put("mediaflow_dimensions", "unverified_label")
                    .put("http_headers", JSONObject().put("user-agent", DESKTOP_UA))
                if (sdApproxSize != null && sdApproxSize > 0) sdObj.put("filesize_approx", sdApproxSize)
                formats.put(sdObj)
            }

            if (formats.length() == 0) return null

            val mediaObj = JSONObject()
                .put("id", videoId)
                .put("title", title)
                .put("source", "Facebook")
                .put("thumbnail", thumbnail)
                .put("formats", formats)
                .put("scraper_origin", origin)
            if (duration != null && duration > 0.0) {
                mediaObj.put("duration", duration)
            }
            return mediaObj
    }

    suspend fun scrape(rawUrl: String): Media? = withContext(Dispatchers.IO) {
        if (!isFacebookUrl(rawUrl)) return@withContext null
        val canonical = resolveCanonicalUrl(rawUrl)
        android.util.Log.i("ZAnalysis", "FacebookScraper starting for canonical: $canonical")

        // Tier 1: Embed Player
        var mediaJson = extractFromEmbed(canonical)

        // Tier 2: Mobile Web fallback
        if (mediaJson == null || mediaJson.optJSONArray("formats")?.length() == 0) {
            mediaJson = extractFromMobile(canonical)
        }

        // Tier 3: Crawler fallback
        if (mediaJson == null || mediaJson.optJSONArray("formats")?.length() == 0) {
            mediaJson = extractFromBot(canonical)
        }

        if (mediaJson != null && (mediaJson.optJSONArray("formats")?.length() ?: 0) > 0) {
            android.util.Log.i("ZAnalysis", "FacebookScraper succeeded from origin: ${mediaJson.optString("scraper_origin")}")
            runCatching { Media.parse(mediaJson, rawUrl) }.getOrNull()
        } else {
            android.util.Log.i("ZAnalysis", "FacebookScraper tiers found no direct streams, falling back to python")
            null
        }
    }

    internal fun extractJsonStringAfterKey(html: String, key: String): String? {
        val keyIdx = html.indexOf(key)
        if (keyIdx < 0) return null
        val colonIdx = html.indexOf(':', keyIdx + key.length)
        if (colonIdx < 0) return null
        var startIdx = colonIdx + 1
        while (startIdx < html.length && html[startIdx].isWhitespace()) {
            startIdx++
        }
        if (startIdx >= html.length) return null
        val quoteChar = html[startIdx]
        if (quoteChar != '"' && quoteChar != '\'') return null
        var escaped = false
        var i = startIdx + 1
        while (i < html.length) {
            val c = html[i]
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == quoteChar) {
                val rawQuoted = html.substring(startIdx, i + 1)
                return runCatching {
                    JSONObject("{\"d\":$rawQuoted}").optString("d")
                }.getOrElse { unescape(html.substring(startIdx + 1, i)) }
            }
            i++
        }
        return null
    }
}
