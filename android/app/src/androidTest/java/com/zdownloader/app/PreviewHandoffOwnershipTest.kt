package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.playback.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PreviewHandoffOwnershipTest {
    @Test fun supersededHandoffAndStaleCleanupCannotReplaceOrStopNewerItem()=runBlocking<Unit> {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        check(app.packageName=="com.zdownloader.app.candidate0965")
        DisposableDocuments().use {docs->
            val a=docs.create(docs.home,"A.m4a").toString()
            val b=docs.create(docs.home,"B.m4a").toString()
            val connection=app.playback
            withContext(Dispatchers.Main) {
                connection.attach()
                try {
                    val controller=connection.connect()
                    var checks=0
                    // The second ownership check is exactly after async connect and
                    // before any setMediaItem/prepare/play from the stale request.
                    val failed=runCatching {connection.handoffExternalPreview(PlaybackMedia(a,"A",false),1000) {
                        checks++
                        if(checks==1) true else {
                            controller.setMediaItem(PlaybackMedia(b,"B",false).item())
                            false
                        }
                    }}
                    assertTrue(failed.exceptionOrNull() is CancellationException)
                    assertEquals(b,controller.currentMediaItem!!.mediaId)
                    assertFalse("Cleanup from A cannot stop B",connection.stopPreviewHandoff(a))
                    assertEquals(b,controller.currentMediaItem!!.mediaId)
                    controller.setMediaItem(PlaybackMedia(a,"A",false).item())
                    connection.playUri(b,open=false)
                    assertFalse("Cleanup cannot cancel a newer request still resolving behind A",connection.stopPreviewHandoff(a))
                } finally {
                    connection.stopAudio()
                    connection.detach()
                }
            }
        }
    }
}
