package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import java.io.File
import java.security.MessageDigest

/** Durable JUnit results survive a USB/ADB transport disconnect; no synthetic success. */
class DeviceEvidenceListener:RunListener() {
    private val rows=JSONArray()
    private val failures=mutableMapOf<String,String>()
    private var expected=0
    private var hash=""
    private fun key(d:Description)=d.className+"#"+d.methodName
    override fun testRunStarted(description:Description) {
        expected=description.testCount()
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val digest=MessageDigest.getInstance("SHA-256")
        File(app.packageCodePath).inputStream().use {input->val buffer=ByteArray(64*1024);while(true) {val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        hash=digest.digest().joinToString("") {"%02x".format(it)}
        write(false)
    }
    override fun testFailure(failure:Failure) {failures[key(failure.description)]=failure.trace}
    override fun testFinished(description:Description) {
        rows.put(JSONObject().put("class",description.className).put("name",description.methodName).put("failure",failures[key(description)]).put("skipped",false))
        write(false)
    }
    override fun testIgnored(description:Description) {rows.put(JSONObject().put("class",description.className).put("name",description.methodName).put("skipped",true));write(false)}
    override fun testAssumptionFailure(failure:Failure) {failures[key(failure.description)]="ASSUMPTION: "+failure.trace}
    override fun testRunFinished(result:Result) {write(true,result.runCount,result.failureCount)}
    private fun write(complete:Boolean,runCount:Int=0,failureCount:Int=0) {
        val args=InstrumentationRegistry.getArguments()
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val phase=args.getString("evidencePhase","instrumentation.log")!!
        val run=args.getString("evidenceRun","manual")!!
        val directory=File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
        val document=JSONObject().put("run",run).put("phase",phase).put("expected",expected).put("complete",complete)
            .put("run_count",runCount).put("failure_count",failureCount).put("apk_sha256",hash).put("cases",rows).put("time_ms",System.currentTimeMillis())
        val target=File(directory,"native-$phase.json")
        val temporary=File(directory,"native-$phase.pending")
        temporary.writeText(document.toString(2));check(temporary.renameTo(target))
    }
}
