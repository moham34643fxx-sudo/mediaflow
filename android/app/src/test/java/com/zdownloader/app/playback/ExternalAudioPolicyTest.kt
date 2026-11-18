package com.zdownloader.app.playback

import org.junit.Assert.*
import org.junit.Test

class ExternalAudioPolicyTest {
    @Test fun externalVideoUsesAnExplicitLocalContainerAllowlist() {
        for(mime in listOf("video/mp4","video/webm","video/x-matroska","video/3gpp"," AUDIO/MPEG ; charset=binary")) assertTrue(ExternalMediaOpen.acceptsMime(mime))
        for(mime in listOf("video/unknown","application/octet-stream","text/html")) assertFalse(ExternalMediaOpen.acceptsMime(mime))
        assertFalse(ExternalAudioOpen.acceptsMime("video/mp4"))
    }
    @Test fun onlyRegisteredAudioContainersAreAccepted() {
        for(mime in listOf("audio/mpeg","audio/mp4","audio/flac","audio/wav","audio/ogg","audio/opus")) assertTrue(ExternalAudioOpen.acceptsMime(mime))
        for(mime in listOf("video/mp4","text/plain","application/octet-stream","audio/not-a-real-format")) assertFalse(ExternalAudioOpen.acceptsMime(mime))
    }
    @Test fun malformedAndNetworkUrisNeverEnterLocalPlayback() {
        for(uri in listOf("https://example.com/a.mp3","file:///data/data/com.zdownloader.app/private","content://user:pass@provider/a","content://provider:123/a","content://provider/a#fragment")) assertFalse(LocalPlaybackPolicy.accepts(uri))
        assertTrue(LocalPlaybackPolicy.accepts("content://provider/audio/1"))
    }
    @Test fun serviceOwnedGrantHandoffRequiresAnUnpredictableOneTimeAuthorization() {
        val uri="content://provider/audio/1"
        assertFalse(RetainedUriGrantAuthorizations.consume("forged",uri))
        val token=RetainedUriGrantAuthorizations.issue(uri)
        assertFalse(RetainedUriGrantAuthorizations.consume(token,"content://attacker/audio/2"))
        assertFalse(RetainedUriGrantAuthorizations.consume(token,uri))
        val next=RetainedUriGrantAuthorizations.issue(uri)
        assertTrue(RetainedUriGrantAuthorizations.consume(next,uri))
        assertFalse(RetainedUriGrantAuthorizations.consume(next,uri))
    }
}
