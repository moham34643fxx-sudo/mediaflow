package com.zdownloader.app.adblock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRequestFilterTest {
    @Test
    fun emptyFetchDestinationIsAnXmlHttpRequestRegardlessOfMode() {
        val url = "https://www.youtube.com/youtubei/v1/player/ad_break?probe=1"

        assertEquals(
            "xmlhttprequest",
            BrowserRequestFilter.inferRequestType(
                mapOf("Sec-Fetch-Dest" to "empty", "Sec-Fetch-Mode" to "no-cors"),
                url
            )
        )
        assertEquals(
            "xmlhttprequest",
            BrowserRequestFilter.inferRequestType(mapOf("sec-fetch-dest" to "empty"), url)
        )
    }

    @Test
    fun explicitVideoDestinationRemainsMedia() {
        assertEquals(
            "media",
            BrowserRequestFilter.inferRequestType(
                mapOf("Sec-Fetch-Dest" to "video"),
                "https://rr.googlevideo.com/videoplayback?id=content"
            )
        )
    }

    @Test
    fun workerDestinationsAreScriptEvenWithAnExtensionlessUrl() {
        for (destination in listOf("worker", "sharedworker", "serviceworker")) {
            assertEquals(
                "script",
                BrowserRequestFilter.inferRequestType(
                    mapOf("Sec-Fetch-Dest" to destination),
                    "https://cdn.example/worker"
                )
            )
        }
    }

    @Test
    fun mainFrameNavigationPassesThroughWithoutCallingMatcher() {
        val result = decide(
            request(
                url = "https://publisher.example/article",
                isForMainFrame = true
            ),
            network = { _, _, _, _, _ -> error("Main-frame navigation is not a network subresource") },
            redirect = { _, _, _, _ -> error("Non-redirect navigation must not invoke redirect rules") }
        )
        assertFalse(result.blocked)
        assertEquals("main_frame_navigation", result.reason)
    }

    @Test
    fun matchingMainFrameRedirectIsBlockedAndAttributed() {
        val result = decide(
            request(
                url = "https://ads.example/landing",
                isForMainFrame = true,
                isRedirect = true
            ),
            redirect = { _, _, _, _ ->
                BrowserAdblock.RuleDecision(true, "filter-hash-abc", "source_location_or_hash")
            }
        )
        assertTrue(result.blocked)
        assertEquals("document", result.requestType)
        assertEquals("filter-hash-abc", result.ruleId)
        assertEquals("source_location_or_hash", result.ruleAttribution)
    }

    @Test
    fun unmatchedMainFrameRedirectPassesThrough() {
        val result = decide(
            request(
                url = "https://publisher.example/next",
                isForMainFrame = true,
                isRedirect = true
            )
        )
        assertFalse(result.blocked)
        assertEquals("redirect_allowed", result.reason)
    }

    @Test
    fun nonHttpRequestsAreNotPassedToRules() {
        val result = decide(
            request(url = "data:text/plain,fixture"),
            network = { _, _, _, _, _ -> error("Non-HTTP request must not be matched") },
            redirect = { _, _, _, _ -> error("Non-HTTP request must not be matched") }
        )
        assertFalse(result.blocked)
        assertEquals("non_http_request", result.reason)
    }

    @Test
    fun invalidPageContextFailsOpen() {
        val result = decide(
            request(url = "https://ads.example/probe.js"),
            pageUrl = "about:blank",
            network = { _, _, _, _, _ -> error("Invalid page context must not be matched") }
        )
        assertFalse(result.blocked)
        assertEquals("invalid_page_context", result.reason)
    }

    @Test
    fun extensionlessRequestsWithoutTypeEvidenceAreNotBlocked() {
        val youtubePage = "https://m.youtube.com/watch"
        val adBreakUrl = "https://m.youtube.com/youtubei/v1/player/ad_break?probe=1"
        val cases = listOf(
            PAGE_URL to request(url = "https://cdn.example/resource", headers = emptyMap()),
            youtubePage to request(url = adBreakUrl, headers = mapOf("Accept" to "*/*")),
            youtubePage to request(
                url = adBreakUrl,
                method = "POST",
                headers = mapOf("Accept" to "*/*", "Content-Type" to "application/json")
            )
        )
        for ((pageUrl, info) in cases) {
            val result = decide(info, pageUrl = pageUrl, network = { _, _, _, _, _ ->
                error("Unknown request types must not be guessed for ${info.method}")
            })
            assertFalse(result.blocked)
            assertEquals("request_type_unknown", result.reason)
        }
    }

    @Test
    fun documentSubresourceIsNotTreatedAsAnAdResource() {
        val result = decide(
            request(
                url = "https://publisher.example/frame",
                headers = mapOf("Sec-Fetch-Dest" to "document")
            ),
            network = { _, _, _, _, _ -> error("Document subresources are explicitly allowed") }
        )
        assertFalse(result.blocked)
        assertEquals("document_subresource_allowed", result.reason)
    }

    @Test
    fun mediaRequestUsesNormalRuleEvaluationInsteadOfBeingGloballySkipped() {
        var matchedType: String? = null
        val result = decide(
            request(
                url = "https://ads.example/creative.mp4",
                headers = mapOf("Sec-Fetch-Dest" to "video")
            ),
            network = { _, _, _, type, _ ->
                matchedType = type
                BrowserAdblock.RuleDecision(false, attribution = "no_match")
            }
        )
        assertEquals("media", matchedType)
        assertFalse(result.blocked)
    }

    @Test
    fun ruleMatchedMediaRequestCanBeBlocked() {
        val result = decide(
            request(
                url = "https://ads.example/creative.mp4",
                headers = mapOf("Sec-Fetch-Dest" to "video")
            ),
            network = { _, _, _, type, _ ->
                assertEquals("media", type)
                BrowserAdblock.RuleDecision(true, "filter-list-1-line-25", "source_location_or_hash")
            }
        )
        assertTrue(result.blocked)
        assertEquals("network_rule_match", result.reason)
    }

    @Test
    fun refererHeaderIsUsedAsTheRuleSource() {
        var sourceUrl: String? = null
        decide(
            request(
                url = "https://ads.example/probe.js",
                headers = mapOf(
                    "Sec-Fetch-Dest" to "script",
                    "Referer" to "https://publisher.example/article"
                )
            ),
            network = { _, _, source, _, _ ->
                sourceUrl = source
                BrowserAdblock.RuleDecision(false)
            }
        )
        assertEquals("https://publisher.example/article", sourceUrl)
    }

    @Test
    fun missingRefererFallsBackToCurrentPage() {
        var sourceUrl: String? = null
        decide(
            request(
                url = "https://ads.example/probe.js",
                headers = mapOf("Sec-Fetch-Dest" to "script")
            ),
            network = { _, _, source, _, _ ->
                sourceUrl = source
                BrowserAdblock.RuleDecision(false)
            }
        )
        assertEquals(PAGE_URL, sourceUrl)
    }

    @Test
    fun invalidHttpRefererDoesNotOverrideTheSelectedPageContext() {
        decide(
            request(
                url = "https://ads.example/probe.js",
                headers = mapOf("Sec-Fetch-Dest" to "script", "Referer" to "https://[invalid")
            ),
            network = { _, _, source, _, _ ->
                assertEquals(PAGE_URL, source)
                BrowserAdblock.RuleDecision(true)
            }
        ).also { assertTrue(it.blocked) }
    }

    @Test
    fun requestMethodIsPreservedForRuleEvaluation() {
        var method: String? = null
        decide(
            request(
                url = "https://www.youtube.com/youtubei/v1/player/ad_break",
                method = "POST",
                headers = mapOf("Sec-Fetch-Dest" to "empty")
            ),
            network = { _, _, _, _, requestMethod ->
                method = requestMethod
                BrowserAdblock.RuleDecision(true)
            }
        )
        assertEquals("POST", method)
    }

    @Test
    fun networkMatchCarriesSafeRuleAttributionInDecision() {
        val result = decide(
            request(
                url = "https://ads.example/probe.js",
                headers = mapOf("Sec-Fetch-Dest" to "script")
            ),
            network = { _, _, _, _, _ ->
                BrowserAdblock.RuleDecision(true, "filter-list-2-line-99", "source_location_or_hash")
            }
        )
        assertEquals("filter-list-2-line-99", result.ruleId)
        assertEquals("source_location_or_hash", result.ruleAttribution)
    }

    @Test
    fun redirectWithoutMainFrameFlagUsesSubresourceRules() {
        var requestType: String? = null
        val result = decide(
            request(
                url = "https://cdn.example/probe.js",
                isRedirect = true,
                headers = mapOf("Sec-Fetch-Dest" to "script")
            ),
            network = { _, _, _, type, _ ->
                requestType = type
                BrowserAdblock.RuleDecision(false)
            },
            redirect = { _, _, _, _ -> error("Only main-frame redirects use the redirect matcher") }
        )
        assertFalse(result.blocked)
        assertEquals("script", requestType)
    }

    private fun decide(
        request: BrowserRequestFilter.RequestInfo,
        pageUrl: String = PAGE_URL,
        network: (
            pageUrl: String,
            requestUrl: String,
            sourceUrl: String,
            requestType: String,
            method: String
        ) -> BrowserAdblock.RuleDecision = { _, _, _, _, _ -> BrowserAdblock.RuleDecision(false) },
        redirect: (
            pageUrl: String,
            targetUrl: String,
            sourceUrl: String,
            method: String
        ) -> BrowserAdblock.RuleDecision = { _, _, _, _ -> BrowserAdblock.RuleDecision(false) }
    ) = BrowserRequestFilter.decide(request, pageUrl, network, redirect)

    private fun request(
        url: String,
        method: String = "GET",
        isForMainFrame: Boolean = false,
        isRedirect: Boolean = false,
        headers: Map<String, String> = mapOf("Sec-Fetch-Dest" to "script")
    ) = BrowserRequestFilter.RequestInfo(
        url = url,
        method = method,
        isForMainFrame = isForMainFrame,
        isRedirect = isRedirect,
        headers = headers
    )

    private companion object {
        const val PAGE_URL = "https://publisher.example/article"
    }
}
