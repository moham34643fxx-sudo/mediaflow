package com.zdownloader.app.background

import com.zdownloader.app.data.Settings

internal enum class BatchNotice { COMPLETED, FAILED }

internal fun batchTerminalNotice(completed:Int,failed:Int,settings:Settings):BatchNotice? =
    when {
        !settings.notifications->null
        failed>0&&settings.failureNotifications->BatchNotice.FAILED
        completed>0&&settings.completionNotifications->BatchNotice.COMPLETED
        else->null
    }
