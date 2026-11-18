@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.zdownloader.app.engine.NativeWork
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Decoder capability only; a first-frame probe never authorizes source/output integrity. */
class NativeVideoBudgetTest {
    @Test fun nativeDecoderEnforcesTheInputPixelBudget()=runBlocking<Unit> {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        check(app.repository.downloads.first().none {it.status !in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")})
        val prefix=File(requireNotNull(app.getExternalFilesDir(null)),"source-paired-performance").canonicalPath+File.separator
        val fixture=File(requireNotNull(InstrumentationRegistry.getArguments().getString("nativeBudgetVideoPath"))).canonicalFile
        require(fixture.path.startsWith(prefix)&&fixture.isFile)
        val expected="568ba37ba5b60b3393f873e53cdbcb9ded1d999eb8a1ccc65aaec51ec955da83"
        assertEquals(expected,hash(fixture))
        val home=File(app.filesDir,"transfers/native-budget-${UUID.randomUUID()}").apply {check(!exists()&&mkdirs())}
        val report=JSONObject().put("passed",false).put("scope","Actual native pixel-limit capability only; not full decode or gallery approval")
            .put("main_app_replaced",false).put("output_approval_issued",false)
        val cases=JSONArray();report.put("cases",cases)
        var failure:Throwable?=null
        try {
            for(limit in listOf(1,1920*1080)) {
                val lease=NativeWork.acquire(requireNotNull(fixture.parentFile))
                val session=try {FFmpegKit.executeWithArguments(arrayOf("-nostdin","-v","error","-xerror","-err_detect","explode",
                    "-hwaccel","none","-threads:v","4","-max_pixels",limit.toString(),"-protocol_whitelist","file,pipe",
                    "-i",fixture.path,"-map","0:v:0","-frames:v","1","-f","null","-"))}
                    finally {lease.release()}
                val logs=session.allLogsAsString
                cases.put(JSONObject().put("limit",limit).put("return_code",session.returnCode?.value).put("logs",logs.takeLast(4000)))
                if(limit==1) {
                    assertFalse("A native input pixel ceiling must reject the larger coded frame",ReturnCode.isSuccess(session.returnCode))
                    assertTrue("Failure must identify the decoder pixel ceiling, not an unknown CLI option",
                        Regex("exceeds[^\\r\\n]*max[^\\r\\n]*pixel",RegexOption.IGNORE_CASE).containsMatchIn(logs))
                } else assertTrue("The same untouched source must decode with an adequate budget",ReturnCode.isSuccess(session.returnCode))
            }
            assertEquals(expected,hash(fixture))
        } catch(error:Throwable) {failure=error}
        finally {
            val settled=!NativeWork.busy(requireNotNull(fixture.parentFile))
            if(!settled&&failure==null) failure=AssertionError("Pixel capability native read did not settle")
            report.put("cleanup_confirmed",settled).put("passed",failure==null)
            failure?.let {report.put("failure",it.javaClass.simpleName+":"+it.message)}
            val output=File(home,"result.json");output.writeText(report.toString(2))
            instrument.sendStatus(2,Bundle().apply {putString("native_budget_report",output.path)})
        }
        failure?.let {throw it}
    }

    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val bytes=ByteArray(64*1024);while(true) {val count=input.read(bytes);if(count<0) break;digest.update(bytes,0,count)}}
        return digest.digest().joinToString("") {"%02x".format(it)}
    }
}
