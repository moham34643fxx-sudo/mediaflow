@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.engine.FragmentBudget
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Stagger real native transfers to test whether snapshot budgets enforce a live global ceiling. */
class FragmentGlobalBudgetAuditTest {
    @Test fun failedAndAbandonedResponsesDoNotBlockTheNextRequest()=budgetFixture("""
for scenario in ('known_length', 'unknown_length', 'zero_read', 'double_close', 'abandoned', 'open_error', 'http_error', 'read_error'):
    engine = Engine(scenario)
    slots = threading.BoundedSemaphore(1)
    with NativeResponseBudget(engine, check, slots):
        try:
            first = engine.urlopen('fixture')
            if scenario == 'known_length': assert first.read(3) == b'abc'
            elif scenario == 'unknown_length': assert first.read() == b'abc'
            elif scenario == 'zero_read':
                assert first.read(0) == b''
                assert not engine.bodies[-1].closed
                first.close()
            elif scenario == 'double_close':
                first.close()
                first.close()
            elif scenario == 'read_error': first.read()
        except (OSError, HTTPError, TransportError):
            assert scenario in ('open_error', 'http_error', 'read_error')
        else:
            assert scenario not in ('open_error', 'http_error', 'read_error')
        if scenario != 'abandoned':
            with engine.urlopen('successor') as successor: assert successor.read() == b'abc'
    assert all(body.closed for body in engine.bodies)
    # Exiting the owner must also return an unread response's slot.
    with NativeResponseBudget(engine, check, slots):
        with engine.urlopen('next-owner') as successor: assert successor.read() == b'abc'
""".trimIndent())

    @Test fun cancellationWhileWaitingDoesNotOpenAnotherResponse()=budgetFixture("""
engine = Engine('known_length')
cancelled = threading.Event()
waiting = threading.Event()
caller = threading.current_thread()
outcomes = []
def cancellation_check():
    check()
    if cancelled.is_set(): raise InterruptedError('Controlled cancellation')
    if threading.current_thread() is not caller: waiting.set()
def waiter():
    try: engine.urlopen('waiting')
    except InterruptedError: outcomes.append('cancelled')
with NativeResponseBudget(engine, cancellation_check, threading.BoundedSemaphore(1)):
    first = engine.urlopen('first')
    thread = threading.Thread(target=waiter, daemon=True)
    thread.start()
    try:
        assert waiting.wait(1)
        cancelled.set()
        thread.join(2)
        assert not thread.is_alive() and outcomes == ['cancelled']
        assert engine.calls == 1
    finally:
        cancelled.set()
        first.close()
        thread.join(3)
assert all(body.closed for body in engine.bodies)
""".trimIndent())

    private fun budgetFixture(scenario:String) {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val python=app.extractor.python()
        val fixture=python.getModule("types").callAttr("ModuleType","response_budget_lifecycle_audit")
        python.builtins.callAttr("exec","""
import io, threading, time
from native_response_budget import NativeResponseBudget
from yt_dlp.networking.common import Response
from yt_dlp.networking.exceptions import HTTPError, TransportError
deadline = time.monotonic() + 10
def check():
    if time.monotonic() > deadline: raise TimeoutError('Response budget did not settle')
class BrokenBody(io.BytesIO):
    def read(self, amount=None): raise OSError('Controlled body failure')
class Engine:
    def __init__(self, scenario):
        self.scenario, self.calls, self.bodies = scenario, 0, []
    def urlopen(self, request):
        self.calls += 1
        if self.calls == 1 and self.scenario == 'open_error': raise OSError('Controlled open failure')
        body = BrokenBody(b'abc') if self.calls == 1 and self.scenario == 'read_error' else io.BytesIO(b'abc')
        self.bodies.append(body)
        headers = {} if self.scenario == 'unknown_length' else {'Content-Length': '3'}
        response = Response(body, 'https://response-budget-audit.example/', headers, 404 if self.scenario == 'http_error' and self.calls == 1 else 200)
        if self.calls == 1 and self.scenario == 'http_error': raise HTTPError(response)
        return response
""".trimIndent()+"\n"+scenario,fixture.get("__dict__"))
    }

    @Test fun staggeredNativeTransfersStayWithinTheGlobalResponseBudget() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as ZApplication
        check(!app.coordinator.running)
        assertEquals(0,app.repository.dao.let {kotlinx.coroutines.runBlocking {it.remaining()}})
        val source=File(app.getExternalFilesDir(null),"source-paired-performance/track-reference-6d8525fb/audio.m4a")
        assertTrue(source.isFile)
        val root=File(app.cacheDir,"fragment-global-audit-${UUID.randomUUID()}").apply {check(mkdirs())}
        val python=app.extractor.python()
        val fixture=python.getModule("types").callAttr("ModuleType","fragment_global_budget_audit")
        fixture.put("source_path",source.path);fixture.put("output_root",root.path)
        val globalBudget=FragmentBudget.perTrack(1,1)
        fixture.put("first_budget",globalBudget);fixture.put("second_budget",FragmentBudget.perTrack(2,1))
        try {
            python.builtins.callAttr("exec","""
import io, os, json, time, math, hashlib, threading
from urllib.parse import urlsplit
import zextractor as z
from yt_dlp.networking.common import Response
original_engine = z.yt_dlp.YoutubeDL
lock = threading.Lock()
release = threading.Event()
first_ready = threading.Event()
second_ready = threading.Event()
active = [0, 0]
peak = [0, 0]
global_peak = 0
results = [None, None]
failures = []
chunk = 65536
size = os.path.getsize(source_path)
budgets = [first_budget, second_budget]
def sha(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(65536), b''):
            digest.update(block)
    return digest.hexdigest()
class ControlledBody(io.BytesIO):
    def __init__(self, payload, job):
        global global_peak
        super().__init__(payload)
        self.job, self.released = job, False
        with lock:
            active[job] += 1
            peak[job] = max(peak[job], active[job])
            global_peak = max(global_peak, sum(active))
            if active[0] >= budgets[0]: first_ready.set()
            if active[1] >= budgets[1]: second_ready.set()
    def read(self, amount=-1):
        if not release.wait(12):
            raise TimeoutError('Controlled response barrier timed out')
        return super().read(amount)
    def close(self):
        if not self.released:
            self.released = True
            with lock: active[self.job] -= 1
        super().close()
class FixtureEngine(original_engine):
    def urlopen(self, request):
        url = request if isinstance(request, str) else request.url
        parts = urlsplit(url)
        if parts.scheme != 'https' or parts.hostname != 'fragment-global-audit.example':
            raise AssertionError('Unexpected network request')
        job, index = map(int, parts.path.strip('/').split('/'))
        if job not in (0, 1) or not 0 <= index < math.ceil(size / chunk):
            raise AssertionError('Invalid fixture fragment')
        with open(source_path, 'rb') as stream:
            stream.seek(index * chunk)
            payload = stream.read(chunk)
        return Response(ControlledBody(payload, job), url, {'Content-Length': str(len(payload))}, 200)
class Callback:
    def confidence(self, kind): pass
    def progress(self, downloaded, total): pass
def transfer(job):
    try:
        fmt = dict(format_id='global-budget-audit', url='https://fragment-global-audit.example/%d/0' % job,
            ext='m4a', protocol='http_dash_segments', vcodec='none', acodec='aac',
            fragments=[dict(url='https://fragment-global-audit.example/%d/%d' % (job, i)) for i in range(math.ceil(size / chunk))])
        target = os.path.join(output_root, 'job-%d.m4a' % job)
        result = json.loads(z.download_stream(json.dumps(fmt), target, 'global-budget-audit-%d' % job, Callback(), budgets[job]))
        results[job] = dict(error=result.get('error'), sha256=sha(target) if os.path.isfile(target) else None)
    except BaseException as error:
        failures.append(type(error).__name__)
threads = [threading.Thread(target=transfer, args=(job,), daemon=True) for job in (0, 1)]
started = time.monotonic()
try:
    z.yt_dlp.YoutubeDL = FixtureEngine
    threads[0].start()
    if not first_ready.wait(10): raise AssertionError('First native transfer did not reach its allocation')
    threads[1].start()
    # Bounded observation, not a requirement that the second job bypass a future global gate.
    second_ready.wait(2)
finally:
    release.set()
    for thread in threads:
        if thread.ident is not None: thread.join(60)
    # Do not restore the transport or delete inputs while native threads still own them.
    if any(thread.is_alive() for thread in threads):
        raise RuntimeError('Native transfer did not settle; fixture retained for diagnosis')
    z.yt_dlp.YoutubeDL = original_engine
expected = sha(source_path)
report = dict(global_peak=global_peak, per_job_peak=peak, active_after=active,
    snapshot_budgets=budgets, elapsed_ms=round((time.monotonic()-started)*1000),
    fixture='Actual native transport with staged local response bodies; not a full QueueCoordinator or Internet benchmark',
    source_bytes=size, failures=failures, both_outputs_identical=all(r and not r['error'] and r['sha256']==expected for r in results))
""".trimIndent(),fixture.get("__dict__"))
            val report=JSONObject(python.getModule("json").callAttr("dumps",fixture.get("report")).toString())
            val run=InstrumentationRegistry.getArguments().getString("evidenceRun","manual")!!
            require(run.matches(Regex("[a-zA-Z0-9-]{1,100}")))
            val evidence=File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
            File(evidence,"fragment-global-budget.json").writeText(report.toString(2))
            assertEquals("Native worker exceptions",0,report.getJSONArray("failures").length())
            assertTrue("Both real transfers must preserve source bytes",report.getBoolean("both_outputs_identical"))
            assertEquals(0,report.getJSONArray("active_after").getInt(0))
            assertEquals(0,report.getJSONArray("active_after").getInt(1))
            assertTrue("Snapshot allocations exceeded the nominal global budget: ${report.getInt("global_peak")}",report.getInt("global_peak")<=globalBudget)
        } finally {
            val threads=fixture.get("threads")
            val alive=threads?.asList()?.any {it.callAttr("is_alive").toBoolean()} ?: false
            if(!alive) root.deleteRecursively()
        }
    }
}
