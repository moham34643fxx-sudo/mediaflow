package com.zdownloader.app.adblock

import org.junit.Assert.assertEquals
import org.junit.Test

class YoutubeAdRecoveryPolicyTest {
    @Test
    fun inactivePolicyDoesNothing() {
        assertEquals(
            YoutubeAdRecoveryPolicy.Action.NONE,
            policy().update(false, false, true, false, false, 0).action
        )
    }

    @Test
    fun unavailableSkipControlWaitsWithoutSpendingAttempts() {
        val decision = policy().update(true, true, true, false, false, 0)
        assertEquals(YoutubeAdRecoveryPolicy.Action.WAIT, decision.action)
        assertEquals(0, decision.attempt)
    }

    @Test
    fun usableSkipControlCanBeClickedOnFirstObservation() {
        val decision = policy().update(true, true, true, true, false, 0)
        assertEquals(YoutubeAdRecoveryPolicy.Action.CLICK_SKIP, decision.action)
        assertEquals(1, decision.attempt)
    }

    @Test
    fun retryWaitsUntilBackoffExpires() {
        val recovery = policy()
        recovery.update(true, true, true, true, false, 0)
        assertEquals(
            YoutubeAdRecoveryPolicy.Action.WAIT,
            recovery.update(true, true, true, true, false, 999).action
        )
    }

    @Test
    fun retryAfterBackoffHasAnIncrementedAttemptNumber() {
        val recovery = policy()
        recovery.update(true, true, true, true, false, 0)
        val decision = recovery.update(true, true, true, true, false, 1_000)
        assertEquals(YoutubeAdRecoveryPolicy.Action.CLICK_SKIP, decision.action)
        assertEquals(2, decision.attempt)
    }

    @Test
    fun firstUsableSkipAfterObservedLongPrerollGetsFirstAttempt() {
        val recovery = policy()
        recovery.update(true, false, false, false, false, 0)
        assertEquals(
            YoutubeAdRecoveryPolicy.Action.WAIT,
            recovery.update(true, false, false, false, false, 92_000).action
        )
        val decision = recovery.update(true, false, true, true, false, 93_000)
        assertEquals(YoutubeAdRecoveryPolicy.Action.CLICK_SKIP, decision.action)
        assertEquals(1, decision.attempt)
    }

    @Test
    fun lateFirstClickStartsItsOwnTwelveSecondRetryWindow() {
        val recovery = policy()
        recovery.update(true, false, false, false, false, 0)
        recovery.update(true, false, true, true, false, 93_000)
        assertEquals(
            YoutubeAdRecoveryPolicy.Action.WAIT,
            recovery.update(true, false, true, false, false, 104_999).action
        )
        val decision = recovery.update(true, false, true, true, false, 105_000)
        assertEquals(YoutubeAdRecoveryPolicy.Action.GIVE_UP, decision.action)
        assertEquals(1, decision.attempt)
    }

    @Test
    fun attemptBudgetEndsWithGiveUp() {
        val recovery = YoutubeAdRecoveryPolicy(maxSkipAttempts = 2)
        assertEquals(
            YoutubeAdRecoveryPolicy.Action.CLICK_SKIP,
            recovery.update(true, true, true, true, false, 0).action
        )
        assertEquals(
            YoutubeAdRecoveryPolicy.Action.CLICK_SKIP,
            recovery.update(true, true, true, true, false, 1_000).action
        )
        assertEquals(
            YoutubeAdRecoveryPolicy.Action.GIVE_UP,
            recovery.update(true, true, true, true, false, 2_000).action
        )
    }

    @Test
    fun exhaustedPolicyDoesNotRetry() {
        val recovery = YoutubeAdRecoveryPolicy(maxSkipAttempts = 1)
        recovery.update(true, true, true, true, false, 0)
        assertEquals(
            YoutubeAdRecoveryPolicy.Action.GIVE_UP,
            recovery.update(true, true, true, true, false, 1_000).action
        )
        assertEquals(
            YoutubeAdRecoveryPolicy.Action.STOP,
            recovery.update(true, true, true, true, false, 1_001).action
        )
    }

    @Test
    fun durationLimitEndsRecoveryEvenWhenSkipControlIsUnavailable() {
        val recovery = YoutubeAdRecoveryPolicy(maxObservationMillis = 2_000)
        recovery.update(true, true, true, false, false, 0)
        assertEquals(
            YoutubeAdRecoveryPolicy.Action.GIVE_UP,
            recovery.update(true, true, true, false, false, 2_000).action
        )
    }

    @Test
    fun durationLimitEndsRecoveryEvenWhenSkipControlIsVisible() {
        val recovery = YoutubeAdRecoveryPolicy(maxObservationMillis = 2_000)
        recovery.update(true, true, true, false, false, 0)
        assertEquals(
            YoutubeAdRecoveryPolicy.Action.GIVE_UP,
            recovery.update(true, true, true, true, false, 2_000).action
        )
    }

    @Test
    fun previouslyPlayingContentResumesAfterAdWhenStillPaused() {
        val recovery = policy()
        recovery.update(true, true, true, false, false, 0)
        assertEquals(
            YoutubeAdRecoveryPolicy.Action.RESUME,
            recovery.update(false, true, true, false, false, 500).action
        )
    }

    @Test
    fun contentAlreadyPlayingAfterAdIsNotRestarted() {
        val recovery = policy()
        recovery.update(true, true, true, false, false, 0)
        assertEquals(
            YoutubeAdRecoveryPolicy.Action.NONE,
            recovery.update(false, true, false, false, false, 500).action
        )
    }

    @Test
    fun contentThatWasPausedBeforeAdRemainsPaused() {
        val recovery = policy()
        recovery.update(true, false, true, false, false, 0)
        assertEquals(
            YoutubeAdRecoveryPolicy.Action.NONE,
            recovery.update(false, false, true, false, false, 500).action
        )
    }

    @Test
    fun userInteractionDuringAdPreventsAutomaticSkipAndResume() {
        val recovery = policy()
        recovery.update(true, true, true, false, false, 0)
        assertEquals(
            YoutubeAdRecoveryPolicy.Action.STOP,
            recovery.update(true, true, true, true, true, 93_000).action
        )
        assertEquals(
            YoutubeAdRecoveryPolicy.Action.STOP,
            recovery.update(true, true, true, true, false, 94_000).action
        )
        assertEquals(
            YoutubeAdRecoveryPolicy.Action.NONE,
            recovery.update(false, true, true, false, false, 95_000).action
        )
    }

    @Test
    fun userInteractionOnAdTransitionPreventsAutomaticResume() {
        val recovery = policy()
        recovery.update(true, true, true, false, false, 0)
        assertEquals(
            YoutubeAdRecoveryPolicy.Action.NONE,
            recovery.update(false, true, true, false, true, 500).action
        )
    }

    private fun policy() = YoutubeAdRecoveryPolicy()
}
