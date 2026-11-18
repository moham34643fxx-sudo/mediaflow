package com.zdownloader.app

import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.zdownloader.app.updates.ProductionEnrollmentWorker
import com.zdownloader.app.updates.PushNoticeWorker
import com.zdownloader.app.updates.PushTokenWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class RetiredControlIntegrationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun persistedControlJobsTerminateWithoutRetryOrRemoteRegistration():Unit=runBlocking {
        val workers=listOf(TestListenableWorkerBuilder<ProductionEnrollmentWorker>(context).build(),
            TestListenableWorkerBuilder<PushTokenWorker>(context).build(),
            TestListenableWorkerBuilder<PushNoticeWorker>(context).build())
        for(worker in workers) {
            val future=withContext(Dispatchers.Main) {worker.startWork()}
            try {
                val result=withContext(Dispatchers.IO) {future.get(10,TimeUnit.SECONDS)}
                assertEquals(ListenableWorker.Result.failure(workDataOf("reason" to "control_integration_retired")),result)
            } finally {future.cancel(true)}
        }
    }

    @Suppress("DEPRECATION")
    @Test fun installedManifestHasNoPushServiceAndRetainsBothUpdateReceivers() {
        val info=context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS)
        val components=info.services.orEmpty().map {it.name}+info.providers.orEmpty().map {it.name}
        assertFalse(components.any {it.startsWith("com.google.firebase.") || it.endsWith(".MediaFlowMessagingService")})
        val receivers=info.receivers.orEmpty().map {it.name}
        assertTrue(receivers.contains("com.zdownloader.app.updates.UpdateInstallReceiver"))
        assertTrue(receivers.contains("com.zdownloader.app.updates.UpdateReplacedReceiver"))
    }
}
