package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.domain.ErrorCode
import com.zdownloader.app.domain.Format
import com.zdownloader.app.domain.MediaException
import com.zdownloader.app.engine.Transfer
import com.zdownloader.app.engine.TransferControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class BoundedTransferRegressionTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
    private fun format()=Format("bounded","bin",null,null,"none","none",null,null,"https",
        JSONObject().put("url","https://example.com/file").put("http_headers",JSONObject().put("Cookie","session=fixture")).toString())
    private fun response(request:Request,code:Int,bytes:ByteArray,range:String?=null,etag:String="\"fixture-v1\""):Response {
        val builder=Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
            .header("ETag",etag).body(ResponseBody.create(null,bytes))
        if(range!=null) builder.header("Content-Range",range)
        return builder.build()
    }
    @Test fun multipleRangesKeepCookiesAndAssembleExactBytes():Unit=runBlocking {
        val directory=app.storage.temporary("bounded-${UUID.randomUUID()}")
        try {
            val expected=ByteArray(5*1024*1024+17) {(it%251).toByte()};var requests=0
            val client=OkHttpClient.Builder().addInterceptor {chain->
                val request=chain.request();assertEquals("session=fixture",request.header("Cookie"));requests++
                val bounds=request.header("Range")!!.removePrefix("bytes=").split('-').map(String::toInt)
                val end=minOf(bounds[1],expected.lastIndex)
                response(request,206,expected.copyOfRange(bounds[0],end+1),"bytes ${bounds[0]}-$end/${expected.size}")
            }.build()
            val output=File(directory,"output.bin")
            Transfer(app.extractor,client).stream(format(),output,"bounded",TransferControl()) {_,_->}
            assertTrue("Multiple bounded ranges must assemble the full representation",requests in 2..3);assertArrayEquals(expected,output.readBytes())
        } finally {directory.deleteRecursively()}
    }
    @Test fun unknownLengthPartialResponseMustNotBePublishedAsACompleteFile():Unit=runBlocking {
        val directory=app.storage.temporary("unknown-range-${UUID.randomUUID()}")
        try {
            val expected=ByteArray(100_000) {(it%239).toByte()};var requests=0
            val client=OkHttpClient.Builder().addInterceptor {chain->
                requests++
                if(requests==1) response(chain.request(),206,expected.copyOfRange(0,60_000),"bytes 0-59999/*")
                else {assertNull(chain.request().header("Range"));response(chain.request(),200,expected)}
            }.build()
            val output=File(directory,"output.bin")
            Transfer(app.extractor,client).stream(format(),output,"unknown-range",TransferControl()) {_,_->}
            assertEquals(2,requests);assertArrayEquals(expected,output.readBytes())
        } finally {directory.deleteRecursively()}
    }
    @Test fun knownSizeStartsAt16MiBWindowAndCompletesInSingleBoundedRequest():Unit=runBlocking {
        val directory=app.storage.temporary("known-16m-${UUID.randomUUID()}")
        try {
            val expected=ByteArray(2*1024*1024+257) {(it%241).toByte()}
            var requests=0
            var observedRange:String?=null
            val client=OkHttpClient.Builder().addInterceptor {chain->
                val request=chain.request();requests++
                observedRange=request.header("Range")
                val bounds=observedRange!!.removePrefix("bytes=").split('-').map(String::toInt)
                val end=minOf(bounds[1],expected.lastIndex)
                response(request,206,expected.copyOfRange(bounds[0],end+1),"bytes ${bounds[0]}-$end/${expected.size}")
            }.build()
            val output=File(directory,"output.bin")
            val knownFormat=format().copy(bytes=expected.size.toLong())
            Transfer(app.extractor,client).stream(knownFormat,output,"known-16m",TransferControl()) {_,_->}
            assertEquals(1,requests)
            assertEquals("bytes=0-16777215",observedRange)
            val expectedSha=MessageDigest.getInstance("SHA-256").digest(expected)
            val actualSha=MessageDigest.getInstance("SHA-256").digest(output.readBytes())
            assertArrayEquals(expectedSha,actualSha)
            assertArrayEquals(expected,output.readBytes())
        } finally {directory.deleteRecursively()}
    }
    @Test fun interruptedTransferResumesWithRetainedOffsetAndIfRange():Unit=runBlocking {
        val directory=app.storage.temporary("resume-${UUID.randomUUID()}")
        try {
            val expected=ByteArray(2*1024*1024) {(it%247).toByte()}
            val partialSize=64*1024
            val control1=TransferControl()
            var requestCount=0
            var resumedRange:String?=null
            var resumedIfRange:String?=null
            val client=OkHttpClient.Builder().addInterceptor {chain->
                val request=chain.request();requestCount++
                if(requestCount==1) {
                    response(request,206,expected.copyOfRange(0,partialSize),"bytes 0-${partialSize-1}/${expected.size}")
                } else {
                    resumedRange=request.header("Range")
                    resumedIfRange=request.header("If-Range")
                    val bounds=resumedRange!!.removePrefix("bytes=").split('-').map(String::toInt)
                    val end=minOf(bounds[1],expected.lastIndex)
                    response(request,206,expected.copyOfRange(bounds[0],end+1),"bytes ${bounds[0]}-$end/${expected.size}")
                }
            }.build()
            val output=File(directory,"output.bin")
            val knownFormat=format().copy(bytes=expected.size.toLong())
            try {
                Transfer(app.extractor,client).stream(knownFormat,output,"resume-1",control1) {downloaded,_->
                    if(downloaded>=partialSize) control1.stop()
                }
                fail("First transfer attempt must be cancelled")
            } catch(_:CancellationException) {}
            val part=File(output.path+".part")
            val resume=File(output.path+".resume")
            assertTrue("Part file must exist",part.isFile)
            assertEquals(partialSize.toLong(),part.length())
            assertTrue("Resume file must exist",resume.isFile)
            val resumeJson=JSONObject(resume.readText())
            assertEquals("\"fixture-v1\"",resumeJson.optString("validator"))
            assertEquals(expected.size.toLong(),resumeJson.optLong("total"))
            val control2=TransferControl()
            Transfer(app.extractor,client).stream(knownFormat,output,"resume-2",control2) {_,_->}
            assertEquals(2,requestCount)
            val expectedResumeEnd=partialSize+16L*1024*1024-1
            assertEquals("bytes=$partialSize-$expectedResumeEnd",resumedRange)
            assertEquals("\"fixture-v1\"",resumedIfRange)
            val expectedSha=MessageDigest.getInstance("SHA-256").digest(expected)
            val actualSha=MessageDigest.getInstance("SHA-256").digest(output.readBytes())
            assertArrayEquals(expectedSha,actualSha)
            assertArrayEquals(expected,output.readBytes())
            val completeMarker=File(output.path+".complete")
            assertTrue("Complete marker must be non-empty",completeMarker.isFile&&completeMarker.length()>0)
        } finally {directory.deleteRecursively()}
    }
    @Test fun resumedPrefixDoesNotCountAsTheFirstNewByte()=runBlocking {
        val directory=app.storage.temporary("fresh-byte-resume-${UUID.randomUUID()}")
        try {
            val expected=ByteArray(256*1024+31) {(it%239).toByte()}
            val partialSize=64*1024
            val output=File(directory,"output.bin")
            File(output.path+".part").writeBytes(expected.copyOfRange(0,partialSize))
            File(output.path+".resume").writeText(JSONObject().put("validator","\"fixture-v1\"")
                .put("formatId","bounded").put("total",expected.size).toString())
            val callbacks=AtomicInteger()
            var retainedProgressSawNoFreshByte=false
            val client=OkHttpClient.Builder().addInterceptor {chain->
                val request=chain.request()
                assertTrue("Resume request must begin at the retained offset",
                    request.header("Range")?.startsWith("bytes=$partialSize-")==true)
                assertEquals("\"fixture-v1\"",request.header("If-Range"))
                response(request,206,expected.copyOfRange(partialSize,expected.size),
                    "bytes $partialSize-${expected.lastIndex}/${expected.size}")
            }.build()
            Transfer(app.extractor,client).stream(format().copy(bytes=expected.size.toLong()),output,"fresh-byte-resume",TransferControl(),
                onFirstNewByte={callbacks.incrementAndGet()}) {downloaded,_->
                if(downloaded==partialSize.toLong()) retainedProgressSawNoFreshByte=callbacks.get()==0
            }
            assertTrue("The retained-prefix progress callback must not report a new network byte",retainedProgressSawNoFreshByte)
            assertEquals("Fresh bytes produce one first-byte callback",1,callbacks.get())
            assertArrayEquals(expected,output.readBytes())
        } finally {directory.deleteRecursively()}
    }
    @Test fun completedRetainedFileDoesNotReportANewByte()=runBlocking {
        val directory=app.storage.temporary("fresh-byte-complete-${UUID.randomUUID()}")
        try {
            val expected=ByteArray(128*1024+3) {(it%233).toByte()}
            val output=File(directory,"output.bin")
            val format=format().copy(bytes=expected.size.toLong())
            val client=OkHttpClient.Builder().addInterceptor {chain->
                val request=chain.request()
                response(request,206,expected,"bytes 0-${expected.lastIndex}/${expected.size}")
            }.build()
            val transfer=Transfer(app.extractor,client)
            transfer.stream(format,output,"fresh-byte-complete",TransferControl()) {_,_->}
            val callbacks=AtomicInteger()
            transfer.stream(format,output,"fresh-byte-complete-reuse",TransferControl(),
                onFirstNewByte={callbacks.incrementAndGet()}) {_,_->}
            assertEquals("Reusing a completed retained track is not a fresh-byte event",0,callbacks.get())
            assertArrayEquals(expected,output.readBytes())
        } finally {directory.deleteRecursively()}
    }
    @Test fun changedETagOnResumeRejectsAndDeletesPartialBytes():Unit=runBlocking {
        val directory=app.storage.temporary("etag-change-${UUID.randomUUID()}")
        try {
            val expected=ByteArray(2*1024*1024) {(it%251).toByte()}
            val partialSize=64*1024
            val control1=TransferControl()
            val client1=OkHttpClient.Builder().addInterceptor {chain->
                response(chain.request(),206,expected.copyOfRange(0,partialSize),"bytes 0-${partialSize-1}/${expected.size}",etag="\"etag-v1\"")
            }.build()
            val output=File(directory,"output.bin")
            val knownFormat=format().copy(bytes=expected.size.toLong())
            try {
                Transfer(app.extractor,client1).stream(knownFormat,output,"etag-1",control1) {downloaded,_->
                    if(downloaded>=partialSize) control1.stop()
                }
                fail("First transfer must stop")
            } catch(_:CancellationException) {}
            val part=File(output.path+".part")
            assertTrue("Partial bytes must be preserved after cancellation",part.isFile&&part.length()>=partialSize)
            val client2=OkHttpClient.Builder().addInterceptor {chain->
                response(chain.request(),206,expected.copyOfRange(partialSize,expected.size),"bytes $partialSize-${expected.lastIndex}/${expected.size}",etag="\"etag-v2-changed\"")
            }.build()
            try {
                Transfer(app.extractor,client2).stream(knownFormat,output,"etag-2",TransferControl()) {_,_->}
                fail("Changed ETag on resume must be rejected")
            } catch(e:MediaException) {
                assertEquals(ErrorCode.DOWNLOAD_INTERRUPTED,e.code)
            }
            assertFalse("Part file must be deleted when validator mismatches",part.exists())
            assertFalse("Output file must not exist",output.exists())
        } finally {directory.deleteRecursively()}
    }
}
