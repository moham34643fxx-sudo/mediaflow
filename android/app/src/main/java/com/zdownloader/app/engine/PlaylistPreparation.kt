package com.zdownloader.app.engine

import kotlinx.coroutines.*

internal data class PlaylistPreparationTarget(val ownerId:String,val childId:String,val url:String,val network:Long?)

/** One best-effort metadata subscriber per finalizing owner. No selections or durable queue state. */
internal class PlaylistPreparation(private val scope:CoroutineScope,private val load:suspend (PlaylistPreparationTarget)->Unit):AutoCloseable {
    private var target:PlaylistPreparationTarget?=null
    private var task:Deferred<Unit>?=null
    private var attemptedOwner:String?=null
    private var handoffExpiry:Job?=null
    val currentTarget:PlaylistPreparationTarget? get()=target

    fun update(next:PlaylistPreparationTarget?,completedOwnerId:String?=null) {
        if(next==target) return
        // Publication can finish between the admission query and eligibility read.
        // Preserve the subscriber briefly rather than cancel/restart in that gap.
        if(next==null&&completedOwnerId!=null&&target?.ownerId==completedOwnerId) {
            if(handoffExpiry==null) {
                val departing=task
                handoffExpiry=scope.launch {delay(15_000);departing?.cancel()}
            }
            return
        }
        close()
        if(next==null||next.ownerId==attemptedOwner) return
        attemptedOwner=next.ownerId
        target=next
        task=scope.async {load(next)}
    }

    /** Called only after a real claim; the download now owns cancellation and any failure. */
    fun take(childId:String):Deferred<Unit>? {
        if(target?.childId!=childId) return null
        handoffExpiry?.cancel();handoffExpiry=null
        val adopted=task
        target=null;task=null
        return adopted
    }

    override fun close() {
        handoffExpiry?.cancel();handoffExpiry=null
        task?.cancel()
        target=null;task=null
    }
}

/** An expired speculative subscriber is not a failed durable attempt. Provider failures still propagate. */
internal suspend fun awaitPlaylistPreparation(prepared:Deferred<Unit>?) {
    try {prepared?.await()}
    catch(_:CancellationException) {currentCoroutineContext().ensureActive()}
}
