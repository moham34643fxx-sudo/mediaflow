package com.zdownloader.app.engine

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NativeWorkTest {
    @get:Rule val temporary=TemporaryFolder()

    @Test fun relatedPathsBlockButSiblingsAndSharedPrefixesDoNot()=runBlocking<Unit> {
        val parent=temporary.newFolder("parent")
        val child=File(parent,"track")
        val lease=NativeWork.acquire(child)
        try {
            for(path in listOf(parent,child,File(child,"frames"))) assertTrue(path.path,NativeWork.busy(path))
            for(path in listOf(File(parent,"audio"),File(parent,"track-other"))) {
                assertFalse(path.path,NativeWork.busy(path))
                withTimeout(5_000) {NativeWork.awaitIdle(path)}
            }
        } finally {lease.release()}
        assertFalse(NativeWork.busy(parent))
    }

    @Test fun readOwnerCannotExcludeWritersOrAnotherAttemptsReaders()=runBlocking<Unit> {
        val directory=temporary.newFolder()
        val owner=NativeWork.ReadOwner()
        val own=NativeWork.acquireRead(directory,owner)
        try {
            withTimeout(5_000) {NativeWork.awaitOtherWork(directory,owner)}
            assertTrue(NativeWork.busy(directory))
            for(blocker in listOf<()->NativeWork.Lease>(
                {NativeWork.acquire(directory)},
                {NativeWork.acquireRead(directory,NativeWork.ReadOwner())}
            )) {
                val lease=blocker()
                val waiting=async(start=CoroutineStart.UNDISPATCHED) {NativeWork.awaitOtherWork(directory,owner)}
                try {assertFalse(waiting.isCompleted)} finally {lease.release()}
                withTimeout(5_000) {waiting.await()}
            }
        } finally {own.release()}
        assertFalse(NativeWork.busy(directory))
    }

    @Test fun cancellingAWaitDoesNotReleaseTheReadersFiles()=runBlocking<Unit> {
        val directory=temporary.newFolder()
        val lease=NativeWork.acquireRead(directory,NativeWork.ReadOwner())
        try {
            val waiting=async(start=CoroutineStart.UNDISPATCHED) {NativeWork.awaitIdle(directory)}
            assertFalse(waiting.isCompleted)
            waiting.cancelAndJoin()
            assertTrue(NativeWork.busy(directory))
        } finally {lease.release();lease.release()}
        withTimeout(5_000) {NativeWork.awaitIdle(directory)}
    }

    @Test fun newWorkArrivingDuringAWaitStillBlocksReuse()=runBlocking<Unit> {
        val directory=temporary.newFolder()
        val first=NativeWork.acquire(directory)
        val waiting=async(start=CoroutineStart.UNDISPATCHED) {NativeWork.awaitIdle(directory)}
        val second=NativeWork.acquire(directory)
        try {
            first.release()
            yield()
            assertFalse(waiting.isCompleted)
        } finally {first.release();second.release()}
        withTimeout(5_000) {waiting.await()}
    }

    @Test fun queuedReservationCancellationAndStartedWorkKeepDifferentOwnership()=runBlocking<Unit> {
        val directory=temporary.newFolder()
        val queued=NativeWork.Reservation(directory)
        assertTrue(NativeWork.busy(directory))
        queued.cancelPending()
        assertFalse(queued.start())
        queued.finish()
        assertFalse(NativeWork.busy(directory))
        val running=NativeWork.Reservation(directory)
        try {
            assertTrue(running.start())
            running.cancelPending()
            assertFalse(running.start())
            assertTrue(NativeWork.busy(directory))
        } finally {running.finish();running.finish()}
        withTimeout(5_000) {NativeWork.awaitIdle(directory)}
    }
}
