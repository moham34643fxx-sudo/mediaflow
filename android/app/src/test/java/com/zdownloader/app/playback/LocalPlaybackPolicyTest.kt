package com.zdownloader.app.playback

import org.junit.Assert.*
import org.junit.Test

class LocalPlaybackPolicyTest {
    @Test fun largeLibrarySelectionAlwaysIncludesTheRequestedTrack() {
        val media=(0..1200).map {PlaybackMedia("content://media/external/audio/media/$it","Track $it",false)}
        for(index in listOf(0,499,500,750,1200)) {
            val (queue,selected)=LocalPlaybackPolicy.queue(media,index)
            assertEquals(500,queue.size)
            assertEquals(media[index],queue[selected])
        }
    }
    @Test fun queueSelectionSurvivesInvalidAndDuplicateReferences() {
        val first=PlaybackMedia("content://media/audio/1","First",false)
        val second=first.copy(uri="content://media/audio/2")
        val (queue,selected)=LocalPlaybackPolicy.queue(listOf(first,first,first.copy(uri="https://example.com/private"),second),3)
        assertEquals(listOf(first,second),queue);assertEquals(1,selected)
        assertTrue(LocalPlaybackPolicy.queue(emptyList(),0).first.isEmpty())
    }
    @Test fun onlyLocalContentUrisCanReachThePlaybackDataSource() {
        assertTrue(LocalPlaybackPolicy.accepts("content://media/external/audio/media/12"))
        assertTrue(LocalPlaybackPolicy.accepts("content://com.android.externalstorage.documents/document/primary%3AMusic%2Fa.mp3"))
        listOf("https://example.com/a.mp3","file:///data/data/com.zdownloader.app/files/private","data:audio/wav;base64,AA","javascript:alert(1)","content:///missing-authority","content://media/a#fragment","content://media/"+"x".repeat(8192)).forEach {assertFalse(it,LocalPlaybackPolicy.accepts(it))}
    }
    @Test fun resumeNeverRestartsSoundAndFinishedPositionsRestartAtZero() {
        assertEquals(0L,LocalPlaybackPolicy.resume(-1,10000));assertEquals(0L,LocalPlaybackPolicy.resume(9900,10000))
        assertEquals(4200L,LocalPlaybackPolicy.resume(4200,10000));assertEquals(4200L,LocalPlaybackPolicy.resume(4200,0))
    }
    @Test fun subtitleTypesAreExplicitAndNotInferredFromRemoteUrls() {
        assertEquals("application/x-subrip",LocalPlaybackPolicy.subtitleMime("Captions.SRT"))
        assertEquals("text/vtt",LocalPlaybackPolicy.subtitleMime("captions.vtt"));assertEquals("text/x-ssa",LocalPlaybackPolicy.subtitleMime("captions.ass"))
        assertNull(LocalPlaybackPolicy.subtitleMime("captions.exe"));assertNull(LocalPlaybackPolicy.subtitleMime("captions.srt?url=https://example.com"))
    }
}
