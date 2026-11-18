package com.zdownloader.app

import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.engine.DiagnosticsExport
import com.zdownloader.app.engine.NetworkDiagnostics
import com.zdownloader.app.engine.PerformanceCapture
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.io.IOException
import java.io.Writer

/** Run on an isolated emulator first. Never clears diagnostics, queue or application data. */
class DiagnosticsExportAcceptanceTest {
    @Test fun snapshotExportPreservesSourceAndRemovesSecretCanaries() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        NetworkDiagnostics.start(context)
        NetworkDiagnostics.record("media","video_validation_timing",null,operation="acceptance-private-id",
            details=JSONObject().put("validation_ms",731).put("url","https://private.invalid/?token=CANARY")
                .put("cookie","CANARY").put("decoder","c2.qti.CANARY.decoder"))
        val before=NetworkDiagnostics.exportSnapshot()
        val destination=StringWriter()
        DiagnosticsExport().write(before,destination)
        val exported=destination.toString()
        assertFalse(exported.contains("CANARY"))
        assertFalse(exported.contains("private.invalid"))
        assertFalse(exported.contains("acceptance-private-id"))
        assertTrue(exported.lineSequence().filter {it.isNotBlank()}.map(::JSONObject).any {
            it.optJSONObject("details")?.optInt("validation_ms")==731
        })
        assertTrue(NetworkDiagnostics.exportSnapshot().containsAll(before))
    }

    @Test fun captureIsOptionalAndStopsAddingContextAfterStop() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assertFalse(PerformanceCapture.active)
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            scenario.onActivity {activity->
                PerformanceCapture.start(activity)
                try {
                    val captured=JSONObject();PerformanceCapture.decorate(captured)
                    assertTrue(captured.has("capture_session"))
                    assertTrue(captured.has("monotonic_ms"))
                    assertTrue(captured.getBoolean("app_foreground"))
                } finally {PerformanceCapture.stop()}
                val disabled=JSONObject();PerformanceCapture.decorate(disabled)
                assertEquals(0,disabled.length())
            }
        }
        instrumentation.runOnMainSync {assertFalse(PerformanceCapture.active)}
    }

    @Test fun captureTracksBackgroundReturnAndActivityRecreation() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            scenario.onActivity {PerformanceCapture.start(it)}
            try {
                scenario.moveToState(Lifecycle.State.CREATED)
                val background=JSONObject()
                instrumentation.runOnMainSync {PerformanceCapture.decorate(background)}
                assertFalse(background.getBoolean("app_foreground"))
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.recreate()
                scenario.onActivity {
                    val foreground=JSONObject();PerformanceCapture.decorate(foreground)
                    assertTrue(foreground.getBoolean("app_foreground"))
                    assertEquals(background.getString("capture_session"),foreground.getString("capture_session"))
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                instrumentation.runOnMainSync {
                    val backgroundAgain=JSONObject();PerformanceCapture.decorate(backgroundAgain)
                    assertFalse(backgroundAgain.getBoolean("app_foreground"))
                }
            } finally {instrumentation.runOnMainSync {PerformanceCapture.stop()}}
        }
    }

    @Test fun failedDestinationDoesNotDeleteSourceDiagnostics() = runBlocking {
        NetworkDiagnostics.start(InstrumentationRegistry.getInstrumentation().targetContext)
        NetworkDiagnostics.record("media","video_validation_timing",null,
            details=JSONObject().put("validation_ms",919))
        val before=NetworkDiagnostics.exportSnapshot()
        assertTrue(before.isNotEmpty())
        val failedDestination=object:Writer() {
            override fun write(buffer:CharArray,offset:Int,length:Int) {throw IOException("Destination unavailable")}
            override fun flush() {}
            override fun close() {}
        }
        assertThrows(IOException::class.java) {DiagnosticsExport().write(before,failedDestination)}
        assertTrue(NetworkDiagnostics.exportSnapshot().containsAll(before))
    }
}
