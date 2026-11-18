package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.*
import com.zdownloader.app.engine.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okio.Buffer
import okio.BufferedSource
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AuditConflictTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication

    @Test fun fullUnknownLengthReplacementDiscardsOldTotal()=runBlocking {
        val id="audit-unknown-replacement"
        val dir=app.storage.temporary(id)
        try {
            val output=File(dir,"source.bin")
            File(output.path+".part").writeBytes(byteArrayOf(9,9))
            File(output.path+".resume").writeText(JSONObject().put("validator","old-tag").put("formatId","fixture").put("total",20).toString())
            val expected=byteArrayOf(1,2,3,4)
            val body=object:ResponseBody() {
                override fun contentType():MediaType?=null
                override fun contentLength()=-1L
                override fun source():BufferedSource=Buffer().write(expected)
            }
            val client=OkHttpClient.Builder().addInterceptor {chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("Representation replaced").header("ETag","new-tag").body(body).build()}.build()
            val format=Format("fixture","bin",null,null,"none","none",null,null,"https",JSONObject().put("url","https://example.com/replacement").toString())
            var lastTotal:Long?=null
            Transfer(app.extractor,client).stream(format,output,id,TransferControl()) {_,total ->lastTotal=total}
            assertNull(lastTotal)
            assertArrayEquals(expected,output.readBytes())
        } finally {app.storage.removeTemporary(id)}
    }

    @Test fun newValidatorSurvivesAnotherPauseAfterFullReplacement()=runBlocking {
        val id="audit-validator-replacement"
        val dir=app.storage.temporary(id)
        try {
            val output=File(dir,"source.bin")
            File(output.path+".part").writeBytes(byteArrayOf(9,9))
            File(output.path+".resume").writeText(JSONObject().put("validator","old-tag").put("formatId","fixture").put("total",20).toString())
            val expected=ByteArray(200000) {(it%251).toByte()}
            var request=0
            val client=OkHttpClient.Builder().addInterceptor {chain ->
                request++
                val response=Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("Fixture").header("ETag","new-tag")
                if(request==1) response.code(200).body(ResponseBody.create(null,expected)).build()
                else {
                    assertEquals("new-tag",chain.request().header("If-Range"))
                    val start=chain.request().header("Range")!!.substringAfter("bytes=").substringBefore('-').toInt()
                    response.code(206).header("Content-Range","bytes $start-${expected.lastIndex}/${expected.size}")
                        .body(ResponseBody.create(null,expected.copyOfRange(start,expected.size))).build()
                }
            }.build()
            val format=Format("fixture","bin",null,null,"none","none",null,null,"https",JSONObject().put("url","https://example.com/replacement").toString())
            val control=TransferControl()
            try {Transfer(app.extractor,client).stream(format,output,id,control) {bytes,_ ->if(bytes>=65536) control.stop()};fail("Expected pause")} catch(_:kotlinx.coroutines.CancellationException) { }
            Transfer(app.extractor,client).stream(format,output,id,TransferControl()) {_,_->}
            assertArrayEquals(expected,output.readBytes())
        } finally {app.storage.removeTemporary(id)}
    }

    @Test fun signedAudioPreferenceIsNotDiscardedDuringParsing() {
        fun format(id:String,preference:Double)=Format.parse(JSONObject().put("format_id",id).put("ext","m4a").put("vcodec","none")
            .put("acodec","aac").put("abr",128).put("language_preference",preference)
            .put("protocol","https").put("url","https://example.com/$id.m4a"))
        val commentary=format("commentary",-10.0)
        val preferred=format("preferred",-1.0)
        assertEquals(-10.0,commentary.languagePreference!!,0.0)
        val media=Media("https://example.com/audio","audio","Audio","Fixture",null,null,10.0,listOf(preferred,commentary))
        assertEquals("preferred",Selection.audio(media,"original").primary.id)
    }
    @Test fun pythonRejectsTopLevelLiveAndEmptySupportedFormats() {
        val module=app.extractor.python().getModule("zextractor")
        val script="""
_audit_saved_ydl = AnalysisYoutubeDL
_audit_saved_configure = android_jsc.configure
class _AuditYdl:
    def __init__(self, options): self.cookiejar = None
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def extract_info(self, *args, **kwargs): return _audit_info
try:
    AnalysisYoutubeDL = _AuditYdl
    android_jsc.configure = lambda engine: None
    _audit_info = {'is_live': True, 'formats': [{'url': 'https://example.com/live.m3u8', 'protocol': 'm3u8_native'}]}
    assert json.loads(analyze('https://example.com/watch', 'audit-live-root'))['error'] == 'LIVE_STREAM_DETECTED'
    _audit_info = {'formats': [{'url': 'https://example.com/storyboard', 'protocol': 'mhtml'}]}
    assert json.loads(analyze('https://example.com/watch', 'audit-no-formats'))['error'] == 'FORMAT_UNAVAILABLE'
finally:
    AnalysisYoutubeDL = _audit_saved_ydl
    android_jsc.configure = _audit_saved_configure
    del _audit_saved_ydl, _audit_saved_configure, _AuditYdl, _audit_info
""".trimIndent()
        app.extractor.python().builtins.callAttr("exec",script,module.get("__dict__"))
    }
}
