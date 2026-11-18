package com.zdownloader.app.engine

import android.content.Context
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.annotation.DoNotInline
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.SandboxUnsupportedException
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resumeWithException

/** Cold V8 acceleration without retaining player globals, user requests or a background service. */
@RequiresApi(26)
internal object IsolatedEjsRuntime {
    private val admission=Mutex()
    private val direct=Executor {it.run()}

    /** Null selects QuickJS only when a usable sandbox could not be opened; evaluation errors propagate. */
    @DoNotInline
    suspend fun evaluate(context:Context,script:String):String?=admission.withLock {
        if(!JavaScriptSandbox.isSupported()) return@withLock null
        val at=SystemClock.elapsedRealtime()
        val sandbox=connect(context) ?: return@withLock null
        sandbox.use {
            val required=listOf(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE,
                JavaScriptSandbox.JS_FEATURE_ISOLATE_TERMINATION,
                JavaScriptSandbox.JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT)
            if(required.any {!sandbox.isFeatureSupported(it)}) return@withLock null
            val bridge=JSONObject().put("engine","v8_sandbox").put("connection_ms",SystemClock.elapsedRealtime()-at)
            evaluateFresh(sandbox,script,bridge)
        }
    }

    private suspend fun connect(context:Context):JavaScriptSandbox?=try {
        withTimeoutOrNull(3_000) {JavaScriptSandbox.createConnectedInstanceAsync(context).await {it.close()}}
    } catch(_:SandboxUnsupportedException) {null}
    catch(_:IllegalStateException) {null} // A cancelled connection may still be completing/closing.

    private suspend fun evaluateFresh(sandbox:JavaScriptSandbox,script:String,bridge:JSONObject):String {
        val params=IsolateStartupParameters().apply {
            setMaxHeapSizeBytes(192L*1024*1024)
            setMaxEvaluationReturnSizeBytes(16*1024*1024)
        }
        val creating=SystemClock.elapsedRealtime()
        return sandbox.createIsolate(params).use {isolate->
            bridge.put("isolate_ms",SystemClock.elapsedRealtime()-creating)
            // No host binding, DOM, network or files; all state dies with this isolate.
            val program="var __mediaflowOutput='';var console={log(...args){__mediaflowOutput=args.join(' ')},warn(){},error(){}};\n"+
                script+"\n;__mediaflowOutput;"
            val evaluating=SystemClock.elapsedRealtime()
            val output=isolate.evaluateJavaScriptAsync(program).await()
            bridge.put("evaluation_ms",SystemClock.elapsedRealtime()-evaluating)
            JSONObject().put("output",output).put("bridge",bridge).toString()
        }
    }

    // A cancelled connection can still complete. Dispose its late result instead of leaking a service.
    private suspend fun <T> ListenableFuture<T>.await(dispose:(T)->Unit={}):T=suspendCancellableCoroutine {continuation->
        addListener({
            try {continuation.resume(get(),onCancellation={_,value,_->dispose(value)})}
            catch(error:ExecutionException) {continuation.resumeWithException(error.cause ?: error)}
            catch(error:Exception) {continuation.resumeWithException(error)}
        },direct)
    }
}
