@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.SessionState
import com.zdownloader.app.domain.MediaException
import com.zdownloader.app.engine.NativeWork
import com.zdownloader.app.engine.Processor
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import org.junit.Test

/** Isolated source-derived packet/table research; never changes production validation. */
class ContainerAwareOutputProofTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext

    @Test fun sourceDerivedContractRejectsDamagedCopiesAndChecksFinalSeeks()=runBlocking<Unit> {
        require(android.os.Build.VERSION.SDK_INT>=29)
        val began=SystemClock.elapsedRealtime()
        val base=File(requireNotNull(context.getExternalFilesDir(null)),"source-paired-performance").canonicalPath+File.separator
        fun owned(path:String)=File(path).canonicalFile.also {require(it.path.startsWith(base)&&it.isFile)}
        val specFile=owned(requireNotNull(InstrumentationRegistry.getArguments().getString("containerProofSpecPath")))
        require(specFile.length() in 1..(4L*1024*1024))
        val spec=JSONObject(specFile.readText())
        val module=owned(spec.getString("module_path"))
        require(module.length() in 1..(128L*1024))
        assertEquals(spec.getString("module_sha256"),hash(module))
        val contract=owned(spec.getString("contract_path"))
        require(contract.length() in 1..(24L*1024*1024))
        assertEquals(spec.getString("contract_sha256"),hash(contract))
        val reference=owned(spec.getString("baseline_path"))
        assertEquals(56365991L,reference.length())
        val baselineSha="45446109c38af58a751c6b4a204c246b602d542cb7d0bfbc630955835042a5ff"
        assertEquals(baselineSha,hash(reference))
        val video=owned(spec.getString("video_path"))
        val audio=owned(spec.getString("audio_path"))
        val videoSha="568ba37ba5b60b3393f873e53cdbcb9ded1d999eb8a1ccc65aaec51ec955da83"
        val audioSha="be6b60e0ec9130e37e3077c24e348e78d5a9be7069fe896d4dde5cde2b13d703"
        assertEquals(videoSha,hash(video));assertEquals(audioSha,hash(audio))
        val cases=spec.getJSONArray("cases")
        require(cases.length()==20)
        val directory=File(context.getExternalFilesDir(null),"container-aware-proof/${UUID.randomUUID()}")
        check(!directory.exists()&&directory.mkdirs())
        require(android.os.StatFs(directory.path).availableBytes>=2L*1024*1024*1024)
        val report=JSONObject().put("passed",false).put("production_changed",false).put("certificate_issued",false)
            .put("scope","Actual existing Python runtime packet/table prototype; all final strict oracles retained; no production speedup")
            .put("module_sha256",spec.getString("module_sha256")).put("contract_sha256",spec.getString("contract_sha256"))
        val results=JSONArray();report.put("cases",results)
        val controls=mutableListOf<TransferControl>()
        var activity:MainActivity?=null
        var failure:Throwable?=null
        try {
            val app=context.applicationContext as ZApplication
            check(app.repository.downloads.first().none {it.status !in setOf("COMPLETED","FAILED","CANCELLED","PAUSED")})
            assertTrue(FFmpegKitConfig.getSessionsByState(SessionState.RUNNING).isEmpty())
            check(!context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            val active=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=active
            withContext(Dispatchers.Main) {active.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            val preparation=SystemClock.elapsedRealtime()
            val python=app.extractor.python()
            val namespace=python.getModule("runpy").callAttr("run_path",module.path)
            val study=namespace.callAttr("__getitem__","DeviceStudy").callAttr("__call__",contract.path)
            val seeks=JSONArray(study.callAttr("expected_seeks_json").toString())
            report.put("python_and_contract_preparation_ms",SystemClock.elapsedRealtime()-preparation)
                .put("expected_final_seeks",seeks)
            val sourceStarted=SystemClock.elapsedRealtime()
            report.put("device_source_derivation",JSONObject(study.callAttr("rebuild_sources_json",video.path,audio.path).toString()))
                .put("device_source_derivation_ms",SystemClock.elapsedRealtime()-sourceStarted).put("resources_after_source_derivation",resources())
            for(index in 0 until cases.length()) {
                val case=cases.getJSONObject(index)
                val name=case.getString("name");require(name.matches(Regex("[a-z0-9_]{1,64}")))
                val row=JSONObject().put("name",name).put("expected_accept",case.getBoolean("expected_accept"))
                    .put("conditions_before",conditions())
                results.put(row)
                val fixtureStarted=SystemClock.elapsedRealtime()
                val file=File(study.callAttr("prepare_case_json",reference.path,directory.path,case.toString()).toString()).canonicalFile
                check(file.parentFile==directory.canonicalFile&&file.isFile)
                assertEquals(case.getString("sha256"),hash(file))
                row.put("fixture_preparation_ms",SystemClock.elapsedRealtime()-fixtureStarted)
                val gateStarted=SystemClock.elapsedRealtime()
                val gate=JSONObject(study.callAttr("verify_json",file.path).toString())
                row.put("candidate_gate_ms",SystemClock.elapsedRealtime()-gateStarted).put("candidate",gate).put("resources_after_gate",resources())
                var seekFailure:Throwable?=null
                if(gate.getBoolean("accepted")) {
                    val seekStarted=SystemClock.elapsedRealtime()
                    try {row.put("final_seeks",checkFinalSeeks(file,seeks))}
                    catch(error:Throwable) {seekFailure=error;row.put("seek_failure",error.javaClass.simpleName+":"+error.message)}
                    row.put("final_seek_ms",SystemClock.elapsedRealtime()-seekStarted)
                }
                val strictStarted=SystemClock.elapsedRealtime()
                val control=TransferControl().also {controls+=it}
                try {
                    Processor().validate(file,audio=false,requireAudio=true,expectedDuration=888.053,expectedContainer="mp4",
                        expectedWidth=640,expectedHeight=272,expectedVideoCodec="vp9",control=control)
                    row.put("retained_strict_accepted",true)
                } catch(error:MediaException) {
                    row.put("retained_strict_accepted",false).put("retained_strict_failure",error.code.name+":"+error.validationCheck)
                }
                row.put("retained_strict_ms",SystemClock.elapsedRealtime()-strictStarted).put("conditions_after",conditions()).put("resources_after_oracle",resources())
                val readbackStarted=SystemClock.elapsedRealtime()
                assertEquals(case.getString("sha256"),hash(file))
                row.put("post_oracle_fixture_hash_ms",SystemClock.elapsedRealtime()-readbackStarted)
                seekFailure?.let {throw it}
                assertNull("Native ownership still active after full oracle",control.session)
                assertFalse(NativeWork.busy(directory))
                assertEquals("Candidate verdict differs from real fixture expectation: $name",case.getBoolean("expected_accept"),gate.getBoolean("accepted"))
                if(case.getBoolean("expected_accept")) assertTrue("Valid control failed full final oracle",row.getBoolean("retained_strict_accepted"))
                assertFalse("Full-oracle rejected output was accepted by candidate",gate.getBoolean("accepted")&&!row.getBoolean("retained_strict_accepted"))
                File(directory,"interim.json").writeText(report.toString(2))
            }
            assertEquals(baselineSha,hash(reference));assertEquals(videoSha,hash(video));assertEquals(audioSha,hash(audio))
            report.put("all_twenty_cases_completed",true)
        } catch(error:Throwable) {failure=error}
        finally {
            controls.forEach {it.stop()}
            val settled=controls.all {it.session==null}&&!NativeWork.busy(directory)
            try {withContext(NonCancellable+Dispatchers.Main) {activity?.finish()}}
            catch(error:Throwable) {if(failure==null) failure=error else failure.addSuppressed(error)}
            if(!settled) {
                val cleanup=AssertionError("Native ownership unsettled; all evidence preserved")
                if(failure==null) failure=cleanup else failure.addSuppressed(cleanup)
            }
            report.put("cleanup_confirmed",settled).put("passed",failure==null&&settled)
                .put("total_study_wall_ms",SystemClock.elapsedRealtime()-began)
            failure?.let {report.put("failure",it.javaClass.simpleName+":"+it.message)}
            File(directory,"result.json").writeText(report.toString(2))
            instrumentation.sendStatus(2,android.os.Bundle().apply {putString("container_proof_report",File(directory,"result.json").path)})
        }
        failure?.let {throw it}
    }

    internal fun checkFinalSeeks(file:File,expected:JSONArray):JSONArray {
        val results=JSONArray()
        for(kind in listOf("video","audio")) {
            val extractor=MediaExtractor()
            try {
                extractor.setDataSource(file.path)
                val track=(0 until extractor.trackCount).single {extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("$kind/")==true}
                extractor.selectTrack(track)
                val buffer=ByteBuffer.allocate(2*1024*1024)
                for(index in 0 until expected.length()) {
                    val record=expected.getJSONObject(index)
                    if(record.getString("kind")!=kind) continue
                    extractor.seekTo(record.getLong("target_us"),MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    buffer.clear();val size=extractor.readSampleData(buffer,0)
                    assertEquals(record.getInt("size"),size)
                    assertEquals(record.getLong("pts_us"),extractor.sampleTime)
                    assertEquals(record.getInt("flags"),extractor.sampleFlags)
                    val bytes=ByteArray(size);buffer.position(0);buffer.get(bytes)
                    assertEquals(record.getString("sha256"),digest(bytes))
                    results.put(record)
                }
            } finally {extractor.release()}
        }
        assertEquals(6,results.length())
        return results
    }

    private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val buffer=ByteArray(64*1024);while(true) {val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n)}}
        return digest.digest().hex()
    }
    private fun ByteArray.hex()=buildString(size*2) {for(byte in this@hex) {
        val value=byte.toInt() and 255;append("0123456789abcdef"[value ushr 4]);append("0123456789abcdef"[value and 15])
    }}
    private fun conditions()=context.getSystemService(android.os.PowerManager::class.java).let {
        JSONObject().put("thermal_status",it.currentThermalStatus).put("power_save",it.isPowerSaveMode).put("interactive",it.isInteractive)
    }
    private fun resources()=JSONObject().put("pss_kib_sample_not_peak",android.os.Debug.getPss())
        .put("java_heap_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
}
