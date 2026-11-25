package com.zdownloader.app.adblock

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import java.net.URI
import java.util.Locale
import java.util.WeakHashMap

/**
 * Installs the WebView provider's process-wide Service Worker hook once.
 *
 * Requests are filtered only while an ordinary MediaFlow browser WebView for
 * the request's referrer origin is active. Auth WebViews and requests without
 * attributable page context are deliberately passed through.
 */
internal object BrowserServiceWorkerInterceptor {
    internal data class PageContext(
        val url: String,
        val authMode: Boolean
    )

    private val pageContexts = WeakHashMap<WebView, PageContext>()
    @Volatile private var installationAttempted = false
    @Volatile private var installed = false

    @Synchronized
    fun install(): Boolean {
        if (installationAttempted) return installed
        installationAttempted = true

        val basicUsage = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)
        }.getOrDefault(false)
        val canIntercept = runCatching {
            WebViewFeature.isFeatureSupported(
                WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST
            )
        }.getOrDefault(false)
        if (!supportsInterception(basicUsage, canIntercept)) {
            BrowserAdblockDiagnostics.serviceWorkerSupport(
                supported = false,
                reason = "required_webview_feature_missing"
            )
            return false
        }

        installed = runCatching {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
                object : ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(
                        request: WebResourceRequest
                    ): WebResourceResponse? =
                        runCatching { interceptRequest(request) }.getOrNull()
                }
            )
            true
        }.getOrDefault(false)
        BrowserAdblockDiagnostics.serviceWorkerSupport(
            supported = installed,
            reason = if (installed) "installed" else "provider_setup_failed"
        )
        return installed
    }

    @Synchronized
    fun registerWebView(webView: WebView, url: String, authMode: Boolean) {
        pageContexts[webView] = PageContext(url, authMode)
    }

    @Synchronized
    fun updatePage(webView: WebView, url: String) {
        val existing = pageContexts[webView] ?: return
        pageContexts[webView] = existing.copy(url = url)
    }

    @Synchronized
    fun unregisterWebView(webView: WebView) {
        pageContexts.remove(webView)
    }

    internal fun supportsInterception(
        basicUsageSupported: Boolean,
        shouldInterceptSupported: Boolean
    ): Boolean = basicUsageSupported && shouldInterceptSupported

    private fun interceptRequest(request: WebResourceRequest): WebResourceResponse? {
        val headers = request.requestHeaders
        val referer = headers.entries.firstOrNull {
            it.key.equals("Referer", ignoreCase = true)
        }?.value
        val originHeader = headers.entries.firstOrNull {
            it.key.equals("Origin", ignoreCase = true)
        }?.value
        val context = resolvePageContext(referer, originHeader) ?: run {
            BrowserAdblockDiagnostics.request(
                BrowserRequestFilter.InterceptionSource.SERVICE_WORKER,
                request.url?.toString().orEmpty(),
                BrowserRequestFilter.inferRequestType(
                    headers,
                    request.url?.toString().orEmpty()
                ),
                decision = "allow",
                reason = "no_active_page_context",
                ruleId = null,
                ruleAttribution = "not_evaluated"
            )
            return null
        }
        return BrowserRequestFilter.intercept(
            request = request,
            currentPageUrl = context,
            source = BrowserRequestFilter.InterceptionSource.SERVICE_WORKER
        )
    }

    private fun resolvePageContext(referer: String?, originHeader: String?): String? {
        val contexts = synchronized(this) { pageContexts.values.toList() }
        return selectPageContext(referer, originHeader, contexts)
    }

    internal fun selectPageContext(
        referer: String?,
        originHeader: String?,
        contexts: List<PageContext>
    ): String? {
        val refererOrigin = originOf(referer)
        val headerOrigin = originOf(originHeader)
        if (refererOrigin != null && headerOrigin != null && refererOrigin != headerOrigin) return null
        val origin = refererOrigin ?: headerOrigin ?: return null
        val matching = contexts.filter { originOf(it.url) == origin }
        if (matching.isEmpty() || matching.any(PageContext::authMode)) return null
        return if (refererOrigin != null) referer else matching.first().url
    }

    internal fun originOf(value: String?): String? = runCatching {
        if (value.isNullOrBlank()) return null
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val portNumber = uri.port
        val defaultPort = (scheme == "http" && portNumber == 80) ||
            (scheme == "https" && portNumber == 443)
        val port = portNumber.takeIf { it >= 0 && !defaultPort }?.let { ":$it" }.orEmpty()
        "$scheme://$host$port"
    }.getOrNull()
}
