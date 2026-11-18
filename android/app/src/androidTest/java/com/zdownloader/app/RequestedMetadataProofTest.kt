@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFprobeKit
import com.zdownloader.app.engine.NativeWork
import com.zdownloader.app.engine.Processor
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Explicit requested metadata is part of equivalence, never ignored to pass a copy. */
class RequestedMetadataProofTest {
    @Test fun copiedPacketsCannotHideMissingUnexpectedOrWrongRequestedTags()=runBlocking<Unit> {
        require(android.os.Build.VERSION.SDK_INT>=29)
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        val began=SystemClock.elapsedRealtime()
        val prefix=File(requireNotNull(app.getExternalFilesDir(null)),"source-paired-performance").canonicalPath+File.separator
        fun owned(path:String)=File(path).canonicalFile.also {require(it.path.startsWith(prefix)&&it.isFile)}
        val specFile=owned(requireNotNull(InstrumentationRegistry.getArguments().getString("metadataProofSpecPath")))
        require(specFile.length() in 1..65536)
        val spec=JSONObject(specFile.readText())
        for(name in listOf("metadata_contract.py","container_proof.py","proof_runtime.py")) {
            val row=spec.getJSONObject("programs").getJSONObject(name)
            val file=owned(row.getString("path"));require(file.length() in 1..(128L*1024))
            assertEquals(row.getString("sha256"),hash(file))
        }
        val video=owned(spec.getString("video_path"));val audio=owned(spec.getString("audio_path"));val baseline=owned(spec.getString("baseline_path"))
        val pins=listOf(video to "568ba37ba5b60b3393f873e53cdbcb9ded1d999eb8a1ccc65aaec51ec955da83",
            audio to "be6b60e0ec9130e37e3077c24e348e78d5a9be7069fe896d4dde5cde2b13d703",
            baseline to "45446109c38af58a751c6b4a204c246b602d542cb7d0bfbc630955835042a5ff")
        for((file,pin) in pins) assertEquals(pin,hash(file))
        check(app.repository.downloads.first().none {it.status !in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")})
        check(!app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val directory=File(app.getExternalFilesDir(null),"proof-requested-metadata/${UUID.randomUUID()}").apply {check(!exists()&&mkdirs())}
        require(android.os.StatFs(directory.path).availableBytes>=1024L*1024*1024)
        val report=JSONObject().put("passed",false).put("production_changed",false).put("certificate_issued",false)
            .put("scope","Actual runtime metadata-policy prototype; all native final oracles retained, no production request-selection integration")
        val cases=JSONArray();report.put("cases",cases)
        val controls=mutableListOf<TransferControl>()
        var activity:MainActivity?=null
        var failure:Throwable?=null
        try {
            val active=instrument.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=active
            withContext(Dispatchers.Main) {active.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            val start=SystemClock.elapsedRealtime()
            val namespace=app.extractor.python().getModule("runpy").callAttr("run_path",spec.getJSONObject("programs").getJSONObject("metadata_contract.py").getString("path"))
            val study=namespace.callAttr("__getitem__","MetadataDeviceStudy").callAttr("__call__",video.path,audio.path)
            report.put("runtime_and_source_derivation_ms",SystemClock.elapsedRealtime()-start)
            val plan=listOf("strip_original" to true,"strip_unrequested_title" to false,"embed_requested_title" to true,
                "embed_wrong_title" to false,"embed_missing_title" to false,"embed_requested_artist" to true,
                "strip_unrequested_artist" to false,"unknown_userdata_tag" to false)
            report.put("plan",JSONArray(plan.map {JSONObject().put("name",it.first).put("expected_accept",it.second)}))
            for((name,expected) in plan) {
                val started=SystemClock.elapsedRealtime()
                val candidate=JSONObject(study.callAttr("evaluate_json",baseline.path,directory.path,name).toString())
                val row=JSONObject().put("name",name).put("expected_accept",expected).put("candidate",candidate)
                    .put("fixture_and_candidate_ms",SystemClock.elapsedRealtime()-started)
                cases.put(row)
                val file=File(candidate.getString("path")).canonicalFile
                check(file.parentFile==directory.canonicalFile&&file.isFile)
                val before=hash(file)
                val properties=FFprobeKit.getMediaInformation(file.path).mediaInformation?.allProperties
                val tags=properties?.optJSONObject("format")?.optJSONObject("tags")?:JSONObject()
                row.put("independent_native_format_tags",tags).put("file_sha256",before)
                when(name) {
                    "strip_original","embed_missing_title"->{assertFalse(tags.has("title"));assertFalse(tags.has("artist"))}
                    "strip_unrequested_title","embed_requested_title","embed_wrong_title"->assertEquals("Lavf60.16.100",tags.getString("title"))
                    "embed_requested_artist","strip_unrequested_artist"->assertEquals("Lavf60.16.100",tags.getString("artist"))
                }
                val control=TransferControl().also {controls+=it}
                val nativeStart=SystemClock.elapsedRealtime()
                Processor().validate(file,audio=false,requireAudio=true,expectedDuration=888.053,expectedContainer="mp4",
                    expectedWidth=640,expectedHeight=272,expectedVideoCodec="vp9",control=control)
                row.put("retained_full_final_oracle_ms",SystemClock.elapsedRealtime()-nativeStart).put("retained_full_final_oracle_passed",true)
                assertEquals(before,hash(file));assertFalse(NativeWork.busy(directory))
                assertEquals("Requested metadata policy must not be waived: $name",expected,candidate.getBoolean("accepted"))
                File(directory,"interim.json").writeText(report.toString(2))
            }
            for((file,pin) in pins) assertEquals(pin,hash(file))
            report.put("all_eight_metadata_cases_completed",true)
        } catch(error:Throwable) {failure=error}
        finally {
            controls.forEach {it.stop()}
            val settled=controls.all {it.session==null}&&!NativeWork.busy(directory)
            if(!settled&&failure==null) failure=AssertionError("Metadata-study native ownership did not settle")
            try {withContext(NonCancellable+Dispatchers.Main) {activity?.finish()}}
            catch(error:Throwable) {if(failure==null) failure=error else failure!!.addSuppressed(error)}
            report.put("cleanup_confirmed",settled).put("passed",failure==null&&settled).put("total_study_wall_ms",SystemClock.elapsedRealtime()-began)
            failure?.let {report.put("failure",it.javaClass.simpleName+":"+it.message)}
            val result=File(directory,"result.json");result.writeText(report.toString(2))
            instrument.sendStatus(2,android.os.Bundle().apply {putString("requested_metadata_report",result.path)})
        }
        failure?.let {throw it}
    }
    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val buffer=ByteArray(64*1024);while(true) {val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n)}}
        return buildString(64) {for(byte in digest.digest()) {val n=byte.toInt() and 255;append("0123456789abcdef"[n ushr 4]);append("0123456789abcdef"[n and 15])}}
    }
}
