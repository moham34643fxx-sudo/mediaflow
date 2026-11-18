package com.zdownloader.app.engine

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SharedAnalysisTest {
    @Test fun durableAttachmentAdoptsLeaseAndLastDepartureStillCancelsProducer()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val requests=SharedAnalysis<Int>(scope);val stopped=CompletableDeferred<Unit>();var calls=0
            val load:suspend ((Int)->Unit)->Int={
                calls++
                try {awaitCancellation()} finally {stopped.complete(Unit)}
            }
            val preview=async(start=CoroutineStart.UNDISPATCHED) {requests.run("child",{},load)}
            val lease=requireNotNull(requests.retain("child"));preview.cancelAndJoin()
            val queue=async(start=CoroutineStart.UNDISPATCHED) {requests.run("child",{},AnalysisSubscriber.DURABLE,load)}
            assertFalse(stopped.isCompleted);assertEquals(1,calls)
            queue.cancelAndJoin()
            assertTrue("The UI lease must no longer keep cancelled durable work alive",stopped.isCompleted)
            lease.close()
            assertEquals(42,requests.run("child",{}) {42})
        } finally {scope.cancel()}
    }

    @Test fun retryStartsFreshWhileCancelledProducerIsStillCleaningUp()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val releaseCleanup=CompletableDeferred<Unit>()
        try {
            withTimeout(5000) {
                val requests=SharedAnalysis<Int>(scope)
                val producer=CompletableDeferred<Job>()
                val cleaningUp=CompletableDeferred<Unit>()
                val first=scope.async {
                    requests.run("same",{}) {
                        producer.complete(currentCoroutineContext().job)
                        try {awaitCancellation()}
                        finally {withContext(NonCancellable) {cleaningUp.complete(Unit);releaseCleanup.await()}}
                    }
                }
                producer.await().cancel()
                cleaningUp.await()
                assertNull(requests.retain("same"))
                assertEquals(7,requests.run("same",{}) {7})
                releaseCleanup.complete(Unit)
                first.join()
            }
        } finally {releaseCleanup.complete(Unit);scope.cancel()}
    }

    @Test fun handoffRetainsTheSameDiscoveryAcrossAZeroSubscriberGap()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val requests=SharedAnalysis<Int>(scope);val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
            val calls=AtomicInteger()
            val loader:suspend ((Int)->Unit)->Int={calls.incrementAndGet();entered.complete(Unit);finish.await();42}
            val preview=launch {requests.run("same",{},loader)};entered.await()
            val handoff=requireNotNull(requests.retain("same"))
            preview.cancelAndJoin()
            val queue=async(start=CoroutineStart.UNDISPATCHED) {requests.run("same",{},loader)}
            handoff.close();finish.complete(Unit)
            assertEquals(42,queue.await());assertEquals(1,calls.get())
        } finally {scope.cancel()}
    }
    @Test fun audioAndVideoPreferenceHandoffPreventsDuplicateExtraction()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val requests=SharedAnalysis<String>(scope)
            val entered=CompletableDeferred<Unit>()
            val finish=CompletableDeferred<Unit>()
            val callCount=AtomicInteger()
            val sharedKey="https://youtu.be/test|100|AUTOMATIC|BASE"
            val loader:suspend ((String)->Unit)->String={
                callCount.incrementAndGet()
                entered.complete(Unit)
                finish.await()
                "parsed_media_with_audio_and_video"
            }
            // 1. QuickShare starts extraction in background
            val uiJob=launch {requests.run(sharedKey,{},loader)}
            entered.await()

            // 2. User chooses audio preference (preference = -1) and taps download.
            // Handoff is acquired before cancelling UI analysis.
            val audioHandoff=requireNotNull(requests.retain(sharedKey))
            uiJob.cancelAndJoin()

            // 3. Queue claims the item and requests the exact same base metadata key
            val queueSubscriber=async(start=CoroutineStart.UNDISPATCHED) {
                requests.run(sharedKey,{},loader)
            }
            audioHandoff.close()
            finish.complete(Unit)

            // 4. Verify result is delivered and exactly ONE extraction call occurred
            assertEquals("parsed_media_with_audio_and_video", queueSubscriber.await())
            assertEquals("Audio handoff must never trigger a duplicate extraction", 1, callCount.get())
        } finally {scope.cancel()}
    }
    @Test fun failedHandoffCanReleaseItsLastOwnerImmediately()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val requests=SharedAnalysis<Int>(scope);val entered=CompletableDeferred<Unit>();val stopped=CompletableDeferred<Unit>()
            val preview=launch {requests.run("same",{}) {try {entered.complete(Unit);awaitCancellation()} finally {stopped.complete(Unit)}}}
            entered.await();val hold=requireNotNull(requests.retain("same"));preview.cancelAndJoin()
            assertFalse(stopped.isCompleted);hold.close();withTimeout(2000) {stopped.await()}
            assertNull(requests.retain("missing"))
        } finally {scope.cancel()}
    }
    @Test fun abandonedHandoffExpiresInsteadOfRunningForever()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val requests=SharedAnalysis<Int>(scope);val entered=CompletableDeferred<Unit>();val stopped=CompletableDeferred<Unit>()
            val preview=launch {requests.run("same",{}) {try {entered.complete(Unit);awaitCancellation()} finally {stopped.complete(Unit)}}}
            entered.await();requireNotNull(requests.retain("same",100));preview.cancelAndJoin()
            withTimeout(2000) {stopped.await()}
            assertEquals(7,requests.run("same",{}) {7})
        } finally {scope.cancel()}
    }
    @Test fun repeatedSharesJoinOneExtractionAndCancellingOneDoesNotCancelTheOther()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val requests=SharedAnalysis<Int>(scope);val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
            val calls=AtomicInteger()
            val loader:suspend ((Int)->Unit)->Int={publish ->calls.incrementAndGet();entered.complete(Unit);finish.await();publish(42);42}
            val first=async {requests.run("same",{},loader)};entered.await()
            val secondStarted=CompletableDeferred<Unit>()
            val second=async(start=CoroutineStart.UNDISPATCHED) {secondStarted.complete(Unit);requests.run("same",{},loader)}
            secondStarted.await();first.cancelAndJoin();finish.complete(Unit)
            assertEquals(42,second.await());assertEquals(1,calls.get())
        } finally {scope.cancel()}
    }
    @Test fun lastSubscriberCancellationStopsWorkAndNextShareCanRetry()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val requests=SharedAnalysis<Int>(scope);val entered=CompletableDeferred<Unit>();val stopped=CompletableDeferred<Unit>()
            val task=launch {requests.run("same",{}) {try {entered.complete(Unit);awaitCancellation()} finally {stopped.complete(Unit)}}}
            entered.await();task.cancelAndJoin();withTimeout(2000) {stopped.await()}
            assertEquals(7,requests.run("same",{}) {7})
        } finally {scope.cancel()}
    }
}
