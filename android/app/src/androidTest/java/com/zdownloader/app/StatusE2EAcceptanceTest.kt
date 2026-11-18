package com.zdownloader.app

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.status.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class StatusE2EAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation: UiAutomation get() = instrumentation.uiAutomation

    private fun clickNodeWithId(viewId: String, timeoutMs: Long = 10000): Boolean {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            val root = automation.rootInActiveWindow
            if (root != null) {
                val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
                if (nodes.isNotEmpty()) {
                    for (node in nodes) {
                        if (node.isClickable && node.isEnabled) {
                            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                return true
                            }
                        }
                    }
                }
            }
            SystemClock.sleep(200)
        }
        return false
    }

    private fun clickNodeWithText(text: String, timeoutMs: Long = 5000): Boolean {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            val root = automation.rootInActiveWindow
            if (root != null) {
                val nodes = root.findAccessibilityNodeInfosByText(text)
                if (nodes.isNotEmpty()) {
                    for (node in nodes) {
                        if (node.isClickable && node.isEnabled) {
                            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                return true
                            }
                        }
                    }
                }
            }
            SystemClock.sleep(200)
        }
        return false
    }

    @Test
    fun endToEndRealWhatsAppStatusWorkflow() = runBlocking {
        val prefs = context.getSharedPreferences("status_saver_sources", Context.MODE_PRIVATE)
        val oldPref = prefs.getString(StatusSource.WHATSAPP.key, null)
        val settings = SettingsRepository(context)

        try {
            // Step 1: Verify ACCESS_REQUIRED on an unconfigured, ungranted source
            val initialPermissions = StatusPermissions(context, settings)
            try {
                initialPermissions.location(StatusSource.BUSINESS)
                fail("Expected ACCESS_REQUIRED for ungranted Business source")
            } catch (e: StatusException) {
                assertEquals(StatusProblem.ACCESS_REQUIRED, e.problem)
            }

            // Step 2: For WhatsApp, ensure permission is configured or discovered via SAF
            var currentPermissions = initialPermissions
            var configured = currentPermissions.configured(StatusSource.WHATSAPP)
            if (configured == null) {
                // Test DocumentsUI picker grant flow
                val chosenUri = CompletableDeferred<Uri?>()
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.activityResultRegistry.register(
                            "e2e-status-test",
                            ActivityResultContracts.OpenDocumentTree()
                        ) { uri ->
                            chosenUri.complete(uri)
                        }.launch(initialPermissions.initial(StatusSource.WHATSAPP))
                    }

                    // In DocumentsUI: Click "Use this folder" (android:id/button1)
                    val clickedUseFolder = clickNodeWithId("android:id/button1", 10000)
                    assertTrue("Should find and click 'Use this folder' button in DocumentsUI", clickedUseFolder)

                    // In the system confirmation dialog: Click "Allow" (android:id/button1 or text)
                    SystemClock.sleep(500)
                    val clickedAllow = clickNodeWithId("android:id/button1", 5000) ||
                            clickNodeWithText("السماح", 3000) ||
                            clickNodeWithText("Allow", 3000)
                    assertTrue("Should find and click 'Allow' button in confirmation dialog", clickedAllow)

                    val grantedUri = withTimeoutOrNull(15000) { chosenUri.await() }
                    assertNotNull("DocumentsUI should return a granted tree URI", grantedUri)

                    val accepted = initialPermissions.choose(StatusSource.WHATSAPP, grantedUri!!)
                    assertTrue("Persistable grant should be accepted", accepted)
                }
                configured = currentPermissions.configured(StatusSource.WHATSAPP)
            }

            assertNotNull("Source should now be configured", configured)
            assertFalse("Source should not be session-only", currentPermissions.sourceIsSessionOnly(StatusSource.WHATSAPP))

                // Step 4: Scan and discover real WhatsApp statuses
                val scanner = StatusMediaScanner(context, initialPermissions)
                val scan = scanner.scan(StatusSource.WHATSAPP)
                println("E2E SCAN RESULT: items=${scan.items.size}, skipped=${scan.skipped}, limited=${scan.limited}")
                assertTrue("Should discover real status items (expected >= 10, found ${scan.items.size})", scan.items.size >= 10)
                assertEquals("No supported status files should be skipped in a standard WhatsApp status folder", 0, scan.skipped)

                // Step 5: Verify no duplicates, valid dimensions, and distinct media types
                val distinctKeys = scan.items.map { it.key }.distinct()
                assertEquals("All status items must have unique keys", scan.items.size, distinctKeys.size)

                var photoCount = 0
                var videoCount = 0
                for (item in scan.items) {
                    assertTrue("Width must be positive", item.width > 0)
                    assertTrue("Height must be positive", item.height > 0)
                    assertTrue("Bytes must be positive", (item.bytes ?: 0) > 0)
                    if (item.type.video) {
                        videoCount++
                        assertEquals("video/mp4", item.type.mime)
                    } else {
                        photoCount++
                        assertTrue("Image mime must be supported", item.type.mime in setOf("image/jpeg", "image/png", "image/webp"))
                    }

                    // Verify thumbnail decode
                    val docUri = Uri.parse(item.uri)
                    val pfd = context.contentResolver.openFileDescriptor(docUri, "r")
                    assertNotNull("Document URI must be readable via ContentResolver", pfd)
                    pfd!!.use {
                        if (item.type.video) {
                            val retriever = android.media.MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(it.fileDescriptor)
                                val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()
                                val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
                                val frame = retriever.getFrameAtTime(0)
                                assertNotNull("Video must yield a valid thumbnail frame", frame)
                                frame!!.recycle()
                                assertEquals(item.width, width)
                                assertEquals(item.height, height)
                            } finally {
                                retriever.release()
                            }
                        } else {
                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, options)
                            assertEquals(item.width, options.outWidth)
                            assertEquals(item.height, options.outHeight)
                        }
                    }
                }
                println("E2E MEDIA TYPES: photos=$photoCount, videos=$videoCount")
                assertTrue("Should discover at least one photo status", photoCount > 0)

                // Step 6: Verify Persistence Across Simulated Restart
                val freshPermissions = StatusPermissions(context, settings)
                val configuredUri = freshPermissions.configured(StatusSource.WHATSAPP)
                assertNotNull("Configured URI must persist across restart", configuredUri)
                assertEquals(initialPermissions.configured(StatusSource.WHATSAPP), configuredUri)

                val location = freshPermissions.location(StatusSource.WHATSAPP)
                assertTrue("Location must be Location.Granted after restart", location is StatusPermissions.Location.Granted)

                val freshScanner = StatusMediaScanner(context, freshPermissions)
                val freshScan = freshScanner.scan(StatusSource.WHATSAPP)
                assertEquals("Scan after restart must discover identical item count", scan.items.size, freshScan.items.size)
                assertEquals("Scan items after restart must match", scan.items.map { it.key }, freshScan.items.map { it.key })

                // Step 7: Verify StatusSaveCoordinator saves a copy accurately
                val store = StatusStore(context)
                val saver = StatusSaveCoordinator(context, freshPermissions, freshScanner, store, settings)
                val sampleItem = scan.items.first()
                val saveResult = saver.save(sampleItem)
                assertNotNull("Saved URI must not be null", saveResult.uri)
                println("E2E SAVE RESULT: uri=${saveResult.uri}, alreadySaved=${saveResult.alreadySaved}")

                // Re-saving the same item should recognize duplicate
                val duplicateResult = saver.save(sampleItem)
                assertTrue("Duplicate save must report alreadySaved", duplicateResult.alreadySaved)
                assertEquals(saveResult.uri, duplicateResult.uri)

                // Verify saving a video status
                val videoItem = scan.items.firstOrNull { it.type.video }
                assertNotNull("At least one video status must be present", videoItem)
                val videoSaveResult = saver.save(videoItem!!)
                assertNotNull("Saved video URI must not be null", videoSaveResult.uri)
                val dupVideoResult = saver.save(videoItem)
                assertTrue("Duplicate video save must report alreadySaved", dupVideoResult.alreadySaved)
                assertEquals(videoSaveResult.uri, dupVideoResult.uri)
        } finally {
            if (oldPref != null) {
                prefs.edit().putString(StatusSource.WHATSAPP.key, oldPref).commit()
            }
        }
    }
}
