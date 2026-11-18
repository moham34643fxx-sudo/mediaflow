package com.zdownloader.app

import com.zdownloader.app.engine.OutputDuration
import org.junit.Assert.assertEquals
import org.junit.Test

class OutputDurationTest {
    @Test fun exactDurationLimitsPassButValuesBeyondThemFail() {
        // Real TikTok metadata: expected 32 s, output 32.96 s was falsely rejected.
        val cases=listOf(
            Triple(32.96,32.0,true),Triple(31.04,32.0,true),
            Triple(32.960001,32.0,false),Triple(31.039999,32.0,false),
            Triple(10.5,10.0,true),Triple(9.5,10.0,true),
            Triple(10.500001,10.0,false),Triple(9.499999,10.0,false),
            Triple(32.0,32.0,true),Triple(Double.NaN,32.0,false),
            Triple(Double.POSITIVE_INFINITY,32.0,false),Triple(32.0,Double.NaN,false)
        )
        for((actual,expected,accepted) in cases) {
            assertEquals("actual=$actual expected=$expected",accepted,OutputDuration.matches(actual,expected))
        }
    }
}
