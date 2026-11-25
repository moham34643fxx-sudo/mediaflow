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
    @Test fun audioOutputMimeAndMediaStoreCollectionMatchThePublishedContainer() {
        val expected=mapOf(
            "mp3" to "audio/mpeg","m4a" to "audio/mp4","aac" to "audio/aac",
            "flac" to "audio/flac","opus" to "audio/ogg","ogg" to "audio/ogg",
            "wav" to "audio/wav","mka" to "audio/x-matroska","mkv" to "audio/x-matroska"
        )
        for((extension,mime) in expected) {
            val target=MediaOutputStoragePolicy.target(extension,audioOnly=true,imageOnly=false)
            assertEquals(extension,mime,target.mime)
            assertEquals(extension,MediaStoreCollection.AUDIO,target.collection)
            assertEquals(extension,"Music/MediaFlow",target.folderRoot)
        }
    }
    @Test fun audioWebmUsesDownloadsWithAudioMimeRatherThanVideoClassification() {
        val target=MediaOutputStoragePolicy.target("webm",audioOnly=true,imageOnly=false)
        assertEquals("audio/webm",target.mime)
        assertEquals(MediaStoreCollection.DOWNLOADS,target.collection)
        assertEquals("Download/MediaFlow",target.folderRoot)
    }
    @Test fun audioOnlyMp4AndM4aRemainAudioInMediaStore() {
        for(extension in listOf("mp4","m4a")) {
            val target=MediaOutputStoragePolicy.target(extension,audioOnly=true,imageOnly=false)
            assertEquals(extension,"audio/mp4",target.mime)
            assertEquals(extension,MediaStoreCollection.AUDIO,target.collection)
            assertEquals(extension,"Music/MediaFlow",target.folderRoot)
        }
    }
    @Test fun videoAndImageContainersKeepTheirTypedCollections() {
        for((extension,mime) in listOf("mp4" to "video/mp4","webm" to "video/webm","mkv" to "video/x-matroska")) {
            val target=MediaOutputStoragePolicy.target(extension,audioOnly=false,imageOnly=false)
            assertEquals(extension,mime,target.mime)
            assertEquals(extension,MediaStoreCollection.VIDEO,target.collection)
        }
        for((extension,mime) in listOf("jpg" to "image/jpeg","png" to "image/png","webp" to "image/webp")) {
            val target=MediaOutputStoragePolicy.target(extension,audioOnly=false,imageOnly=true)
            assertEquals(extension,mime,target.mime)
            assertEquals(extension,MediaStoreCollection.IMAGES,target.collection)
        }
    }
}
