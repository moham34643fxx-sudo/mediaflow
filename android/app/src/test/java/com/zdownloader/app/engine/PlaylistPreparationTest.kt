package com.zdownloader.app.engine

import com.zdownloader.app.domain.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaylistPreparationTest {
    private val target=PlaylistPreparationTarget("current","next","https://example.com/child",1)
    private val media=Media(target.url,"audio","Fixture","Fixture",null,null,30.0,listOf(Format.parse(JSONObject()
        .put("format_id","audio").put("url","https://example.com/audio.m4a").put("protocol","https")
        .put("ext","m4a").put("vcodec","none").put("acodec","aac"))))

    @Test fun claimedChildAdoptsInFlightPreparationAndReusesExtractorMetadata()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val metadata=ExtractorMetadata(scope,{0L});val finish=CompletableDeferred<Unit>();var extractions=0
            val request=MetadataRequest(target.url,target.network,MetadataRoute.EXTRACTOR)
            val preparation=PlaylistPreparation(scope) {
                metadata.analyze(request) {extractions++;finish.await();media};Unit
            }
            preparation.update(target);preparation.update(target)
            val adopted=requireNotNull(preparation.take(target.childId))
            preparation.close()
            val durable=async(start=CoroutineStart.UNDISPATCHED) {
                adopted.await()
                metadata.analyze(request.copy(subscriber=AnalysisSubscriber.DURABLE)) {
                    throw AssertionError("Claimed child must reuse the prepared metadata")
                }
            }
            assertFalse(durable.isCompleted);assertEquals(1,extractions)
            finish.complete(Unit)
            assertSame(media,durable.await());assertEquals(1,extractions)
        } finally {scope.cancel()}
    }

    @Test fun eligibilityOrNetworkChangeCancelsWithoutPreparingAnotherChildForTheSameOwner()=runBlocking {
        for(invalid in listOf(null,target.copy(network=2),target.copy(childId="later",url="https://example.com/later"))) {
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
            try {
                var starts=0;val stopped=CompletableDeferred<Unit>()
                val preparation=PlaylistPreparation(scope) {
                    starts++
                    try {awaitCancellation()} finally {stopped.complete(Unit)}
                }
                preparation.update(target);preparation.update(invalid)
                assertTrue(stopped.isCompleted)
                assertNull(preparation.take(target.childId))
                preparation.update(target)
                assertEquals("No speculative restart during the same finalization",1,starts)
                preparation.update(target.copy(ownerId="new-current"))
                assertEquals(2,starts)
                preparation.close()
            } finally {scope.cancel()}
        }
    }

    @Test fun preparationThrottleIsDeliveredAtClaimWithoutAnotherProviderRequest()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            var requests=0;val throttled=MediaException(ErrorCode.RATE_LIMITED,retryAfterMillis=60_000L)
            val preparation=PlaylistPreparation(scope) {requests++;throw throttled}
            preparation.update(target);preparation.update(target)
            assertNull(preparation.take("unrelated"))
            val adopted=requireNotNull(preparation.take(target.childId))
            assertSame(throttled,runCatching {adopted.await()}.exceptionOrNull())
            assertEquals(1,requests)
            preparation.close()
        } finally {scope.cancel()}
    }

    @Test fun publicationBetweenCoordinatorReadsKeepsPreparationForTheNextClaim()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val finish=CompletableDeferred<Unit>();val stopped=CompletableDeferred<Unit>();var requests=0
            val preparation=PlaylistPreparation(scope) {
                requests++
                try {finish.await()} finally {stopped.complete(Unit)}
            }
            preparation.update(target)
            preparation.update(null,completedOwnerId=target.ownerId)
            assertFalse(stopped.isCompleted)
            val adopted=requireNotNull(preparation.take(target.childId))
            preparation.close();finish.complete(Unit);adopted.await()
            assertEquals(1,requests)
        } finally {scope.cancel()}
    }

    @Test fun expiredPreparationFallsBackButRealDurableCancellationStillStops()=runBlocking {
        val expired=CompletableDeferred<Unit>().also {it.cancel()}
        awaitPlaylistPreparation(expired)
        val throttled=MediaException(ErrorCode.RATE_LIMITED)
        val failed=CompletableDeferred<Unit>().also {it.completeExceptionally(throttled)}
        assertSame(throttled,runCatching {awaitPlaylistPreparation(failed)}.exceptionOrNull())
        val unfinished=CompletableDeferred<Unit>()
        val durable=launch(start=CoroutineStart.UNDISPATCHED) {
            awaitPlaylistPreparation(unfinished)
            throw AssertionError("Cancelled durable work must not continue into extraction")
        }
        durable.cancelAndJoin()
        assertTrue(durable.isCancelled)
    }

    @Test fun stoppingClaimedChildCancelsItsAdoptedPreparationAndSharedProducer()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val metadata=ExtractorMetadata(scope,{0L});val stopped=CompletableDeferred<Unit>()
            val preparation=PlaylistPreparation(scope) {
                metadata.analyze(MetadataRequest(it.url,it.network,MetadataRoute.EXTRACTOR)) {
                    try {awaitCancellation()} finally {stopped.complete(Unit)}
                };Unit
            }
            preparation.update(target)
            val adopted=requireNotNull(preparation.take(target.childId))
            preparation.close()
            assertFalse(stopped.isCompleted)
            adopted.cancelAndJoin()
            assertTrue(stopped.isCompleted)
        } finally {scope.cancel()}
    }
}
