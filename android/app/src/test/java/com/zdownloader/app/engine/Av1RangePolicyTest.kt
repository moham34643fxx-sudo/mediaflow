package com.zdownloader.app.engine

import org.junit.Assert.*
import org.junit.Test

class Av1RangePolicyTest {
    @Test fun duplicateOrReorderedPresentationTimesCannotUseSplitValidation() {
        assertFalse(Av1FullDecoder.increasingTimestamp(1000,1000))
        assertFalse(Av1FullDecoder.increasingTimestamp(2000,1000))
    }
    @Test fun increasingVariableFrameRateTimesHaveUnambiguousBoundaries() {
        assertTrue(Av1FullDecoder.increasingTimestamp(Long.MIN_VALUE,0))
        assertTrue(Av1FullDecoder.increasingTimestamp(0,1000))
        assertTrue(Av1FullDecoder.increasingTimestamp(1000,1900))
    }
}
