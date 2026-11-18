package com.zdownloader.app.background

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaylistStartupSchedulingTest {
    @Test fun unresolvedRecoveryDoesNotBlockOwnerButReceiptWaitsForAcceptance()=runBlocking {
        val recoveryStarted=CompletableDeferred<Unit>();val releaseRecovery=CompletableDeferred<Unit>()
        val ownerAttempted=CompletableDeferred<Unit>()
        withTimeout(5_000) {
            val startup=async {
                requestUserStartupOwners(recovery={recoveryStarted.complete(Unit);releaseRecovery.await();true},
                    immediateOwner={ownerAttempted.complete(Unit);"user_foreground"})
            }
            ownerAttempted.await();recoveryStarted.await()
            assertFalse("A final receipt still requires recovery's outcome",startup.isCompleted)
            releaseRecovery.complete(Unit)
            assertEquals(UserStartupOwners("user_foreground",true),startup.await())
        }
    }

    @Test fun ownerAndRecoveryOutcomesRemainIndependentForExistingFallbacks()=runBlocking {
        for(owner in listOf("user_foreground","")) for(recovery in listOf(true,false)) {
            val owners=requestUserStartupOwners(recovery={recovery},immediateOwner={owner})
            assertEquals(owner,owners.mechanism)
            assertEquals(recovery,owners.recoveryAccepted)
        }
    }
}
