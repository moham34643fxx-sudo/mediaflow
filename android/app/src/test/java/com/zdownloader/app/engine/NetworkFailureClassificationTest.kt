package com.zdownloader.app.engine

import com.zdownloader.app.domain.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.net.ssl.SSLException

class NetworkFailureClassificationTest {
    @Test fun concreteHttpFailuresKeepTheirUserFacingMeaning() {
        assertEquals(ErrorCode.VIDEO_NOT_FOUND,NetworkDiagnostics.httpError(404))
        assertEquals(ErrorCode.MEDIA_URL_EXPIRED,NetworkDiagnostics.httpError(410))
        assertEquals(ErrorCode.HTTP_FORBIDDEN,NetworkDiagnostics.httpError(403))
        assertEquals(ErrorCode.RATE_LIMITED,NetworkDiagnostics.httpError(429))
        assertEquals(ErrorCode.SERVER_UNAVAILABLE,NetworkDiagnostics.httpError(503))
        assertEquals(null,NetworkDiagnostics.httpError(206))
    }
    @Test fun tlsClosureAndTimeoutAreRecoverableWithoutAcceptingInvalidCertificates() {
        assertEquals(ErrorCode.DOWNLOAD_INTERRUPTED,NetworkDiagnostics.classify(SSLException("connection reset"),"tls"))
        assertEquals(ErrorCode.DOWNLOAD_INTERRUPTED,NetworkDiagnostics.classify(SSLException(java.io.EOFException()),"tls"))
        assertEquals(ErrorCode.TLS_TIMEOUT,NetworkDiagnostics.classify(SSLException(java.net.SocketTimeoutException()),"tls"))
        assertEquals(ErrorCode.TLS_FAILED,NetworkDiagnostics.classify(SSLException(java.security.cert.CertificateException()),"tls"))
        assertEquals(ErrorCode.TLS_FAILED,NetworkDiagnostics.classify(javax.net.ssl.SSLPeerUnverifiedException("unverified"),"tls"))
    }
}
