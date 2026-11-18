package com.zdownloader.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zdownloader.app.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Disposable SQLite fixtures only; no installed user history, network or file deletion APIs. */
class DownloadTrashTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun row(id:String,status:String="QUEUED")=DownloadEntity(id,"https://example.com/$id","Original title","Fixture",null,null,"{}","$id.mp4",null,id,status=status,bytes=123)
    @Test fun cancellationKeepsTheOriginalLinkAndDoesNotCreateDuplicateTrashRows()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ZDatabase::class.java).build()
        try {
            val dao=db.downloads();val original=row("cancel")
            dao.insert(original);assertEquals(1,dao.cancel(original.id));assertEquals(0,dao.cancel(original.id))
            val archived=dao.trash().first().single()
            assertEquals(original.url,archived.url);assertEquals(original.title,archived.title);assertEquals("CANCELLED",archived.reason)
            assertEquals(123L,dao.get(original.id)!!.bytes)
            dao.removeHistory(original.id)
            assertNull(dao.get(original.id));assertEquals("REMOVED",dao.trash().first().single().reason)
        } finally {db.close()}
    }
    @Test fun removingCompletedHistoryKeepsItsLinkAndDoesNotDeleteTheDownloadedFile()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ZDatabase::class.java).build()
        val file=File(context.cacheDir,"trash-preserved-${UUID.randomUUID()}.mp4").apply {writeBytes(byteArrayOf(1,2,3))}
        try {
            val dao=db.downloads();val original=row("saved","COMPLETED").copy(uri=file.toURI().toString())
            dao.insert(original);assertEquals(0,dao.cancel(original.id));assertTrue(dao.trash().first().isEmpty())
            dao.removeHistory(original.id)
            assertNull(dao.get(original.id));assertEquals(original.url,dao.trashEntry(original.id)!!.url)
            assertArrayEquals(byteArrayOf(1,2,3),file.readBytes())
            dao.forgetTrash(original.id);assertTrue(dao.trash().first().isEmpty());assertTrue(file.exists())
        } finally {db.close();file.delete()}
    }
    @Test fun removingActiveHistoryCannotArchiveOrRemoveAnActiveDownload()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ZDatabase::class.java).build()
        try {
            val dao=db.downloads();dao.insert(row("active","DOWNLOADING"));dao.removeHistory("active")
            assertEquals("DOWNLOADING",dao.get("active")!!.status);assertTrue(dao.trash().first().isEmpty())
        } finally {db.close()}
    }
    @Test fun failedArchiveRollsBackCancellationInsteadOfLosingTheLink()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ZDatabase::class.java).build()
        try {
            val dao=db.downloads();dao.insert(row("rollback"))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_trash BEFORE INSERT ON download_trash BEGIN SELECT RAISE(ABORT,'fixture'); END")
            assertTrue(runCatching {dao.cancel("rollback")}.isFailure)
            assertEquals("QUEUED",dao.get("rollback")!!.status);assertTrue(dao.trash().first().isEmpty())
        } finally {db.close()}
    }
    @Test fun migrationFromSevenPreservesDownloadsAndRecoversExistingCancelledLinks()=runBlocking {
        val name="trash-migration-${UUID.randomUUID()}.db"
        val schema=org.json.JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("com.zdownloader.app.data.ZDatabase/7.json").bufferedReader().use {it.readText()}).getJSONObject("database")
        val path=context.getDatabasePath(name).apply {parentFile!!.mkdirs()}
        var room:ZDatabase?=null
        try {
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path,null).use {db->
                val entities=schema.getJSONArray("entities")
                for(index in 0 until entities.length()) {
                    val entity=entities.getJSONObject(index)
                    fun sql(value:String)=value.replace("\${TABLE_NAME}",entity.getString("tableName"))
                    db.execSQL(sql(entity.getString("createSql")))
                    val indices=entity.optJSONArray("indices") ?: org.json.JSONArray()
                    for(i in 0 until indices.length()) db.execSQL(sql(indices.getJSONObject(i).getString("createSql")))
                }
                val queries=schema.getJSONArray("setupQueries")
                for(i in 0 until queries.length()) db.execSQL(queries.getString(i))
                for(state in listOf("COMPLETED","CANCELLED")) {
                    val values=android.content.ContentValues().apply {
                        put("id",state);put("url","https://example.com/$state");put("title",state);put("source","Fixture");put("selection","{}");put("filename","$state.mp4");put("fingerprint",state)
                        put("status",state);put("createdAt",123L);put("bytes",321L);put("speed",0L);put("retries",0);put("nextAttempt",0L)
                    }
                    db.insertOrThrow("downloads",null,values)
                }
                db.version=7
            }
            room=Room.databaseBuilder(context,ZDatabase::class.java,name).addMigrations(ZDatabase.MIGRATION_7_8).build()
            assertEquals(2,room.downloads().observe().first().size)
            val archived=room.downloads().trash().first().single()
            assertEquals("CANCELLED",archived.id);assertEquals("https://example.com/CANCELLED",archived.url)
            assertEquals(321L,room.downloads().get("COMPLETED")!!.bytes)
            assertEquals(8,room.openHelper.readableDatabase.version)
        } finally {room?.close();context.deleteDatabase(name)}
    }
}
