package com.zdownloader.app.engine

import android.os.PowerManager
import kotlinx.coroutines.*

internal data class WakeLeaseTiming(val timeoutMillis:Long=15*60_000L,val renewalMillis:Long=5*60_000L) {
    init {require(renewalMillis>0&&timeoutMillis>renewalMillis)}
}

/** Bounded CPU ownership renews only while the admitted request is alive. */
internal suspend fun <T> withRenewableWakeLock(lock:PowerManager.WakeLock,timing:WakeLeaseTiming=WakeLeaseTiming(),work:suspend ()->T):T=coroutineScope {
    lock.setReferenceCounted(false)
    lock.acquire(timing.timeoutMillis)
    val renewal=launch {while(isActive) {delay(timing.renewalMillis);lock.acquire(timing.timeoutMillis)}}
    try {work()}
    finally {withContext(NonCancellable) {renewal.cancelAndJoin();if(lock.isHeld) lock.release()}}
}
