@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.os.PowerManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.engine.WakeLeaseTiming
import com.zdownloader.app.engine.withRenewableWakeLock
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RenewableWakeLockTest {
    @Test fun actualBoundedLeaseRenewsBeyondInitialTimeoutAndReleasesOnCancellationAndFailure()=runBlocking<Unit> {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val manager=app.getSystemService(PowerManager::class.java)
        val lock=manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MediaFlow:renewal-test")
        val timing=WakeLeaseTiming(timeoutMillis=1800,renewalMillis=300)
        val started=CompletableDeferred<Unit>()
        val beginning=SystemClock.elapsedRealtime()
        val owner=launch(Dispatchers.Default) {
            withRenewableWakeLock(lock,timing) {started.complete(Unit);awaitCancellation()}
        }
        try {
            withTimeout(5000) {started.await()}
            repeat(14) {delay(300);assertTrue("Wake ownership expired during active work",lock.isHeld)}
            assertTrue(SystemClock.elapsedRealtime()-beginning>2*timing.timeoutMillis)
            owner.cancelAndJoin()
            assertFalse("Cancelled work retained CPU ownership",lock.isHeld)
            delay(2*timing.renewalMillis)
            assertFalse("A stale renewal reacquired the cancelled lease",lock.isHeld)
            try {withRenewableWakeLock(lock,timing) {throw java.io.IOException("controlled failure")}}
            catch(_:java.io.IOException) {assertFalse(lock.isHeld)}
            val run=InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "wake-renewal"
            require(run.matches(Regex("[a-zA-Z0-9-]{1,100}")))
            val home=File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {check(isDirectory||mkdirs())}
            File(home,"wake-renewal.json").writeText(JSONObject().put("passed",true).put("elapsed_ms",SystemClock.elapsedRealtime()-beginning)
                .put("timeout_ms",timing.timeoutMillis).put("renewal_ms",timing.renewalMillis)
                .put("renewed_beyond_initial_timeout",true).put("released_on_cancel_and_failure",true)
                .put("qualification","Real Android wake lock and production owner with scaled timing; not a literal greater-than-one-hour download").toString(2))
        } finally {
            withContext(NonCancellable) {owner.cancelAndJoin();if(lock.isHeld) lock.release()}
        }
    }
}
