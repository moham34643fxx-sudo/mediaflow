package com.zdownloader.app.status

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class StatusRulesTest {
    @Test fun existingGrantReuseRespectsVolumeAndDirectoryBoundaries() {
        val status="primary:Android/media/com.whatsapp/WhatsApp/Media/.Statuses"
        assertTrue(StatusRules.containsDocument("primary:",status))
        assertTrue(StatusRules.containsDocument("primary:Android/media",status))
        assertTrue(StatusRules.containsDocument(status,status))
        assertFalse(StatusRules.containsDocument("secondary:Android/media",status))
        assertFalse(StatusRules.containsDocument("primary:Android/med",status))
        assertFalse(StatusRules.containsDocument("primary:Android/media/com.whatsapp.w4b",status))
    }
    private fun fails(problem:StatusProblem,operation:()->Unit) {
        try {operation();fail("Expected $problem")} catch(error:StatusException) {assertEquals(problem,error.problem)}
    }
    @Test fun supportedTypesComeFromBytesNotNamesOrProviderMime() {
        assertEquals("image/jpeg",StatusRules.type(byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte()))!!.mime)
        assertEquals("image/png",StatusRules.type(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))!!.mime)
        assertEquals("image/webp",StatusRules.type("RIFF0000WEBP".toByteArray())!!.mime)
        assertEquals("video/mp4",StatusRules.type(byteArrayOf(0,0,0,24)+"ftypisom".toByteArray())!!.mime)
        assertEquals("video/mp4",StatusRules.type(byteArrayOf(0,0,0,24)+"ftypMSNV".toByteArray())!!.mime)
        assertEquals("video/mp4",StatusRules.type(byteArrayOf(0,0,0,28)+"ftypsevd\u0000\u0000\u0000\u0000isom".toByteArray())!!.mime)
        assertNull(StatusRules.type("private encrypted unsupported bytes".toByteArray()))
        assertNull(StatusRules.type(byteArrayOf()))
    }
    @Test fun exactOriginalCopyAndDigestArePreserved() {
        val bytes="abc".toByteArray();val target=ByteArrayOutputStream()
        val result=StatusRules.copy(ByteArrayInputStream(bytes),target,3)
        assertArrayEquals(bytes,target.toByteArray());assertEquals(3L,result.bytes)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",result.sha256)
    }
    @Test fun missingFileSizeDoesNotMakeAReadableFileUnavailable() {
        val target=ByteArrayOutputStream();val result=StatusRules.copy(ByteArrayInputStream(ByteArray(777) {it.toByte()}),target,null)
        assertEquals(777L,result.bytes);assertEquals(777,target.size())
    }
    @Test fun emptyAndTruncatedSourcesCannotReportSuccess() {
        fails(StatusProblem.SOURCE_CHANGED) {StatusRules.copy(ByteArrayInputStream(byteArrayOf()),ByteArrayOutputStream(),null)}
        fails(StatusProblem.SOURCE_CHANGED) {StatusRules.copy(ByteArrayInputStream(byteArrayOf(1,2)),ByteArrayOutputStream(),3)}
    }
    @Test fun knownAndUnknownOversizedStreamsAreBounded() {
        fails(StatusProblem.TOO_LARGE) {StatusRules.copy(ByteArrayInputStream(ByteArray(9)),ByteArrayOutputStream(),9,limit=8)}
        fails(StatusProblem.TOO_LARGE) {StatusRules.copy(ByteArrayInputStream(ByteArray(9)),ByteArrayOutputStream(),null,limit=8)}
    }
    @Test fun interruptionAndWriteFailurePropagateWithoutPretendingCompletion() {
        var checks=0
        try {StatusRules.copy(ByteArrayInputStream(ByteArray(200000)),ByteArrayOutputStream(),null) {if(++checks==2) throw java.util.concurrent.CancellationException()};fail()}
        catch(_:java.util.concurrent.CancellationException) {}
        try {StatusRules.copy(ByteArrayInputStream(byteArrayOf(1,2)),object:java.io.OutputStream() {override fun write(value:Int) {throw java.io.IOException("ENOSPC")}},2);fail()}
        catch(_:java.io.IOException) {}
    }
    @Test fun duplicateIdentityDoesNotDependOnSourceFilename() {
        val bytes=ByteArray(900) {it.toByte()}
        val first=StatusRules.copy(ByteArrayInputStream(bytes),ByteArrayOutputStream(),null)
        val renamed=StatusRules.copy(ByteArrayInputStream(bytes),ByteArrayOutputStream(),null)
        assertEquals(first.sha256,renamed.sha256)
        assertNotEquals(StatusRules.fingerprint("content://a/old",900,1),StatusRules.fingerprint("content://a/renamed",900,1))
    }
    @Test fun customDestinationsMustMatchTypedMediaStoreCollections() {
        assertEquals("Pictures/Chosen/",StatusRules.customPath("com.android.externalstorage.documents","primary:Pictures/Chosen",false))
        assertEquals("Movies/Chosen/",StatusRules.customPath("com.android.externalstorage.documents","primary:Movies/Chosen",true))
        assertNull(StatusRules.customPath("com.android.externalstorage.documents","primary:Music",true))
        assertNull(StatusRules.customPath("com.android.externalstorage.documents","primary:Movies/Chosen",false))
        assertNull(StatusRules.customPath("com.android.externalstorage.documents","secondary:Movies/Chosen",true))
        assertNull(StatusRules.customPath("remote.provider","primary:Pictures/Chosen",false))
    }
    @Test fun destinationsCannotWriteIntoTheOriginalStatusDirectoryOrTraverse() {
        for(path in listOf("primary:Pictures/.Statuses","primary:DCIM/Statuses","primary:Pictures/../Android/media/com.whatsapp","primary:Pictures//folder","primary:Pictures/./folder"))
            assertNull(path,StatusRules.customPath("com.android.externalstorage.documents",path,false))
    }
    @Test fun whatsappAndBusinessUseIndependentPermissionKeys() {
        assertNotEquals(StatusSource.WHATSAPP.key,StatusSource.BUSINESS.key)
        assertEquals("com.whatsapp",StatusSource.WHATSAPP.packageName)
        assertEquals("com.whatsapp.w4b",StatusSource.BUSINESS.packageName)
    }
}
