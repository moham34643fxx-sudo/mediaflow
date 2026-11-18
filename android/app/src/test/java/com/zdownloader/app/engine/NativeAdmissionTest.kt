package com.zdownloader.app.engine

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeAdmissionTest {
    @Test fun ordinaryWorkStaysExclusiveAndDuplicateReleaseCannotAddCapacity()=runBlocking<Unit> {
        val admission=NativeAdmission()
        val first=admission.acquire()
        val second=async(start=CoroutineStart.UNDISPATCHED) {admission.acquire()}
        assertFalse(second.isCompleted)
        assertNull(admission.tryAcquireOptional())
        first.release();first.release()
        val next=withTimeout(5_000) {second.await()}
        try {assertNull(admission.tryAcquireOptional())} finally {next.release()}
        requireNotNull(admission.tryAcquireOptional()).finish()
    }

    @Test fun ordinaryRequestYieldsOptionalWorkButWaitsForItsActualSettlement()=runBlocking<Unit> {
        val admission=NativeAdmission()
        val optional=requireNotNull(admission.tryAcquireOptional())
        val native=requireNotNull(optional.tryStart())
        val reader=requireNotNull(optional.tryStart())
        val ordinary=async(start=CoroutineStart.UNDISPATCHED) {admission.acquire()}
        try {
            assertTrue(optional.yieldRequested.isCompleted)
            assertNull(optional.tryStart())
            assertFalse(ordinary.isCompleted)
            native.release()
            yield()
            assertFalse(ordinary.isCompleted)
            assertNull(admission.tryAcquireOptional())
        } finally {native.release();reader.release()}
        val permit=withTimeout(5_000) {ordinary.await()}
        try {assertNull(admission.tryAcquireOptional())} finally {permit.release()}
    }

    @Test fun optionalBundleBoundsOperationsAndFinishWaitsForEveryCompletion()=runBlocking<Unit> {
        val admission=NativeAdmission()
        val optional=requireNotNull(admission.tryAcquireOptional())
        val operations=(0 until 3).map {requireNotNull(optional.tryStart())}
        try {
            assertNull(optional.tryStart())
            assertNull(admission.tryAcquireOptional())
            operations.first().release()
            val replacement=requireNotNull(optional.tryStart())
            try {
                optional.finish()
                assertNull(optional.tryStart())
                assertFalse(optional.yieldRequested.isCompleted)
                operations.forEach {it.release()}
                assertNull(admission.tryAcquireOptional())
            } finally {replacement.release()}
        } finally {operations.forEach {it.release()};optional.finish()}
        requireNotNull(admission.tryAcquireOptional()).cancel()
    }

    @Test fun cancelledOrdinaryWaitDoesNotReviveAnOptionalBundleOrLeakPriority()=runBlocking<Unit> {
        val admission=NativeAdmission()
        val optional=requireNotNull(admission.tryAcquireOptional())
        val running=requireNotNull(optional.tryStart())
        val waiting=async(start=CoroutineStart.UNDISPATCHED) {admission.acquire()}
        waiting.cancelAndJoin()
        try {
            assertTrue(optional.yieldRequested.isCompleted)
            assertNull(optional.tryStart())
            assertNull(admission.tryAcquireOptional())
        } finally {running.release()}
        val next=requireNotNull(admission.tryAcquireOptional())
        next.finish()
        optional.cancel();optional.finish()
        val ordinary=admission.acquire()
        try {assertNull(admission.tryAcquireOptional())} finally {ordinary.release()}
    }

    @Test fun ordinaryQueueCannotBeOvertakenByNewOptionalWork()=runBlocking<Unit> {
        val admission=NativeAdmission()
        val first=admission.acquire()
        val waiting=async(start=CoroutineStart.UNDISPATCHED) {admission.acquire()}
        first.release()
        assertNull(admission.tryAcquireOptional())
        val queued=withTimeout(5_000) {waiting.await()}
        try {assertNull(admission.tryAcquireOptional())} finally {queued.release()}
        requireNotNull(admission.tryAcquireOptional()).finish()
    }

    @Test fun stoppingOneOptionalBundleDoesNotReleaseAnotherBundlesSlot()=runBlocking<Unit> {
        val admission=NativeAdmission()
        val old=requireNotNull(admission.tryAcquireOptional())
        old.cancel()
        val current=requireNotNull(admission.tryAcquireOptional())
        try {
            old.finish();old.cancel()
            assertNull(admission.tryAcquireOptional())
            assertFalse(current.yieldRequested.isCompleted)
        } finally {current.finish()}
        val ordinary=admission.acquire()
        ordinary.release()
    }
}
