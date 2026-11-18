package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.engine.JsBridge
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EmbeddedEjsTest {
    @Test fun bundledEjsParsesAndSolvesControlledChallengesThroughTheRegisteredAndroidRuntime()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val module=app.extractor.python().getModule("android_jsc")
        val script="""
def _zd_ejs_fixture():
    import hashlib
    import time
    import yt_dlp_ejs.yt.solver as solver
    from yt_dlp.extractor.youtube.jsc._builtin import vendor
    lib, core = solver.lib(), solver.core()
    for name, source in [('yt.solver.lib.min.js', lib), ('yt.solver.core.min.js', core)]:
        expected = vendor.HASHES[name]
        actual = hashlib.sha3_512(source.encode()).hexdigest()
        assert actual in expected if isinstance(expected, (list, tuple, set)) else actual == expected
    reset_stats()
    start = time.monotonic()
    prefix = lib + '\nObject.assign(globalThis, lib);\n' + core + '\n'
    def solve(value):
        source = prefix + 'console.log(JSON.stringify(jsc(' + json.dumps(value) + ')));'
        return json.loads(AndroidQuickJSJCP._run_js_runtime(None, source))
    parsed = solve({'type': 'player', 'player': '(function(){var marker=1;}).call(this);', 'requests': [], 'output_preprocessed': True})
    assert parsed['type'] == 'result' and parsed['preprocessed_player']
    result = solve({'type': 'preprocessed', 'preprocessed_player': '_result.n = s => s.split(\"\").reverse().join(\"\"); _result.sig = s => s.slice(1) + s[0];',
        'requests': [{'type':'n','challenges':['abc']},{'type':'sig','challenges':['abcde']}]})
    assert result['responses'][0]['data']['abc'] == 'cba', result
    assert result['responses'][1]['data']['abcde'] == 'bcdea', result
    return json.dumps({'state':'passed','scope':'Bundled EJS parser and controlled n/sig fixture, not a live YouTube compatibility claim',
        'library_hashes_verified':True,'elapsed_ms':round((time.monotonic()-start)*1000),'statistics':stats(),'result':result})
_zd_ejs_fixture_json = _zd_ejs_fixture()
del _zd_ejs_fixture
""".trimIndent()
        app.extractor.python().builtins.callAttr("exec",script,module.get("__dict__"))
        val report=JSONObject(module.get("_zd_ejs_fixture_json").toString())
        assertEquals(2,report.getJSONObject("statistics").getInt("solved"))
        val unicode="العربية 😀"
        val roundTrip=JsBridge.run("console.log(${JSONObject.quote(unicode)})")
        assertEquals(unicode,roundTrip)
        report.put("unicode_and_emoji_round_trip",true).put("runtime",JsBridge.version())
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "manual"}").apply {mkdirs()}
        File(folder,"embedded-ejs.json").writeText(report.toString(2))
        app.extractor.python().builtins.callAttr("exec","del _zd_ejs_fixture_json",module.get("__dict__"))
        Unit
    }
}
