package com.zdownloader.app.engine

import com.zdownloader.app.domain.ErrorCode
import org.junit.Assert.*
import org.junit.Test

class NetworkRoutePolicyTest {
    @Test fun aValidatedBearerCanCarryAVpnWhoseOwnValidationIsMissing() {
        assertNull(NetworkRoutePolicy.issue(true,false,false,true,true,true))
    }
    @Test fun aValidatedVpnCanWorkWhenItsBearerCannotProbeOutsideTheTunnel() {
        assertNull(NetworkRoutePolicy.issue(true,true,false,true,true,false))
    }
    @Test fun aStaleVpnWithoutAPhysicalBearerMustStillWait() {
        assertEquals(ErrorCode.NETWORK_UNVALIDATED,NetworkRoutePolicy.issue(true,true,false,true,false,false))
    }
    @Test fun portalAndUnvalidatedPhysicalNetworksAreNotMistakenForConnectivity() {
        assertEquals(ErrorCode.CAPTIVE_PORTAL,NetworkRoutePolicy.issue(true,true,true,true,true,true))
        assertEquals(ErrorCode.NETWORK_UNVALIDATED,NetworkRoutePolicy.issue(true,false,false,false,true,false))
    }
}
