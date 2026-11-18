package com.zdownloader.app.updates

import org.junit.Assert.*
import org.junit.Test

class SigningUpdatePolicyTest {
    private val rotationCandidate="5a889db782d9019a56810bee1cd7942d315b9e115697c3e46ee1c0f7391ccbb1"
    private val production="1533e8982418960b19cd8b56048e9e2ee3b8588ec0ccd8d31a2f39eac0010460"
    private val hypotheticalLegacy="d".repeat(64)
    private val attacker="c".repeat(64)
    private fun allows(current:Set<String>,target:Set<String>,history:Set<String> = emptySet(),sdk:Int=35)=
        SigningUpdatePolicy.compatible(current,target,history,sdk,production,"")
    private fun allowsVerifiedRotation(current:Set<String>,target:Set<String>,history:Set<String>,sdk:Int=35)=
        SigningUpdatePolicy.compatible(current,target,history,sdk,production,hypotheticalLegacy)

    @Test fun theInstalledSignerStillUpdatesNormallyOnOldAndNewAndroid() {
        assertTrue(allows(setOf(production),setOf(production),sdk=24))
        assertTrue(allows(setOf(production),setOf(production)))
    }

    @Test fun theRotationCandidateIsNotAnAuthorizedStableSigner() {
        assertFalse(allows(setOf(rotationCandidate),setOf(production)))
        assertFalse(allows(setOf(rotationCandidate),setOf(production),setOf(rotationCandidate,production)))
    }

    @Test fun aDifferentSignerCannotBecomeStableWithoutConfiguredLineage() {
        assertFalse(allows(setOf(hypotheticalLegacy),setOf(production)))
        assertFalse(allows(setOf(hypotheticalLegacy),setOf(production),setOf(hypotheticalLegacy,production)))
        assertFalse(allows(setOf(hypotheticalLegacy),setOf(attacker),setOf(hypotheticalLegacy,attacker)))
        assertFalse(allows(setOf(attacker),setOf(production),setOf(attacker,production)))
    }

    @Test fun aFutureRotationStillRequiresBothPinnedIdentitiesAndPlatformHistory() {
        assertTrue(allowsVerifiedRotation(setOf(hypotheticalLegacy),setOf(production),setOf(hypotheticalLegacy,production),28))
        assertFalse(allowsVerifiedRotation(setOf(hypotheticalLegacy),setOf(production),setOf(production)))
        assertFalse(allowsVerifiedRotation(setOf(hypotheticalLegacy),setOf(production),setOf(hypotheticalLegacy),28))
        assertFalse(allowsVerifiedRotation(setOf(hypotheticalLegacy),setOf(production),setOf(hypotheticalLegacy,production),27))
    }

    @Test fun rotationNeverAuthorizesDowngradeOrUnrelatedMultipleSigners() {
        assertFalse(allowsVerifiedRotation(setOf(production),setOf(hypotheticalLegacy),setOf(hypotheticalLegacy,production)))
        assertFalse(allowsVerifiedRotation(setOf(hypotheticalLegacy),setOf(production,attacker),setOf(hypotheticalLegacy,production,attacker)))
        assertFalse(allows(emptySet(),emptySet()))
    }
}
