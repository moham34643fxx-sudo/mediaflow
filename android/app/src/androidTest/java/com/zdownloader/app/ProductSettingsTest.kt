package com.zdownloader.app

import android.graphics.Bitmap
import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zdownloader.app.data.Settings
import com.zdownloader.app.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class ProductSettingsTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val settings get()=SettingsRepository(compose.activity.applicationContext)
    private lateinit var original:Settings
    private val folder get()=File(compose.activity.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
    private fun notificationChannelEnabled(channelId:String):Boolean {
        if(Build.VERSION.SDK_INT<26) return true
        return compose.activity.getSystemService(NotificationManager::class.java).getNotificationChannel(channelId)?.importance!=NotificationManager.IMPORTANCE_NONE
    }
    private fun notificationTypeEnabled(channelId:String):Boolean {
        val permissionGranted=Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(compose.activity,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
        return permissionGranted&&NotificationManagerCompat.from(compose.activity).areNotificationsEnabled()&&notificationChannelEnabled(channelId)
    }
    @Before fun prepare()=runBlocking {
        original=settings.current();settings.text("language","en");settings.text("theme","dark")
        settings.notification("completion_notifications",true);settings.notification("failure_notifications",true)
    }
    @After fun restore()=runBlocking {
        settings.text("language",original.language);settings.text("theme",original.theme)
        settings.notification("completion_notifications",original.completionNotifications)
        settings.notification("failure_notifications",original.failureNotifications)
        settings.boolean("notifications",original.notifications)
    }
    private fun capture(name:String) {
        compose.waitForIdle()
        val dialogs=compose.onAllNodes(isDialog())
        val node=if(dialogs.fetchSemanticsNodes().isNotEmpty()) dialogs.onLast() else compose.onAllNodes(isRoot()).onFirst()
        val image=node.captureToImage().asAndroidBitmap()
        File(folder,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun retiredAdministrativeControlsAreAbsentAndUpdatesRemainAccessible() {
        val before=runBlocking {settings.current()}
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("open_privacy_controls"))
        compose.onNodeWithTag("usage_statistics").assertDoesNotExist()
        compose.onNodeWithTag("cloud_notices").assertDoesNotExist()
        compose.onNodeWithTag("open_privacy_controls").performClick()
        compose.onNodeWithTag("information_privacy").assertIsDisplayed()
        for(tag in listOf("privacy_share_diagnostics","privacy_general_messages","privacy_device_registration")) {
            assertThrows(AssertionError::class.java) {
                compose.onNodeWithTag("information_privacy").performScrollToNode(hasTestTag(tag))
            }
        }
        compose.onNodeWithTag("information_back").performClick()
        compose.onNodeWithTag("settings_list").assertIsDisplayed()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("open_updates"))
        compose.onNodeWithTag("open_updates").performClick()
        compose.onNodeWithTag("updates_back").assertIsDisplayed()
        val after=runBlocking {settings.current()}
        assertEquals(before.usageStatistics,after.usageStatistics)
        assertEquals(before.cloudNotices,after.cloudNotices)
    }
    @Test fun settingsSwitchesAboutAndInformationAreLocalizedAndFunctional() {
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("bottom_navigation").assertDoesNotExist()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("notify_completion"))
        val progressAllowed=notificationTypeEnabled(com.zdownloader.app.background.DownloadNotifications.CHANNEL)
        val progressText=if(progressAllowed) "Download progress notifications are allowed by Android"
            else "Download progress notifications are not currently allowed by Android"
        compose.waitUntil(5000) {compose.onAllNodesWithText(progressText).fetchSemanticsNodes().isNotEmpty()}
        val completionAllowed=notificationTypeEnabled(com.zdownloader.app.background.DownloadNotifications.COMPLETED_CHANNEL)
        if(completionAllowed) {
            compose.onNodeWithTag("notify_completion").assertIsOn()
            compose.onNodeWithTag("notify_completion").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
            compose.waitUntil(5000) {runBlocking {!settings.current().completionNotifications}}
            compose.onNodeWithTag("notify_completion").assertIsOff()
        } else {
            compose.onNodeWithTag("notify_completion").assertIsOff()
            assertTrue("A denied OS permission must not erase the saved preference",runBlocking {settings.current().completionNotifications})
        }
        compose.onNodeWithText("Notify me when a file has been saved successfully.").assertExists()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("notify_failure"))
        val failureAllowed=notificationTypeEnabled(com.zdownloader.app.background.DownloadNotifications.FAILED_CHANNEL)
        if(failureAllowed) {
            compose.onNodeWithTag("notify_failure").assertIsOn()
            compose.onNodeWithTag("notify_failure").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
            compose.waitUntil(5000) {runBlocking {!settings.current().failureNotifications}}
            compose.onNodeWithTag("notify_failure").assertIsOff()
        } else {
            compose.onNodeWithTag("notify_failure").assertIsOff()
            assertTrue("A denied OS permission must not erase the saved preference",runBlocking {settings.current().failureNotifications})
        }
        if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(compose.activity,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("notification_permission_denied"))
            compose.onNodeWithTag("notification_permission_denied").assertIsDisplayed()
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("request_notification_permission"))
            compose.onNodeWithTag("request_notification_permission").assertIsDisplayed()
        }
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("android_notification_settings"))
        compose.onNodeWithTag("android_notification_settings").assertIsDisplayed()
        capture("settings-notifications-en")
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("about_zd"))
        compose.onNodeWithTag("about_zd").performClick()
        val info=compose.activity.packageManager.getPackageInfo(compose.activity.packageName,0)
        compose.onNodeWithTag("about_version").assertTextEquals("Version ${info.versionName}")
        compose.onNodeWithTag("about_developer").assertTextEquals("Powered by Mohamed Abuzaid")
        compose.onNodeWithText("com.zdownloader.app").assertDoesNotExist()
        capture("about-en")
        compose.onNodeWithTag("privacy_information").performScrollTo().performClick()
        compose.onNodeWithTag("information_privacy").assertIsDisplayed()
        compose.onNodeWithTag("information_privacy").performScrollToNode(hasTestTag("open_source_licenses"))
        compose.onNodeWithTag("open_source_licenses").performClick()
        compose.onNodeWithTag("information_licenses").assertIsDisplayed()
        compose.onNodeWithTag("information_back").performClick()
        compose.onNodeWithTag("information_privacy").assertIsDisplayed()
        compose.onNodeWithTag("information_back").performClick()
        compose.onNodeWithTag("information_back").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("language_settings"))
        compose.onNodeWithTag("language_settings").performClick()
        compose.onNodeWithTag("language_ar").performClick()
        compose.waitUntil(5000) {runBlocking {settings.current().language=="ar"}}
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("notify_completion"))
        compose.onNodeWithTag("notify_completion").assertIsOff()
        compose.onNodeWithText("إظهار إشعار عند حفظ الملف بنجاح.").assertExists()
        capture("settings-notifications-ar")
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("about_zd"))
        compose.onNodeWithTag("about_zd").performClick()
        compose.onNodeWithTag("about_developer").assertTextEquals("Powered by Mohamed Abuzaid")
        capture("about-ar")
        File(folder,"consumer-settings.json").writeText(JSONObject().put("completion_disabled_persisted",true).put("failure_disabled_persisted",true)
            .put("verified_version_name",info.versionName).put("about_ar_en",true).put("privacy_legal_licenses_navigation",true).toString(2))
    }
}
