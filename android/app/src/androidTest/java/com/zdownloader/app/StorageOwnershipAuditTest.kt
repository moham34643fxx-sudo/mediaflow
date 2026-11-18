@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.content.ContextWrapper
import android.system.Os
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.data.StorageRepository
import com.zdownloader.app.data.ZDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class StorageOwnershipAuditTest {
    @Test fun unsafeJournalDoesNotBlockReconciliationOfOtherOwnedOperations()=runBlocking<Unit> {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        for(alias in listOf(false,true)) {
            val root=File(app.cacheDir,"journal-isolation-${UUID.randomUUID()}").apply {check(mkdirs())}
            val context=object:ContextWrapper(app) {override fun getFilesDir()=root}
            val database=Room.inMemoryDatabaseBuilder(context,ZDatabase::class.java).build()
            val journals=File(root,"publications").apply {check(mkdirs())}
            val sentinel=File(root,"retained-output").apply {writeText("test-owned output must remain intact")}
            val unsafeId=if(alias) "alias" else "invalid.token"
            val unsafe=File(journals,"$unsafeId.json")
            val validId="safe-${UUID.randomUUID()}"
            val valid=File(journals,"$validId.json")
            try {
                if(alias) Os.symlink(sentinel.path,unsafe.path) else unsafe.writeText("unknown journal evidence")
                valid.writeText("{}")
                val completed=DownloadEntity(validId,"https://journal-audit.example/media","Completed fixture","audit",
                    null,null,"{}","retained.mp4",null,validId,status="COMPLETED")
                database.downloads().insert(completed)
                val blocked=completed.copy(id=unsafeId,fingerprint=unsafeId,status="INTERRUPTED",pendingUri="mediaflow-publication:$unsafeId")
                database.downloads().insert(blocked)
                val storage=StorageRepository(context,database.downloads())

                storage.reconcilePublications()
                storage.reconcilePublications()

                assertFalse("A rejected journal blocked reconciliation of a valid completed row",valid.exists())
                assertEquals("COMPLETED",database.downloads().get(validId)!!.status)
                assertEquals("FAILED",database.downloads().get(unsafeId)!!.status)
                assertEquals(blocked.pendingUri,database.downloads().get(unsafeId)!!.pendingUri)
                assertTrue("Unknown or aliased journal evidence must not be deleted",unsafe.exists())
                assertEquals("test-owned output must remain intact",sentinel.readText())
                if(!alias) assertEquals("unknown journal evidence",unsafe.readText())
            } finally {
                if(alias&&unsafe.exists()) check(unsafe.delete()) {"Could not unlink test-owned journal alias"}
                database.close();root.deleteRecursively()
            }
        }
    }

    @Test fun storageOperationsRejectTraversalAndSymlinkAliasesWithoutDeletingAdjacentFiles()=runBlocking<Unit> {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"storage-ownership-${UUID.randomUUID()}").apply {check(mkdirs())}
        val context=object:ContextWrapper(app) {override fun getFilesDir()=root}
        val database=Room.inMemoryDatabaseBuilder(context,ZDatabase::class.java).build()
        val protected=File(root,"protected").apply {check(mkdirs())}
        val sentinel=File(protected,"sentinel.txt").apply {writeText("test-owned data must remain intact")}
        val storage=StorageRepository(context,database.downloads())
        val links=mutableListOf<File>()
        try {
            for(id in listOf("..","../protected","nested/../../protected","nested\\..\\protected")) {
                try {storage.temporary(id);fail("Unsafe temporary path accepted")} catch(_:IllegalArgumentException) {}
                try {storage.removeTemporary(id);fail("Unsafe cleanup path accepted")} catch(_:IllegalArgumentException) {}
                assertEquals("test-owned data must remain intact",sentinel.readText())
            }
            val transfers=File(root,"transfers").apply {check(mkdirs())}
            val link=File(transfers,"alias")
            Os.symlink(protected.path,link.path);links+=link
            try {storage.removeTemporary("alias");fail("Cleanup followed an aliased operation directory")}
            catch(_:IllegalArgumentException) {}
            assertEquals("test-owned data must remain intact",sentinel.readText())
            val valid=storage.temporary("owned-task")
            File(valid,"part").writeText("owned partial")
            storage.removeTemporary("owned-task")
            assertFalse(valid.exists());assertTrue(sentinel.isFile)
        } finally {
            for(link in links) check(link.delete()) {"Could not unlink test-owned alias"}
            database.close();root.deleteRecursively()
        }
    }
}
