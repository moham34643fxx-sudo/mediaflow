package com.zdownloader.app.status

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StatusDirectoriesTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun status(path:String,name:String="viewed.jpg"):File=File(temporary.root,path).apply {mkdirs()}.let {File(it,name).apply {writeBytes(byteArrayOf(1,2,3))}}

    @Test fun modernStatusMediaAppearsWithoutAStoredTreeAndDoesNotMixBusiness() {
        val image=status("Android/media/com.whatsapp/WhatsApp/Media/.Statuses")
        assertEquals(listOf(image.canonicalFile),StatusDirectories.readable(temporary.root,StatusSource.WHATSAPP)?.files)
        assertNull(StatusDirectories.readable(temporary.root,StatusSource.BUSINESS))
    }
    @Test fun legacyBusinessLocationIsDiscoveredWithoutSearchingChats() {
        val image=status("WhatsApp Business/Media/.Statuses")
        status("WhatsApp Business/Media/WhatsApp Business Images","chat.jpg")
        assertEquals(listOf(image.canonicalFile),StatusDirectories.readable(temporary.root,StatusSource.BUSINESS)?.files)
        assertNull(StatusDirectories.readable(temporary.root,StatusSource.WHATSAPP))
    }
    @Test fun unrelatedStatusesAndImportedFilesAreNeverAutomaticSources() {
        status("Pictures/.Statuses");status("WhatsApp/Media/WhatsApp Images")
        assertNull(StatusDirectories.readable(temporary.root,StatusSource.WHATSAPP))
        assertNull(StatusDirectories.readable(temporary.root,StatusSource.IMPORTS))
    }
    @Test fun emptyDirectoryIsAnEmptyCollectionNotARequestForAnotherFolder() {
        val folder=File(temporary.root,"WhatsApp/Media/.Statuses").apply {mkdirs()}
        File(folder,".nomedia").writeText("")
        val result=StatusDirectories.readable(temporary.root,StatusSource.WHATSAPP)
        assertNotNull(result);assertTrue(result!!.files.isEmpty())
    }
    @Test fun nestedDirectoriesAndHiddenControlFilesAreNotMediaItems() {
        val image=status("WhatsApp/Media/.Statuses")
        status("WhatsApp/Media/.Statuses/subfolder")
        status("WhatsApp/Media/.Statuses",".nomedia")
        assertEquals(listOf(image.canonicalFile),StatusDirectories.readable(temporary.root,StatusSource.WHATSAPP)?.files)
    }
    @Test fun discoveryReturnsNewestItemsAndEnoughMetadataToReportTheLimit() {
        val folder="WhatsApp/Media/.Statuses"
        repeat(StatusRules.MAX_ITEMS+5) {index->status(folder,"$index.jpg").setLastModified(1_700_000_000_000L+index*1000)}
        val result=StatusDirectories.readable(temporary.root,StatusSource.WHATSAPP)!!
        assertEquals(StatusRules.MAX_ITEMS+1,result.files.size)
        assertEquals("${StatusRules.MAX_ITEMS+4}.jpg",result.files.first().name)
    }
    @Test fun providerExposesExactlyTheKnownStatusDirectoriesAndNoStorageRoot() {
        val document=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/status_source_paths.xml"))
        val nodes=document.getElementsByTagName("external-path")
        val paths=(0 until nodes.length).map {nodes.item(it).attributes.getNamedItem("path").nodeValue.trimEnd('/')}.toSet()
        assertEquals((StatusDirectories.relativePaths(StatusSource.WHATSAPP)+StatusDirectories.relativePaths(StatusSource.BUSINESS)).toSet(),paths)
        assertTrue(paths.none {it=="."||it.isBlank()||it.contains("..")})
    }
    @Test fun mediaRootsReturnsVisibleParentMediaDirectories() {
        assertEquals(
            listOf("Android/media/com.whatsapp/WhatsApp/Media", "WhatsApp/Media"),
            StatusDirectories.mediaRoots(StatusSource.WHATSAPP)
        )
        assertEquals(
            listOf("Android/media/com.whatsapp.w4b/WhatsApp Business/Media", "WhatsApp Business/Media"),
            StatusDirectories.mediaRoots(StatusSource.BUSINESS)
        )
        assertEquals(emptyList<String>(), StatusDirectories.mediaRoots(StatusSource.IMPORTS))
    }
    @Test fun relativePathsAreDerivedFromMediaRoots() {
        for (source in listOf(StatusSource.WHATSAPP, StatusSource.BUSINESS, StatusSource.IMPORTS)) {
            val expected = StatusDirectories.mediaRoots(source).flatMap { listOf("$it/.Statuses", "$it/Statuses") }
            assertEquals(expected, StatusDirectories.relativePaths(source))
        }
    }
    @Test fun initialDocumentIdPointsToVisibleParentMediaRootNotHiddenDirectory() {
        val whatsappId = StatusDirectories.initialDocumentId(StatusSource.WHATSAPP)
        assertEquals("primary:Android/media/com.whatsapp/WhatsApp/Media", whatsappId)
        assertFalse(whatsappId!!.contains(".Statuses"))

        val businessId = StatusDirectories.initialDocumentId(StatusSource.BUSINESS)
        assertEquals("primary:Android/media/com.whatsapp.w4b/WhatsApp Business/Media", businessId)
        assertFalse(businessId!!.contains(".Statuses"))

        assertNull(StatusDirectories.initialDocumentId(StatusSource.IMPORTS))
    }
    @Test fun initialDocumentIdPrefersExistingStorageLocation() {
        val selected = StatusDirectories.initialDocumentId(StatusSource.WHATSAPP) { path ->
            path == "WhatsApp/Media"
        }
        assertEquals("primary:WhatsApp/Media", selected)
    }
    @Test fun emptyDirectoryWithoutNomediaIsReadableWithoutWriteProbe() {
        File(temporary.root, "WhatsApp/Media/.Statuses").mkdirs()
        val result = StatusDirectories.readable(temporary.root, StatusSource.WHATSAPP)
        assertNotNull(result)
        assertTrue(result!!.files.isEmpty())
    }
}
