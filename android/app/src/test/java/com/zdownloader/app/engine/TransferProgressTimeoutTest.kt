package com.zdownloader.app.engine

import com.zdownloader.app.domain.ErrorCode
import com.zdownloader.app.domain.MediaException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TransferProgressTimeoutTest {
    @Test fun repeatingTheSameByteCountCannotKeepAStalledTransferAlive():Unit=runBlocking {
        try {
            withTimeout(5000) {
                withTransferProgressTimeout(100, {_,_->}) {progress->
                    while(true) {progress(60*1024L,null);delay(10)}
                }
            }
            fail("Expected idle transfer failure")
        } catch(error:MediaException) {assertEquals(ErrorCode.READ_TIMEOUT,error.code)}
    }
    @Test fun progressingTransfersCanOutlastTheIdleBudget():Unit=runBlocking {
        var last=0L
        val result=withTransferProgressTimeout(1000,{bytes,_->last=bytes}) {progress->
            repeat(40) {progress(it.toLong()+1,null);delay(40)}
            "complete"
        }
        assertEquals("complete",result);assertEquals(40L,last)
    }
    @Test fun explicitCancellationIsNotConvertedToANetworkFailure():Unit=runBlocking {
        val job=launch {
            withTransferProgressTimeout(1000,{_,_->}) {awaitCancellation()}
        }
        yield();job.cancelAndJoin();assertTrue(job.isCancelled)
    }
}
