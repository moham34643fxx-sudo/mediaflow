package com.zdownloader.app

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.dokar.quickjs.quickJs
import com.dokar.quickjs.evaluate
import com.zdownloader.app.engine.JsBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Offline known-answer comparison; no extractor requests, submitted URLs or media transfers. */
class EjsColdProfileTest {
    @Test fun profileSameColdPlayerInFreshIsolatedEngines()=runBlocking(Dispatchers.Default) {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        val args=InstrumentationRegistry.getArguments()
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${args.getString("evidenceRun")}").apply {mkdirs()}
        val player=File(requireNotNull(args.getString("playerFixture"))).readText()
        val solver=app.extractor.python().getModule("yt_dlp_ejs.yt.solver")
        val library=solver.callAttr("lib").toString()
        val core=solver.callAttr("core").toString()
        val signature="NJAJEij0EwRgIhAI0KExTgjfPk-MPM9MAdzyyPRt=BM8-XO5tm5hlMCSVpAiEAv7eP3CURqZNSPow8BXXAoazVoXgeMP7gH9BdylHCwgw=gwzz"
        val input=JSONObject().put("type","player").put("player",player).put("output_preprocessed",true)
            .put("requests",JSONArray().put(JSONObject().put("type","n").put("challenges",JSONArray().put("IlLiA21ny7gqA2m4p37")))
                .put(JSONObject().put("type","sig").put("challenges",JSONArray().put(signature))))
        val source=library+"\nObject.assign(globalThis,lib);\n"+"""
            const phases={parse_ms:0,parse_calls:0,generate_ms:0,generate_calls:0,player_compile_ms:0,player_evaluate_ms:0,player_compile_calls:0};
            for (const [object,key,label] of [[meriyah,'parse','parse'],[astring,'generate','generate']]) {
                const original=object[key];
                object[key]=function(...args) {const start=Date.now(); phases[label+'_calls']++;
                    try {return original.apply(this,args);} finally {phases[label+'_ms']+=Date.now()-start;}};
            }
            const NativeFunction=globalThis.Function;
            globalThis.Function=function(...args) {const start=Date.now();phases.player_compile_calls++;
                let fn;try {fn=NativeFunction(...args);} finally {phases.player_compile_ms+=Date.now()-start;}
                return function(...values) {const at=Date.now();try {return fn.apply(this,values);} finally {phases.player_evaluate_ms+=Date.now()-at;}};
            };
        """.trimIndent()+"\n"+core+"\nconst started=Date.now();const result=jsc($input);"+
            "const summary=JSON.stringify({phases,total_solver_ms:Date.now()-started,responses:result.responses,transformed:!!result.preprocessed_player});"+
            "if(typeof console!=='undefined')console.log(summary);summary;"
        val report=JSONObject().put("player_characters",player.length).put("scope","Offline public upstream EJS 0.8.0 known-answer player 74edf1a3; both engines cold")
        try {
            var start=SystemClock.elapsedRealtime()
            if(args.getString("profileQuickJs","true")=="true") quickJs {
                memoryLimit=192L*1024*1024;maxStackSize=1024L*1024
                evaluationTimeoutMillis=30_000
                val row=JSONObject().put("runtime_init_ms",SystemClock.elapsedRealtime()-start)
                start=SystemClock.elapsedRealtime();val bytecode=compile(source)
                row.put("script_compile_ms",SystemClock.elapsedRealtime()-start)
                start=SystemClock.elapsedRealtime();val result=evaluate<String>(bytecode)
                row.put("evaluation_ms",SystemClock.elapsedRealtime()-start).put("result",JSONObject(result))
                report.put("quickjs",row)
            }
            start=SystemClock.elapsedRealtime()
            val envelope=JSONObject(JsBridge.runEjs(source))
            val row=envelope.getJSONObject("bridge").put("cold_total_ms",SystemClock.elapsedRealtime()-start)
                .put("result",JSONObject(envelope.getString("output")))
            assertEquals("v8_sandbox",row.getString("engine"))
            report.put("v8_sandbox",row)
            val expectedN="9nRTxrbM1f0yHg"
            val expectedSig="NJAJEij0EwRgIhAI0KExTgjfPk-MPM9MAdzyyPRt=BM8-XO5tm5hzMCSVpAiEAv7eP3CURqZNSPow8BXXAoazVoXgeMP7gH9BdylHCwgw=gwzl"
            for(engine in listOf("quickjs","v8_sandbox").filter {report.has(it)}) {
                val result=report.getJSONObject(engine).getJSONObject("result")
                assertTrue(result.getBoolean("transformed"))
                val responses=result.getJSONArray("responses")
                assertEquals(expectedN,responses.getJSONObject(0).getJSONObject("data").getString("IlLiA21ny7gqA2m4p37"))
                assertEquals(expectedSig,responses.getJSONObject(1).getJSONObject("data").getString(signature))
            }
            report.put("known_answers_verified",true)
        } finally {File(folder,"ejs-cold-profile.json").writeText(report.toString(2))}
        Unit
    }
}
