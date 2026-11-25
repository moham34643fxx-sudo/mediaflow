package com.zdownloader.app.adblock

import android.util.Log
import com.zdownloader.app.BuildConfig
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/**
 * Small, privacy-safe diagnostics for the QA application identity only.
 *
 * URLs, paths, query values, request headers, cookies and account data are never
 * written. Request logging is bounded independently from player-state events.
 */
internal object BrowserAdblockDiagnostics {
    private const val QA_APPLICATION_ID = "com.zdownloader.app.candidate0965"
    private const val TAG = "MediaFlowAdblockQA"
    private const val MAX_REQUEST_EVENTS = 128
    private const val MAX_PLAYER_EVENTS = 64
    private const val MAX_COSMETIC_EVENTS = 24
    private const val MAX_POPUP_EVENTS = 8

    private val requestEvents = AtomicInteger()
    private val playerEvents = AtomicInteger()
    private val cosmeticEvents = AtomicInteger()
    private val popupEvents = AtomicInteger()
    fun isEnabledForQa(): Boolean =
        BuildConfig.APPLICATION_ID == QA_APPLICATION_ID

    private val enabled: Boolean
        get() = isEnabledForQa()

    fun request(
        source: BrowserRequestFilter.InterceptionSource,
        url: String,
        requestType: String?,
        decision: String,
        reason: String,
        ruleId: String?,
        ruleAttribution: String
    ) {
        if (!enabled) return
        if (!claimEvent(requestEvents, MAX_REQUEST_EVENTS)) return

        log(
            "event=request source=${source.logValue} " +
                "category=${requestCategory(url)} type=${safeToken(requestType ?: "unknown")} " +
                "decision=${safeToken(decision)} reason=${safeToken(reason)} " +
                "matched_rule_id=${safeToken(ruleId ?: "none")} " +
                "rule_attribution=${safeToken(ruleAttribution)}"
        )
    }

    fun serviceWorkerSupport(supported: Boolean, reason: String) {
        if (!enabled) return
        log(
            "event=service_worker_setup supported=$supported " +
                "reason=${safeToken(reason)}"
        )
    }

    enum class CosmeticLookup(val label: String) {
        MISSING_PAGE("missing_page"),
        INVALID_CONTEXT("invalid_context"),
        FILTER_DISABLED("filter_disabled"),
        ENGINE_NOT_READY("engine_not_ready"),
        QUERY_LIMIT("query_limit"),
        BASE_UNAVAILABLE("base_unavailable"),
        GENERIC_DISABLED("generic_disabled"),
        GENERIC_UNAVAILABLE("generic_unavailable"),
        NO_GENERIC_MATCH("no_generic_match"),
        GENERIC_MATCH("generic_match")
    }

    fun cosmetic(lookup: CosmeticLookup) {
        if (enabled && claimEvent(cosmeticEvents, MAX_COSMETIC_EVENTS)) {
            log("event=cosmetic result=${lookup.label}")
        }
    }

    fun popupBlocked() {
        if (enabled && claimEvent(popupEvents, MAX_POPUP_EVENTS)) {
            log("event=popup decision=block")
        }
    }

    fun playerState(active: Boolean) {
        if (!enabled) return
        playerEvent("event=player_ad_state active=$active")
    }

    fun playerAction(action: String, attempt: Int = 0, result: String = "none") {
        if (!enabled) return
        playerActionMessage(action, attempt, result)?.let(::playerEvent)
    }

    // The JavaScript bridge is callable by page scripts; character filtering
    // alone cannot prevent arbitrary (including sensitive) strings being logged.
    private val playerActionResults = mapOf(
        "ad_detected" to setOf("content_was_playing", "content_was_not_playing"),
        "skip_attempt" to setOf("usable_button_click"),
        "skip_result" to setOf("ad_cleared", "ad_still_active", "click_failed"),
        "ad_finished" to setOf("preserve_playback_state"),
        "resume_content" to setOf(
            "playback_started", "playback_rejected", "playback_requested", "playback_failed"
        ),
        "recovery_give_up" to setOf(
            "user_interaction", "bounded_recovery_limit_reached", "retry_budget_exhausted",
            "policy_unavailable", "skip_control_wait_limit_reached"
        )
    )

    internal fun playerActionMessage(action: String, attempt: Int, result: String): String? {
        if (action.length > 32 || result.length > 32 ||
            playerActionResults[action]?.contains(result) != true
        ) return null
        return "event=player_action action=$action attempt=${attempt.coerceIn(0, 16)} result=$result"
    }

    private fun playerEvent(message: String) {
        if (!claimEvent(playerEvents, MAX_PLAYER_EVENTS)) return
        log(message)
    }

    private fun claimEvent(counter: AtomicInteger, maximum: Int): Boolean {
        while (true) {
            val current = counter.get()
            if (current >= maximum) return false
            if (counter.compareAndSet(current, current + 1)) return true
        }
    }

    private fun requestCategory(value: String): String = runCatching {
        val uri = URI(value)
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty()
        when {
            host == "youtube.com" || host.endsWith(".youtube.com") ->
                if (path == "/youtubei/v1/player/ad_break") "youtube_ad_break" else "youtube"
            host == "googlevideo.com" || host.endsWith(".googlevideo.com") ->
                if (path == "/videoplayback") "googlevideo_media" else "googlevideo_other"
            else -> "other_http"
        }
    }.getOrDefault("other_http")

    private fun safeToken(value: String): String =
        value.take(72).map { char ->
            if (char.isLetterOrDigit() || char in "._:-") char else '_'
        }.joinToString("")

    private fun log(message: String) {
        // Local JVM tests run without Android's logging implementation.
        runCatching { Log.d(TAG, message) }
    }
}
