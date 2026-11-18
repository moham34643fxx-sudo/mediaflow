package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.engine.IsolatedEjsRuntime
import com.zdownloader.app.engine.JsBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EjsRuntimeIsolationTest {
    @Test fun failedV8EvaluationFallsBackOnceWithoutDisablingTheNextV8Request() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        app.extractor.python()
        val fallback=JSONObject(JsBridge.runEjs("if(typeof android!=='undefined')throw new Error('isolate fixture');console.log('fallback');"))
        assertEquals("fallback",fallback.getString("output"))
        assertEquals("quickjs",fallback.getJSONObject("bridge").getString("engine"))
        assertEquals(2,fallback.getJSONObject("bridge").getInt("attempts"))
        val next=JSONObject(JsBridge.runEjs("console.log('next');"))
        assertEquals("next",next.getString("output"))
        assertEquals("v8_sandbox",next.getJSONObject("bridge").getString("engine"))
    }

    @Test fun requestGlobalsDoNotLeakAndUnicodeConsoleResultsSurvive() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        app.extractor.python()
        val first=JSONObject(JsBridge.runEjs("globalThis.previousRequest='private';console.log('العربية 😀');"))
        assertEquals("العربية 😀",first.getString("output"))
        val next=JSONObject(JsBridge.runEjs("console.log(typeof previousRequest);"))
        assertEquals("undefined",next.getString("output"))
        assertEquals("v8_sandbox",next.getJSONObject("bridge").getString("engine"))
        assertEquals("42",JsBridge.run("console.log(6*7)")) // Original embedded fallback remains executable.
    }

    @Test fun timedOutEvaluationReleasesItsIsolateAndConnection()=runBlocking(Dispatchers.Default) {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        try {
            withTimeout(2_000) {assertNotNull(IsolatedEjsRuntime.evaluate(app,"while(true){}"))}
            fail("Busy JavaScript must be interrupted")
        } catch(_:TimeoutCancellationException) { /* The next evaluation must not inherit a dead service. */ }
        val next=JSONObject(requireNotNull(IsolatedEjsRuntime.evaluate(app,"console.log('after-timeout')")))
        assertEquals("after-timeout",next.getString("output"))
    }
}
