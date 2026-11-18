package com.zdownloader.app.updates

import org.junit.Assert.*
import org.junit.Test

class ForegroundUpdateCheckGateTest {
    @Test fun firstEntryDoesNotDependOnPersistedWallClockChecks() {
        assertTrue(ForegroundUpdateCheckGate().allow(0))
    }
    @Test fun rapidResumesDoNotFloodMetadataRequests() {
        val gate=ForegroundUpdateCheckGate()
        assertTrue(gate.allow(100));assertFalse(gate.allow(101));assertFalse(gate.allow(60_099))
        assertTrue(gate.allow(60_100))
    }
    @Test fun suppressedResumesDoNotExtendTheInterval() {
        val gate=ForegroundUpdateCheckGate()
        assertTrue(gate.allow(0));assertFalse(gate.allow(59_999));assertTrue(gate.allow(60_000))
    }
    @Test fun newProcessHasANewEntryCheck() {
        assertTrue(ForegroundUpdateCheckGate().allow(500))
        assertTrue(ForegroundUpdateCheckGate().allow(501))
    }
    @Test fun elapsedClockResetCannotBlockEntryIndefinitely() {
        val gate=ForegroundUpdateCheckGate()
        assertTrue(gate.allow(120_000));assertTrue(gate.allow(0))
    }
}
