package com.zdownloader.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import com.zdownloader.app.adblock.BrowserServiceWorkerInterceptor
import com.zdownloader.app.data.DownloadRepository
import com.zdownloader.app.engine.QueueCoordinator
import com.zdownloader.app.engine.Extractor
import com.zdownloader.app.background.DownloadNotifications
import com.zdownloader.app.data.StorageRepository
import javax.inject.Inject
import kotlinx.coroutines.*

@HiltAndroidApp
class ZApplication : Application(), coil.ImageLoaderFactory, androidx.work.Configuration.Provider {
    internal val playback by lazy {com.zdownloader.app.playback.PlaybackConnection(this)}
    internal val audioExtraction by lazy {com.zdownloader.app.extraction.ExtractionRepository(this)}
    override val workManagerConfiguration:androidx.work.Configuration
        get()=androidx.work.Configuration.Builder().setJobSchedulerJobIdRange(20000,30000).build()
    @Inject lateinit var coordinator: QueueCoordinator
    @Inject lateinit var repository: DownloadRepository
    @Inject lateinit var database:com.zdownloader.app.data.ZDatabase
    @Inject lateinit var playlists: com.zdownloader.app.data.PlaylistRepository
    @Inject lateinit var notifications: DownloadNotifications
    @Inject lateinit var extractor: Extractor
    @Inject lateinit var storage: StorageRepository
    @Inject lateinit var mediaFiles: com.zdownloader.app.data.MediaFiles
    @Inject lateinit var plannedDownloads: com.zdownloader.app.background.PlannedDownloads
    @Inject lateinit var storageManagement: com.zdownloader.app.data.StorageManagement
    @Inject lateinit var mediaAssets: com.zdownloader.app.data.MediaAssets
    @Inject lateinit var scheduler: com.zdownloader.app.background.Scheduler
    @Inject lateinit var transfer: com.zdownloader.app.engine.Transfer
    override fun onCreate() {
        com.zdownloader.app.data.SettingsRepository.shouldShowWelcome(this)
        super.onCreate()
        // Install before an Activity can create a WebView or register a worker.
        // The client is pass-through unless an ordinary browser page is active.
        BrowserServiceWorkerInterceptor.install()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {
                runCatching { com.zdownloader.app.engine.NetworkDiagnostics.start(this@ZApplication) }
                plannedDownloads.restore()
                // Recover an intent even if the process died in the short gap
                // between its Room commit and scheduler acknowledgement. This
                // is ordinary quota-managed work, never a background UIDT/FGS.
                if(repository.dao.recoveryNeeded()) scheduler.scheduleRecovery(0)
            } catch(e:CancellationException) {throw e} catch(_:Exception) {com.zdownloader.app.engine.NetworkDiagnostics.record("scheduler","startup_recovery_failed",com.zdownloader.app.domain.ErrorCode.SCHEDULING_FAILED)}
        }
    }
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this).okHttpClient(transfer.client).components {add(coil.decode.VideoFrameDecoder.Factory())}.build()
}
