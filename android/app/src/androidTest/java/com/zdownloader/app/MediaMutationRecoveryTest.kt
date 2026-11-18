package com.zdownloader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.*
import com.zdownloader.app.playback.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MediaMutationRecoveryTest {
    @Test fun hotCacheRenameAndRoundTripMoveRelinkSameAndNewUris()=runBlocking<Unit> {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        check(app.packageName=="com.zdownloader.app.candidate0965")
        for(stable in listOf(true,false)) DisposableDocuments().use {docs->
            docs.mode(stable=stable)
            val a=docs.create(docs.home,"A",true);val b=docs.create(docs.home,"B",true)
            val initial=docs.create(a,"before.m4a")
            docs.resolver.openOutputStream(initial)!!.use {it.write(byteArrayOf(1,2,3,4))}
            val id="mf03-${UUID.randomUUID()}";val list=id+"-list"
            val dao=app.repository.dao;val library=app.database.playbackLibrary()
            dao.insert(DownloadEntity(id,"https://example.com/$id","Before","Test",null,null,"{}","before.m4a",null,id,status="COMPLETED",uri=initial.toString()))
            library.favorite(PlaybackFavorite(initial.toString(),"Before",false,"audio/mp4",null,null,null,0))
            library.create(PlaybackList(list,"Fixture"))
            library.insert(PlaybackListItem(list,initial.toString(),0,"Before",false,"audio/mp4",null,null,null,0))
            try {
                val hot=app.mediaFiles.metadata(initial.toString()).copy(tree=docs.tree(a).toString(),parent=a.toString())
                assertEquals("before.m4a",app.mediaFiles.cachedMetadata(hot.uri)!!.name)
                val renamed=app.mediaFiles.renamed(hot,"after");docs.remember(android.net.Uri.parse(renamed.uri))
                assertEquals(stable,initial.toString()==renamed.uri)
                assertEquals("after.m4a",app.mediaFiles.metadata(renamed.uri).name)
                var current=renamed
                for(target in listOf(b,a)) {
                    current=app.mediaFiles.moved(current,docs.tree(target).toString())
                    docs.remember(android.net.Uri.parse(current.uri))
                    assertEquals(android.provider.DocumentsContract.getDocumentId(target),
                        android.provider.DocumentsContract.getDocumentId(android.net.Uri.parse(current.parent)))
                    assertEquals(current.uri,dao.get(id)!!.uri)
                    assertEquals("after.m4a",dao.get(id)!!.filename)
                    assertEquals(current.uri,library.ordered(list).single().uri)
                    assertEquals("after.m4a",library.favorites().first().single {it.uri==current.uri}.title)
                }
                assertEquals("after.m4a",MediaFiles(app,dao).metadata(current.uri).name)
                docs.mode(stable=stable,refuse=true)
                assertTrue(runCatching {app.mediaFiles.renamed(current,"refused")}.isFailure)
                assertEquals("after.m4a",app.mediaFiles.metadata(current.uri).name)
                assertEquals(current.uri,dao.get(id)!!.uri)
                docs.mode(stable=stable,failMetadata=true)
                val fallback=app.mediaFiles.renamed(current,"after-query-failure")
                docs.remember(android.net.Uri.parse(fallback.uri))
                assertEquals(fallback.uri,dao.get(id)!!.uri)
                assertNull(app.mediaFiles.cachedMetadata(fallback.uri))
                assertEquals("after-query-failure.m4a",app.mediaFiles.metadata(fallback.uri).name)
            } finally {
                library.delete(list)
                dao.get(id)?.uri?.let {library.unfavorite(it)}
                library.unfavorite(initial.toString())
                dao.removeHistory(id)
            }
        }
    }
}
