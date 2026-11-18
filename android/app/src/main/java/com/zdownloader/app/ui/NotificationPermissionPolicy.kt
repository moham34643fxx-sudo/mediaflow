package com.zdownloader.app.ui

/** Android 13 (API 33) introduced the user-controlled notification runtime permission. */
internal object NotificationPermissionPolicy {
    const val ANDROID_13_API = 33
    const val PREFERENCES = "mediaflow_permissions"
    const val FIRST_LAUNCH_REQUESTED = "first_launch_notification_requested"

    fun requiresRuntimePermission(apiLevel: Int): Boolean = apiLevel >= ANDROID_13_API

    fun shouldRequestOnFirstLaunch(apiLevel: Int, permissionGranted: Boolean, alreadyRequested: Boolean): Boolean =
        requiresRuntimePermission(apiLevel) && !permissionGranted && !alreadyRequested

    fun notificationsAvailable(apiLevel: Int, permissionGranted: Boolean, appEnabled: Boolean, channelEnabled: Boolean): Boolean =
        (!requiresRuntimePermission(apiLevel) || permissionGranted) && appEnabled && channelEnabled
}
