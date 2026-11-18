package com.zdownloader.app.domain

import org.junit.Assert.*
import org.junit.Test

class PlaylistRemovalPolicyTest {
    @Test fun cancelledCollectionCanPreserveItsCompletedChildren() {
        assertTrue(PlaylistRemovalPolicy.allowed("CANCELLED",listOf("CANCELLED","COMPLETED")))
    }
    @Test fun allIndividuallyCancelledChildrenCanRemoveTheCollection() {
        assertTrue(PlaylistRemovalPolicy.allowed("ACTIVE",listOf("CANCELLED","CANCELLED")))
    }
    @Test fun emptyCancelledRecordCanBeRemoved() {assertTrue(PlaylistRemovalPolicy.allowed("CANCELLED",emptyList()))}
    @Test fun emptyActiveRecordCannotBeRemovedAsCancelled() {assertFalse(PlaylistRemovalPolicy.allowed("ACTIVE",emptyList()))}
    @Test fun activeAndPendingChildrenAreNeverDiscarded() {
        for(state in listOf("QUEUED","DOWNLOADING","MERGING","SAVING","PAUSING","PAUSED","FAILED","SCHEDULED","INTERRUPTED","RESUMING"))
            assertFalse(state,PlaylistRemovalPolicy.allowed("CANCELLED",listOf("CANCELLED",state)))
    }
    @Test fun completedCollectionIsNotACancelledCollection() {
        assertFalse(PlaylistRemovalPolicy.allowed("ACTIVE",listOf("COMPLETED")))
    }
}
