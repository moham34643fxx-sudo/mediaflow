package com.zdownloader.app.domain

import org.junit.Assert.*
import org.junit.Test

class HttpsPortPolicyTest {
    @Test fun publicTlsPortsPreserveSignedUrls() {
        for(port in listOf(1,443,8443,65535)) {
            val url="https://example.com:$port/a%2Fb?sig=a%2Bb&key=1&key=2"
            assertEquals(url,UrlPolicy.normalize(url))
        }
    }
    @Test fun differentPortsHaveDifferentIdentity() {
        assertNotEquals(MediaIdentity.urlKey("https://example.com:8443/a"),MediaIdentity.urlKey("https://example.com:9443/a"))
    }
    @Test fun nondefaultPortsNeverBypassAddressOrCleartextPolicy() {
        for(url in listOf("http://example.com:8443/a","https://127.0.0.1:8443/a","https://[::1]:9443/a","https://169.254.169.254:8443/a","https://192.168.1.1:8443/a","https://example.com:65536/a","https://example.com:0/a","https://example.com:/a"))
            assertThrows(url,MediaException::class.java) {UrlPolicy.normalize(url)}
    }
}
