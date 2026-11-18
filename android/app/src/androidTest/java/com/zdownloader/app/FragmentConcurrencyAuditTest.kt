package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual yt-dlp fragment downloader; only the network response is a bounded local-latency fixture. */
class FragmentConcurrencyAuditTest {
    @Test fun compareNativeFragmentBudgetsWithoutRedownloadingRetainedAudio()=runBlocking<Unit> {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        val source=File(app.getExternalFilesDir(null),"source-paired-performance/track-reference-6d8525fb/audio.m4a")
        assertTrue(source.isFile)
        val root=File(app.cacheDir,"fragment-concurrency-audit-${UUID.randomUUID()}").apply {check(mkdirs())}
        val python=app.extractor.python()
        val fixture=python.getModule("types").callAttr("ModuleType","fragment_concurrency_audit")
        fixture.put("source_path",source.path);fixture.put("output_root",root.path)
        try {
            withTimeout(120_000) {
                python.builtins.callAttr("exec","""
import io, os, json, time, math, hashlib, threading
from urllib.parse import urlsplit
import zextractor as z
from yt_dlp.networking.common import Response
original_engine = z.yt_dlp.YoutubeDL
lock = threading.Lock()
active = 0
maximum = 0
requests = 0
chunk = 65536
size = os.path.getsize(source_path)
def sha(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(65536), b''):
            digest.update(block)
    return digest.hexdigest()
class ControlledBody(io.BytesIO):
    def __init__(self, payload):
        global active, maximum
        super().__init__(payload)
        self.released = False
        with lock:
            active += 1
            maximum = max(maximum, active)
    def read(self, amount=-1):
        time.sleep(.008)
        return super().read(amount)
    def close(self):
        global active
        if not self.released:
            self.released = True
            with lock:
                active -= 1
        super().close()
class FixtureEngine(original_engine):
    def urlopen(self, request):
        global requests
        url = request if isinstance(request, str) else request.url
        parts = urlsplit(url)
        if parts.scheme != 'https' or parts.hostname != 'fragment-audit.example':
            raise AssertionError('Unexpected network request in local fragment benchmark')
        index = int(parts.path.rsplit('/', 1)[-1])
        if not 0 <= index < math.ceil(size / chunk):
            raise AssertionError('Out-of-bounds fragment request')
        with open(source_path, 'rb') as stream:
            stream.seek(index * chunk)
            payload = stream.read(chunk)
        with lock:
            requests += 1
        return Response(ControlledBody(payload), url, {'Content-Length': str(len(payload))}, 200)
class Callback:
    def confidence(self, kind): pass
    def progress(self, downloaded, total): pass
fmt = dict(format_id='retained-fragment-audit', url='https://fragment-audit.example/0',
    ext='m4a', protocol='http_dash_segments', vcodec='none', acodec='aac',
    fragments=[dict(url='https://fragment-audit.example/%d' % i) for i in range(math.ceil(size / chunk))])
expected = sha(source_path)
trials = []
report = dict(passed=False, transport='Controlled local response latency, not an Internet speed benchmark',
    source_sha256=expected, source_bytes=size, trials=trials)
try:
    z.yt_dlp.YoutubeDL = FixtureEngine
    for repeat, budgets in enumerate(((2, 4, 8, 16, 32), (32, 16, 8, 4, 2))):
        for budget in budgets:
            active = maximum = requests = 0
            target = os.path.join(output_root, 'budget-%d-%d.m4a' % (repeat, budget))
            started = time.monotonic()
            result = json.loads(z.download_stream(json.dumps(fmt), target, 'audit-budget-%d-%d' % (repeat, budget), Callback(), budget))
            elapsed = time.monotonic() - started
            if result.get('error'):
                raise AssertionError('Native fragment transport failed: %s' % result['error'])
            actual = sha(target)
            if actual != expected or active != 0 or maximum > budget:
                raise AssertionError('Fragment bytes or response lifecycle/concurrency violated')
            trials.append(dict(budget=budget, repeat=repeat, elapsed_ms=round(elapsed*1000),
                max_active_responses=maximum, requests=requests, output_bytes=os.path.getsize(target),
                sha256_matches=True))
            os.remove(target)
    report['passed'] = True
finally:
    z.yt_dlp.YoutubeDL = original_engine
""".trimIndent(),fixture.get("__dict__"))
            }
            val report=JSONObject(python.getModule("json").callAttr("dumps",fixture.get("report")).toString())
            val run=InstrumentationRegistry.getArguments().getString("evidenceRun","manual")!!
            require(run.matches(Regex("[a-zA-Z0-9-]{1,100}")))
            val output=File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
            File(output,"fragment-concurrency.json").writeText(report.toString(2))
            assertTrue(report.getBoolean("passed"));assertEquals(10,report.getJSONArray("trials").length())
        } finally {root.deleteRecursively()}
    }
}
