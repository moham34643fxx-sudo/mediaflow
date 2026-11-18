package com.zdownloader.app

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.SettingsRepository
import com.zdownloader.app.engine.DirectMediaResolver
import com.zdownloader.app.engine.Extractor
import com.zdownloader.app.ui.ZViewModel
import kotlinx.coroutines.*
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test

/** Real local MP4 headers and home ViewModel; only HTTP delivery and optional-size latency are controlled. */
class HomeDiscoveryDeliveryTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication

    @Test fun completeCatalogIsSelectableWhileOptionalSizesAreStillBlocked()=runBlocking<Unit> {
        withFixture {model,started,release,finished->
            withContext(Dispatchers.Main) {model.input("https://discovery.example/first.mp4");model.analyze()}
            withTimeout(10_000) {started.await()}
            withTimeout(5_000) {while(model.ui.value.media==null) delay(10)}
            val catalog=model.ui.value.media!!
            assertFalse("Quality selection must not wait for optional size requests",model.ui.value.analyzing)
            assertFalse("The optional stage must really still be blocked",finished.isCompleted)
            assertEquals(1,catalog.formats.size)
            assertNull(catalog.formats.single().bytes)
            assertTrue(catalog.formats.any {it.video})
            release.complete(Unit)
            withTimeout(5_000) {finished.await()}
            withTimeout(5_000) {while(model.ui.value.media?.formats?.single()?.bytes==null) delay(10)}
            val sized=model.ui.value.media!!
            assertEquals(catalog.formats.map {it.id},sized.formats.map {it.id})
            assertEquals(catalog.formats.single().videoCodec,sized.formats.single().videoCodec)
            assertEquals(catalog.formats.single().height,sized.formats.single().height)
            assertTrue(sized.formats.single().bytes!!>0)
            assertNull(model.ui.value.error)
        }
    }

    @Test fun replacingInputCancelsOptionalWorkAndCannotRestoreTheOldCatalog()=runBlocking<Unit> {
        withFixture {model,started,release,finished->
            withContext(Dispatchers.Main) {model.input("https://discovery.example/first.mp4");model.analyze()}
            withTimeout(10_000) {started.await()}
            withTimeout(5_000) {while(model.ui.value.media==null) delay(10)}
            withContext(Dispatchers.Main) {model.input("https://discovery.example/replacement.mp4")}
            release.complete(Unit)
            withTimeout(5_000) {finished.await()}
            withContext(Dispatchers.Main) {yield()}
            assertEquals("https://discovery.example/replacement.mp4",model.ui.value.text)
            assertNull("An obsolete completion must never restore the previous selection",model.ui.value.media)
            assertFalse(model.ui.value.analyzing)
            assertNull(model.ui.value.error)
        }
    }

    private suspend fun withFixture(check:suspend (ZViewModel,CompletableDeferred<Unit>,CompletableDeferred<Unit>,CompletableDeferred<Unit>)->Unit) {
        val bytes=InstrumentationRegistry.getInstrumentation().context.assets.open("status-original.mp4").use {it.readBytes()}
        val client=OkHttpClient.Builder().addInterceptor {chain->
            val request=chain.request()
            require(request.url.host=="discovery.example")
            val range=requireNotNull(Regex("bytes=(\\d+)-(\\d+)").matchEntire(requireNotNull(request.header("Range"))))
            val first=range.groupValues[1].toInt();val last=minOf(range.groupValues[2].toInt(),bytes.lastIndex)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(206).message("Owned fixture")
                .header("Content-Type","video/mp4").header("Content-Range","bytes $first-$last/*")
                .body(ResponseBody.create(null,bytes.copyOfRange(first,last+1))).build()
        }.build()
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val finished=CompletableDeferred<Unit>()
        val sizeClient=OkHttpClient.Builder().addInterceptor {chain->
            require(chain.request().method=="HEAD")
            started.complete(Unit)
            try {
                runBlocking {withTimeout(10_000) {release.await()}}
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Owned size fixture")
                    .header("Content-Type","video/mp4").header("Content-Length",bytes.size.toString())
                    .body(ResponseBody.create(null,ByteArray(0))).build()
            } finally {finished.complete(Unit)}
        }.build()
        val extractor=Extractor(app,DirectMediaResolver(client),sizeClient={sizeClient},connectionIssue={null})
        val store=ViewModelStore()
        val model=withContext(Dispatchers.Main) {
            ZViewModel(app.repository,SettingsRepository(app),app.storage,extractor,app.scheduler,app.playlists,app.plannedDownloads)
                .also {store.put("discovery-delivery",it)}
        }
        try {check(model,started,release,finished)}
        finally {
            withContext(NonCancellable+Dispatchers.Main) {store.clear()}
            release.complete(Unit)
            client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()
            sizeClient.connectionPool.evictAll();sizeClient.dispatcher.executorService.shutdown()
        }
    }
}
