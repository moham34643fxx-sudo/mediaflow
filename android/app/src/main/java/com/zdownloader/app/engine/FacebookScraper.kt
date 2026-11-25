package com.zdownloader.app.engine

import com.zdownloader.app.domain.Media
import com.zdownloader.app.domain.UrlPolicy
import com.zdownloader.app.domain.estimateBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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
    private val numberedQuality = Regex("[1-9][0-9]{1,4}p")
    private val progressiveBitrate = Regex("[?&]bitrate=(\\d+)(?:&|$)")

    private val metaTagPattern = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE)
    private val videoTagPattern = Pattern.compile("<video\\b[^>]*>", Pattern.CASE_INSENSITIVE)
    private val titleTagPattern = Pattern.compile("<title\\b[^>]*>([\\s\\S]{0,2048}?)</title\\s*>", Pattern.CASE_INSENSITIVE)
    private val titleWhitespacePattern = Pattern.compile("\\s+")
    private val facebookTitleSuffixPattern = Pattern.compile("\\s*\\|\\s*Facebook$", Pattern.CASE_INSENSITIVE)
    private val htmlAttributePatterns = listOf("property", "name", "content", "poster").associateWith { name ->
        Pattern.compile(
            "(?:^|\\s)" + Pattern.quote(name) + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
            Pattern.CASE_INSENSITIVE
        )
    }
    private val genericTitles = setOf(
        "facebook", "facebook video", "facebook watch", "watch | facebook", "log in or sign up"
    )

    private val httpClient: OkHttpClient by lazy {
        Transfer.networkClient.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val sessionHttpClient: OkHttpClient by lazy {
        Transfer.networkClient.newBuilder()
            .cookieJar(FacebookSessionCookieJar { url ->
                FacebookSessionManager.getFacebookCookiesForUrl(url.toString())
            })
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

    private const val MAX_FACEBOOK_HTML_BYTES = 8 * 1024 * 1024

    internal fun readBoundedHtml(body: ResponseBody?, maxBytes: Int = MAX_FACEBOOK_HTML_BYTES): String {
        if (body == null) return ""
        require(maxBytes >= 0)
        if (body.contentLength() > maxBytes.toLong()) throw IOException("Facebook response exceeds size limit")
        val source = body.source()
        if (source.request(maxBytes.toLong() + 1)) throw IOException("Facebook response exceeds size limit")
        return source.readByteArray().toResponseBody(body.contentType()).string()
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
                    val html = readBoundedHtml(response.body)
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

    private fun htmlAttribute(tag: String, name: String): String? {
        val pattern = htmlAttributePatterns[name.lowercase(Locale.ROOT)] ?: return null
        val matcher = pattern.matcher(tag)
        if (!matcher.find()) return null
        return (1..3).firstNotNullOfOrNull { index -> matcher.group(index) }
    }

    private fun metaContents(html: String, names: Set<String>): Map<String, String> {
        val tags = metaTagPattern.matcher(html)
        val values = mutableMapOf<String, String>()
        while (tags.find()) {
            val tag = tags.group()
            val name = (htmlAttribute(tag, "property") ?: htmlAttribute(tag, "name"))
                ?.lowercase(Locale.ROOT) ?: continue
            if (name !in names || name in values) continue
            htmlAttribute(tag, "content")?.let { values[name] = it }
        }
        return values
    }

    private fun safeThumbnail(raw: String?, baseUrl: String): String? {
        val value = raw?.let(::cleanHtmlText)?.let(::unescape)?.trim().orEmpty()
        if (value.isBlank() || value.length > 4096) return null
        return try {
            val resolved = URI(baseUrl).resolve(value)
            val normalized = UrlPolicy.normalize(resolved.toString())
            normalized.takeIf(::isSafeFacebookThumbnailUrl)
        } catch (_: java.net.URISyntaxException) {
            null
        } catch (_: com.zdownloader.app.domain.MediaException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun isSafeFacebookThumbnailUrl(url: String): Boolean {
        val uri = URI(url)
        val host = uri.host?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: return false
        val facebookHost = listOf("facebook.com", "fbcdn.net", "fbsbx.com")
            .any { host == it || host.endsWith(".$it") }
        return uri.scheme.equals("https", true) && (uri.port == -1 || uri.port == 443) && facebookHost
    }

    private fun usefulTitle(raw: String?): String? {
        val normalized = raw?.let(::cleanHtmlText)
            ?.let { titleWhitespacePattern.matcher(it).replaceAll(" ") }?.trim()
            ?.let { facebookTitleSuffixPattern.matcher(it).replaceFirst("") }
            ?.trim()
            ?: return null
        return normalized.takeIf {
            it.isNotBlank() && it.lowercase(Locale.ROOT) !in genericTitles
        }?.take(240)
    }

    private fun pageTitle(html: String): String {
        val meta = metaContents(html, setOf("og:title", "twitter:title"))
        val metaTitle = listOf("og:title", "twitter:title")
            .firstNotNullOfOrNull { key -> usefulTitle(meta[key]) }
        if (metaTitle != null) return metaTitle
        val matcher = titleTagPattern.matcher(html)
        return usefulTitle(if (matcher.find()) matcher.group(1).orEmpty() else null) ?: "Facebook Video"
    }

    private fun hasUsefulTitle(raw: String?): Boolean = usefulTitle(raw) != null

    internal fun parsePageMetadata(html: String, canonicalUrl: String): JSONObject? {
        if (html.isBlank()) return null
        val metadata = JSONObject()
        val title = pageTitle(html)
        if (hasUsefulTitle(title)) metadata.put("title", title)
        pageThumbnail(html, canonicalUrl)?.let { metadata.put("thumbnail", it) }
        return metadata.takeIf { it.length() > 0 }
    }

    internal fun mergeFallbackMetadata(primary: JSONObject, metadata: JSONObject): JSONObject {
        val merged = JSONObject(primary.toString())
        val primaryTitle = primary.optString("title").takeIf { it.isNotBlank() }
        val metadataTitle = metadata.optString("title").takeIf { it.isNotBlank() }
        if (!hasUsefulTitle(primaryTitle) && hasUsefulTitle(metadataTitle)) {
            merged.put("title", metadataTitle)
        }

        val primaryThumbnail = primary.optString("thumbnail").takeIf { it.isNotBlank() }
        val metadataThumbnail = metadata.optString("thumbnail").takeIf { it.isNotBlank() }
        if (primaryThumbnail == null && metadataThumbnail != null) {
            merged.put("thumbnail", metadataThumbnail)
        }
        return merged
    }

    private fun pageThumbnail(html: String, baseUrl: String): String? {
        val meta = metaContents(
            html,
            setOf("og:image:secure_url", "og:image", "twitter:image", "twitter:image:src")
        )
        val thumbnail = listOf("og:image:secure_url", "og:image", "twitter:image", "twitter:image:src")
            .firstNotNullOfOrNull { key -> meta[key]?.let { safeThumbnail(it, baseUrl) } }
        if (thumbnail != null) return thumbnail
        val matcher = videoTagPattern.matcher(html)
        val poster = if (matcher.find()) htmlAttribute(matcher.group(), "poster") else null
        return safeThumbnail(poster, baseUrl)
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
                    val html = readBoundedHtml(response.body)
                    parseHtml(html, canonicalUrl, "embed")
                } else null
            }
        }.getOrNull()
    }

    private fun mobilePageRequest(canonicalUrl: String): Request {
        val uri = URI(canonicalUrl)
        val mUrl = "https://m.facebook.com" + uri.path + (uri.query?.let { "?$it" } ?: "")
        return Request.Builder()
            .url(mUrl)
            .header("User-Agent", MOBILE_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Mode", "navigate")
            .build()
    }

    private suspend fun extractFromMobile(canonicalUrl: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            sessionHttpClient.newCall(mobilePageRequest(canonicalUrl)).execute().use { response ->
                if (response.isSuccessful) {
                    val html = readBoundedHtml(response.body)
                    parseHtml(html, canonicalUrl, "mobile")
                } else null
            }
        }.getOrNull()
    }

    private fun fetchPageMetadata(request: Request, canonicalUrl: String): JSONObject? {
        return try {
            sessionHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    parsePageMetadata(readBoundedHtml(response.body), canonicalUrl)
                } else null
            }
        } catch (_: IOException) {
            null
        }
    }

    private suspend fun extractMetadataFromMobile(canonicalUrl: String): JSONObject? = withContext(Dispatchers.IO) {
        val request = try {
            mobilePageRequest(canonicalUrl)
        } catch (_: java.net.URISyntaxException) {
            return@withContext null
        } catch (_: IllegalArgumentException) {
            return@withContext null
        }
        fetchPageMetadata(request, canonicalUrl)
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
                    val html = readBoundedHtml(response.body)
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

        val pageMetadata = parsePageMetadata(html, canonicalUrl)
        val title = pageMetadata?.optString("title")?.takeIf { it.isNotBlank() } ?: "Facebook Video"
        val thumbnail = pageMetadata?.optString("thumbnail")?.takeIf { it.isNotBlank() }

        val videoIdMatch = Pattern.compile("(?:reel/|watch/\\?v=|videos/|v=)(\\d+)").matcher(canonicalUrl)
        val videoId = if (videoIdMatch.find()) videoIdMatch.group(1) else canonicalUrl.hashCode().toString()

        val formats = JSONArray()
        val seenFormatIds = mutableSetOf<String>()

        // Representation metadata needs no document type or entity declarations.
        if (!dashXmlString.isNullOrBlank() && !dashXmlString.contains("<!DOCTYPE")) {
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
                    val qualityLabel = rep.getAttribute("FBQualityLabel").trim().takeIf(numberedQuality::matches)
                    val widthAttr = rep.getAttribute("width").toIntOrNull()?.takeIf { it in 1..32768 }
                    val heightAttr = rep.getAttribute("height").toIntOrNull()?.takeIf { it in 1..32768 }

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
                        val edge = heightAttr?.let { height -> widthAttr?.let { minOf(it, height) } ?: height }
                        val tier = qualityLabel ?: edge?.let { "${it}p" }
                        val fmtId = "dash_${tier ?: "video"}_$id"
                        if (seenFormatIds.add(fmtId)) {
                            val dimensionsStatus = if (widthAttr != null || heightAttr != null) "measured_header" else "unverified_label"
                            val videoObj = JSONObject()
                                .put("format_id", fmtId)
                                .put("url", baseUrl)
                                .put("ext", "mp4")
                                .put("video_ext", "mp4")
                                .put("vcodec", if (codecs.isNotBlank()) codecs else "h264")
                                .put("acodec", "none")
                                .put("width", widthAttr)
                                .put("height", heightAttr)
                                .put("vbr", if (bandwidth > 0) bandwidth / 1000.0 else 0.0)
                                .put("tbr", if (bandwidth > 0) bandwidth / 1000.0 else 0.0)
                                .put("bitrate", if (bandwidth > 0) bandwidth / 1000.0 else 0.0)
                                .put("protocol", "https")
                                .put("mediaflow_source_quality_label", qualityLabel)
                                .put("mediaflow_dimensions", dimensionsStatus)
                                .put("http_headers", JSONObject().put("user-agent", DESKTOP_UA))
                            formats.put(videoObj)
                        }
                    }
                    }
                } catch (_: Exception) { }
            }

            hdUrl?.let { formats.put(progressiveFormat("hd", it, duration)) }
            sdUrl?.let { formats.put(progressiveFormat("sd", it, duration)) }

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
        android.util.Log.i("ZAnalysis", "FacebookScraper starting")

        // Tier 1: Embed Player
        var mediaJson = extractFromEmbed(canonical)

        // Only fetch a second page when the embed succeeded but supplied placeholder metadata.
        val embeddedMedia = mediaJson
        if (embeddedMedia != null && (embeddedMedia.optJSONArray("formats")?.length() ?: 0) > 0) {
            val titleMissing = !hasUsefulTitle(embeddedMedia.optString("title"))
            val thumbnailMissing = embeddedMedia.optString("thumbnail").isBlank()
            if (titleMissing || thumbnailMissing) {
                val metadata = extractMetadataFromMobile(canonical)
                if (metadata != null) {
                    mediaJson = mergeFallbackMetadata(embeddedMedia, metadata)
                }
            }
        }

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

    private fun progressiveFormat(id: String, url: String, duration: Double?): JSONObject {
        // A DASH representation's tier and aspect ratio do not describe a different progressive URL.
        val bitrate = progressiveBitrate.find(url)?.groupValues?.get(1)?.toDoubleOrNull()
            ?.takeIf { it > 0 && it.isFinite() }?.div(1000.0)
        return JSONObject().put("format_id", id).put("url", url).put("ext", "mp4")
            .put("video_ext", "mp4").put("audio_ext", "m4a")
            .put("vcodec", "h264").put("acodec", "aac")
            .put("bitrate", bitrate).put("tbr", bitrate)
            .put("filesize_approx", estimateBytes(duration, bitrate))
            .put("protocol", "https").put("mediaflow_dimensions", "unverified_label")
            .put("http_headers", JSONObject().put("user-agent", DESKTOP_UA))
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

internal class FacebookSessionCookieJar(
    private val cookieHeader: (HttpUrl) -> String
) : CookieJar {
    // WebView remains the session store; don't persist scraper response cookies.
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!FacebookSessionManager.isFacebookCookieUrl(url.toString())) return emptyList()
        val header = cookieHeader(url)
            .takeIf { it.isNotBlank() && it.length <= MAX_HEADER_CHARS }
            ?: return emptyList()
        return header.split(';').asSequence().take(MAX_COOKIES).mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val name = part.substring(0, separator).trim()
            val value = part.substring(separator + 1).trim()
            if (name.isEmpty()) return@mapNotNull null
            try {
                Cookie.Builder().name(name).value(value)
                    .hostOnlyDomain(url.host).path("/").secure().build()
            } catch (_: IllegalArgumentException) {
                null
            }
        }.toList()
    }

    private companion object {
        const val MAX_HEADER_CHARS = 16 * 1024
        const val MAX_COOKIES = 64
    }
}
