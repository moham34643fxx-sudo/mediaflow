package com.zdownloader.app.domain

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The short persist-and-schedule transaction outlives the submitting screen.
 * Extraction and the actual download remain independently cancellable. */
object SubmissionHandoff {
    suspend fun <T> run(block:suspend ()->T):T=withContext(NonCancellable) {block()}
}
