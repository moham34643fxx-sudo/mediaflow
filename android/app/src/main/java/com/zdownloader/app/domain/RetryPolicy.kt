package com.zdownloader.app.domain

import kotlin.random.Random

/** Bounded exponential backoff. A server's Retry-After is a minimum, not a suggestion. */
object RetryPolicy {
    fun delayMillis(attempt: Int, retryAfterMillis: Long? = null, jitter: Double = Random.nextDouble()): Long {
        val base = 5_000L * (1L shl attempt.coerceIn(0,5))
        return maxOf((base * (1 + jitter.coerceIn(0.0,1.0) * .25)).toLong(), retryAfterMillis?.coerceIn(0,3_600_000) ?: 0)
    }
}
