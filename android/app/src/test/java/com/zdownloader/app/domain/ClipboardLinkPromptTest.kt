package com.zdownloader.app.domain

import org.junit.Assert.*
import org.junit.Test

class ClipboardLinkPromptTest {
    @Test fun dismissedCopyDoesNotReappearAcrossForegroundChecksOrPolicyRecreation() {
        val policy=ClipboardLinkPrompt()
        policy.offer("Watch https://example.com/video",100)
        assertEquals("https://example.com/video",policy.pending?.url)
        policy.consume()
        val fingerprint=checkNotNull(policy.lastHandledFingerprint)
        assertTrue(fingerprint.matches(Regex("[a-f0-9]{64}")))
        policy.offer("https://example.com/video",100)
        assertNull(policy.pending)
        val recreated=ClipboardLinkPrompt(fingerprint)
        recreated.offer("https://example.com/video",100)
        assertNull(recreated.pending)
    }

    @Test fun copyingTheSameLinkAgainIsANewOfferAndChangedClipboardReplacesAnOldOffer() {
        val policy=ClipboardLinkPrompt()
        policy.offer("https://example.com/video",100);policy.consume()
        policy.offer("https://example.com/video",101)
        assertNotNull(policy.pending)
        policy.offer("https://example.com/other",102)
        assertEquals("https://example.com/other",policy.pending?.url)
        policy.offer("ordinary notes",103)
        assertNull(policy.pending)
    }

    @Test fun unsafeAmbiguousEmptyOrOversizedClipboardNeverBecomesADownloadOffer() {
        val policy=ClipboardLinkPrompt()
        // Boundary cases: absent text, non-links, unsafe schemes/hosts, more than
        // one distinct link, oversized input, and oversized individual URLs.
        for(text in listOf(null,"","ordinary notes","http://example.com/video","javascript:alert(1)",
            "https://127.0.0.1/private","https://example.com/a https://example.com/b",
            "https://example.com/"+"x".repeat(UrlPolicy.MAX_INPUT_LENGTH),
            "https://example.com/"+"x".repeat(UrlPolicy.MAX_URL_LENGTH))) {
            policy.offer(text,100)
            assertNull("Unsafe/ambiguous clipboard became an offer",policy.pending)
            assertNull(policy.lastHandledFingerprint)
        }
    }

    @Test fun explicitShareIsNotOfferedAgainButAnUnrelatedCopyStillCanBeOffered() {
        val policy=ClipboardLinkPrompt()
        policy.offer("https://example.com/video",100,alreadyHandling="https://example.com/video")
        assertNull(policy.pending)
        assertNotNull(policy.lastHandledFingerprint)
        policy.offer("https://example.com/video",101)
        assertNotNull(policy.pending)
        policy.offer("https://example.com/other",101,alreadyHandling="https://example.com/video")
        assertEquals("https://example.com/other",policy.pending?.url)
    }
}
