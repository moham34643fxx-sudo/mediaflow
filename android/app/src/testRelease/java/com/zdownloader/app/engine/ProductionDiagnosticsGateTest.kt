package com.zdownloader.app.engine

import com.zdownloader.app.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

class ProductionDiagnosticsGateTest {
    @Test fun releaseCannotExportEvenWhenCalledWithoutTheSettingsUi()=runBlocking {
        assertFalse(BuildConfig.INTERNAL_DIAGNOSTICS)
        val destination=StringWriter()
        assertThrows(IllegalStateException::class.java) {DiagnosticsExport().write(emptyList(),destination)}
        assertEquals("",destination.toString())
        try {
            NetworkDiagnostics.exportSnapshot()
            fail("Stable must reject snapshots before reading private files")
        } catch(expected:IllegalStateException) {
            assertEquals("Internal diagnostics unavailable",expected.message)
        }
    }
}
