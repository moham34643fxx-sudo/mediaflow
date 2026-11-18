package com.zdownloader.app.engine

import com.zdownloader.app.domain.ErrorCode
import com.zdownloader.app.domain.MediaException
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

/** Bounds lack of forward progress, not total transfer duration or file size. */
internal suspend fun <T> withTransferProgressTimeout(
    idleMillis:Long=90_000,
    progress:(Long,Long?)->Unit,
    block:suspend ((Long,Long?)->Unit)->T
):T=coroutineScope {
    require(idleMillis>0)
    val lastAdvance=AtomicLong(System.nanoTime())
    val previous=AtomicLong(-1)
    val watcher=launch {
        while(isActive) {
            delay(minOf(idleMillis,1000))
            if((System.nanoTime()-lastAdvance.get())/1_000_000>=idleMillis)
                throw MediaException(ErrorCode.READ_TIMEOUT)
        }
    }
    try {
        block {bytes,total->
            val before=previous.getAndSet(bytes)
            if(bytes>before) lastAdvance.set(System.nanoTime())
            progress(bytes,total)
        }
    } finally {watcher.cancel()}
}
