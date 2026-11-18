package com.zdownloader.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class ProblemReportTest {
    @Test fun reportUsesCorrectRecipientAndRoundTripsReviewedArabicText() {
        val report=ProblemReport("https://example.com/watch?v=123",ReportProblem.UNSUPPORTED,"الرابط يعمل في المتصفح", "UNSUPPORTED_URL")
        val uri=URI(report.whatsAppUrl("0.9.34"))
        assertEquals("https",uri.scheme)
        assertEquals("wa.me",uri.host)
        assertEquals("/201099305871",uri.path)
        assertEquals(report.message("0.9.34"),URLDecoder.decode(uri.rawQuery.substringAfter("text="),"UTF-8"))
        assertTrue(report.message("0.9.34").contains("App Version: 0.9.34"))
    }

    @Test fun emptyInvalidAndOversizedReportsCannotBeHandedOff() {
        for(report in listOf(ProblemReport(),ProblemReport(description="short"),ProblemReport(url="file:///private"),
            ProblemReport(url="https://",description="Useful description"),ProblemReport(description="x".repeat(2001)))) {
            assertNotNull(report.validationError())
            assertThrows(IllegalArgumentException::class.java) {report.whatsAppUrl("0.9.34")}
        }
        assertNull(ProblemReport(description="Playback stops halfway through").validationError())
        assertNull(ProblemReport(url="https://example.com/audio").validationError())
    }

    @Test fun credentialsFragmentsAndSignedParametersNeverEnterTheReport() {
        val raw="https://name:password@example.com/watch?v=123&access_token=secret&%73ig=private&X-Amz-Credential=account#private-fragment"
        val report=ProblemReport(raw,errorCode="raw exception with private data")
        assertEquals("https://example.com/watch?v=123",safeReportUrl(raw))
        val message=report.message("0.9.34")
        for(secret in listOf("password","private","account","access_token","raw exception")) assertFalse(message.contains(secret))
        assertEquals(raw,report.url)
    }
}
