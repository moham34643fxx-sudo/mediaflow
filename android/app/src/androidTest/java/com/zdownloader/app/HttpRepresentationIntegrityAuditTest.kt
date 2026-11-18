@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.Format
import com.zdownloader.app.engine.Transfer
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.runBlocking
import okhttp3.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Transport integrity, deliberately using same-length but different representation bytes. */
class HttpRepresentationIntegrityAuditTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
    private fun format()=Format("audit","mp4",null,null,"h264","none",8,null,"https",
        JSONObject().put("url","https://representation-audit.example/media").toString())

    @Test fun unvalidatedWindowsRestartAsOneFullRepresentation()=runBlocking<Unit> {
        val root=File(app.cacheDir,"range-audit-${UUID.randomUUID()}").apply {check(mkdirs())}
        try {
            for(etag in listOf<String?>(null,"W/\"weak\"")) {
                val requests=mutableListOf<Request>()
                val client=OkHttpClient.Builder().addInterceptor {chain->
                    val request=chain.request();requests+=request
                    val range=request.header("Range")
                    val response=Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("Controlled source")
                    if(range==null) response.code(200).body(ResponseBody.create(null,"QRSTUVWX"))
                    else {
                        val start=if(range.startsWith("bytes=0-")) 0 else 4
                        response.code(206).header("Content-Range","bytes $start-${start+3}/8")
                            .body(ResponseBody.create(null,if(start==0) "ABCD" else "UVWX"))
                        etag?.let {response.header("ETag",it)}
                    }
                    response.build()
                }.build()
                val output=File(root,"${requests.hashCode()}-${UUID.randomUUID()}.mp4")
                Transfer(app.extractor,client).stream(format(),output,"audit",TransferControl()) {_,_->}
                assertEquals("Range windows from unproved representations were spliced", "QRSTUVWX",output.readText())
                assertEquals(2,requests.size)
                assertNull("Unvalidated continuation must become a full request",requests.last().header("Range"))
            }
        } finally {root.deleteRecursively()}
    }

    @Test fun missingResumeValidatorEchoNeverApprovesAnOldPrefix()=runBlocking<Unit> {
        val root=File(app.cacheDir,"resume-audit-${UUID.randomUUID()}").apply {check(mkdirs())}
        try {
            val output=File(root,"resumed.mp4")
            File(output.path+".part").writeText("ABCD")
            File(output.path+".resume").writeText(JSONObject().put("validator","\"old\"").put("formatId","audit").put("total",8).toString())
            val requests=mutableListOf<Request>()
            val client=OkHttpClient.Builder().addInterceptor {chain->
                val request=chain.request();requests+=request
                val response=Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("Changed source")
                if(request.header("Range")!=null) response.code(206).header("Content-Range","bytes 4-7/8")
                    .body(ResponseBody.create(null,"UVWX"))
                else response.code(200).body(ResponseBody.create(null,"QRSTUVWX"))
                response.build()
            }.build()
            Transfer(app.extractor,client).stream(format(),output,"audit",TransferControl()) {_,_->}
            assertEquals("An unconfirmed old prefix survived resume","QRSTUVWX",output.readText())
            assertEquals("\"old\"",requests.first().header("If-Range"))
            assertNull(requests.last().header("Range"))
        } finally {root.deleteRecursively()}
    }

    @Test fun matchingStrongValidatorsRetainEfficientWindowedTransfers()=runBlocking<Unit> {
        val root=File(app.cacheDir,"valid-range-audit-${UUID.randomUUID()}").apply {check(mkdirs())}
        try {
            val requests=mutableListOf<Request>()
            val client=OkHttpClient.Builder().addInterceptor {chain->
                val request=chain.request();requests+=request
                val start=if(request.header("Range")!!.startsWith("bytes=0-")) 0 else 4
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("Stable representation").code(206)
                    .header("ETag","\"stable\"").header("Content-Range","bytes $start-${start+3}/8")
                    .body(ResponseBody.create(null,if(start==0) "ABCD" else "EFGH")).build()
            }.build()
            val output=File(root,"stable.mp4")
            Transfer(app.extractor,client).stream(format(),output,"audit",TransferControl()) {_,_->}
            assertEquals("ABCDEFGH",output.readText());assertEquals(2,requests.size)
            assertEquals("\"stable\"",requests.last().header("If-Range"))
        } finally {root.deleteRecursively()}
    }
}
