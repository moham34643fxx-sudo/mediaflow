package com.zdownloader.app

import com.zdownloader.app.background.*
import com.zdownloader.app.data.Settings
import org.junit.Assert.*
import org.junit.Test

class BatchNotificationPolicyTest {
    @Test fun failuresUseFailurePreferenceEvenWithCompletionDisabled() {
        val failureOnly=Settings(completionNotifications=false)
        assertEquals(BatchNotice.FAILED,batchTerminalNotice(0,3,failureOnly))
        assertEquals(BatchNotice.FAILED,batchTerminalNotice(2,1,failureOnly))
        assertEquals(BatchNotice.FAILED,batchTerminalNotice(2,1,Settings()))
        assertNull(batchTerminalNotice(0,3,Settings(failureNotifications=false)))
    }
    @Test fun successAndCancellationRespectIndependentPreferences() {
        assertEquals(BatchNotice.COMPLETED,batchTerminalNotice(3,0,Settings(failureNotifications=false)))
        assertEquals(BatchNotice.COMPLETED,batchTerminalNotice(2,1,Settings(failureNotifications=false)))
        assertNull(batchTerminalNotice(3,0,Settings(completionNotifications=false)))
        assertNull(batchTerminalNotice(0,0,Settings()))
        assertNull(batchTerminalNotice(2,1,Settings(notifications=false)))
    }
}
