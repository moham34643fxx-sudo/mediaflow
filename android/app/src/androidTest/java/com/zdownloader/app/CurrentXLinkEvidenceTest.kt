package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Opt-in metadata-only reproduction; captures response shape, never cookies or media URLs. */
class CurrentXLinkEvidenceTest {
    @Test fun inspectEnteredPostAcrossPublicXEndpoints()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val python=app.extractor.python()
        val namespace=python.getModule("types").callAttr("ModuleType","x_source_evidence").get("__dict__")!!
        python.builtins.callAttr("exec","""
import json
from analysis_engine import AnalysisYoutubeDL
from yt_dlp.extractor.twitter import TwitterIE
rows=[]
class Probe(TwitterIE):
    def _download_json(self,*args,**kwargs):
        response=super()._download_json(*args,**kwargs)
        if 'TweetResult' in args[0] or 'tweet-result' in args[0]:
            result=response.get('data',{}).get('tweetResult',{}).get('result',response)
            rows.append({'endpoint':'graphql' if 'TweetResult' in args[0] else 'syndication',
                         'typename':result.get('__typename'),
                         'media_count':len(result.get('mediaDetails',[])),
                         'has_legacy_media':bool(result.get('legacy',{}).get('extended_entities',{}).get('media'))})
        return response
for api in ('graphql','syndication'):
    with AnalysisYoutubeDL({'quiet':True,'cachedir':False,'socket_timeout':20,'skip_download':True,
                           'extractor_args':{'twitter':{'api':[api]}}}) as engine:
        status=Probe(engine)._extract_status('2108610685756436951')
        rows[-1]['resolved_media_count']=len(status.get('extended_entities',{}).get('media',[]))
report=json.dumps({'post_id':'2108610685756436951','metadata_only':True,'responses':rows})
""".trimIndent(),namespace)
        val report=JSONObject(namespace.callAttr("__getitem__","report").toString())
        val directory=File(app.getExternalFilesDir(null),"three-fixes-evidence").apply {check(isDirectory||mkdirs())}
        File(directory,"current-x-endpoints.json").writeText(report.toString(2))
        assertEquals(2,report.getJSONArray("responses").length())
    }
}
