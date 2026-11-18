package com.zdownloader.app.engine

import com.zdownloader.app.domain.ErrorCode

internal object NetworkRoutePolicy {
    fun issue(internet:Boolean,validated:Boolean,captive:Boolean,vpn:Boolean,bearerPresent:Boolean,bearerValidated:Boolean):ErrorCode?=when {
        captive->ErrorCode.CAPTIVE_PORTAL
        !internet->ErrorCode.NETWORK_UNVALIDATED
        vpn&&!bearerPresent->ErrorCode.NETWORK_UNVALIDATED
        validated->null
        vpn&&bearerValidated->null
        else->ErrorCode.NETWORK_UNVALIDATED
    }
}
