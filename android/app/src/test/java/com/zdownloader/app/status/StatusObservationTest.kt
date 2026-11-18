package com.zdownloader.app.status

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusObservationTest {
    @Test fun recoveryCancellationStopsBeforeStartingNextOwnedCleanup()=kotlinx.coroutines.runBlocking {
        val processed=mutableListOf<Int>()
        val job=launch {
            cancellableForEach(listOf(1,2,3)) {entry->
                processed+=entry
                if(entry==1) kotlinx.coroutines.currentCoroutineContext().cancel()
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf(1),processed)
    }

    @Test fun committedImportsSurviveObsoleteUiOperationAndCancellation() {
        val imported=MutableStateFlow(listOf("folder-A"))
        val state=MutableStateFlow(StatusUiState(source=StatusSource.IMPORTS))

        // B committed while a newer C operation was pending; C then fails and has no commit.
        assertEquals(false,applyCommittedImports(imported,state,listOf("folder-B"),false))
        assertEquals(listOf("folder-B"),imported.value)
        assertEquals(StatusSource.IMPORTS,state.value.source)
        applyCommittedImports(imported,state,listOf("temporary-B"),true)
        assertTrue("Authoritative replacement updates temporary-access status even without navigation",state.value.sessionOnly)
        applyCommittedImports(imported,state,listOf("durable-B"),false)
        assertEquals(false,state.value.sessionOnly)

        // A source-folder removal does not suppress a committed Imports replacement.
        state.value=state.value.copy(source=StatusSource.WHATSAPP)
        applyCommittedImports(imported,state,listOf("folder-C"),false)
        assertEquals(listOf("folder-C"),imported.value)
        assertEquals(StatusSource.WHATSAPP,state.value.source)

        // Cancellation after persistence reconciles the same durable selection.
        applyCommittedImports(imported,state,listOf("folder-D"),true)
        assertEquals(listOf("folder-D"),imported.value)

        // A stale clear still removes the committed selection and exits the empty Imports view.
        state.value=state.value.copy(source=StatusSource.IMPORTS)
        assertEquals(true,applyCommittedImports(imported,state,emptyList(),false))
        assertEquals(emptyList<String>(),imported.value)
        assertEquals(StatusSource.WHATSAPP,state.value.source)
    }

    @Test fun importReplacementCancelsOldObserverBeforeKeepingNewOne() = kotlinx.coroutines.runBlocking {
        val source=MutableStateFlow(StatusSource.IMPORTS)
        val selection=MutableStateFlow(listOf("folder-A"))
        val active=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val observer=scope.launch {
            selectedSourceChanges(source,selection) {current,uris->flow {
                val key="$current:${uris.joinToString()}"
                active+=key
                emit(Unit)
                try {awaitCancellation()} finally {active-=key}
            }}.collect {}
        }
        try {
            withTimeout(2000) {while(active.none {it.endsWith("folder-A")}) delay(5)}
            selection.value=listOf("folder-B")
            withTimeout(2000) {while(active.none {it.endsWith("folder-B")}) delay(5)}
            assertEquals(setOf("IMPORTS:folder-B"),active)
            source.value=StatusSource.WHATSAPP
            withTimeout(2000) {while(active.none {it.startsWith("WHATSAPP:")}) delay(5)}
            assertEquals(setOf("WHATSAPP:folder-B"),active)
        } finally {observer.cancelAndJoin();scope.cancel()}
        assertTrue(active.isEmpty())
    }
}
