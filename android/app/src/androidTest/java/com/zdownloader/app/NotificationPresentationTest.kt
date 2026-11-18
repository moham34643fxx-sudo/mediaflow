package com.zdownloader.app

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.background.DownloadNotifications
import com.zdownloader.app.data.DownloadEntity
import com.zdownloader.app.domain.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Controlled persisted presentation fixture, separate from real transfer regressions. */
class NotificationPresentationTest {
    @Test fun ongoingGroupReflectsRoomProgressAndProcessing()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as ZApplication
        val manager=app.getSystemService(NotificationManager::class.java)
        val id="ui-notification-${UUID.randomUUID()}"
        val format=Format.parse(JSONObject().put("format_id","ui-fixture").put("url","https://example.com/presentation.mp4")
            .put("protocol","https").put("ext","mp4").put("vcodec","h264").put("acodec","aac").put("filesize",2048))
        val row=DownloadEntity(id,"https://example.com/presentation.mp4","معاينة إشعار التنزيل — عنوان طويل", "UI fixture",null,10.0,
            Selection(format).json(),"ui-fixture.mp4",null,id,status="DOWNLOADING_VIDEO",bytes=1024,total=2048,speed=128,nextAttempt=Long.MAX_VALUE)
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
        fun render(name:String,notice:Notification) {
            instrumentation.runOnMainSync {
                val builder=Notification.Builder.recoverBuilder(app,notice)
                val remote=builder.createBigContentView() ?: builder.createContentView()
                val view=remote.apply(app,FrameLayout(app))
                val width=app.resources.displayMetrics.widthPixels
                view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                view.layout(0,0,width,view.measuredHeight)
                val bitmap=Bitmap.createBitmap(width,view.measuredHeight,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap));File(folder,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
        }
        try {
            app.repository.dao.insert(row)
            val summary=app.notifications.summary()
            assertTrue(summary.flags and Notification.FLAG_ONGOING_EVENT!=0)
            assertTrue(summary.flags and Notification.FLAG_GROUP_SUMMARY!=0)
            assertEquals(DownloadNotifications.ACTIVE_GROUP,summary.group)
            assertEquals("Required group header must not freeze a progress snapshot",0,summary.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
            render("notification-active-summary",summary)
            app.notifications.progress(row,1024,2048,128)
            if(manager.areNotificationsEnabled()) {
                val child=manager.activeNotifications.single {it.id==id.hashCode()}.notification
                assertEquals(summary.group,child.group);assertEquals(50,child.extras.getInt(Notification.EXTRA_PROGRESS))
                render("notification-active-item",child)
            }
            app.repository.dao.state(id,"MERGING")
            val processing=row.copy(status="MERGING")
            app.notifications.processing(processing,"MERGING")
            if(manager.areNotificationsEnabled()) {
                val processingNotice=manager.activeNotifications.single {it.id==id.hashCode()}.notification
                assertTrue(processingNotice.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
                render("notification-processing-item",processingNotice)
            }
            File(folder,"notification-presentation.json").writeText(JSONObject().put("fixture","controlled Room presentation data; no network transfer")
                .put("group",summary.group).put("required_notice_ongoing",true).put("summary_grouped",true).put("persisted_percent",50)
                .put("processing_indeterminate",true).put("system_notifications_enabled",manager.areNotificationsEnabled()).toString(2))
        } finally {
            app.notifications.dismiss(id);app.repository.cancel(id);app.coordinator.stopAndAwait(id)
            app.repository.remove(id);app.storage.removeTemporary(id)
        }
    }
}
