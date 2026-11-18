package com.zdownloader.app.engine

import org.junit.Assert.*
import org.junit.Test

class AudioProcessingProgressTest {
    @Test fun sourceProgressIsMonotonicThrottledAndNeverClaimsPublished100Percent() {
        var now=0L
        val task=AudioProcessingProgress.start("throttle",10.0) {now}
        try {
            task.sample(2000.0);assertEquals(20,AudioProcessingProgress.state.value["throttle"]!!.percent)
            task.sample(3000.0);assertEquals(2000L,AudioProcessingProgress.state.value["throttle"]!!.positionMs)
            now=1000;task.sample(1000.0);assertEquals(2000L,AudioProcessingProgress.state.value["throttle"]!!.positionMs)
            task.sample(11000.0);assertEquals(99,AudioProcessingProgress.state.value["throttle"]!!.percent)
            task.phase(AudioProcessingPhase.FINALIZING);now=2000;task.sample(0.0)
            assertEquals(AudioProcessingPhase.FINALIZING,AudioProcessingProgress.state.value["throttle"]!!.phase)
        } finally {task.close()}
        assertFalse(AudioProcessingProgress.state.value.containsKey("throttle"))
    }
    @Test fun oldCallbacksCannotReviveOrRemoveANewerAttempt() {
        val old=AudioProcessingProgress.start("retry",10.0)
        val next=AudioProcessingProgress.start("retry",20.0)
        old.sample(9000.0);old.phase(AudioProcessingPhase.VALIDATING);old.close()
        assertEquals(20000L,AudioProcessingProgress.state.value["retry"]!!.durationMs)
        assertEquals(0L,AudioProcessingProgress.state.value["retry"]!!.positionMs)
        next.close();old.sample(5000.0)
        assertFalse(AudioProcessingProgress.state.value.containsKey("retry"))
    }
    @Test fun unknownDurationNeverCreatesAPercentageAndPhasesCannotRegress() {
        val task=AudioProcessingProgress.start("unknown",Double.NaN)
        try {
            task.sample(Double.POSITIVE_INFINITY);task.sample(-1.0)
            assertNull(AudioProcessingProgress.state.value["unknown"]!!.percent)
            task.phase(AudioProcessingPhase.VALIDATING);task.phase(AudioProcessingPhase.ENCODING)
            assertEquals(AudioProcessingPhase.VALIDATING,AudioProcessingProgress.state.value["unknown"]!!.phase)
        } finally {task.close()}
    }
}
