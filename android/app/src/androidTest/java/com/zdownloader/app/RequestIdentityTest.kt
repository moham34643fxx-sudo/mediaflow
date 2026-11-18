package com.zdownloader.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.*
import com.zdownloader.app.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RequestIdentityTest {
    @Test fun orphanedActiveRowsCanBePausedWithoutAProcessingOwner()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val database=Room.inMemoryDatabaseBuilder(app,ZDatabase::class.java).build()
        try {
            val dao=database.downloads();val repository=DownloadRepository(dao,SettingsRepository(app),app.coordinator,app.storage,app.notifications,app.mediaFiles)
            val id="orphan-${UUID.randomUUID()}"
            dao.insert(DownloadEntity(id,"https://example.com/v","Fixture","Test",null,null,"{}","fixture.mp4",null,id,status="VALIDATING",checkpoint="MERGED"))
            repository.pause(id)
            assertEquals("PAUSED",dao.get(id)!!.status)
        } finally {database.close()}
    }
    private fun media()=Media("https://www.facebook.com/share/v/first/","123456","Alias fixture","Facebook",null,null,1.0,
        listOf(Format("v","mp4",720,30.0,"h264","aac",1000,1000.0,"https","{\"url\":\"https://example.com/v.mp4\",\"ext\":\"mp4\"}")))
    @Test fun aliasAndLegacyRequestsReuseOnePersistentRow()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val database=Room.inMemoryDatabaseBuilder(app,ZDatabase::class.java).build()
        try {
            val dao=database.downloads();val repository=DownloadRepository(dao,SettingsRepository(app),app.coordinator,app.storage,app.notifications,app.mediaFiles)
            val original=media();val selection=Selection.video(original,original.formats.single())
            val id=UUID.randomUUID().toString()
            dao.insert(DownloadEntity(id,original.url,"Legacy","Facebook",null,1.0,selection.json(),"legacy.mp4",null,RequestFingerprint.legacy(original.url,selection),status="PAUSED"))
            assertEquals(id,repository.create(original,selection,"Repeat"))
            assertEquals(id,repository.create(original.copy(url="https://www.facebook.com/share/v/second/"),selection,"Another alias"))
            assertEquals(1,dao.observe().first().size)
            assertEquals("PAUSED",dao.get(id)!!.status)
        } finally {database.close()}
    }
    @Test fun oversizedSelectionsFailBeforeBecomingUnreadableRoomRows()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val database=Room.inMemoryDatabaseBuilder(app,ZDatabase::class.java).build()
        try {
            val repository=DownloadRepository(database.downloads(),SettingsRepository(app),app.coordinator,app.storage,app.notifications,app.mediaFiles)
            val original=media();val format=original.formats.single().copy(payload=JSONObject().put("url","https://example.com/v.mp4").put("padding","x".repeat(1024*1024)).toString())
            try {repository.create(original,Selection(format),"Large metadata");fail("Oversized row accepted")}
            catch(error:MediaException) {assertEquals(ErrorCode.FORMAT_UNAVAILABLE,error.code)}
            assertTrue(database.downloads().observe().first().isEmpty())
        } finally {database.close()}
    }
    @Test fun legacyCreatorlessRequestsAreReusedWithoutRewritingTheirPersistedSelection()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZApplication
        val database=Room.inMemoryDatabaseBuilder(app,ZDatabase::class.java).build()
        try {
            val dao=database.downloads();val repository=DownloadRepository(dao,SettingsRepository(app),app.coordinator,app.storage,app.notifications,app.mediaFiles)
            val source=media().copy(uploader="Actual creator")
            val modern=Selection.video(source,source.formats.first());val historical=modern.copy(creator=null)
            val id=UUID.randomUUID().toString();val oldJson=historical.json()
            dao.insert(DownloadEntity(id,source.url,"Legacy","Facebook",null,1.0,oldJson,"legacy.mp4",null,RequestFingerprint.legacy(source.url,historical),status="PAUSED"))
            assertEquals(id,repository.create(source,modern,"Resubmit"))
            assertEquals(oldJson,dao.get(id)!!.selection)
            assertEquals(id,repository.create(source.copy(url="https://www.facebook.com/share/v/new-alias/"),modern,"Alias"))
            assertEquals(1,dao.observe().first().size)
        } finally {database.close()}
    }
}
