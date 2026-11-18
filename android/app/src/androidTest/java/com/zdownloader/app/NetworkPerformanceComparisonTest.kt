package com.zdownloader.app

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.Format
import com.zdownloader.app.engine.AnalysisTrace
import com.zdownloader.app.engine.NativeWork
import com.zdownloader.app.engine.TransferControl
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Bounded real-network measurement only. Fragment prefixes are NOT complete media or published outputs. */
class NetworkPerformanceComparisonTest {
    @Test fun measuresDiscoveryAndActualFragmentConcurrency(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as ZApplication
        val args = InstrumentationRegistry.getArguments()
        val run = requireNotNull(args.getString("evidenceRun")); require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val directory = File(app.getExternalFilesDir(null), "network-comparison/$run").also { check(it.mkdirs()) }
        val source = "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd"
        val budgets = args.getString("comparisonBudgets", "2,4,8,16,32").split(',').map(String::toInt)
        require(budgets.isNotEmpty() && budgets.distinct().size == budgets.size && budgets.all { it in setOf(2, 4, 8, 16, 32) })
        val pilot = args.getString("pilot") == "true"
        val workload = args.getString("workload", "both")
        require(workload in setOf("both", "audio", "video"))
        val report = JSONObject().put("scope", "REAL INTERNET: same bounded DASH fragment prefixes with native app transport; NOT complete media validation, TCP socket count or universal optimality")
            .put("published", false).put("pilot", pilot).put("workload", workload).put("media_byte_budget", 96L * 1024 * 1024)
        val trials = JSONArray(); report.put("trials", trials)
        val power = app.getSystemService(android.os.PowerManager::class.java)
        var activity: MainActivity? = null
        var namespace: com.chaquo.python.PyObject? = null
        var hooksInstalled = false
        var completedBytes = 0L
        fun conditions() = JSONObject().put("thermal_status", power.currentThermalStatus).put("power_save", power.isPowerSaveMode)
            .put("interactive", power.isInteractive).put("uid_rx_bytes", android.net.TrafficStats.getUidRxBytes(app.applicationInfo.uid))
        try {
            check(app.repository.downloads.first().none { it.status !in setOf("COMPLETED", "FAILED", "CANCELLED", "PAUSED") })
            assertFalse(app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            activity = instrument.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            withContext(Dispatchers.Main) { activity!!.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val discovery = JSONArray(); report.put("discovery", discovery)
            var media: com.zdownloader.app.domain.Media? = null
            repeat(3) { index ->
                val id = "discovery-${UUID.randomUUID()}"
                if (index < 2) app.extractor.clearMetadataCache()
                val runtimeStarted = com.chaquo.python.Python.isStarted()
                val began = SystemClock.elapsedRealtime(); var firstChoices: Long? = null
                val result = withTimeout(90_000) { app.extractor.analyze(source, id, forceRefresh = index < 2, enrichOptional = false,
                    onMetadata = { firstChoices = firstChoices ?: (SystemClock.elapsedRealtime() - began) }) }
                media = result
                discovery.put(JSONObject().put("trial", index).put("cache_trial", index == 2).put("analysis_ms", SystemClock.elapsedRealtime() - began)
                    .put("python_started_before_trial", runtimeStarted)
                    .put("first_choices_ms", firstChoices).put("formats", result.formats.size).put("video_formats", result.formats.count { it.video })
                    .put("audio_formats", result.formats.count { it.audio }).put("format_ids", JSONArray(result.formats.map { it.id }))
                    .put("trace", JSONArray(AnalysisTrace.snapshot())))
                File(directory, "result.json").writeText(report.toString(2))
            }
            val formats = requireNotNull(media).formats.filter { JSONObject(it.payload).optJSONArray("fragments")?.length()?.let { count -> count >= 65 } == true && it.protocol == "http_dash_segments" }
            val audio = requireNotNull(formats.filter { it.audio && !it.video }.minByOrNull { it.audioBitrate ?: it.bitrate ?: Double.MAX_VALUE }) { "no real fragmented audio plan; do not emulate concurrency on a progressive file" }
            val video = requireNotNull(formats.filter { it.video }.minByOrNull { it.videoBitrate ?: it.bitrate ?: Double.MAX_VALUE }) { "no bounded low-rate fragmented video" }
            val python = app.extractor.python()
            namespace = python.getModule("types").callAttr("ModuleType", "native_request_observer").get("__dict__")!!
            python.builtins.callAttr("exec", OBSERVER, namespace)
            hooksInstalled = true
            val workloads = if (pilot) listOf("audio" to audio) else listOf("audio" to audio, "video" to video).filter { workload == "both" || it.first == workload }
            for ((label, original) in workloads) {
                val count = if (label == "audio") 65 else 33
                val payload = JSONObject(original.payload)
                val all = requireNotNull(payload.optJSONArray("fragments"))
                payload.put("fragments", JSONArray((0 until count).map { all.getJSONObject(it) }))
                payload.remove("filesize"); payload.remove("filesize_approx")
                val format = Format.parse(payload)
                val sequence = if (pilot) budgets else budgets + budgets.reversed()
                var expected: Pair<Long, String>? = null
                for (budget in sequence) {
                    val id = "native-concurrency-${UUID.randomUUID()}"; val scratch = app.storage.temporary(id)
                    val file = File(scratch, "prefix.${format.ext}")
                    val control = TransferControl().apply { fragmentConcurrency = budget }
                    val trial = JSONObject().put("workload", label).put("format_id", format.id).put("fragment_count", count)
                        .put("requested_budget", budget).put("conditions_before", conditions())
                    trials.put(trial)
                    python.builtins.callAttr("exec", "reset_stats()", namespace)
                    val started = SystemClock.elapsedRealtime(); val cpu = android.os.Process.getElapsedCpuTime()
                    var firstBytes: Long? = null
                    try {
                        withTimeout(120_000) { app.transfer.stream(format, file, id, control) { bytes, _ ->
                            if (bytes > 0 && firstBytes == null) firstBytes = SystemClock.elapsedRealtime() - started
                            if (bytes > 12L * 1024 * 1024 || completedBytes + bytes > 96L * 1024 * 1024) control.stop()
                        } }
                        val elapsed = SystemClock.elapsedRealtime() - started
                        val size = file.length(); check(size in 1..12L * 1024 * 1024)
                        val hash = MessageDigest.getInstance("SHA-256")
                        file.inputStream().use { input -> val buffer = ByteArray(128 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
                        val sha = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                        if (expected == null) expected = size to sha else assertEquals("same prefix must have identical bytes/order across budgets", expected, size to sha)
                        completedBytes += size
                        val observed = JSONObject(python.builtins.callAttr("eval", "stats_json()", namespace).toString())
                        trial.put("observed_requests", observed)
                        assertEquals("HTTP body observer must settle, not count completed responses as active", 0, observed.getInt("active"))
                        assertTrue("observed request concurrency must respect the effective fragment budget", observed.getInt("peak_inflight_http_requests") in 1..budget)
                        assertEquals(listOf(budget), (0 until observed.getJSONArray("observed_budgets").length()).map { observed.getJSONArray("observed_budgets").getInt(it) })
                        trial.put("elapsed_ms", elapsed).put("first_progress_ms", firstBytes).put("process_cpu_ms", android.os.Process.getElapsedCpuTime() - cpu)
                            .put("bytes", size).put("sha256", sha).put("mbps", size * 8.0 / elapsed / 1000)
                            .put("conditions_after", conditions()).put("passed", true)
                    } catch (error: Throwable) {
                        trial.put("passed", false).put("error", "${error.javaClass.simpleName}:${error.message}")
                        throw error
                    } finally {
                        control.stop()
                        withContext(NonCancellable) { withTimeout(30_000) { NativeWork.awaitIdle(scratch) }; app.storage.removeTemporary(id) }
                        report.put("completed_media_bytes", completedBytes)
                        File(directory, "result.json").writeText(report.toString(2))
                    }
                }
            }
            report.put("passed", true)
        } catch (error: Throwable) {
            report.put("passed", false).put("error", "${error.javaClass.simpleName}:${error.message}")
            throw error
        } finally {
            withContext(NonCancellable) {
                if (hooksInstalled) app.extractor.python().builtins.callAttr("exec", "restore()", namespace!!)
                withContext(Dispatchers.Main) { activity?.finish() }
                File(directory, "result.json").writeText(report.toString(2))
            }
        }
    }
    private companion object {
        val OBSERVER = """
import json, threading, time, yt_dlp
original_urlopen = yt_dlp.YoutubeDL.urlopen
lock = threading.RLock()
def reset_stats():
    global stats
    stats = dict(active=0, peak_inflight_http_requests=0, requests=0, received_body_bytes=0, request_failures=0, body_read_failures=0, response_statuses={}, observed_budgets=[])
reset_stats()
class ObservedResponse:
    def __init__(self, wrapped):
        self.wrapped, self.done = wrapped, False
        self.received = 0
        with lock:
            status = str(wrapped.status)
            stats['response_statuses'][status] = stats['response_statuses'].get(status, 0) + 1
        try: self.expected = int(wrapped.get_header('Content-Length'))
        except (ValueError, TypeError): self.expected = None
    def __getattr__(self, key): return getattr(self.wrapped, key)
    def finish(self):
        with lock:
            if not self.done:
                self.done = True
                stats['active'] -= 1
    def read(self, *args):
        try: data = self.wrapped.read(*args)
        except Exception:
            with lock: stats['body_read_failures'] += 1
            self.finish()
            raise
        self.received += len(data)
        with lock: stats['received_body_bytes'] += len(data)
        if not data or not args or args[0] is None or args[0] < 0 or (self.expected is not None and self.received >= self.expected): self.finish()
        return data
    def close(self):
        try: self.wrapped.close()
        finally: self.finish()
    def __enter__(self): return self
    def __exit__(self, *args): self.close()
    def __del__(self):
        try: self.finish()
        except Exception: pass
def observed_urlopen(self, request):
    with lock:
        stats['active'] += 1
        stats['requests'] += 1
        stats['peak_inflight_http_requests'] = max(stats['peak_inflight_http_requests'], stats['active'])
        budget = self.params.get('concurrent_fragment_downloads')
        if budget not in stats['observed_budgets']: stats['observed_budgets'].append(budget)
    try: return ObservedResponse(original_urlopen(self, request))
    except Exception:
        with lock:
            stats['active'] -= 1
            stats['request_failures'] += 1
        raise
yt_dlp.YoutubeDL.urlopen = observed_urlopen
def stats_json():
    with lock: return json.dumps(stats)
def restore(): yt_dlp.YoutubeDL.urlopen = original_urlopen
""".trimIndent()
    }
}
