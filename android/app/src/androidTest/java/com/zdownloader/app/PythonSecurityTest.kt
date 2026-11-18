package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PythonSecurityTest {
    @Test fun bundledRuntimeEnforcesCredentialAndCompressedMetadataBoundaries()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val python=app.extractor.python()
        val fixture=python.getModule("types").callAttr("ModuleType","mediaflow_audit_fixture")
        val script="""
import json, gzip, io, threading, urllib.request, email.message
import zextractor as z
from network_errors import classify
import yt_dlp.extractor as registry
from yt_dlp.extractor.generic import GenericIE
for port in (1, 443, 8443, 65535):
    value = 'https://example.com:%d/a%%2Fb?sig=one%%2Btwo' % port
    assert z.validate_url(value) == value
for value in ('http://example.com:8443/a', 'https://127.0.0.1:8443/a', 'https://169.254.169.254:8443/a', 'https://example.com:0/a', 'https://example.com:65536/a'):
    try: z.validate_url(value)
    except ValueError: pass
    else: raise AssertionError('Unsafe URL accepted')
assert classify(Exception('Unable to fetch live stream recording: HTTP Error 403'))[0] == 'HTTP_FORBIDDEN'
assert classify(Exception('This video is a live stream'))[0] == 'LIVE_STREAM_DETECTED'
extractors = registry.gen_extractor_classes()
assert extractors
assert GenericIE not in extractors
from yt_dlp.networking._urllib import RedirectHandler, HTTPHandler
from yt_dlp import YoutubeDL
request = urllib.request.Request('https://source.example/video', headers={'Authorization':'Bearer SYNTHETIC','X-Custom-Credential':'SYNTHETIC'})
redirected = z.secure_redirect(RedirectHandler(), request, None, 302, 'Found', {}, 'https://other.example/video')
assert not any(k.lower() == 'authorization' for k, v in redirected.header_items())
assert not any(k.lower() == 'x-custom-credential' for k, v in redirected.header_items())
same = z.secure_redirect(RedirectHandler(), request, None, 302, 'Found', {}, 'https://source.example/other')
assert same.get_header('Authorization') == 'Bearer SYNTHETIC'
with YoutubeDL({'quiet':True,'cachedir':False,'remote_components':[],'js_runtimes':{}}) as engine:
    info = {'url':'https://source.example/status/clip.mp4', 'cookies':'session=SYNTHETIC; Domain=source.example; Path=/status/; Secure'}
    engine._calc_headers(info, load_cookies=True)
    assert len(engine.cookiejar.get_cookies_for_url(info['url'])) == 1
    assert not engine.cookiejar.get_cookies_for_url('https://other.example/status/clip.mp4')
    assert not engine.cookiejar.get_cookies_for_url('https://source.example/unrelated/clip.mp4')
event = z.register_request('audit-compressed-fixture')
try:
    payload = b'x' * (8 * 1024 * 1024 + 1)
    headers = email.message.Message(); headers['Content-Encoding'] = 'gzip'
    response = urllib.request.addinfourl(io.BytesIO(gzip.compress(payload)), headers, 'https://fixture.example/page', 200)
    response.msg = 'OK'
    blocked = False
    try: z.secure_http_response(HTTPHandler(), request, response)
    except ValueError as error: blocked = str(error) == 'METADATA_LIMIT'
    assert blocked
finally:
    z._current.event = None
    with z._lock: z._requests.pop('audit-compressed-fixture', None)
result = json.dumps({'cross_origin_credentials_stripped':True,'same_origin_authorization_retained':True,
    'public_https_ports_validated':True,'live_recording_error_not_misclassified':True,'specialized_extractors':len(extractors),
    'cookie_domain_and_path_scoped':True,'compressed_metadata_limit_enforced':True,
    'scope':'Bundled Android Python runtime, in-memory synthetic HTTP responses only'})
""".trimIndent()
        python.builtins.callAttr("exec",script,fixture.get("__dict__"))
        val report=JSONObject(fixture.get("result").toString())
        assertTrue(report.getBoolean("compressed_metadata_limit_enforced"))
        val out=File(app.getExternalFilesDir(null),"advanced-audit/${InstrumentationRegistry.getArguments().getString("evidenceRun","manual")}").apply {mkdirs()}
        File(out,"python-security-boundaries.json").writeText(report.toString(2))
        Unit
    }
}
