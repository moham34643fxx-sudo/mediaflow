package com.zdownloader.app.adblock

/**
 * Bounded policy for an explicitly detected YouTube ad.
 *
 * This class never seeks media and never resumes playback unless playback was
 * observed before the ad and no trusted player interaction occurred during it.
 */
internal class YoutubeAdRecoveryPolicy(
    private val maxSkipAttempts: Int = DEFAULT_MAX_SKIP_ATTEMPTS,
    private val retryIntervalMillis: Long = DEFAULT_RETRY_INTERVAL_MILLIS,
    private val maxObservationMillis: Long = 150_000L,
    private val maxRetryWindowMillis: Long = 12_000L
) {
    enum class Action {
        NONE,
        WAIT,
        CLICK_SKIP,
        GIVE_UP,
        STOP,
        RESUME
    }

    data class Decision(
        val action: Action,
        val attempt: Int = 0,
        val reason: String
    )

    private var adActive = false
    private var adStartedAtMillis = 0L
    private var firstAttemptAtMillis = 0L
    private var lastAttemptAtMillis = 0L
    private var attempts = 0
    private var gaveUp = false
    private var wasPlayingBeforeAd = false
    private var userInteractedDuringAd = false

    init {
        require(maxSkipAttempts > 0)
        require(retryIntervalMillis > 0)
        require(maxObservationMillis >= retryIntervalMillis)
        require(maxRetryWindowMillis >= retryIntervalMillis)
    }

    @Synchronized
    fun update(
        active: Boolean,
        videoWasPlaying: Boolean,
        videoPaused: Boolean,
        skipButtonUsable: Boolean,
        userInteracted: Boolean,
        nowMillis: Long
    ): Decision {
        if (!active) {
            if (!adActive) return Decision(Action.NONE, reason = "no_active_ad")
            val shouldResume =
                wasPlayingBeforeAd && !userInteractedDuringAd && !userInteracted && videoPaused
            reset()
            return if (shouldResume) {
                Decision(Action.RESUME, reason = "previously_playing_without_user_pause")
            } else {
                Decision(Action.NONE, reason = "preserve_playback_state")
            }
        }

        if (!adActive) {
            adActive = true
            adStartedAtMillis = nowMillis
            attempts = 0
            gaveUp = false
            wasPlayingBeforeAd = videoWasPlaying
            userInteractedDuringAd = false
        }
        if (userInteracted) userInteractedDuringAd = true

        if (userInteractedDuringAd) return Decision(Action.STOP, attempts, "user_interaction")
        if (gaveUp) return Decision(Action.STOP, attempts, "retry_budget_exhausted")
        // The provider may show a usable control only after a long first creative.
        // Waiting must not spend the retry window, but still has a hard ceiling.
        val observationExpired = nowMillis - adStartedAtMillis >= maxObservationMillis
        val retryWindowExpired = attempts > 0 && nowMillis - firstAttemptAtMillis >= maxRetryWindowMillis
        if (observationExpired || retryWindowExpired || attempts >= maxSkipAttempts) {
            gaveUp = true
            val reason = if (observationExpired && attempts == 0) {
                "skip_control_wait_limit_reached"
            } else {
                "bounded_recovery_limit_reached"
            }
            return Decision(Action.GIVE_UP, attempts, reason)
        }

        val retryReady = attempts == 0 || nowMillis - lastAttemptAtMillis >= retryIntervalMillis
        if (skipButtonUsable && retryReady) {
            if (attempts == 0) firstAttemptAtMillis = nowMillis
            attempts += 1
            lastAttemptAtMillis = nowMillis
            return Decision(Action.CLICK_SKIP, attempts, "usable_skip_button")
        }
        return Decision(
            Action.WAIT,
            attempts,
            if (skipButtonUsable) "skip_retry_backoff" else "skip_button_unavailable"
        )
    }

    @Synchronized
    fun reset() {
        adActive = false
        adStartedAtMillis = 0L
        firstAttemptAtMillis = 0L
        lastAttemptAtMillis = 0L
        attempts = 0
        gaveUp = false
        wasPlayingBeforeAd = false
        userInteractedDuringAd = false
    }

    companion object {
        const val DEFAULT_MAX_SKIP_ATTEMPTS = 5
        const val DEFAULT_RETRY_INTERVAL_MILLIS = 1_000L
    }
}
