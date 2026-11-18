package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline JNI wire check; the actual HTTPS/pooling/failure behavior is covered by host TLS tests. */
class MetadataTransportContractTest {
    @Test fun nativeBytesAndRepeatedHeadersSurviveTheUrllibBoundary() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val python=app.extractor.python()
        val namespace=python.getModule("__main__").get("__dict__")
        python.getModule("builtins").callAttr("exec","""
import json
import urllib.request
from java import jclass, jarray, jbyte
from metadata_transport import open_response
def _metadata_transport_contract():
    response = jclass('com.zdownloader.app.engine.MetadataHttpResponse')(
        302, 'Found', json.dumps([['Set-Cookie', 'a=1'], ['Set-Cookie', 'b=2'], ['Location', 'https://other.invalid/']]),
        jarray(jbyte)([0, 1, -1, 42]))
    class FixtureTransport:
        def execute(self, request_json, payload):
            request = json.loads(request_json)
            assert request['method'] == 'POST' and request['timeout_ms'] == 1500
            assert bytes(payload) == b'\x00\xff'
            return response
    request = urllib.request.Request('https://example.com/metadata', data=b'\x00\xff', method='POST')
    converted = open_response(FixtureTransport(), request, {'timeout_ms': 1500, 'operation': 'offline-wire'})
    assert converted.status == 302 and converted.read() == b'\x00\x01\xff*'
    assert converted.headers.get_all('Set-Cookie') == ['a=1', 'b=2']
    assert converted.headers['Location'] == 'https://other.invalid/'
    converted.close()
    return True
""".trimIndent(),namespace)
        assertTrue(python.getModule("__main__").callAttr("_metadata_transport_contract").toBoolean())
    }
}
