package com.zdownloader.app.domain

import org.junit.Assert.*
import org.junit.Test

class TransferProgressTest {
    @Test fun resumeKeepsTheOtherTracksRetainedBytesBeforeItsFirstCallback() {
        val progress=TransferProgress(listOf(MediaSize(100,false),MediaSize(20,false)),retained=listOf(40L,20L))
        assertEquals(60L,progress.retainedBytes)
        assertEquals(TransferSnapshot(65,120,"EXACT"),progress.update(0,45,100,"EXACT"))
        assertEquals(TransferSnapshot(65,120,"EXACT"),progress.update(1,20,20,"EXACT"))
    }
    @Test fun hlsCombinedRateIsNotCountedAgainAsVideoPlusAudio() {
        val video=Format("v","mp4",720,null,"h264","none",null,1600.0,"m3u8_native","{}",combinedBitrate=1600.0)
        val audio=Format("a","m4a",null,null,"none","aac",null,128.0,"m3u8_native","{}")
        val selected=Selection(video,audio,duration=10.0)
        assertNull(video.size(10.0))
        assertEquals(MediaSize(2_000_000,true),selected.transferSize)
    }
    @Test fun bothTracksAreIncludedEvenBeforeTheOtherTrackReports() {
        val progress=TransferProgress(listOf(MediaSize(100,false),MediaSize(20,false)))
        assertEquals(TransferSnapshot(30,120,"EXACT"),progress.update(0,30,100,"EXACT"))
        assertEquals(TransferSnapshot(40,120,"EXACT"),progress.update(1,10,20,"EXACT"))
        assertEquals(TransferSnapshot(40,120,"EXACT"),progress.update(1,10,20,"EXACT"))
    }
    @Test fun completedTrackRemainsInTheAggregateDuringTheOtherTrack() {
        val progress=TransferProgress(listOf(MediaSize(100,false),null))
        assertNull(progress.update(0,100,100,"EXACT").total)
        assertEquals(TransferSnapshot(110,120,"EXACT"),progress.update(1,10,20,"EXACT"))
    }
    @Test fun estimatesAreLabelledAndBecomeUnknownIfUnderestimated() {
        val progress=TransferProgress(listOf(null))
        assertEquals(TransferSnapshot(20,100,"ESTIMATED"),progress.update(0,20,100,"ESTIMATED"))
        assertEquals(TransferSnapshot(120,null,"UNKNOWN"),progress.update(0,120,100,"ESTIMATED"))
    }
    @Test fun unknownTotalsNeverBorrowTheVideoOnlySize() {
        val progress=TransferProgress(listOf(MediaSize(100,false),null))
        assertEquals(TransferSnapshot(20,null,"UNKNOWN"),progress.update(0,20,100,"EXACT"))
        assertEquals(TransferSnapshot(25,null,"UNKNOWN"),progress.update(1,5,null,"UNKNOWN"))
    }
    @Test fun liveAuthoritativeLengthOverridesAnOldApproximation() {
        val progress=TransferProgress(listOf(MediaSize(90,true)))
        assertEquals(TransferSnapshot(25,100,"EXACT"),progress.update(0,25,100,"EXACT"))
    }
    @Test fun representationReplacementMayDiscardInvalidPartialWithoutDoubleCounting() {
        val progress=TransferProgress(listOf(null))
        progress.update(0,80,100,"EXACT")
        assertEquals(TransferSnapshot(5,120,"EXACT"),progress.update(0,5,120,"EXACT"))
    }
}
