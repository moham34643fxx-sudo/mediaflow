package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Registered provider, pinned EJS and native QuickJS; only player/challenge inputs are controlled. */
class PlayerTransformReuseTest {
    @Test fun transformedPlayerReusesParsingButSolvesFreshChallengesAndRecoversFromBadCache()=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as ZApplication
        val python=app.extractor.python()
        val namespace=python.getModule("types").callAttr("ModuleType","transform_reuse_fixture").get("__dict__")!!
        python.builtins.callAttr("exec","""
import json, logging, hashlib
import android_jsc
from analysis_engine import AnalysisYoutubeDL
from yt_dlp.extractor.youtube.jsc.provider import JsChallengeRequest, JsChallengeType, NChallengeInput
player = '''(function(){
function Bucket(sig){this.values={s:sig};}
Bucket.prototype.set=function(key,value){this.values[key]=value;};
Bucket.prototype.get=function(key){return this.values[key];};
Bucket.prototype.decode=function(){if(this.values.n)this.values.n=this.values.n.split('').reverse().join('');};
function candidate(url,key,sig){var bucket=new Bucket(sig);bucket.set('alr','yes');return bucket;}
}).call(this);'''
android_jsc._player_transforms.clear()
try:
    with AnalysisYoutubeDL({'quiet':True,'cachedir':False,'remote_components':[],'js_runtimes':{}}) as engine:
        provider=android_jsc.AndroidQuickJSJCP(engine.get_info_extractor('Youtube'),logging.getLogger('reuse-fixture'),{})
        def solve(source, challenge):
            android_jsc.reset_stats()
            request=JsChallengeRequest(JsChallengeType.N,NChallengeInput('https://fixture.example/player.js',[challenge]))
            output=json.loads(provider._run_js_runtime(provider._construct_stdin(source,False,[request])))
            assert output['type']=='result'
            assert output['responses'][0]['type']=='result'
            assert output['responses'][0]['data'][challenge]==challenge[::-1]
            return android_jsc.stats()
        cold=solve(player,'abc')
        warm=solve(player,'different')
        assert cold['transform_cache_misses']==1 and warm['transform_cache_hits']==1
        assert warm['runs']==1 and warm['solved']==1
        changed=solve(player+'\n// new source bytes','new')
        assert changed['transform_cache_misses']==1
        key=(hashlib.sha256(player.encode('utf-8')).digest(),provider._lib_script.hash,provider._core_script.hash)
        recoveries=[]
        for poisoned in ['not valid javascript {', '_result.n = function(){throw new Error("fixture");};']:
            android_jsc._player_transforms.put(key,poisoned)
            recovered=solve(player,'recovered')
            assert recovered['transform_cache_hits']==1 and recovered['transform_cache_fallbacks']==1
            assert recovered['runs']==2 and recovered['solved']==1
            recoveries.append(recovered)
        report=json.dumps({'passed':True,'cold':cold,'warm':warm,'changed_source':changed,'recoveries':recoveries,
            'scope':'Controlled real-EJS/native-provider correctness, not live network performance'})
finally:
    android_jsc._player_transforms.clear()
""".trimIndent(),namespace)
        val report=JSONObject(namespace.callAttr("__getitem__","report").toString())
        assertEquals(true,report.getBoolean("passed"))
        val run=requireNotNull(InstrumentationRegistry.getArguments().getString("evidenceRun"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val folder=File(app.getExternalFilesDir(null),"advanced-audit/$run")
        check(!folder.exists()&&folder.mkdirs())
        File(folder,"transform-reuse-fixture.json").writeText(report.toString(2))
    }
}
