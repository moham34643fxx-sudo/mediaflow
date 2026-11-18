package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Opt-in diagnostic: profiler overhead is NOT a user-visible latency benchmark. */
class ManifestDiscoveryProfilingTest {
    @Test fun comparesExtractorMatchingWithoutChangingRegistryOrder() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as ZApplication
        val args = InstrumentationRegistry.getArguments()
        val run = requireNotNull(args.getString("evidenceRun")); require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val directory = File(app.getExternalFilesDir(null), "manifest-profile/$run").also { check(it.mkdirs()) }
        check(runBlocking { app.repository.downloads.first().none { it.status !in setOf("COMPLETED", "FAILED", "CANCELLED", "PAUSED") } })
        val source = args.getString("probeSource") ?: "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd"
        val expectedFormats = args.getString("expectedFormats")?.toInt() ?: if (args.getString("probeSource") == null) 11 else null
        val runtimeAlreadyStarted = com.chaquo.python.Python.isStarted()
        val python = app.extractor.python()
        val namespace = python.getModule("types").callAttr("ModuleType", "manifest_profile").get("__dict__")!!
        namespace.callAttr("__setitem__", "request_id", run)
        namespace.callAttr("__setitem__", "source", source)
        namespace.callAttr("__setitem__", "prefix_rules_path", args.getString("prefixRulesPath"))
        namespace.callAttr("__setitem__", "profile_enabled", args.getString("profileEnabled", "true") == "true")
        python.builtins.callAttr("exec", """
import cProfile, functools, hashlib, json, os, pstats, random, sys, time
assert 'yt_dlp' not in sys.modules, 'comparison requires a fresh SDK import'
# yt-dlp chooses its default User-Agent at import. Keep that choice identical
# across comparison arms without changing downloader options or ignoring fields.
random.seed(1729)
import zextractor
from yt_dlp.extractor.lazy_extractors import LazyLoadExtractor
from yt_dlp.version import __version__
original_match = LazyLoadExtractor._match_valid_url.__func__
began = time.monotonic()
prefix_mismatch_calls = 0
if prefix_rules_path:
    assert os.path.getsize(prefix_rules_path) <= 1024*1024
    with open(prefix_rules_path) as handle: index = json.load(handle)
    assert index['yt_dlp_version'] == __version__
    rules = index['rules']
@functools.lru_cache(maxsize=2048)
def pattern_prefixes(patterns):
    fingerprint = hashlib.sha256(json.dumps(patterns, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()
    return tuple(rules.get(fingerprint, ()))
@functools.lru_cache(maxsize=4)
def ascii_url(url): return url.lower() if url.isascii() else None
def filtered_match(cls, url):
    global prefix_mismatch_calls
    if '_VALID_URL_RE' not in cls.__dict__:
        raw = cls._VALID_URL
        patterns = (raw,) if isinstance(raw, str) else tuple(raw) if isinstance(raw, (list, tuple)) else ()
        if patterns and all(isinstance(pattern, str) for pattern in patterns):
            prefixes = pattern_prefixes(patterns)
            normalized = ascii_url(url)
            if prefixes and normalized is not None and not normalized.startswith(prefixes):
                prefix_mismatch_calls += 1
                return None
    return original_match(cls, url)
profile = cProfile.Profile()
try:
    if prefix_rules_path: LazyLoadExtractor._match_valid_url = classmethod(filtered_match)
    if profile_enabled: profile.enable()
    extracted = json.loads(zextractor.analyze(source, request_id))
finally:
    profile.disable()
    LazyLoadExtractor._match_valid_url = classmethod(original_match)
elapsed = round((time.monotonic() - began) * 1000)
hot = []
if profile_enabled:
    stats = pstats.Stats(profile)
    for (filename, line, function), (primitive, calls, self_s, cumulative_s, callers) in sorted(stats.stats.items(), key=lambda item: item[1][3], reverse=True)[:70]:
        hot.append(dict(file=os.path.basename(filename), line=line, function=function, calls=calls, self_ms=round(self_s*1000, 3), cumulative_ms=round(cumulative_s*1000, 3)))
report = dict(prefix_filter=bool(prefix_rules_path), prefix_mismatch_calls=prefix_mismatch_calls, profile_enabled=profile_enabled, default_sdk_randomness_seed=1729,
              elapsed_native_analysis_ms=elapsed, scope='Native Python analysis with identical default SDK randomness and prefix-index preparation; runtime/module initialization excluded; test-only experiment, not a production speedup claim',
              error=extracted.get('error'), timings=extracted.get('timings_ms'), hot=hot,
              catalog_payload_sha256=hashlib.sha256(json.dumps(extracted.get('formats', []), sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest(),
              format_field_sha256=[{key: hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest() for key, value in f.items()} for f in extracted.get('formats', [])],
              formats=[{key: f.get(key) for key in ('format_id','ext','width','height','vcodec','acodec','protocol','tbr','abr')} for f in extracted.get('formats', [])])
report_json = json.dumps(report)
""".trimIndent(), namespace)
        val report = JSONObject(python.builtins.callAttr("eval", "report_json", namespace).toString())
            .put("python_started_before_test", runtimeAlreadyStarted)
        File(directory, "result.json").writeText(report.toString(2))
        assertTrue(report.isNull("error")); assertTrue(report.getJSONArray("formats").length() > 0)
        expectedFormats?.let { assertEquals(it, report.getJSONArray("formats").length()) }
    }
}
