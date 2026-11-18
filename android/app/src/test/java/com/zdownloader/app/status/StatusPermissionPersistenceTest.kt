package com.zdownloader.app.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusPermissionPersistenceTest {
    @Test fun failedCommitRestoresLastKnownGoodValueAndDoesNotAdvertiseSuccess() {
        val durable = mutableMapOf("whatsapp" to "content://fixture/folder-a")
        var inMemory = durable.toMutableMap()
        var failWrites = true
        val preferences = RecoverableStringPreferences(
            read = { inMemory[it] },
            write = { key, value ->
                if (value == null) inMemory.remove(key) else inMemory[key] = value
                if (failWrites) false else {
                    if (value == null) durable.remove(key) else durable[key] = value
                    true
                }
            }
        )

        assertFalse(preferences.put("whatsapp", "content://fixture/folder-b"))
        assertEquals("content://fixture/folder-a", preferences.get("whatsapp"))
        assertEquals("content://fixture/folder-a", inMemory["whatsapp"])

        // A fresh process reads the prior durable value, while the old grant is kept by callers.
        val afterProcessRestart = RecoverableStringPreferences({ durable[it] }, { _, _ -> false })
        assertEquals("content://fixture/folder-a", afterProcessRestart.get("whatsapp"))

        failWrites = false
        assertTrue(preferences.put("whatsapp", "content://fixture/folder-b"))
        assertEquals("content://fixture/folder-b", preferences.get("whatsapp"))
        val afterSuccessfulSettingsWrite=RecoverableStringPreferences({durable[it]},{_,_->false})
        assertEquals("A successfully committed setting survives process recreation",
            "content://fixture/folder-b",afterSuccessfulSettingsWrite.get("whatsapp"))
    }

    @Test fun onlyNewlyAddedRequestedGrantBitsAreRollbackCandidates() {
        val read=android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write=android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        assertEquals(0,newlyPersistedGrantFlags(read,read,read))
        assertEquals(read,newlyPersistedGrantFlags(write,read or write,read))
        assertEquals(read,newlyPersistedGrantFlags(0,read,read))
        assertEquals(0,newlyPersistedGrantFlags(read,read or write,read))
    }
}
