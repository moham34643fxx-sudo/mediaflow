package com.zdownloader.app

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** System shade verification deliberately runs without an Espresso/Compose activity rule. */
class BrandNotificationTest {
    @Test fun notificationUsesDedicatedMonochromeZdIcon() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as ZApplication
        val automation=instrumentation.uiAutomation
        fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use {it.readText()}
        val notification=app.notifications.summary()
        assertEquals(R.drawable.ic_zd_notification,notification.smallIcon.resId)
        assertEquals("MediaFlow",notification.extras.getString(Notification.EXTRA_TITLE))
        val manager=app.getSystemService(NotificationManager::class.java)
        assertTrue("Notification permission is required for system rendering verification",manager.areNotificationsEnabled())
        val run=InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "manual"
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
        val id=0x5a4404
        try {
            manager.notify(id,notification)
            Thread.sleep(1000)
            assertTrue(manager.activeNotifications.any {it.id==id})
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
            Thread.sleep(1500)
            val deadline=SystemClock.elapsedRealtime()+5000
            while(automation.rootInActiveWindow?.packageName?.toString()?.contains("systemui")!=true&&SystemClock.elapsedRealtime()<deadline) Thread.sleep(50)
            val image=automation.takeScreenshot() ?: error("Screenshot unavailable")
            File(folder,"mediaflow-notification-panel.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
            image.recycle()
            // OEM SystemUI may omit notification text from the accessibility tree.
            // Posting/icon are asserted above; the retained system screenshot is
            // reviewed separately for the visible name and actual rendered glyph.
            assertTrue("Notification panel did not open",automation.rootInActiveWindow?.packageName?.toString()?.contains("systemui")==true)
        } finally {manager.cancel(id);shell("cmd statusbar collapse")}
    }
}
