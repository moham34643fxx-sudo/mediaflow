package com.zdownloader.app.engine

import com.zdownloader.app.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*

class AnalysisOperationTest {
    @Test fun originalConsumerPatternLeavesLoadingTrueWhenSharedProducerCancels()=runBlocking {
        val producer=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        var loading=true
        try {
            val shared=SharedAnalysis<Int>(producer)
            val consumer=launch {try {shared.run("original",{}) {throw java.util.concurrent.CancellationException("producer cancelled")};loading=false} catch(error:kotlinx.coroutines.CancellationException) {throw error}}
            consumer.join()
            assertTrue("Reproduces the pre-fix loading leak",loading)
            assertTrue(consumer.isCancelled)
        } finally {producer.cancel()}
    }
    @Test fun independentlyCancelledProducerBecomesTerminalErrorWithoutCancellingSubscriber()=runBlocking {
        val producer=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val shared=SharedAnalysis<Int>(producer)
            val error=runCatching {AnalysisOperation.run {shared.run("cancelled",{}) {throw java.util.concurrent.CancellationException("producer aborted")}}}.exceptionOrNull()
            assertEquals(ErrorCode.EXTRACTION_FAILED,(error as MediaException).code)
            assertTrue(currentCoroutineContext().isActive)
            assertEquals(42,AnalysisOperation.run {shared.run("cancelled",{}) {42}})
        } finally {producer.cancel()}
    }
    @Test fun deadlineFinishesSlowWorkAndReleasesItsFinally()=runBlocking {
        val released=CompletableDeferred<Unit>()
        val error=runCatching {AnalysisOperation.run(100) {try {awaitCancellation()} finally {released.complete(Unit)}}}.exceptionOrNull()
        assertEquals(ErrorCode.EXTRACTION_TIMEOUT,(error as MediaException).code)
        assertTrue(released.isCompleted)
    }
    @Test fun ownerCancellationIsNotReportedAsNetworkFailure()=runBlocking {
        val entered=CompletableDeferred<Unit>()
        val task=async {AnalysisOperation.run {entered.complete(Unit);awaitCancellation()}}
        entered.await();task.cancelAndJoin();assertTrue(task.isCancelled)
    }
    @Test fun cancelledQueuedNativeWorkDoesNotSaturateTheBoundedExecutor()=runBlocking {
        val executor=ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,ArrayBlockingQueue(2))
        val unblock=CountDownLatch(1);val entered=CountDownLatch(1)
        executor.execute {entered.countDown();unblock.await()};assertTrue(entered.await(2,TimeUnit.SECONDS))
        try {
            repeat(10) {
                val task=async(start=CoroutineStart.UNDISPATCHED) {cancellableWorker(executor) {fail("Cancelled work ran");0}}
                task.cancelAndJoin();assertEquals(0,executor.queue.size)
            }
            unblock.countDown()
            assertEquals(9,cancellableWorker(executor) {9})
        } finally {unblock.countDown();executor.shutdownNow()}
    }
}
