package com.zdownloader.app.engine

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MetadataHttpBridgeTest {
    private lateinit var server:MockWebServer
    private lateinit var client:OkHttpClient
    private lateinit var bridge:MetadataHttpBridge

    @Before fun setup() {
        val certificate=HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val identity=HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server=MockWebServer().apply {useHttps(identity.sslSocketFactory(),false);start()}
        client=OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        bridge=MetadataHttpBridge(client)
    }

    @After fun cleanup() {bridge.close();client.connectionPool.evictAll();server.shutdown()}

    private fun request(method:String="GET",timeout:Long=2000)=JSONObject().put("url",server.url("/metadata").toString())
        .put("method",method).put("timeout_ms",timeout).put("operation","controlled-metadata")
        .put("headers",JSONArray().put(JSONArray().put("Accept-Encoding").put("gzip")).put(JSONArray().put("Cookie").put("scoped=one"))).toString()

    @Test fun sequentialHttpsRequestsReuseOneVerifiedConnection() {
        repeat(2) {server.enqueue(MockResponse().setBody("metadata"))}
        repeat(2) {assertEquals("metadata",String(bridge.execute(request(),null).body))}
        assertEquals(0,server.takeRequest().sequenceNumber)
        assertEquals(1,server.takeRequest().sequenceNumber)
    }

    @Test fun untrustedCertificateIsRejectedWithoutAnHttpRequest() {
        MetadataHttpBridge(OkHttpClient()).use {untrusted->
            try {untrusted.execute(request(),null);fail("Untrusted TLS must fail")}
            catch(error:MetadataTransportException) {assertEquals("TLS_FAILED",error.code)}
        }
        assertEquals(0,server.requestCount)
    }

    @Test fun redirectsRemainVisibleToTheUpstreamCookieAndSecurityPolicy() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location","https://other.invalid/secret").setBody("redirect"))
        val response=bridge.execute(request(),null)
        assertEquals(302,response.status)
        assertTrue(response.headersJson.contains("https://other.invalid/secret"))
        assertEquals(1,server.requestCount)
    }

    @Test fun binaryPostAndEncodedBodyAndDuplicateCookiesAreNotRewritten() {
        val encoded=byteArrayOf(31,-117,0,1,-1)
        server.enqueue(MockResponse().addHeader("Content-Encoding","gzip").addHeader("Set-Cookie","a=1").addHeader("Set-Cookie","b=2")
            .setBody(Buffer().write(encoded)))
        val payload=byteArrayOf(0,1,-1,42)
        val response=bridge.execute(request("POST"),payload)
        assertArrayEquals(encoded,response.body)
        val received=server.takeRequest()
        assertArrayEquals(payload,received.body.readByteArray())
        assertEquals("scoped=one",received.getHeader("Cookie"))
        assertEquals("POST",received.method)
        val headers=JSONArray(response.headersJson)
        assertEquals(2,(0 until headers.length()).count {headers.getJSONArray(it).getString(0).equals("Set-Cookie",true)})
    }

    @Test fun oversizedCompressedResponseIsRejectedBeforePythonReceivesIt() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(8*1024*1024+1))))
        try {bridge.execute(request(),null);fail("Metadata must be bounded")}
        catch(error:MetadataTransportException) {assertEquals("METADATA_LIMIT",error.code)}
    }

    @Test fun cancellationInterruptsABlockedRequestAndPreventsFurtherRequests() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val executor=Executors.newSingleThreadExecutor()
        try {
            val pending=executor.submit<String> {
                try {bridge.execute(request(timeout=30_000),null);"unexpected success"}
                catch(error:MetadataTransportException) {error.code}
            }
            assertNotNull(server.takeRequest(2,TimeUnit.SECONDS))
            bridge.close()
            assertEquals("DOWNLOAD_INTERRUPTED",pending.get(3,TimeUnit.SECONDS))
            try {bridge.execute(request(),null);fail("Closed operation must not start a new request")}
            catch(error:MetadataTransportException) {assertEquals("DOWNLOAD_INTERRUPTED",error.code)}
        } finally {executor.shutdownNow()}
    }

    @Test fun deadlineDoesNotSilentlyReplayTheRequest() {
        server.enqueue(MockResponse().setHeadersDelay(1,TimeUnit.SECONDS).setBody("late"))
        try {bridge.execute(request(timeout=200),null);fail("Deadline must be enforced")}
        catch(error:MetadataTransportException) {assertEquals("READ_TIMEOUT",error.code)}
        assertEquals(1,server.requestCount)
    }
}
