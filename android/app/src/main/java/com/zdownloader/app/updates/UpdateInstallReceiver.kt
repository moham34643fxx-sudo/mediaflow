package com.zdownloader.app.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Manifest: exported=false. Only the explicit PackageInstaller PendingIntent targets this receiver. */
@AndroidEntryPoint
class UpdateInstallReceiver : BroadcastReceiver() {
    @Inject lateinit var updater: AppUpdater
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        updater.handleInstallStatus(intent) { pending.finish() }
    }
}

/** Manifest: exported=false, action android.intent.action.MY_PACKAGE_REPLACED. */
@AndroidEntryPoint
class UpdateReplacedReceiver : BroadcastReceiver() {
    @Inject lateinit var updater: AppUpdater
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        updater.onPackageReplaced { pending.finish() }
    }
}
