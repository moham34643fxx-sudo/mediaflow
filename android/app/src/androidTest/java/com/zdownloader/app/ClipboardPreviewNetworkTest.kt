@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.zdownloader.app

import com.zdownloader.app.data.ClipboardPreviewLoader
import kotlinx.coroutines.*
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Real sockets at the HTTP boundary; only DNS is redirected inside this test client. */
class ClipboardPreviewNetworkTest {
    // A public test-only identity, never a signing/production certificate or trust-all client.
    private val store=KeyStore.getInstance("PKCS12").apply {
        InstrumentationRegistry.getInstrumentation().context.assets.open("clipboard-preview-test.p12").use {load(it,"fixture-only".toCharArray())}
    }
    private val trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {init(store)}.trustManagers.single() as X509TrustManager
    private val tls=SSLContext.getInstance("TLS").apply {
        val keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {init(store,"fixture-only".toCharArray())}
        init(keys.keyManagers,arrayOf(trust),null)
    }
    private val http=OkHttpClient.Builder().dns(object:Dns {
        override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1"))
    }).sslSocketFactory(tls.socketFactory,trust)
        .readTimeout(10,TimeUnit.SECONDS).build()

    @Test fun directMediaLinksNeverOpenAPreviewNetworkRequest()=runBlocking {
        val noRequests=http.newBuilder().addInterceptor {throw AssertionError("Direct media must not be fetched for its preview")}.build()
        for(extension in listOf("mp4","MP3","m3u8","wav","avif","m4v","png")) {
            val preview=ClipboardPreviewLoader.metadata("https://example.com/source.$extension?copied=1",noRequests)
            assertNull(preview.title);assertNull(preview.imageUrl);assertNull(preview.artwork)
        }
    }

    @Test fun htmlIsBoundedAndNonHtmlIsRejectedWithoutDrainingItsBody()=runBlocking {
        for(contentType in listOf("text/html","video/mp4")) {
            tls.serverSocketFactory.createServerSocket(0).use {server->
                val closed=CountDownLatch(1)
                val sent=CountDownLatch(1)
                val producer=launch(Dispatchers.IO) {
                    server.accept().use {socket->
                        socket.soTimeout=5_000
                        val input=socket.getInputStream().bufferedReader()
                        while(!input.readLine().isNullOrEmpty()) {}
                        val output=socket.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: 9999999\r\n\r\n".toByteArray())
                        if(contentType=="text/html") {
                            val prefix="<meta property='og:title' content='Bounded real socket'>"
                            output.write(prefix.padEnd(192*1024,' ').toByteArray())
                        }
                        output.flush();sent.countDown()
                        try {while(socket.getInputStream().read()!=-1) {}} catch(_:java.net.SocketException) {}
                        closed.countDown()
                    }
                }
                val preview=withTimeout(3_000) {ClipboardPreviewLoader.metadata("https://example.com:${server.localPort}/watch",http)}
                assertTrue(sent.await(1,TimeUnit.SECONDS))
                assertEquals(if(contentType=="text/html") "Bounded real socket" else null,preview.title)
                assertTrue("Rejected/truncated responses must close without waiting for the media body",closed.await(1,TimeUnit.SECONDS))
                producer.join()
            }
        }
    }

    @Test fun cancellingWhileReadingAStalledBodyClosesTheSocketPromptly()=runBlocking {
        tls.serverSocketFactory.createServerSocket(0).use {server->
            val reading=CountDownLatch(1);val closed=CountDownLatch(1)
            val producer=launch(Dispatchers.IO) {
                server.accept().use {socket->
                    socket.soTimeout=5_000
                    val input=socket.getInputStream().bufferedReader()
                    while(!input.readLine().isNullOrEmpty()) {}
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 9999\r\n\r\n<title>".toByteArray());flush()
                    }
                    reading.countDown()
                    try {while(socket.getInputStream().read()!=-1) {}} catch(_:java.net.SocketException) {}
                    closed.countDown()
                }
            }
            val preview=launch(Dispatchers.IO) {ClipboardPreviewLoader.metadata("https://example.com:${server.localPort}/slow",http)}
            assertTrue(reading.await(3,TimeUnit.SECONDS))
            withTimeout(1_000) {preview.cancelAndJoin()}
            assertTrue("Cancellation must stop the body read, not just hide the card",closed.await(1,TimeUnit.SECONDS))
            producer.join()
        }
    }
}
