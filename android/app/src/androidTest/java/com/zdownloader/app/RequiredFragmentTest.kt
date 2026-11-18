package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.util.UUID

/** Offline transport fault fixture. It is not a successful-media/network benchmark. */
class RequiredFragmentTest {
    @Test fun unavailableRequiredFragmentCannotBecomeASuccessfulFinalFile()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val id="required-fragment-${UUID.randomUUID()}";val dir=app.storage.temporary(id)
        val output=File(dir,"incomplete.mp4")
        val run=InstrumentationRegistry.getArguments().getString("evidenceRun") ?: "manual"
        val evidence=File(app.getExternalFilesDir(null),"advanced-audit/$run").apply {mkdirs()}
        val module=app.extractor.python().getModule("zextractor")
        val script="""
import io as _p0_io
from pathlib import Path as _P0Path
from yt_dlp.networking.common import Response as _P0Response
from yt_dlp.networking.exceptions import HTTPError as _P0HTTPError
_p0_fragment_original_send = RequestDirector.send
_p0_fragment_seen = []
def _p0_fragment_send(self, request):
    if not request.url.startswith('https://zd-fragment-fixture.invalid/'):
        return _p0_fragment_original_send(self, request)
    _p0_fragment_seen.append(request.url.rsplit('/', 1)[-1])
    if request.url.endswith('missing.ts'):
        raise _P0HTTPError(_P0Response(_p0_io.BytesIO(b''), request.url, {}, status=404))
    return _P0Response(_p0_io.BytesIO((b'G' + bytes(187)) * 4), request.url, {'Content-Length': '752'})
class _P0Progress:
    def progress(self, *args): pass
try:
    RequestDirector.send = _p0_fragment_send
    _p0_payload = {'format_id': 'required-fragment', 'url': 'https://zd-fragment-fixture.invalid/media.m3u8',
        'ext': 'mp4', 'protocol': 'm3u8_native', 'vcodec': 'h264', 'acodec': 'none',
        'hls_media_playlist_data': '#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:1,\nhttps://zd-fragment-fixture.invalid/first.ts\n#EXTINF:1,\nhttps://zd-fragment-fixture.invalid/missing.ts\n#EXT-X-ENDLIST\n'}
    _p0_result = json.loads(download_stream(json.dumps(_p0_payload), ${JSONObject.quote(output.path).replace("\\/","/")}, ${JSONObject.quote(id)}, _P0Progress()))
    _P0Path(${JSONObject.quote(File(evidence,"required-fragment-diagnostic.json").path).replace("\\/","/")}).write_text(json.dumps({'result': _p0_result, 'requests': _p0_fragment_seen}))
    assert 'error' in _p0_result, _p0_result
    assert 'missing.ts' in _p0_fragment_seen, (_p0_result, _p0_fragment_seen)
    assert not _P0Path(${JSONObject.quote(output.path).replace("\\/","/")}).exists(), 'Incomplete final file was promoted'
    _P0Path(${JSONObject.quote(File(evidence,"required-fragment.json").path).replace("\\/","/")}).write_text(json.dumps({
        'result': _p0_result, 'missing_fragment_requested': True, 'final_file_promoted': False,
        'scope': 'Offline mocked HTTPS transport; nonfixture traffic retains original secure transport'}))
finally:
    RequestDirector.send = _p0_fragment_original_send
    del _p0_fragment_original_send, _p0_fragment_seen, _p0_fragment_send, _P0Progress, _p0_payload, _p0_result
""".trimIndent()
        try {app.extractor.python().builtins.callAttr("exec",script,module.get("__dict__"))}
        finally {app.storage.removeTemporary(id)}
        Unit
    }
}
