package com.zdownloader.app

import com.zdownloader.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SecurityUrlTest {
    @Test fun boundedInputsAndControlCharactersAreRejectedWithoutDns() {
        for(value in listOf("https://example.com/"+"x".repeat(100000),"https://exa\nmple.com/","https://example.com/a\u0000b")) {
            try {UrlPolicy.normalize(value);fail("Expected rejection")} catch(_:MediaException) {}
        }
        try {UrlPolicy.inputLinks("x".repeat(UrlPolicy.MAX_INPUT_LENGTH+1));fail()} catch(_:MediaException) {}
        assertEquals("https://example.com/a?q=%2Fsigned%2Bvalue",UrlPolicy.normalize("https://example.com/a?q=%2Fsigned%2Bvalue#local"))
    }
    @Test fun deterministicMalformedUnicodeCorpusNeverEscapesAsUnexpectedException() {
        val random=Random(41014)
        repeat(2000) {
            val input=buildString {repeat(random.nextInt(0,180)) {append(random.nextInt(0,65536).toChar())}}
            try {val result=UrlPolicy.normalize(input);assertTrue(result.startsWith("https://",true))}
            catch(_:MediaException) {}
        }
    }
    @Test fun filenamesRemainSingleBoundedComponentsForAdversarialUnicode() {
        val random=Random(41015)
        repeat(1000) {
            val source=buildString {repeat(random.nextInt(0,600)) {append(random.nextInt(0,65536).toChar())}}
            val safe=FileNames.output(source,"mp4","{title}")
            assertFalse(safe.contains('/'));assertFalse(safe.contains('\\'))
            assertTrue(safe.toByteArray(Charsets.UTF_8).size<=204)
            assertTrue(safe.endsWith(".mp4"));assertFalse(safe.startsWith('.'))
        }
    }
}
