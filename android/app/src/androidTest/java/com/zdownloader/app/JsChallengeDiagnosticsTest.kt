package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.annotation.Keep
import com.dokar.quickjs.quickJs
import com.dokar.quickjs.evaluate
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.zdownloader.app.domain.UrlPolicy
import com.zdownloader.app.domain.Media
import com.zdownloader.app.domain.Selection
import com.zdownloader.app.domain.MediaException
import com.zdownloader.app.engine.Processor
import com.zdownloader.app.engine.TransferControl
import com.arthenica.ffmpegkit.FFprobeKit
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** No production solver changes. Optional compile/evaluate split is diagnostic, not a speed comparison. */
class JsChallengeDiagnosticsTest {
    @Test fun recordsRealChallengeFailureTypeWithoutSuppressingIt()=runBlocking<Unit> {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        check(!app.coordinator.running)
        val args=InstrumentationRegistry.getArguments()
        val source=UrlPolicy.normalize(requireNotNull(args.getString("discoveryUrl")))
        val run=requireNotNull(args.getString("evidenceRun"));require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/$run")
        check(!folder.exists());check(folder.mkdirs())
        val python=app.extractor.python()
        val warmup=if(args.getString("warmTransformFirst")=="true") {
            val first=app.extractor.pythonCall("analyze_quality_audit",source,"$run-warmup")
            JSONObject().put("js",first.optJSONObject("js_challenges"))
                .put("format_count",first.optJSONArray("formats")?.length() ?: 0)
        } else null
        val namespace=python.getModule("types").callAttr("ModuleType","challenge_diagnostics").get("__dict__")!!
        val splitBridge=ChallengeSplitBridge()
        splitBridge.memoryLimitMiB=args.getString("jsMemoryLimitMiB")?.toLong() ?: 128L
        require(splitBridge.memoryLimitMiB in setOf(128L,192L,256L))
        require(splitBridge.memoryLimitMiB==128L||args.getString("splitJsEvaluation")=="true")
        splitBridge.inspectThrownValue=args.getString("inspectJsThrownValue")=="true"
        splitBridge.tracePhases=args.getString("traceJsPhases")=="true"
        require(!splitBridge.tracePhases||splitBridge.inspectThrownValue&&args.getString("splitJsEvaluation")=="true")
        namespace.callAttr("__setitem__","split_bridge",splitBridge)
        namespace.callAttr("__setitem__","split_evaluation",args.getString("splitJsEvaluation")=="true")
        python.builtins.callAttr("exec","""
import android_jsc, json, re, time
original = android_jsc.AndroidQuickJSJCP._run_js_runtime
observations = []
def observed(self, script):
    row = {'script_characters': len(script)}
    observations.append(row)
    began = time.monotonic()
    try:
        output = split_bridge.run(script) if split_evaluation else original(self, script)
        row['returned'] = True
        parsed = json.loads(output)
        row['response_type'] = parsed.get('type')
        row['response_count'] = len(parsed.get('responses', []))
        row['successful_responses'] = sum(response.get('type') == 'result' for response in parsed.get('responses', []))
        row['failed_responses'] = sum(response.get('type') != 'result' for response in parsed.get('responses', []))
        return output
    except Exception as error:
        row['returned'] = False
        row['error_class'] = type(error).__module__ + '.' + type(error).__name__
        text = str(error).lower()
        row['error_categories'] = [name for name in ('out of memory', 'stack overflow', 'syntaxerror', 'referenceerror', 'typeerror', 'timeout', 'cancelled', 'unsupported') if name in text]
        row['undefined_name_error'] = 'not defined' in text or 'undefined' in text
        row['host_api_names_in_error'] = [name for name in ('setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'TextEncoder', 'TextDecoder', 'URL', 'atob', 'btoa', 'crypto', 'window', 'document', 'fetch') if re.search(r'\b' + re.escape(name) + r'\b', str(error))]
        vocabulary = {'error', 'internalerror', 'typeerror', 'referenceerror', 'syntaxerror', 'rangeerror', 'timeout', 'timed', 'out', 'interrupted', 'execution', 'time', 'limit', 'exceeded', 'quickjs', 'eval', 'compile', 'during', 'javascript', 'resource', 'cancelled', 'canceled', 'parse', 'syntax', 'stack', 'memory', 'maximum', 'call', 'size'}
        row['static_error_words'] = [word for word in re.findall(r'[a-z]+', text.splitlines()[0]) if word in vocabulary]
        # Java exception stringification can begin with a blank line. Inspect the
        # actual message in memory, exporting only fixed diagnostic vocabulary.
        message = str(error.getMessage()).lower() if hasattr(error, 'getMessage') else text
        row['message_characters'] = len(message)
        row['message_static_words'] = sorted(set(re.findall(r'[a-z]+', message)) & vocabulary)
        row['message_timeout_word'] = bool(re.search(r'\btimeout\b', message))
        raise
    finally:
        row['wall_ms'] = round((time.monotonic() - began) * 1000)
android_jsc.AndroidQuickJSJCP._run_js_runtime = observed
""".trimIndent(),namespace)
        val memorySamples=mutableListOf<Long>()
        val sampling=launch(Dispatchers.IO) {
            while(isActive) {memorySamples.add(android.os.Debug.getPss());delay(250)}
        }
        val cpuStart=android.os.Process.getElapsedCpuTime()
        try {
            val extracted=app.extractor.pythonCall("analyze_quality_audit",source,run)
            sampling.cancelAndJoin()
            val observations=JSONArray(python.getModule("json").callAttr("dumps",namespace.callAttr("__getitem__","observations")).toString())
            val report=JSONObject().put("solver_observations",observations).put("error",extracted.optString("error"))
                .put("warmup",warmup)
                .put("format_count",extracted.optJSONArray("formats")?.length() ?: 0)
                .put("stages",extracted.optJSONObject("timings_ms")).put("js",extracted.optJSONObject("js_challenges"))
                .put("quality_audit",extracted.optJSONObject("quality_audit"))
                .put("split_evaluation",args.getString("splitJsEvaluation")=="true")
                .put("split_compile_ms",splitBridge.compileMillis).put("split_evaluate_ms",splitBridge.evaluateMillis)
                .put("split_memory_used_bytes",splitBridge.memoryUsedBytes)
                .put("js_thrown_value",splitBridge.thrownValue)
                .put("inspect_js_thrown_value",splitBridge.inspectThrownValue)
                .put("trace_js_phases",splitBridge.tracePhases)
                .put("split_js_memory_limit_mib",if(args.getString("splitJsEvaluation")=="true") splitBridge.memoryLimitMiB else JSONObject.NULL)
                .put("process_pss_samples_kb",JSONArray(memorySamples))
                .put("max_sampled_process_pss_kb",memorySamples.maxOrNull())
                .put("process_cpu_ms",android.os.Process.getElapsedCpuTime()-cpuStart)
                .put("scope","Original solver observed unless opt-in split; explicit QA memory trial, unchanged stack/outer deadline; process PSS sampled ~250ms, not a guaranteed peak; scripts/raw errors/signed URLs not retained; not a production speed result")
            File(folder,"challenge-diagnostics.json").writeText(report.toString(2))
            args.getString("verifyDownloadedFormatId")?.let {formatId->
                require(formatId.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
                verifyDownload(app,Media.parse(extracted,source),formatId,folder,run)
            }
            assertTrue("Diagnostic must observe at least one real challenge",observations.length()>0)
            if(splitBridge.inspectThrownValue) {
                assertNotNull("Wrapped diagnostic must observe the JavaScript throw, not only a bridge failure",splitBridge.thrownValue)
            }
            if(args.getString("splitJsEvaluation")!="true") {
                val measured=requireNotNull(extracted.optJSONObject("js_challenges"))
                assertEquals(observations.length(),measured.getInt("runs"))
                val failed=(0 until observations.length()).count {observations.getJSONObject(it).optBoolean("returned").not()}
                assertEquals("Failed native evaluations must not disappear from diagnostics",failed,measured.getInt("failures"))
                assertTrue("Both successful and failed native evaluations consume measured time",measured.getLong("execution_ms")>0)
                if(args.getString("requireChallengeSuccess")=="true") {
                    assertEquals("The production runtime must finish without native evaluation failure",0,measured.getInt("failures"))
                    assertTrue("The production runtime must actually solve a challenge",measured.getInt("solved")>0)
                    for(index in 0 until observations.length()) {
                        val observation=observations.getJSONObject(index)
                        assertTrue(observation.getBoolean("returned"))
                        assertEquals("result",observation.getString("response_type"))
                        assertEquals(0,observation.getInt("failed_responses"))
                    }
                    if(warmup!=null) assertTrue("Repeated analysis must reuse the matching player transformation",measured.getInt("transform_cache_hits")>0)
                }
            }
        } finally {
            sampling.cancelAndJoin()
            python.builtins.callAttr("exec","android_jsc.AndroidQuickJSJCP._run_js_runtime = original",namespace)
        }
    }

    /** Regression: a newly exposed format must download and produce real A/V, not only appear in a catalog. */
    private suspend fun verifyDownload(app:ZApplication,media:Media,formatId:String,folder:File,run:String) {
        val format=media.formats.single {it.id==formatId}
        require(format.protocol=="https"&&format.video&&format.audio)
        val selection=Selection.video(media,format)
        val control=TransferControl()
        val source=File(folder,"verified-source.${format.ext}")
        val output=File(folder,"verified-output.${selection.output}")
        check(!source.exists()&&!output.exists())
        val report=JSONObject().put("format_id",formatId).put("passed",false)
        try {
            withTimeout(120_000) {
                app.transfer.stream(format,source,"$run-verified-source",control) {bytes,_->
                    check(bytes<=32L*1024*1024) {"QA fixture exceeded the 32 MiB transfer budget"}
                }
                Processor().process(source,null,output,selection,control,media.duration)
                assertTrue(output.isFile&&output.length()>0)
                val info=requireNotNull(FFprobeKit.getMediaInformation(output.path).mediaInformation)
                val video=requireNotNull(info.streams?.firstOrNull {it.type=="video"})
                assertTrue(info.streams?.any {it.type=="audio"}==true)
                assertEquals(format.width,video.width.toInt())
                assertEquals(format.height,video.height.toInt())
                val duration=requireNotNull(info.duration?.toDoubleOrNull())
                assertTrue(duration>0)
                media.duration?.let {assertTrue(kotlin.math.abs(duration-it)<=maxOf(1.0,it*0.05))}
                report.put("passed",true).put("source_bytes",source.length()).put("output_bytes",output.length())
                    .put("width",video.width.toInt()).put("height",video.height.toInt()).put("duration",duration)
                    .put("output_sha256",java.security.MessageDigest.getInstance("SHA-256").let {digest->
                        output.inputStream().use {input->val buffer=ByteArray(65536);while(true) {val count=input.read(buffer);if(count<0) break;digest.update(buffer,0,count)}}
                        digest.digest().joinToString("") {"%02x".format(it)}
                    })
            }
        } catch(error:Exception) {
            report.put("failure_class",error.javaClass.name)
            if(error is MediaException) report.put("failure_code",error.code.name)
            throw error
        } finally {
            control.stop()
            File(folder,"verified-download.json").writeText(report.toString(2))
        }
    }
}

@Keep
class ChallengeSplitBridge {
    var memoryLimitMiB=128L
    var compileMillis=-1L
        private set
    var evaluateMillis=-1L
        private set
    var memoryUsedBytes=-1L
        private set
    var inspectThrownValue=false
    var tracePhases=false
    var thrownValue:JSONObject?=null
        private set

    fun run(script:String):String=runBlocking(Dispatchers.Default) {
        withTimeout(30_000) {
            var printed=""
            quickJs {
                memoryLimit=memoryLimitMiB*1024*1024
                maxStackSize=1024L*1024
                define("console") {
                    function("log") {arguments->printed=arguments.joinToString(" ");Unit}
                    function("warn") {Unit}
                    function("error") {Unit}
                }
                if(inspectThrownValue) define("__mediaflowDiagnostic") {
                    function("error") {arguments->
                        thrownValue=JSONObject(arguments.single() as String)
                        Unit
                    }
                }
                // Diagnostic only: a block can affect top-level lexical scope.
                // Retain the thrown value and never count this as a production run.
                val exportBoundary="Object.assign(globalThis, lib);"
                val tracedScript=if(tracePhases) {
                    check(script.indexOf(exportBoundary)>=0&&script.indexOf(exportBoundary)==script.lastIndexOf(exportBoundary))
                    script.replace(exportBoundary,exportBoundary+"\n"+"""
                        __mfTrace.active='library_exported';
                        for(const [owner,key,phase] of [[meriyah,'parse','parse'],[astring,'generate','generate']]) {
                            const original=owner[key];
                            owner[key]=function(...args) {
                                const previous=__mfTrace.active;
                                __mfTrace.active=phase;
                                __mfTrace[phase+'_calls']++;
                                const began=Date.now();
                                try {
                                    const result=Reflect.apply(original,this,args);
                                    __mfTrace.active=previous;
                                    return result;
                                } finally {__mfTrace[phase+'_ms']+=Date.now()-began;}
                            };
                        }
                    """.trimIndent())
                } else script
                val program=if(inspectThrownValue) "var __mfTrace={active:'library_load',parse_calls:0,generate_calls:0,parse_ms:0,generate_ms:0};\ntry {\n"+tracedScript+"\n} catch (__mfThrown) {\n"+"""
                    const __mfMessage=String(__mfThrown && __mfThrown.message || '');
                    const __mfName=String(__mfThrown && __mfThrown.name || '');
                    const __mfWords=['timeout','interrupted','memory','stack','syntax','undefined','function','constructor','property','read','null','convert','allocation','overflow'];
                    __mediaflowDiagnostic.error(JSON.stringify({
                        thrown_type:typeof __mfThrown,
                        is_null:__mfThrown===null,
                        own_property_count:__mfThrown==null?0:Object.getOwnPropertyNames(__mfThrown).length,
                        phases:__mfTrace,
                        name:['Error','InternalError','TypeError','ReferenceError','SyntaxError','RangeError','EvalError'].includes(__mfName)?__mfName:'other',
                        message_characters:__mfMessage.length,
                        static_message_words:__mfWords.filter(word=>new RegExp('\\b'+word+'\\b','i').test(__mfMessage))
                    }));
                    throw __mfThrown;
                    }
                """.trimIndent() else script
                val began=android.os.SystemClock.elapsedRealtime()
                val bytecode=compile(program)
                compileMillis=android.os.SystemClock.elapsedRealtime()-began
                val evaluating=android.os.SystemClock.elapsedRealtime()
                try {evaluate<Any?>(bytecode)}
                finally {
                    evaluateMillis=android.os.SystemClock.elapsedRealtime()-evaluating
                    memoryUsedBytes=memoryUsage.memoryUsedSize
                }
            }
            printed
        }
    }
}
