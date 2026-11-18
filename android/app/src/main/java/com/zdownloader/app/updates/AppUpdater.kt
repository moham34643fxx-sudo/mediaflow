package com.zdownloader.app.updates

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.zdownloader.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateConfiguration(
    val feedUrl: String,
    val repository: String,
    val channel: String,
    val cooldownMillis: Long = 6 * 60 * 60 * 1000L,
    /** A private test directory allows core testing without touching the app's real updater state. */
    val storageRoot: File? = null
)

/** All long-running work and cancellation belong exclusively to this updater. No Room/media/service APIs. */
@Singleton
class AppUpdater(
    private val context: Context,
    private val configuration: UpdateConfiguration,
    private val http: UpdateHttp = UpdateHttp(),
    private val installedIdentity: () -> InstalledUpdateIdentity = { UpdateApkVerifier.installedIdentity(context) }
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, UpdateConfiguration(
        BuildConfig.UPDATE_FEED_URL, BuildConfig.UPDATE_REPOSITORY,
        if (BuildConfig.QA_SIGNED_RELEASE) "qa" else "stable"
    ))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val foregroundChecks = ForegroundUpdateCheckGate()
    private val journal = UpdateJournal(configuration.storageRoot ?: context.filesDir,
        configuration.repository, configuration.channel, context.packageName)
    private val verifier = UpdateApkVerifier(context, installedIdentity)
    private val mutableState = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private var record = UpdateRecord(UpdateState())
    @Volatile private var paused = false
    @Volatile private var foreground = WeakReference<Activity>(null)
    @Volatile private var pendingUserAction: Intent? = null
    private val recovery = scope.launch { mutex.withLock { guarded { recover() } } }

    fun check(manual: Boolean = false): Job = checkRequested(manual, foregroundEntry = false)

    internal fun checkOnEntry(): Job = checkRequested(manual = false, foregroundEntry = true)

    private fun checkRequested(manual: Boolean, foregroundEntry: Boolean): Job = scope.launch {
        recovery.join()
        if (!mutex.tryLock()) return@launch
        try {
            if (state.value.phase in activePhases || state.value.phase in setOf(UpdatePhase.READY, UpdatePhase.WAITING_PERMISSION)) return@launch
            if (!manual && state.value.asset != null && state.value.phase in setOf(UpdatePhase.PAUSED, UpdatePhase.ERROR)) return@launch
            val now = System.currentTimeMillis()
            if (foregroundEntry && !foregroundChecks.allow(android.os.SystemClock.elapsedRealtime())) return@launch
            if (!manual && !foregroundEntry && state.value.checkedAt > 0 && now - state.value.checkedAt in 0 until configuration.cooldownMillis) return@launch
            guarded {
                val previous = state.value
                publish(previous.copy(phase = UpdatePhase.CHECKING, error = null, automaticPrompt = false, checkedAt = now))
                http.begin()
                val release = UpdateDiscovery(http, configuration.repository, context.packageName)
                    .discover(configuration.feedUrl, configuration.channel)
                when {
                    release == null -> publish(UpdateState(phase = UpdatePhase.NO_RELEASE, checkedAt = now))
                    release.versionCode <= installedIdentity().versionCode -> publish(UpdateState(
                        phase = UpdatePhase.CURRENT, release = release, checkedAt = now))
                    else -> {
                        if (release.minSdk > Build.VERSION.SDK_INT) throw UpdateException(UpdateError.INCOMPATIBLE_SDK)
                        val asset = release.selectAsset(Build.SUPPORTED_ABIS.toList()) ?: throw UpdateException(UpdateError.INCOMPATIBLE_ABI)
                        val same = previous.asset == asset && previous.release?.versionCode == release.versionCode
                        if (!same) { journal.apk.delete(); record = record.copy(etag = null, permissionRequested = false) }
                        publish(UpdateState(phase = if (same && journal.apk.length() > 0) UpdatePhase.PAUSED else UpdatePhase.AVAILABLE,
                            release = release, asset = asset, downloaded = if (same) journal.apk.length() else 0,
                            total = asset.bytes, automaticPrompt = !manual, checkedAt = now))
                    }
                }
            }
        } finally { mutex.unlock() }
    }

    /** Explicit user action is the only entry point to download. Recovery never starts network transfer. */
    fun download(installWhenReady: Boolean = true): Job = scope.launch {
        recovery.join()
        if (!mutex.tryLock()) return@launch
        try {
            if (state.value.phase in activePhases) return@launch
            guarded {
                val release = state.value.release ?: return@guarded
                val asset = state.value.asset ?: return@guarded
                if (release.versionCode <= installedIdentity().versionCode) { markInstalled(); return@guarded }
                paused = false
                http.begin()
                publish(state.value.copy(phase = UpdatePhase.DOWNLOADING, error = null, automaticPrompt = false,
                    autoInstall = installWhenReady, installationPending = false))
                try {
                    var lastProgress = 0L
                    UpdateDownloader(http).download(asset, journal.apk, record.etag, { paused }, { etag ->
                        record = record.copy(etag = etag)
                        publish(state.value)
                    }) { bytes ->
                        val now = System.currentTimeMillis()
                        if (now - lastProgress >= 150 || bytes == asset.bytes) {
                            mutableState.value = state.value.copy(downloaded = bytes)
                            lastProgress = now
                        }
                    }
                } catch (e: Exception) {
                    if (paused) {
                        publish(state.value.copy(phase = UpdatePhase.PAUSED, downloaded = journal.apk.length(),
                            autoInstall = false, installationPending = false))
                        return@guarded
                    }
                    throw e
                }
                if (paused) {
                    publish(state.value.copy(phase = UpdatePhase.PAUSED, downloaded = journal.apk.length(), autoInstall = false))
                    return@guarded
                }
                publish(state.value.copy(phase = UpdatePhase.VERIFYING, downloaded = journal.apk.length()))
                verifier.verify(journal.apk, release, asset)
                publish(state.value.copy(phase = UpdatePhase.READY))
                if (installWhenReady && !paused) foregroundActivity()?.let { stageOrRequestPermission(it) }
            }
        } finally { mutex.unlock() }
    }

    fun pause() {
        if (state.value.phase == UpdatePhase.DOWNLOADING) { paused = true; http.cancel() }
    }

    fun dismissPrompt(): Job = command { publish(state.value.copy(automaticPrompt = false)) }

    fun clearError(): Job = command {
        if (state.value.error != null) publish(state.value.copy(
            phase = if (state.value.phase != UpdatePhase.ERROR) state.value.phase
                else if (state.value.asset != null) UpdatePhase.PAUSED else UpdatePhase.IDLE, error = null))
    }

    fun retry(): Job = if (state.value.asset != null) download(state.value.autoInstall) else check(manual = true)

    /** Call on every Activity onResume. Lifecycle is checked again immediately before every system UI launch. */
    fun onForeground(activity: Activity): Job {
        foreground = WeakReference(activity)
        val foregroundWork = command {
            reconcileInstalled()
            if (state.value.phase == UpdatePhase.INSTALLING) {
                if (pendingUserAction != null) launchPendingUserAction()
                else reconcileSession()
            } else if (state.value.phase == UpdatePhase.WAITING_PERMISSION && record.permissionRequested) {
                if (canInstall()) stageOrRequestPermission(activity)
                else publish(state.value.copy(error = UpdateError.INSTALL_PERMISSION))
            } else if (state.value.phase == UpdatePhase.READY && state.value.autoInstall) {
                stageOrRequestPermission(activity)
            }
        }
        return scope.launch { foregroundWork.join(); checkOnEntry().join() }
    }

    fun onBackground(activity: Activity) {
        if (foreground.get() === activity) foreground.clear()
    }

    /** Explicit retry/install action from a foreground root UI. */
    fun install(activity: Activity): Job {
        foreground = WeakReference(activity)
        return command {
            if (state.value.phase == UpdatePhase.INSTALLING && pendingUserAction != null) launchPendingUserAction()
            else if (state.value.phase in setOf(UpdatePhase.READY, UpdatePhase.WAITING_PERMISSION, UpdatePhase.ERROR) &&
                state.value.asset != null && journal.apk.length() == state.value.total) {
                publish(state.value.copy(autoInstall = true, automaticPrompt = false))
                stageOrRequestPermission(activity)
            }
        }
    }

    private suspend fun stageOrRequestPermission(activity: Activity) {
        val release = state.value.release ?: return
        val asset = state.value.asset ?: return
        if (!isForeground(activity)) return
        // A verified journal is not trusted: verify bytes, identity and installed version again before staging.
        publish(state.value.copy(phase = UpdatePhase.VERIFYING, error = null))
        verifier.verify(journal.apk, release, asset)
        if (!canInstall()) {
            record = record.copy(permissionRequested = true)
            publish(state.value.copy(phase = UpdatePhase.WAITING_PERMISSION, installationPending = true))
            withContext(Dispatchers.Main) {
                if (isForeground(activity)) activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")))
            }
            return
        }
        if (!isForeground(activity)) { publish(state.value.copy(phase = UpdatePhase.READY)); return }
        record = record.copy(permissionRequested = false)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(asset.bytes)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        if (journal.directory.usableSpace < asset.bytes + 16L * 1024 * 1024) throw UpdateException(UpdateError.STORAGE)
        val sessionId = installer.createSession(params)
        try {
            publish(state.value.copy(phase = UpdatePhase.INSTALLING, sessionId = sessionId, installationPending = true))
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, asset.bytes).use { output ->
                    journal.apk.inputStream().use { input -> input.copyTo(output, 256 * 1024) }
                    session.fsync(output)
                } // Must be closed before commit.
                val callback = Intent(context, UpdateInstallReceiver::class.java)
                    .setAction("${context.packageName}.UPDATE_INSTALL_STATUS")
                    .setData(Uri.parse("mediaflow-update://session/$sessionId"))
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val sender = PendingIntent.getBroadcast(context, sessionId, callback, flags).intentSender
                session.commit(sender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            publish(state.value.copy(phase = UpdatePhase.READY, sessionId = null, installationPending = false,
                autoInstall = false, error = UpdateError.INSTALL_FAILED))
            throw UpdateException(UpdateError.INSTALL_FAILED)
        }
    }

    /** Receiver delegates here; never starts an activity while the app is backgrounded. */
    internal fun handleInstallStatus(intent: Intent, finished: () -> Unit) {
        scope.launch {
            try {
                recovery.join()
                mutex.withLock {
                    guarded {
                        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                        if (sessionId < 0 || sessionId != state.value.sessionId) return@guarded
                        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                @Suppress("DEPRECATION")
                                val action = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                                    else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                                if (action == null) throw UpdateException(UpdateError.INSTALL_FAILED)
                                pendingUserAction = action
                                publish(state.value.copy(phase = UpdatePhase.INSTALLING, installationPending = true))
                                launchPendingUserAction()
                            }
                            PackageInstaller.STATUS_SUCCESS -> {
                                // PackageManager may briefly lag the callback. Next foreground / MY_PACKAGE_REPLACED reconciles.
                                publish(state.value.copy(phase = UpdatePhase.INSTALLED, sessionId = null,
                                    autoInstall = false, installationPending = false, error = null))
                                reconcileInstalled()
                            }
                            else -> {
                                pendingUserAction = null
                                val failure = UpdateInstallErrors.fromStatus(intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE))
                                publish(state.value.copy(phase = UpdatePhase.READY, error = failure,
                                    autoInstall = false, installationPending = false, sessionId = null))
                                runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
                            }
                        }
                    }
                }
            } finally { finished() }
        }
    }

    internal fun onPackageReplaced(finished: () -> Unit) {
        scope.launch {
            try { recovery.join(); mutex.withLock { guarded { reconcileInstalled() } } }
            finally { finished() }
        }
    }

    private suspend fun launchPendingUserAction() {
        if (!state.value.autoInstall) return
        val activity = foregroundActivity() ?: return
        val action = pendingUserAction ?: return
        val launched = withContext(Dispatchers.Main) {
            if (isForeground(activity)) {
                activity.startActivity(action)
                pendingUserAction = null
                true
            } else false
        }
        if (launched) publish(state.value.copy(installationPending = false))
    }

    private fun recover() {
        record = journal.read() ?: UpdateRecord(UpdateState())
        mutableState.value = record.state
        reconcileInstalled()
        if (state.value.phase == UpdatePhase.INSTALLED) return
        val release = state.value.release
        val asset = state.value.asset
        when {
            state.value.sessionId != null -> reconcileSession(recovering = true)
            release != null && asset != null && journal.apk.isFile -> {
                if (journal.apk.length() == asset.bytes) {
                    verifier.verify(journal.apk, release, asset)
                    publish(state.value.copy(phase = if (state.value.phase == UpdatePhase.WAITING_PERMISSION) UpdatePhase.WAITING_PERMISSION else UpdatePhase.READY,
                        autoInstall = state.value.phase == UpdatePhase.WAITING_PERMISSION && state.value.autoInstall,
                        downloaded = asset.bytes, installationPending = state.value.phase == UpdatePhase.WAITING_PERMISSION))
                } else publish(state.value.copy(phase = UpdatePhase.PAUSED, autoInstall = false, installationPending = false))
            }
            state.value.phase in activePhases || state.value.phase == UpdatePhase.WAITING_PERMISSION || state.value.phase == UpdatePhase.READY -> publish(state.value.copy(phase = if (asset == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE,
                autoInstall = false, installationPending = false))
        }
    }

    private fun reconcileInstalled() {
        // A metadata-only CURRENT result is not evidence that this updater installed anything.
        if (state.value.asset == null) return
        val target = state.value.release?.versionCode ?: return
        if (installedIdentity().versionCode >= target) markInstalled()
    }

    private fun markInstalled() {
        pendingUserAction = null
        val session = state.value.sessionId
        if (session != null) runCatching { context.packageManager.packageInstaller.abandonSession(session) }
        publish(state.value.copy(phase = UpdatePhase.INSTALLED, autoInstall = false, installationPending = false,
            sessionId = null, error = null, automaticPrompt = false))
        journal.apk.delete()
    }

    private fun reconcileSession(recovering: Boolean = false) {
        val id = state.value.sessionId ?: return
        val installer = context.packageManager.packageInstaller
        val info = installer.getSessionInfo(id)
        if (info == null || recovering && !info.isActive) {
            // A pending system Intent cannot safely be serialized. Re-stage the retained verified APK on explicit retry.
            runCatching { installer.abandonSession(id) }
            publish(state.value.copy(phase = UpdatePhase.READY, autoInstall = false, installationPending = false,
                sessionId = null, error = UpdateError.SESSION_LOST))
        }
    }

    private fun canInstall() = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
    private fun foregroundActivity(): Activity? = foreground.get()?.takeIf(::isForeground)
    private fun isForeground(activity: Activity): Boolean = foreground.get() === activity && !activity.isFinishing && !activity.isDestroyed &&
        (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true

    private fun publish(value: UpdateState) {
        record = record.copy(state = value)
        journal.write(record)
        mutableState.value = value
    }

    private fun command(block: suspend () -> Unit): Job = scope.launch {
        recovery.join()
        mutex.withLock { guarded { block() } }
    }

    private suspend inline fun guarded(block: () -> Unit) {
        try { block() }
        catch (e: Exception) {
            val error = when (e) {
                is UpdateException -> e.code
                is java.io.IOException -> UpdateError.NETWORK
                is SecurityException -> UpdateError.INSTALL_PERMISSION
                else -> UpdateError.INTERNAL
            }
            if (error in setOf(UpdateError.CHECKSUM, UpdateError.PACKAGE, UpdateError.SIGNATURE, UpdateError.INVALID_APK,
                    UpdateError.VERSION, UpdateError.INCOMPATIBLE_ABI, UpdateError.INCOMPATIBLE_SDK)) {
                journal.apk.delete()
                record = record.copy(etag = null)
            }
            val failed = state.value.copy(phase = UpdatePhase.ERROR, error = error, autoInstall = false,
                automaticPrompt = false, downloaded = journal.apk.takeIf { it.isFile }?.length() ?: 0)
            // If storage itself failed, still publish the error to the UI; retain the last atomic journal.
            runCatching { publish(failed) }.onFailure { mutableState.value = failed }
        }
    }

    companion object {
        private val activePhases = setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.INSTALLING)
    }
}
