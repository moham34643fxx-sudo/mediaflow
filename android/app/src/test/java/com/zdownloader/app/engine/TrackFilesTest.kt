package com.zdownloader.app.engine

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TrackFilesTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun corruptionInvalidatesOnlyThatTrackAndItsResumeFragments() {
        val dir=temp.newFolder("owned")
        val bad=File(dir,"primary.mp4").apply {writeText("bad")}
        val caches=listOf("primary.mp4.complete","primary.mp4.part","primary.mp4.resume","primary.mp4.ytdl","primary.mp4.part-Frag1")
            .map {File(dir,it).apply {writeText("bad cache")}}
        val audio=File(dir,"secondary.m4a").apply {writeText("healthy audio")}
        val other=File(dir,"unrelated.bin").apply {writeText("keep")}
        TrackFiles.invalidate(bad,dir)
        assertFalse(bad.exists());assertTrue(caches.none {it.exists()})
        assertEquals("healthy audio",audio.readText());assertEquals("keep",other.readText())
    }
    @Test fun aTrackOutsideTheOwnedDirectoryCannotBeDeleted() {
        val dir=temp.newFolder("owned")
        val outside=temp.newFile("outside.mp4").apply {writeText("preserve")}
        try {TrackFiles.invalidate(outside,dir);fail("Outside track accepted")} catch(_:IllegalArgumentException) { }
        assertEquals("preserve",outside.readText())
    }
}
