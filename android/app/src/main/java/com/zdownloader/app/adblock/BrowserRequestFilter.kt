package com.zdownloader.app.adblock

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Locale

internal object BrowserRequestFilter {
    enum class InterceptionSource(val logValue: String) {
        WEBVIEW("webview_client"),
        NAVIGATION("webview_navigation"),
        SERVICE_WORKER("service_worker_client")
    }

    internal data class RequestInfo(
        val url: String,
        val method: String,
        val isForMainFrame: Boolean,
        val isRedirect: Boolean,
        val headers: Map<String, String>
    )

    internal data class Decision(
        val blocked: Boolean,
        val requestType: String?,
        val reason: String,
        val ruleId: String? = null,
        val ruleAttribution: String = "none"
    )

    fun intercept(
        request: WebResourceRequest,
        currentPageUrl: String,
        source: InterceptionSource = InterceptionSource.WEBVIEW
    ): WebResourceResponse? {
        // WebView exposes the initial URL, not the destinations of redirects, here.
        if (request.isForMainFrame) return null
        return if (evaluateAndLog(request, currentPageUrl, source)) emptyBlockedResponse() else null
    }

    fun shouldBlockMainFrameRedirect(request: WebResourceRequest, currentPageUrl: String): Boolean =
        request.isForMainFrame && request.isRedirect &&
            evaluateAndLog(request, currentPageUrl, InterceptionSource.NAVIGATION)

    private fun evaluateAndLog(
        request: WebResourceRequest,
        currentPageUrl: String,
        source: InterceptionSource
    ): Boolean {
        val requestUrl = request.url?.toString().orEmpty()
        val decision = decide(
            RequestInfo(
                url = requestUrl,
                method = request.method.orEmpty(),
                isForMainFrame = request.isForMainFrame,
                isRedirect = request.isRedirect,
                headers = request.requestHeaders.orEmpty()
            ),
            currentPageUrl = currentPageUrl,
            networkMatcher = BrowserAdblock::evaluateRequest,
            redirectMatcher = BrowserAdblock::evaluateRedirect
        )
        BrowserAdblockDiagnostics.request(
            source = source,
            url = requestUrl,
            requestType = decision.requestType,
            decision = if (decision.blocked) "block" else "allow",
            reason = decision.reason,
            ruleId = decision.ruleId,
            ruleAttribution = decision.ruleAttribution
        )
        return decision.blocked
    }

    internal fun decide(
        request: RequestInfo,
        currentPageUrl: String,
        networkMatcher: (
            pageUrl: String,
            requestUrl: String,
            sourceUrl: String,
            requestType: String,
            method: String
        ) -> BrowserAdblock.RuleDecision,
        redirectMatcher: (
            pageUrl: String,
            targetUrl: String,
            sourceUrl: String,
            method: String
        ) -> BrowserAdblock.RuleDecision
    ): Decision {
        if (!isHttpUrl(request.url)) {
            return Decision(false, null, "non_http_request")
        }

        if (request.isForMainFrame) {
            val pageUrl = currentPageUrl.takeIf(::isHttpUrl)
                ?: return Decision(false, "document", "invalid_page_context")
            if (!request.isRedirect) {
                return Decision(false, "document", "main_frame_navigation")
            }
            val match = redirectMatcher(
                pageUrl,
                request.url,
                sourceUrl(request.headers, currentPageUrl),
                request.method
            )
            return Decision(
                blocked = match.blocked,
                requestType = "document",
                reason = if (match.blocked) "redirect_rule_match" else "redirect_allowed",
                ruleId = match.ruleId,
                ruleAttribution = match.attribution
            )
        }

        val pageUrl = currentPageUrl.takeIf(::isHttpUrl)
            ?: return Decision(false, null, "invalid_page_context")
        val requestType = inferRequestType(request.headers, request.url)
            ?: return Decision(false, null, "request_type_unknown")
        if (requestType == "document") {
            return Decision(false, requestType, "document_subresource_allowed")
        }
        val sourceUrl = sourceUrl(request.headers, currentPageUrl)
            .takeIf(::isHttpUrl)
            ?: return Decision(false, requestType, "invalid_source_context")
        val match = networkMatcher(
            pageUrl,
            request.url,
            sourceUrl,
            requestType,
            request.method
        )
        return Decision(
            blocked = match.blocked,
            requestType = requestType,
            reason = if (match.blocked) "network_rule_match" else "no_blocking_rule",
            ruleId = match.ruleId,
            ruleAttribution = match.attribution
        )
    }

    private fun emptyBlockedResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            204,
            "No Content",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0))
        )

    private fun sourceUrl(headers: Map<String, String>, currentPageUrl: String): String =
        headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }
            ?.value
            ?.takeIf { isHttpUrl(it) && runCatching { URI(it).host != null }.getOrDefault(false) }
            ?: currentPageUrl

    internal fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true)

    internal fun inferRequestType(headers: Map<String, String>, url: String): String? {
        val normalizedHeaders = headers.entries.associate {
            it.key.lowercase(Locale.ROOT) to it.value
        }
        val destination = normalizedHeaders["sec-fetch-dest"]
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        when (destination) {
            "document" -> return "document"
            "script", "worker", "sharedworker", "serviceworker" -> return "script"
            "style" -> return "stylesheet"
            "image" -> return "image"
            "font" -> return "font"
            "audio", "video" -> return "media"
            "iframe", "frame", "object", "embed" -> return "subdocument"
            "empty" -> return "xmlhttprequest"
        }

        val accept = normalizedHeaders["accept"]?.lowercase(Locale.ROOT).orEmpty()
        when {
            accept.contains("image/") -> return "image"
            accept.contains("text/css") -> return "stylesheet"
            accept.contains("javascript") || accept.contains("ecmascript") -> return "script"
            accept.contains("font/") || accept.contains("application/font") -> return "font"
            accept.contains("audio/") || accept.contains("video/") -> return "media"
        }

        val path = runCatching { URI(url).path.orEmpty() }
            .getOrDefault("")
            .lowercase(Locale.ROOT)
        return when {
            path.endsWith(".js") || path.endsWith(".mjs") -> "script"
            path.endsWith(".css") -> "stylesheet"
            IMAGE_EXTENSIONS.any(path::endsWith) -> "image"
            FONT_EXTENSIONS.any(path::endsWith) -> "font"
            MEDIA_EXTENSIONS.any(path::endsWith) -> "media"
            else -> null
        }
    }

    private val IMAGE_EXTENSIONS =
        setOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".avif", ".svg")
    private val FONT_EXTENSIONS = setOf(".woff", ".woff2", ".ttf", ".otf")
    private val MEDIA_EXTENSIONS =
        setOf(".mp4", ".m4v", ".webm", ".mp3", ".m4a", ".aac", ".m3u8", ".mpd")
}
