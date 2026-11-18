package com.zdownloader.app.engine

import com.zdownloader.app.domain.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercises the metadata engine used by analyzePlaylistItem; no Python, network or device. */
class ExtractorMetadataTest {
    private val url="https://example.com/playlist-child"
    private val request=MetadataRequest(url,1,MetadataRoute.EXTRACTOR)
    private fun media(source:String="https://example.com/audio",id:String="audio")=Media(
        url,id,"Fixture","Fixture",null,null,30.0,listOf(Format.parse(JSONObject()
            .put("format_id",id).put("url",source).put("protocol","https").put("ext","m4a")
            .put("vcodec","none").put("acodec","aac").put("abr",192))))

    @Test fun newPlaylistExtractionsAreSerialAndSpacedWhileCacheAndJoinsDoNotWait()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            var time=0L;val starts=mutableListOf<Long>();var calls=0
            val metadata=ExtractorMetadata(scope,{time});val admission=PlaylistMetadataAdmission({time},{time+=it})
            val cached=request.copy(url="$url/cached")
            metadata.analyze(cached) {admission.run {starts+=time;calls++;media()}}
            val finish=CompletableDeferred<Unit>()
            val load:suspend ((Media)->Unit)->Media={admission.run {starts+=time;calls++;finish.await();media()}}
            val head=async(start=CoroutineStart.UNDISPATCHED) {metadata.analyze(request,load=load)}
            val joined=async(start=CoroutineStart.UNDISPATCHED) {metadata.analyze(request,load=load)}
            val follower=async(start=CoroutineStart.UNDISPATCHED) {
                metadata.analyze(request.copy(url="$url/follower")) {admission.run {starts+=time;calls++;media()}}
            }
            assertEquals(2,calls);assertFalse(follower.isCompleted)
            metadata.analyze(cached) {throw AssertionError("Cache hit must bypass occupied admission")}
            assertEquals(1200L,time)
            finish.complete(Unit);head.await();joined.await();follower.await()
            assertEquals(listOf(0L,1200L,2400L),starts);assertEquals(3,calls)
        } finally {scope.cancel()}
    }

    @Test fun cancelledAdmissionWaiterNeverExtractsAndDoesNotLeakThePermit()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            var time=0L;val admission=PlaylistMetadataAdmission({time},{time+=it})
            val finish=CompletableDeferred<Unit>()
            val head=async(start=CoroutineStart.UNDISPATCHED) {admission.run {finish.await()}}
            val cancelled=async(start=CoroutineStart.UNDISPATCHED) {admission.run {throw AssertionError("Cancelled waiter ran")}}
            cancelled.cancelAndJoin();finish.complete(Unit);head.await()
            assertEquals(42,admission.run {42});assertEquals(1200L,time)
        } finally {scope.cancel()}
    }

    @Test fun admissionReleasesAfterFailureAndProducerCancellationWithSpacingPreserved()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            var time=0L;val admission=PlaylistMetadataAdmission({time},{time+=it})
            val failure=MediaException(ErrorCode.EXTRACTION_FAILED)
            assertSame(failure,runCatching {admission.run {throw failure}}.exceptionOrNull())
            val cancelled=scope.async {admission.run {throw CancellationException("fixture")}}
            assertTrue(runCatching {cancelled.await()}.exceptionOrNull() is CancellationException)
            assertEquals(42,admission.run {42});assertEquals(2400L,time)
        } finally {scope.cancel()}
    }

    @Test fun previewThenQueueReusesBaseMetadataButResolvesPoliciesIndependently()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val metadata=ExtractorMetadata(scope,{100L});var calls=0
            val preview=metadata.analyze(request) {calls++;media()}
            val queue=metadata.analyze(request) {calls++;media()}
            assertEquals(1,calls);assertSame(preview,queue)
            val low=PlaylistPolicy(audio=true,output="mp3",bitrate=128).resolve(preview)
            val high=PlaylistPolicy(audio=true,output="mp3",bitrate=320).resolve(queue)
            assertEquals(128,low.bitrate);assertEquals(320,high.bitrate)
            assertEquals(low.primary,high.primary)
        } finally {scope.cancel()}
    }

    @Test fun queueJoinsPreviewAndSurvivesPreviewCancellation()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val metadata=ExtractorMetadata(scope,{100L});var calls=0
            val finish=CompletableDeferred<Unit>();val result=media()
            val load:suspend ((Media)->Unit)->Media={calls++;finish.await();result}
            val preview=async(start=CoroutineStart.UNDISPATCHED) {metadata.analyze(request,load=load)}
            val queue=async(start=CoroutineStart.UNDISPATCHED) {metadata.analyze(request.copy(subscriber=AnalysisSubscriber.DURABLE),load=load)}
            preview.cancelAndJoin();finish.complete(Unit)
            assertSame(result,queue.await());assertEquals(1,calls)
        } finally {scope.cancel()}
    }

    @Test fun lastSubscriberCancelsProducerAndRetryStartsFresh()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val metadata=ExtractorMetadata(scope,{100L});val stopped=CompletableDeferred<Unit>()
            val preview=async(start=CoroutineStart.UNDISPATCHED) {
                metadata.analyze(request) {try {awaitCancellation()} finally {stopped.complete(Unit)}}
            }
            preview.cancelAndJoin();assertTrue(stopped.isCompleted)
            val fresh=media(id="retry")
            assertSame(fresh,metadata.analyze(request) {fresh})
        } finally {scope.cancel()}
    }

    @Test fun ageBoundRequiresFreshExtractionAt45Seconds()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            var time=0L;var calls=0;val metadata=ExtractorMetadata(scope,{time})
            val first=metadata.analyze(request) {calls++;media()}
            time=44_999
            assertSame(first,metadata.analyze(request) {calls++;media()})
            time=45_000
            metadata.analyze(request) {calls++;media()}
            assertEquals(2,calls)
        } finally {scope.cancel()}
    }

    @Test fun signedUrlExpiryMarginRequiresFreshExtraction()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            var now=System.currentTimeMillis();val expiry=now/1000+120
            val metadata=ExtractorMetadata(scope,{0L},{now});var calls=0
            metadata.analyze(request) {calls++;media("https://example.com/audio?expire=$expiry")}
            now=expiry*1000-14_000
            metadata.analyze(request) {calls++;media()}
            assertEquals(2,calls)
        } finally {scope.cancel()}
    }

    @Test fun networkRouteAndEnrichmentRemainSeparateCacheIdentities()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val metadata=ExtractorMetadata(scope,{0L});var calls=0
            val load:suspend ((Media)->Unit)->Media={calls++;media()}
            metadata.analyze(request,load=load)
            metadata.analyze(request.copy(network=2),load=load)
            metadata.analyze(request.copy(route=MetadataRoute.AUTOMATIC),load=load)
            metadata.analyze(request.copy(mode=MetadataMode.ENRICHED),load=load)
            assertEquals(4,calls)
            metadata.analyze(request,load=load)
            assertEquals(4,calls)
        } finally {scope.cancel()}
    }

    @Test fun forcedRefreshBypassesCacheAndAnOrdinaryInFlightResult()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val metadata=ExtractorMetadata(scope,{0L});val finish=CompletableDeferred<Unit>()
            val ordinaryMedia=media(id="ordinary");val refreshed=media(id="refreshed")
            val ordinary=async(start=CoroutineStart.UNDISPATCHED) {
                metadata.analyze(request) {finish.await();ordinaryMedia}
            }
            val refresh=async(start=CoroutineStart.UNDISPATCHED) {
                metadata.analyze(request.copy(freshness=MetadataFreshness.REFRESH)) {refreshed}
            }
            assertTrue("Refresh must not wait for the ordinary flight",refresh.isCompleted)
            assertSame(refreshed,refresh.await());finish.complete(Unit);assertSame(ordinaryMedia,ordinary.await())
            assertSame("Late ordinary completion must not overwrite refreshed metadata",refreshed,
                metadata.analyze(request) {throw AssertionError("The refreshed result should be reusable")})
            val newest=media(id="newest")
            assertSame(newest,metadata.analyze(request.copy(freshness=MetadataFreshness.REFRESH)) {newest})
        } finally {scope.cancel()}
    }

    @Test fun legacyExtractorRouteStillBypassesCachedResultsWhenRequested()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val metadata=ExtractorMetadata(scope,{0L});var calls=0
            repeat(2) {metadata.analyze(request.copy(freshness=MetadataFreshness.SKIP_CACHE)) {calls++;media()}}
            assertEquals(2,calls)
        } finally {scope.cancel()}
    }

    @Test fun retentionUsesTheSameKeyWithoutStartingWork()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val metadata=ExtractorMetadata(scope,{0L});var calls=0;val finish=CompletableDeferred<Unit>()
            assertNull(metadata.retain(request))
            val load:suspend ((Media)->Unit)->Media={calls++;finish.await();media()}
            val preview=async(start=CoroutineStart.UNDISPATCHED) {metadata.analyze(request,load=load)}
            val lease=requireNotNull(metadata.retain(request))
            assertNull(metadata.retain(request.copy(network=2)))
            assertNull(metadata.retain(request.copy(route=MetadataRoute.AUTOMATIC)))
            preview.cancelAndJoin()
            val queue=async(start=CoroutineStart.UNDISPATCHED) {metadata.analyze(request.copy(subscriber=AnalysisSubscriber.DURABLE),load=load)}
            lease.close();finish.complete(Unit);queue.await();assertEquals(1,calls)
        } finally {scope.cancel()}
    }
}
