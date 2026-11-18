package com.zdownloader.app.engine

import com.zdownloader.app.domain.ErrorCode
import com.zdownloader.app.domain.MediaException
import kotlinx.coroutines.*
import java.util.concurrent.ThreadPoolExecutor

/** Keep producer cancellation distinct from cancellation of the requesting UI/queue owner. */
internal object AnalysisOperation {
    const val DEADLINE_MS=120_000L // Existing Python extraction budget remains 90s; no shorter site timeout.
    suspend fun <T> run(timeoutMillis:Long=DEADLINE_MS,block:suspend ()->T):T = try {
        withTimeout(timeoutMillis) {block()}
    } catch(error:TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw MediaException(ErrorCode.EXTRACTION_TIMEOUT)
    } catch(error:CancellationException) {
        currentCoroutineContext().ensureActive()
        // A shared producer may cancel independently while this subscriber is still alive.
        // It must produce a terminal error rather than silently cancel the UI's analysis job.
        throw MediaException(ErrorCode.EXTRACTION_FAILED)
    }
}

internal suspend fun <T> cancellableWorker(executor:ThreadPoolExecutor,onCancel:()->Unit={},block:()->T):T = suspendCancellableCoroutine { continuation ->
    val work=Runnable {
        if(continuation.isActive) {
            val result=runCatching(block)
            if(continuation.isActive) continuation.resumeWith(result)
        }
    }
    continuation.invokeOnCancellation {synchronized(work) {executor.remove(work)};onCancel()}
    try {synchronized(work) {if(continuation.isActive) executor.execute(work)}}
    catch(_:java.util.concurrent.RejectedExecutionException) {
        if(continuation.isActive) continuation.resumeWith(Result.failure(MediaException(ErrorCode.ENGINE_BUSY)))
    }
}
