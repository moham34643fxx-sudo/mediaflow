package com.zdownloader.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.ZDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Execute each checked-in historical schema on Android SQLite, then validate the current schema. */
class HistoricalSchemaMigrationTest {
    @Test fun v1ToCurrent() = migrate(1)
    @Test fun v2ToCurrent() = migrate(2)
    @Test fun v3ToCurrent() = migrate(3)
    @Test fun v4ToCurrent() = migrate(4)
    @Test fun v5ToCurrentWithPlaybackRelationships() = migrate(5)
    @Test fun v6ToCurrentPreservesHistoryAndAddsNullableRequest() = migrate(6)
    @Test fun v7ToCurrentPreservesHistoryAndAddsTrash() = migrate(7)

    private fun migrate(version:Int) = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val name="sol-history-$version-${UUID.randomUUID()}.db"
        val schema=instrumentation.context.assets.open("com.zdownloader.app.data.ZDatabase/$version.json").bufferedReader().use {JSONObject(it.readText()).getJSONObject("database")}
        val path=context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        var room:ZDatabase?=null
        try {
            SQLiteDatabase.openOrCreateDatabase(path,null).use {db ->
                db.setForeignKeyConstraintsEnabled(true)
                val entities=schema.getJSONArray("entities")
                for(i in 0 until entities.length()) {
                    val entity=entities.getJSONObject(i)
                    fun sql(value:String)=value.replace("\${TABLE_NAME}",entity.getString("tableName"))
                    db.execSQL(sql(entity.getString("createSql")))
                    val indexes=entity.optJSONArray("indices")
                    if(indexes!=null) for(j in 0 until indexes.length()) db.execSQL(sql(indexes.getJSONObject(j).getString("createSql")))
                }
                val setup=schema.getJSONArray("setupQueries")
                for(i in 0 until setup.length()) db.execSQL(setup.getString(i))
                for((index,status) in listOf("COMPLETED","PAUSED","FAILED").withIndex()) {
                    val row=ContentValues().apply {
                        put("id","legacy-$index");put("url","https://example.com/v$index?token=preserved")
                        put("title","عنوان قديم $index");put("source","example.com");put("selection","{\"preserved\":true}")
                        put("filename","original-$index.mp4");put("destination","content://provider/tree/user-choice")
                        put("fingerprint",if(index==0) "v2:"+"a".repeat(64)+":"+"b".repeat(64) else "legacy-$index")
                        put("status",status);put("createdAt",1000L+index);put("bytes",123L+index)
                        put("speed",0);put("retries",index);put("nextAttempt",0)
                        if(index==0) {put("uri","content://media/external/video/media/123");put("outputSize",123L)}
                         if(index==1) put("pendingUri","content://provider/document/pending")
                         if(version>=6) {put("mediaKey",if(index==0) "v2:"+"a".repeat(64) else "url:legacy-$index");put("urlKey","historical-$index")}
                    }
                    assertTrue(db.insertOrThrow("downloads",null,row)>0)
                }
                db.execSQL("INSERT INTO events(downloadId,time,state) VALUES('legacy-0',1001,'COMPLETED')")
                if(version>=3) db.execSQL("INSERT INTO playlist_batches(id,submissionKey,url,title,policy,createdAt,state,numbered) VALUES('batch','unique','https://example.com/list','Old list','CONTINUE',1,'ACTIVE',1)")
                if(version>=5) {
                    db.execSQL("INSERT INTO playback_lists(id,name,createdAt) VALUES('list','Original list',123)")
                    db.execSQL("INSERT INTO playback_list_items(playlistId,uri,position,title,video,duration) VALUES('list','content://media/external/audio/media/1',0,'Original song',0,1000)")
                    db.execSQL("INSERT INTO playback_favorites(uri,title,video,duration) VALUES('content://media/external/audio/media/1','Favorite',0,1000)")
                }
                db.version=version
            }
            room=Room.databaseBuilder(context,ZDatabase::class.java,name).addMigrations(
                 ZDatabase.MIGRATION_1_2,ZDatabase.MIGRATION_2_3,ZDatabase.MIGRATION_3_4,ZDatabase.MIGRATION_4_5,ZDatabase.MIGRATION_5_6,ZDatabase.MIGRATION_6_7,ZDatabase.MIGRATION_7_8).build()
            val dao=room.downloads()
            assertEquals(3,dao.observe().first().size) // Forces Room's complete schema validation.
            for(index in 0..2) {
                val row=dao.get("legacy-$index")!!
                assertEquals("عنوان قديم $index",row.title)
                 assertEquals("{\"preserved\":true}",row.selection)
                 assertNull(row.request)
                assertEquals("content://provider/tree/user-choice",row.destination)
                assertEquals(123L+index,row.bytes)
                assertEquals("ANY",row.scheduleNetwork);assertFalse(row.requiresCharging)
                assertEquals(0L,row.scheduledAt);assertTrue(row.urlKey.isNotBlank())
            }
            assertEquals("v2:"+"a".repeat(64),dao.get("legacy-0")!!.mediaKey)
            assertEquals("content://provider/document/pending",dao.get("legacy-1")!!.pendingUri)
            assertEquals(1,dao.events("legacy-0").size)
            val sqlite=room.openHelper.writableDatabase
            sqlite.query("PRAGMA integrity_check").use {assertTrue(it.moveToFirst());assertEquals("ok",it.getString(0))}
            sqlite.query("PRAGMA foreign_key_check").use {assertFalse(it.moveToFirst())}
            if(version>=3) {
                assertEquals("Old list",dao.batch("batch")!!.title)
                val duplicate=runCatching {sqlite.execSQL("INSERT INTO playlist_batches(id,submissionKey,url,title,policy,createdAt,state,numbered) VALUES('other','unique','https://example.com/list','Duplicate','CONTINUE',1,'ACTIVE',1)")}.exceptionOrNull()
                assertTrue("Unique submission key must survive",duplicate is android.database.sqlite.SQLiteConstraintException)
            }
            if(version>=5) {
                assertEquals(1,room.playbackLibrary().favorites().first().size)
                sqlite.query("SELECT title FROM playback_list_items WHERE playlistId='list'").use {assertTrue(it.moveToFirst());assertEquals("Original song",it.getString(0))}
                sqlite.execSQL("DELETE FROM playback_lists WHERE id='list'")
                sqlite.query("SELECT COUNT(*) FROM playback_list_items").use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
            }
        } finally { room?.close();context.deleteDatabase(name) }
    }
}
