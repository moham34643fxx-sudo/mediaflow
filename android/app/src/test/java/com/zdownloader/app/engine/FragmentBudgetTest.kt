package com.zdownloader.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class FragmentBudgetTest {
    @Test fun sequentialAudioDoesNotReserveConnectionsForEmptyGlobalSlots() {
        assertEquals(32,FragmentBudget.perTrack(1,1))
    }
    @Test fun separateVideoAndAudioShareTheSameDownloadBudget() {
        assertEquals(16,FragmentBudget.perTrack(1,2))
        assertEquals(8,FragmentBudget.perTrack(2,2))
    }
    @Test fun competingDownloadsReceiveBoundedNonzeroBudgets() {
        assertEquals(16,FragmentBudget.perTrack(2,1))
        assertEquals(3,FragmentBudget.perTrack(5,2))
    }
    @Test fun constrainedDevicesRetainTheConservativeConnectionBudget() {
        assertEquals(8,FragmentBudget.perTrack(1,1,constrained=true))
        assertEquals(2,FragmentBudget.perTrack(2,2,constrained=true))
        assertEquals(1,FragmentBudget.perTrack(5,2,constrained=true))
    }
}
