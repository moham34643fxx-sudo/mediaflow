package com.zdownloader.app.extraction

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtractionPublicationTest {
    @Test fun recoveryNeverTreatsPublishedExportedOrUncertainOutputAsDisposable() {
        // MF-01: mismatch, interrupted publication, SAF and ambiguous provider state.
        for(ownership in listOf(ExtractionOutputOwnership.VISIBLE,ExtractionOutputOwnership.EXPORTED)) {
            assertEquals(ExtractionRecoveryAction.ADOPT,extractionRecoveryAction(ownership,true))
            assertEquals(ExtractionRecoveryAction.PRESERVE,extractionRecoveryAction(ownership,false))
        }
        for(matches in listOf(false,true)) {
            assertEquals(ExtractionRecoveryAction.PRESERVE,extractionRecoveryAction(ExtractionOutputOwnership.UNCERTAIN,matches))
            assertEquals(ExtractionRecoveryAction.REMOVE_PENDING,extractionRecoveryAction(ExtractionOutputOwnership.OWNED_PENDING,matches))
            assertEquals(ExtractionRecoveryAction.CLEAR_MISSING,extractionRecoveryAction(ExtractionOutputOwnership.MISSING,matches))
        }
    }
}
