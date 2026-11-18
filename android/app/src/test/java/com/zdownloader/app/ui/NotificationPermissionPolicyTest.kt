package com.zdownloader.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionPolicyTest {
    @Test fun firstLaunchRequestIsAndroid13PlusAndOnlyBeforeTheFirstAttempt() {
        assertFalse(NotificationPermissionPolicy.shouldRequestOnFirstLaunch(32,permissionGranted=false,alreadyRequested=false))
        assertTrue(NotificationPermissionPolicy.shouldRequestOnFirstLaunch(33,permissionGranted=false,alreadyRequested=false))
        assertFalse(NotificationPermissionPolicy.shouldRequestOnFirstLaunch(35,permissionGranted=false,alreadyRequested=true))
        assertFalse(NotificationPermissionPolicy.shouldRequestOnFirstLaunch(36,permissionGranted=true,alreadyRequested=false))
    }

    @Test fun notificationAvailabilityRequiresRuntimeAppAndChannelAccess() {
        assertFalse(NotificationPermissionPolicy.notificationsAvailable(33,permissionGranted=false,appEnabled=false,channelEnabled=true))
        assertFalse(NotificationPermissionPolicy.notificationsAvailable(35,permissionGranted=true,appEnabled=false,channelEnabled=true))
        assertFalse(NotificationPermissionPolicy.notificationsAvailable(36,permissionGranted=true,appEnabled=true,channelEnabled=false))
        assertTrue(NotificationPermissionPolicy.notificationsAvailable(33,permissionGranted=true,appEnabled=true,channelEnabled=true))
        assertTrue(NotificationPermissionPolicy.notificationsAvailable(32,permissionGranted=false,appEnabled=true,channelEnabled=true))
    }
}
