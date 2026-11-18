package com.zdownloader.app

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ShareTargetContractTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext
    private fun target(intent:Intent)=app.packageManager.queryIntentActivities(intent.setPackage(app.packageName),PackageManager.MATCH_DEFAULT_ONLY)
        .map {it.activityInfo.name}.toSet()

    @Test fun onlyRelevantTextAndMediaSharesResolveToTheirMatchingBrandedTarget() {
        assertEquals(setOf(ShareReceiverActivity::class.java.name),target(Intent(Intent.ACTION_SEND).setType("text/plain")))
        assertEquals(setOf(ShareReceiverActivity::class.java.name),target(Intent(Intent.ACTION_SEND).setType("text/uri-list")))
        assertEquals(setOf(ShareReceiverActivity::class.java.name),target(Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/uri-list")))
        for(type in listOf("video/mp4","video/webm","image/jpeg")) {
            val expected=setOf(com.zdownloader.app.status.StatusImportActivity::class.java.name)
            assertEquals(type,expected,target(Intent(Intent.ACTION_SEND).setType(type)))
            assertEquals(type,expected,target(Intent(Intent.ACTION_SEND_MULTIPLE).setType(type)))
        }
        assertTrue("Do not claim unrelated document/audio types",target(Intent(Intent.ACTION_SEND).setType("application/pdf")).isEmpty())
        val text=app.packageManager.getActivityInfo(android.content.ComponentName(app,ShareReceiverActivity::class.java),0)
        assertEquals(app.getString(R.string.share_link_target),text.loadLabel(app.packageManager).toString())
    }

    @Test fun videoMimeTextUrlRoutesToTheLinkDownloaderInsteadOfEmptyFileImport() {
        val incoming=Intent(app,com.zdownloader.app.status.StatusImportActivity::class.java)
            .setAction(Intent.ACTION_SEND).setType("video/mp4")
            .putExtra(Intent.EXTRA_TEXT,"https://example.com/watch?v=mediaflow-share-target")
        instrument.runOnMainSync {app.startActivity(incoming.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
        try {
            val deadline=android.os.SystemClock.elapsedRealtime()+10_000
            var received=false
            while(!received&&android.os.SystemClock.elapsedRealtime()<deadline) {
                instrument.waitForIdleSync()
                val active=AtomicBoolean(false)
                instrument.runOnMainSync {
                    active.set(
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .any {it is ShareReceiverActivity})
                }
                received=active.get()
                if(!received) Thread.sleep(50)
            }
            assertTrue("video/* + EXTRA_TEXT URL was dropped into the file importer",received)
        } finally {
            instrument.runOnMainSync {
                listOf(Stage.RESUMED,Stage.STARTED,Stage.PAUSED,Stage.STOPPED).flatMap {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
                }.distinct().filter {it is ShareReceiverActivity||it is com.zdownloader.app.status.StatusImportActivity}
                    .forEach {it.finish()}
            }
        }
    }
}
