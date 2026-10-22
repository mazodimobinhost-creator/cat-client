package com.whitedns.vpn

import com.cat.client.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EndpointNetworkIdentityTest {
    @Test fun cellularOperatorsNeverShareEvidenceIdentity() {
        val first = EndpointNetworkIdentity.build(EndpointTransport.CELLULAR, "43211", true)
        val second = EndpointNetworkIdentity.build(EndpointTransport.CELLULAR, "43235", true)
        assertNotEquals(first, second)
    }

    @Test fun wifiDoesNotStoreOperatorOrPrivateSsid() {
        assertEquals("wifi:na:unmetered:home",
            EndpointNetworkIdentity.build(EndpointTransport.WIFI, "43211", false))
    }

    @Test fun roamingAndHomeResultsAreIndependent() {
        val home = EndpointNetworkIdentity.build(EndpointTransport.CELLULAR, "43220", true, false)
        val roaming = EndpointNetworkIdentity.build(EndpointTransport.CELLULAR, "43220", true, true)
        assertNotEquals(home, roaming)
    }

    @Test fun malformedOperatorIsUnknownNotGuessed() {
        assertEquals("cellular:unknown:metered:home",
            EndpointNetworkIdentity.build(EndpointTransport.CELLULAR, "operator-name", true))
    }

    @Test fun meteredWifiIsDifferentFromUnmeteredWifi() {
        assertNotEquals(
            EndpointNetworkIdentity.build(EndpointTransport.WIFI, metered = false),
            EndpointNetworkIdentity.build(EndpointTransport.WIFI, metered = true),
        )
    }
}
