package com.zdownloader.app.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OwnedStoragePathsTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun operationTokensCannotEscapeTransferOrJournalOwnership() {
        val root=temporary.newFolder("files")
        for(id in listOf("",".","..","../protected","a/../../protected","a\\..\\protected","/absolute","C:\\protected","a\u0000b","x".repeat(129))) {
            for(resolve in listOf(OwnedStoragePaths::transfer,OwnedStoragePaths::publication)) {
                try {resolve(root,id);fail("Accepted storage path $id")} catch(_:IllegalArgumentException) {}
            }
        }
        assertTrue("Validation must not create or delete files",root.listFiles()!!.isEmpty())
    }
    @Test fun legitimateOperationTokensRetainTheirExistingLocations() {
        val root=temporary.newFolder("files")
        for(id in listOf("12345678-abcd-1234-abcd-123456789abc","range416","image_test-1")) {
            assertEquals(File(root,"transfers/$id"),OwnedStoragePaths.transfer(root,id))
            assertEquals(File(root,"publications/$id.json"),OwnedStoragePaths.publication(root,id))
        }
    }
}
