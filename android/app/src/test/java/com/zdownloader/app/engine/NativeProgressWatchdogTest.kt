package com.zdownloader.app.engine

import org.junit.Assert.*
import org.junit.Test

class NativeProgressWatchdogTest {
    @Test fun progressAllowsLongWorkButDuplicateOrRegressedStatisticsDoNotHideAStall() {
        var elapsed=0L
        val watchdog=NativeProgressWatchdog({elapsed},1000)
        for(step in 1L..100L) {
            elapsed+=999
            watchdog.progress(step,step*10,step)
            assertFalse(watchdog.stalled())
        }
        elapsed+=999
        watchdog.progress(100,1000,100)
        assertFalse(watchdog.stalled())
        elapsed++
        watchdog.progress(1,1,1)
        assertTrue(watchdog.stalled())
    }

    @Test fun anyRealOutputProgressRenewsTheIdleBudget() {
        for(kind in listOf("time","bytes","frames")) {
            var elapsed=0L
            val watchdog=NativeProgressWatchdog({elapsed},1000)
            elapsed=999
            watchdog.progress(if(kind=="time") 1 else 0,if(kind=="bytes") 1 else 0,if(kind=="frames") 1 else 0)
            elapsed=1998
            assertFalse(kind,watchdog.stalled())
            elapsed=1999
            assertTrue(kind,watchdog.stalled())
        }
    }
}
