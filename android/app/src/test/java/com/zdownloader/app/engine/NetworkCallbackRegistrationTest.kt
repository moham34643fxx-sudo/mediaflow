package com.zdownloader.app.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NetworkCallbackRegistrationTest {
    @Test fun companionRegistrationFailureReleasesOnlyTheAcquiredNetworkCallback() {
        val registered=mutableSetOf<String>();val events=mutableListOf<String>()
        try {
            NetworkCallbackRegistration({registered+="default"},{registered-="default";events+="default_closed"}).use {
                NetworkCallbackRegistration({throw SecurityException("Controlled platform rejection")},{events+="unacquired_closed"}).use {
                    fail("Queue must not start after platform rejection")
                }
            }
            fail("Platform registration failure was hidden")
        } catch(_:SecurityException) {}
        assertTrue(registered.isEmpty());assertEquals(listOf("default_closed"),events)
    }

    @Test fun queueCancellationReleasesBothCallbacksAndKeepsTheOriginalFailure()=runBlocking<Unit> {
        val events=mutableListOf<String>()
        try {
            NetworkCallbackRegistration({events+="default_registered"},{events+="default_closed"}).use {
                NetworkCallbackRegistration({events+="underlying_registered"},{events+="underlying_closed";throw IllegalStateException("Controlled cleanup failure")}).use {
                    throw CancellationException("Queue cancelled")
                }
            }
        } catch(error:CancellationException) {
            assertEquals("Queue cancelled",error.message)
            assertEquals(1,error.suppressed.size)
        }
        assertEquals(listOf("default_registered","underlying_registered","underlying_closed","default_closed"),events)
    }
}
